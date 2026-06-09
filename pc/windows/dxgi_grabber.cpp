#ifdef _WIN32

#include "dxgi_grabber.h"

namespace hyperion {

DXGIGrabber::DXGIGrabber(const FrameConfig& config, std::shared_ptr<HyperionClient> client)
    : GrabberBase(config, std::move(client)) {}

DXGIGrabber::~DXGIGrabber() {
    stop();
}

bool DXGIGrabber::initCapture() {
    D3D_FEATURE_LEVEL level;
    HRESULT hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, 0,
                                   nullptr, 0, D3D11_SDK_VERSION,
                                   &m_device, &level, &m_context);
    if (FAILED(hr)) return false;

    Microsoft::WRL::ComPtr<IDXGIDevice>  dxgiDevice;
    Microsoft::WRL::ComPtr<IDXGIAdapter> adapter;
    Microsoft::WRL::ComPtr<IDXGIOutput>  output;
    Microsoft::WRL::ComPtr<IDXGIOutput1> output1;

    if (FAILED(m_device.As(&dxgiDevice)))          return false;
    if (FAILED(dxgiDevice->GetAdapter(&adapter)))   return false;
    if (FAILED(adapter->EnumOutputs(0, &output)))   return false;
    if (FAILED(output.As(&output1)))                return false;
    if (FAILED(output1->DuplicateOutput(m_device.Get(), &m_duplication))) return false;

    // The duplicated frames come in at the real desktop resolution.
    // CopyResource requires identical texture dimensions, so the staging
    // texture (and the frame processor) must match it, not a hardcoded size.
    DXGI_OUTDUPL_DESC duplDesc{};
    m_duplication->GetDesc(&duplDesc);
    m_config.sourceWidth  = static_cast<int>(duplDesc.ModeDesc.Width);
    m_config.sourceHeight = static_cast<int>(duplDesc.ModeDesc.Height);

    D3D11_TEXTURE2D_DESC desc{};
    desc.Width            = duplDesc.ModeDesc.Width;
    desc.Height           = duplDesc.ModeDesc.Height;
    desc.MipLevels        = 1;
    desc.ArraySize        = 1;
    desc.Format           = DXGI_FORMAT_B8G8R8A8_UNORM;
    desc.SampleDesc.Count = 1;
    desc.Usage            = D3D11_USAGE_STAGING;
    desc.CPUAccessFlags   = D3D11_CPU_ACCESS_READ;

    return SUCCEEDED(m_device->CreateTexture2D(&desc, nullptr, &m_stagingTex));
}

void DXGIGrabber::deinitCapture() {
    m_stagingTex  = nullptr;
    m_duplication = nullptr;
    m_context     = nullptr;
    m_device      = nullptr;
}

CaptureResult DXGIGrabber::captureFrame(FrameProcessor& processor) {
    Microsoft::WRL::ComPtr<IDXGIResource>        resource;
    DXGI_OUTDUPL_FRAME_INFO                      frameInfo{};

    HRESULT hr = m_duplication->AcquireNextFrame(100, &frameInfo, &resource);
    if (hr == DXGI_ERROR_WAIT_TIMEOUT) return CaptureResult::NoFrame;  // static screen
    if (FAILED(hr)) return CaptureResult::Failed;

    Microsoft::WRL::ComPtr<ID3D11Texture2D> tex;
    resource.As(&tex);
    m_context->CopyResource(m_stagingTex.Get(), tex.Get());
    m_duplication->ReleaseFrame();

    D3D11_MAPPED_SUBRESOURCE mapped{};
    if (FAILED(m_context->Map(m_stagingTex.Get(), 0, D3D11_MAP_READ, 0, &mapped)))
        return CaptureResult::Failed;

    auto pixels = processor.processBGRA(
        reinterpret_cast<const uint8_t*>(mapped.pData),
        mapped.RowPitch);

    m_context->Unmap(m_stagingTex.Get(), 0);

    return m_client->sendFrame(pixels, m_config.targetWidth, m_config.targetHeight)
        ? CaptureResult::Sent : CaptureResult::Failed;
}

} // namespace hyperion

#endif // _WIN32
