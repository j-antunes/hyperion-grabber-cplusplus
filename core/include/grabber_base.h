#pragma once

#include "hyperion_client.h"
#include "frame_processor.h"
#include <atomic>
#include <thread>
#include <memory>

namespace hyperion {

// Outcome of one capture attempt:
//  Sent    — a frame was transmitted.
//  NoFrame — nothing to send (DXGI timeout / pointer-only frame on a static
//            screen); must not reset the keepalive timer.
//  Failed  — the TCP send failed; the base reconnects the socket.
//  Lost    — the capture source was invalidated (resolution change, DXGI
//            ACCESS_LOST, mode switch, screen lock); the base re-initialises
//            capture and leaves the healthy TCP connection alone.
enum class CaptureResult { Sent, NoFrame, Failed, Lost };

// Platform-specific grabbers inherit from this.
// IMPORTANT: every derived destructor must call stop() — the base destructor
// cannot, because deinitCapture() is pure virtual and the derived part is
// already destroyed by the time ~GrabberBase() runs.
class GrabberBase {
public:
    GrabberBase(const FrameConfig& config, std::shared_ptr<HyperionClient> client);
    virtual ~GrabberBase();

    bool start();
    void stop();   // idempotent
    bool isRunning() const { return m_running; }

    // initCapture() may correct sourceWidth/Height to the real screen size
    const FrameConfig& config() const { return m_config; }

protected:
    // Implemented by each platform grabber; called on the capture thread
    virtual CaptureResult captureFrame(FrameProcessor& processor) = 0;
    virtual bool initCapture() = 0;
    virtual void deinitCapture() = 0;

    std::shared_ptr<HyperionClient> m_client;
    FrameConfig m_config;

private:
    void runLoop();

    std::atomic<bool>            m_running{false};
    std::thread                  m_thread;
    std::unique_ptr<FrameProcessor> m_processor;
    bool                         m_initialized = false;

    static constexpr int KEEPALIVE_SECS   = 3;
    static constexpr int RECONNECT_SECS   = 5;
    static constexpr int REINIT_BACKOFF_MS = 500;  // wait before re-init after capture loss
};

} // namespace hyperion
