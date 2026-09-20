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

// Sleep in 100ms slices so stop() is honoured promptly.
void GrabberBase::sleepWhileRunning(int ms) {
    using std::chrono::milliseconds;
    for (int slept = 0; slept < ms && m_running; slept += 100)
        std::this_thread::sleep_for(milliseconds(std::min(100, ms - slept)));
}

void GrabberBase::runLoop() {
    using clock    = std::chrono::steady_clock;
    using seconds  = std::chrono::seconds;
    using microsec = std::chrono::microseconds;

    const int fps = m_config.framerate > 0 ? m_config.framerate : 25;
    const auto frameInterval = microsec(1'000'000 / fps);
    auto lastSent = clock::now();
    auto nextFrameDue = clock::now();
    bool pausedForDisplay = false;

    while (m_running) {
        // ── Display power ────────────────────────────────────────────────
        // Checked first: while the panel is dark there is nothing worth
        // capturing, and on Windows the duplication can't be rebuilt anyway.
        if (!isDisplayOn()) {
            if (!pausedForDisplay) {
                printf("[grabber] display off — pausing capture and dropping connection\n");
                pausedForDisplay = true;
                m_client->disconnect();
            }
            sleepWhileRunning(DISPLAY_OFF_POLL_MS);
            continue;
        }
        if (pausedForDisplay) {
            if (!m_client->connect()) {
                printf("[grabber] display on but reconnect failed, retrying in %ds\n", RECONNECT_SECS);
                sleepWhileRunning(RECONNECT_SECS * 1000);
                continue;
            }
            printf("[grabber] display on — resumed\n");
            pausedForDisplay = false;
            lastSent = clock::now();
            nextFrameDue = clock::now();
        }

        // ── Capture re-init ──────────────────────────────────────────────
        // Capture was torn down by a prior Lost result — re-establish it at the
        // (possibly new) screen geometry before capturing again. Backing off
        // keeps a persistent failure (e.g. secure desktop) from spinning.
        if (!m_initialized) {
            sleepWhileRunning(REINIT_BACKOFF_MS);
            if (!m_running) break;
            if (initCapture()) {
                m_initialized = true;
                m_processor = std::make_unique<FrameProcessor>(m_config);
                printf("[grabber] capture reinitialized (%dx%d)\n",
                       m_config.sourceWidth, m_config.sourceHeight);
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
            sleepWhileRunning(RECONNECT_SECS * 1000);
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
