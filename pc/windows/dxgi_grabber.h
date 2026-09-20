#pragma once

#ifdef _WIN32

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <winsock2.h>
#include <ws2tcpip.h>
#include "grabber_base.h"
#include "dxgi_duplicator.h"
#include "display_power.h"

namespace hyperion {

class DXGIGrabber : public GrabberBase {
public:
    DXGIGrabber(const FrameConfig& config, std::shared_ptr<HyperionClient> client);
    ~DXGIGrabber() override;

protected:
    bool initCapture() override;
    void deinitCapture() override;
    CaptureResult captureFrame(FrameProcessor& processor) override;
    bool isDisplayOn() override;

private:
    win::DxgiDuplicator       m_dup;
    // Lives for the grabber's whole lifetime (not per init/deinit cycle) so a
    // capture re-init on the secure desktop doesn't churn a window thread.
    win::DisplayPowerMonitor  m_power;
};

} // namespace hyperion

#endif // _WIN32
