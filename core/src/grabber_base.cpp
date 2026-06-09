#include "grabber_base.h"
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

    while (m_running) {
        auto t0 = clock::now();

        CaptureResult res = captureFrame(*m_processor);
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
            continue;
        }

        auto elapsed = clock::now() - t0;
        if (elapsed < frameInterval)
            std::this_thread::sleep_for(frameInterval - elapsed);
    }
}

} // namespace hyperion
