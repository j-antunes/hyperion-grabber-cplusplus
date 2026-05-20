#include "grabber_base.h"
#include <chrono>

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
    using clock = std::chrono::steady_clock;
    const auto interval = std::chrono::microseconds(1'000'000 / m_config.framerate);

    while (m_running) {
        auto t0 = clock::now();
        captureFrame(*m_processor);
        auto elapsed = clock::now() - t0;
        if (elapsed < interval)
            std::this_thread::sleep_for(interval - elapsed);
    }
}

} // namespace hyperion
