#include "grabber_base.h"
#include <algorithm>
#include <chrono>
#include <cstdio>

namespace hyperion {

GrabberBase::GrabberBase(const FrameConfig& config, std::shared_ptr<HyperionClient> client)
    : m_client(std::move(client)), m_config(config) {}

GrabberBase::~GrabberBase() {
    // Derived destructors are responsible for calling stop(); doing it here
    // would invoke the pure-virtual deinitCapture() after the derived part
    // has been destroyed.
}

bool GrabberBase::start() {
    if (m_running) return false;
    if (!initCapture()) return false;
    m_initialized = true;

    // initCapture() may have corrected the source resolution
    m_processor = std::make_unique<FrameProcessor>(m_config);
    m_running = true;
    m_thread = std::thread(&GrabberBase::runLoop, this);
    return true;
}

void GrabberBase::stop() {
    m_running = false;
    if (m_thread.joinable()) m_thread.join();
    if (m_initialized) {
        m_initialized = false;
        deinitCapture();
    }
}

void GrabberBase::runLoop() {
    using clock    = std::chrono::steady_clock;
    using seconds  = std::chrono::seconds;
    using microsec = std::chrono::microseconds;

    const int fps = m_config.framerate > 0 ? m_config.framerate : 25;
    const auto frameInterval = microsec(1'000'000 / fps);
    auto lastSent = clock::now();
    auto nextFrameDue = clock::now();

    while (m_running) {
        // Capture was torn down by a prior Lost result — re-establish it at the
        // (possibly new) screen geometry before capturing again. Backing off
        // keeps a persistent failure (e.g. no display) from spinning.
        if (!m_initialized) {
            for (int i = 0; i < REINIT_BACKOFF_MS / 100 && m_running; ++i)
                std::this_thread::sleep_for(microsec(100'000));
            if (!m_running) break;
            if (initCapture()) {
                m_initialized = true;
                m_processor = std::make_unique<FrameProcessor>(m_config);
                printf("[grabber] capture reinitialized\n");
            } else {
                printf("[grabber] capture reinit failed, will retry\n");
            }
            lastSent = clock::now();
            nextFrameDue = clock::now();
            continue;
        }

        CaptureResult res = captureFrame(*m_processor);

        if (res == CaptureResult::Lost) {
            // Capture source invalidated (resolution change / DXGI ACCESS_LOST).
            // Tear down capture and let the top of the loop rebuild it, leaving
            // the still-healthy TCP connection alone.
            printf("[grabber] capture lost, reinitializing…\n");
            deinitCapture();
            m_initialized = false;
            continue;
        }

        bool sendFailed = (res == CaptureResult::Failed);

        if (res == CaptureResult::Sent) {
            lastSent = clock::now();
        } else if (!sendFailed && clock::now() - lastSent >= seconds(KEEPALIVE_SECS)) {
            // Nothing transmitted recently (static screen) — resend the last
            // frame so Hyperion's priority doesn't expire.
            const auto& pixels = m_processor->lastPixels();
            if (!pixels.empty()) {
                if (m_client->sendFrame(pixels, m_config.targetWidth, m_config.targetHeight))
                    lastSent = clock::now();
                else
                    sendFailed = true;
            }
        }

        if (sendFailed) {
            printf("[grabber] send failed, reconnecting in %ds…\n", RECONNECT_SECS);
            m_client->disconnect();
            for (int i = 0; i < RECONNECT_SECS * 10 && m_running; ++i)
                std::this_thread::sleep_for(microsec(100'000));
            if (m_running) {
                if (m_client->connect())
                    printf("[grabber] reconnected\n");
                else
                    printf("[grabber] reconnect failed, will retry\n");
            }
            lastSent = clock::now();
            nextFrameDue = clock::now();
            continue;
        }

        // Sleep toward an absolute deadline instead of anchoring to this
        // iteration's start: per-iteration anchoring lets sleep overshoot
        // accumulate, landing the real rate below the target fps (mirrors
        // the Android/desktop pacing fix). The clamp keeps a stall (slow
        // capture) from bursting to catch up afterwards.
        nextFrameDue = std::max(nextFrameDue + frameInterval, clock::now());
        std::this_thread::sleep_until(nextFrameDue);
    }
}

} // namespace hyperion
