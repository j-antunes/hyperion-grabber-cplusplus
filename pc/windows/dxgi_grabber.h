#pragma once

#ifdef _WIN32

#include "grabber_base.h"
#include <d3d11.h>
#include <dxgi1_2.h>
#include <wrl/client.h>

namespace hyperion {

class DXGIGrabber : public GrabberBase {
public:
    DXGIGrabber(const FrameConfig& config, std::shared_ptr<HyperionClient> client);
    ~DXGIGrabber() override;

protected:
    bool initCapture() override;
    void deinitCapture() override;
    bool captureFrame(FrameProcessor& processor) override;

private:
    Microsoft::WRL::ComPtr<ID3D11Device>           m_device;
    Microsoft::WRL::ComPtr<ID3D11DeviceContext>    m_context;
    Microsoft::WRL::ComPtr<IDXGIOutputDuplication> m_duplication;
    Microsoft::WRL::ComPtr<ID3D11Texture2D>        m_stagingTex;
};

} // namespace hyperion

#endif // _WIN32
