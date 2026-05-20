#pragma once

#ifdef __linux__

#include "grabber_base.h"
#include <X11/Xlib.h>

namespace hyperion {

class X11Grabber : public GrabberBase {
public:
    X11Grabber(const FrameConfig& config, std::shared_ptr<HyperionClient> client);
    ~X11Grabber() override;

protected:
    bool initCapture() override;
    void deinitCapture() override;
    bool captureFrame(FrameProcessor& processor) override;

private:
    Display* m_display = nullptr;
    Window   m_root    = 0;
    int      m_screen  = 0;
};

} // namespace hyperion

#endif // __linux__
