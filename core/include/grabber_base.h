#pragma once

#include "hyperion_client.h"
#include "frame_processor.h"
#include <atomic>
#include <thread>
#include <memory>

namespace hyperion {

// Platform-specific grabbers inherit from this
class GrabberBase {
public:
    GrabberBase(const FrameConfig& config, std::shared_ptr<HyperionClient> client);
    virtual ~GrabberBase();

    bool start();
    void stop();
    bool isRunning() const { return m_running; }

protected:
    // Implemented by each platform grabber; called on the capture thread
    virtual bool captureFrame(FrameProcessor& processor) = 0;
    virtual bool initCapture() = 0;
    virtual void deinitCapture() = 0;

    std::shared_ptr<HyperionClient> m_client;
    FrameConfig m_config;

private:
    void runLoop();

    std::atomic<bool>            m_running{false};
    std::thread                  m_thread;
    std::unique_ptr<FrameProcessor> m_processor;

    static constexpr int KEEPALIVE_SECS  = 3;
    static constexpr int RECONNECT_SECS  = 5;
};

} // namespace hyperion
