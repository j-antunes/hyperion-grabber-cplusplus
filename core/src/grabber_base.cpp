#include "grabber_base.h"
#include <chrono>
#include <cstdio>

namespace hyperion {

GrabberBase::GrabberBase(const FrameConfig& config, std::shared_ptr<HyperionClient> client)
    : m_client(std::move(client)), m_config(config) {}

GrabberBase::~GrabberBase() {
    stop();
}

bool GrabberBase::start() {
    if (m_running) return false;
    if (!initCapture()) return false;

    m_processor = std::make_unique<FrameProcessor>(m_config);
    m_running = true;
    m_thread = std::thread(&GrabberBase::runLoop, this);
    return true;
}

void GrabberBase::stop() {
    m_running = false;
    if (m_thread.joinable()) m_thread.join();
    deinitCapture();
}

void GrabberBase::runLoop() {
    using clock    = std::chrono::steady_clock;
    using seconds  = std::chrono::seconds;
    using microsec = std::chrono::microseconds;

    const auto frameInterval = microsec(1'000'000 / m_config.framerate);
    auto lastSent = clock::now() - seconds(KEEPALIVE_SECS); // send immediately

    while (m_running) {
        auto t0 = clock::now();

        bool ok = captureFrame(*m_processor);

        if (!ok) {
            // Connection dropped — reconnect loop
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
            lastSent = clock::now(); // reset keepalive timer
        } else {
            lastSent = clock::now();
        }

        // Keepalive: if no new frame for KEEPALIVE_SECS, resend last pixels
        if (clock::now() - lastSent >= seconds(KEEPALIVE_SECS)) {
            const auto& pixels = m_processor->lastPixels();
            if (!pixels.empty()) {
                m_client->sendFrame(pixels, m_config.targetWidth, m_config.targetHeight);
            }
            lastSent = clock::now();
        }

        auto elapsed = clock::now() - t0;
        if (elapsed < frameInterval)
            std::this_thread::sleep_for(frameInterval - elapsed);
    }
}

} // namespace hyperion
