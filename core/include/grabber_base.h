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
//            ACCESS_LOST, mode switch, screen lock); the base tears capture
//            down, re-initialises it (retrying with a backoff — DXGI
//            re-creation keeps failing while the secure desktop is up) and
//            rebuilds the FrameProcessor at the corrected source size, leaving
//            the healthy TCP connection alone.
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

    // Loop timings, public so tests and the desktop JVM twin stay in step.
    static constexpr int KEEPALIVE_SECS       = 3;
    static constexpr int RECONNECT_SECS       = 5;
    static constexpr int REINIT_BACKOFF_MS    = 500;  // wait before re-init after capture loss
    static constexpr int DISPLAY_OFF_POLL_MS  = 500;

protected:
    // Implemented by each platform grabber; called on the capture thread
    virtual CaptureResult captureFrame(FrameProcessor& processor) = 0;
    virtual bool initCapture() = 0;
    virtual void deinitCapture() = 0;

    // Display power state (Android SCREEN_OFF / X11 DPMS / Windows
    // GUID_CONSOLE_DISPLAY_STATE). While this returns false the run loop
    // stops capturing and drops the TCP connection so Hyperion releases our
    // priority — the same "pause and disconnect" the Android service does —
    // and reconnects as soon as the display comes back. Default: always on.
    virtual bool isDisplayOn() { return true; }

    std::shared_ptr<HyperionClient> m_client;
    FrameConfig m_config;

private:
    void runLoop();
    void sleepWhileRunning(int ms);

    std::atomic<bool>            m_running{false};
    std::thread                  m_thread;
    std::unique_ptr<FrameProcessor> m_processor;
    bool                         m_initialized = false;
};

} // namespace hyperion
