#pragma once

#ifdef __linux__

#include "grabber_base.h"
#include <X11/Xlib.h>
#include <chrono>

namespace hyperion {

class X11Grabber : public GrabberBase {
public:
    X11Grabber(const FrameConfig& config, std::shared_ptr<HyperionClient> client);
    ~X11Grabber() override;

protected:
    bool initCapture() override;
    void deinitCapture() override;
    CaptureResult captureFrame(FrameProcessor& processor) override;
    bool isDisplayOn() override;

private:
    bool readScreenSize();  // true if the size changed

    Display* m_display = nullptr;
    Window   m_root    = 0;
    int      m_screen  = 0;
    bool     m_hasDpms = false;
    std::chrono::steady_clock::time_point m_lastSizeCheck{};

    static constexpr int SIZE_CHECK_MS = 1000;
};

} // namespace hyperion

#endif // __linux__
