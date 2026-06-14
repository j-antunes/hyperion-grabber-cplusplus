// Windows Desktop Duplication (DXGI) screen capture exposed to the JVM desktop
// app via JNI. This mirrors pc/windows/dxgi_grabber.cpp but is self-contained
// (no core/ or flatbuffers dependency) and returns a downscaled RGB frame
// instead of driving the network loop — the Kotlin GrabberState owns pacing,
// sending and reconnect.
//
// Why this exists: java.awt.Robot captures via GDI BitBlt, which makes the
// Windows hardware mouse cursor flicker during continuous capture. Desktop
// Duplication composites the desktop image without the cursor overlay, so the
// pointer stays solid. Linux/macOS keep using Robot (see ScreenGrabber.kt).

#ifdef _WIN32

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif

#include <windows.h>
#include <d3d11.h>
#include <dxgi1_2.h>
#include <wrl/client.h>
#include <jni.h>
#include <cstdint>
#include <vector>

using Microsoft::WRL::ComPtr;

namespace {

struct Capturer {
    ComPtr<ID3D11Device>           device;
    ComPtr<ID3D11DeviceContext>    context;
    ComPtr<IDXGIOutputDuplication> dup;
    ComPtr<ID3D11Texture2D>        staging;
    UINT srcW = 0;
    UINT srcH = 0;
    bool firstFrame = true;
    std::vector<uint8_t> rgb;  // reused frame buffer
};

// (Re)create the output duplication and a matching CPU-readable staging texture
// from the existing D3D11 device. Called at init and after DXGI_ERROR_ACCESS_LOST
// (resolution change, secure desktop, fullscreen transitions).
bool createDuplication(Capturer* c) {
    c->dup.Reset();
    c->staging.Reset();

    ComPtr<IDXGIDevice>  dxgiDevice;
    ComPtr<IDXGIAdapter> adapter;
    ComPtr<IDXGIOutput>  output;
    ComPtr<IDXGIOutput1> output1;
    if (FAILED(c->device.As(&dxgiDevice)))         return false;
    if (FAILED(dxgiDevice->GetAdapter(&adapter)))  return false;
    if (FAILED(adapter->EnumOutputs(0, &output)))  return false;  // primary output
    if (FAILED(output.As(&output1)))               return false;
    if (FAILED(output1->DuplicateOutput(c->device.Get(), &c->dup))) return false;

    DXGI_OUTDUPL_DESC desc{};
    c->dup->GetDesc(&desc);
    c->srcW = desc.ModeDesc.Width;
    c->srcH = desc.ModeDesc.Height;

    D3D11_TEXTURE2D_DESC td{};
    td.Width            = c->srcW;
    td.Height           = c->srcH;
    td.MipLevels        = 1;
    td.ArraySize        = 1;
    td.Format           = DXGI_FORMAT_B8G8R8A8_UNORM;
    td.SampleDesc.Count = 1;
    td.Usage            = D3D11_USAGE_STAGING;
    td.CPUAccessFlags   = D3D11_CPU_ACCESS_READ;
    c->firstFrame = true;
    return SUCCEEDED(c->device->CreateTexture2D(&td, nullptr, &c->staging));
}

// Area-average downscale of a BGRA source (rowPitch bytes per row) to packed
// RGB (dstW*dstH*3). Averaging avoids the sampling noise a nearest-neighbour
// scale would feed into the LED zones.
void downscale(const uint8_t* src, UINT rowPitch, UINT srcW, UINT srcH,
               int dstW, int dstH, std::vector<uint8_t>& out) {
    out.resize(static_cast<size_t>(dstW) * dstH * 3);
    for (int dy = 0; dy < dstH; ++dy) {
        UINT sy0 = static_cast<UINT>((uint64_t)dy * srcH / dstH);
        UINT sy1 = static_cast<UINT>((uint64_t)(dy + 1) * srcH / dstH);
        if (sy1 <= sy0) sy1 = sy0 + 1;
        if (sy1 > srcH)  sy1 = srcH;
        for (int dx = 0; dx < dstW; ++dx) {
            UINT sx0 = static_cast<UINT>((uint64_t)dx * srcW / dstW);
            UINT sx1 = static_cast<UINT>((uint64_t)(dx + 1) * srcW / dstW);
            if (sx1 <= sx0) sx1 = sx0 + 1;
            if (sx1 > srcW)  sx1 = srcW;

            uint64_t r = 0, g = 0, b = 0, n = 0;
            for (UINT y = sy0; y < sy1; ++y) {
                const uint8_t* row = src + static_cast<size_t>(y) * rowPitch;
                for (UINT x = sx0; x < sx1; ++x) {
                    const uint8_t* px = row + static_cast<size_t>(x) * 4;  // BGRA
                    b += px[0];
                    g += px[1];
                    r += px[2];
                    ++n;
                }
            }
            if (n == 0) n = 1;
            size_t o = (static_cast<size_t>(dy) * dstW + dx) * 3;
            out[o]     = static_cast<uint8_t>(r / n);
            out[o + 1] = static_cast<uint8_t>(g / n);
            out[o + 2] = static_cast<uint8_t>(b / n);
        }
    }
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_hyperion_grabber_WindowsCapture_nativeInit(JNIEnv*, jobject) {
    auto* c = new Capturer();
    D3D_FEATURE_LEVEL level;
    HRESULT hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, 0,
                                   nullptr, 0, D3D11_SDK_VERSION,
                                   &c->device, &level, &c->context);
    if (FAILED(hr) || !createDuplication(c)) {
        delete c;          // headless/GPU-less host (e.g. CI) → caller uses Robot
        return 0;
    }
    return reinterpret_cast<jlong>(c);
}

JNIEXPORT jbyteArray JNICALL
Java_com_hyperion_grabber_WindowsCapture_nativeCapture(JNIEnv* env, jobject,
                                                       jlong handle, jint dstW, jint dstH) {
    auto* c = reinterpret_cast<Capturer*>(handle);
    if (!c || !c->dup || dstW <= 0 || dstH <= 0) return nullptr;

    // Wait briefly for the first frame; afterwards return immediately and let
    // the caller reuse the previous frame when nothing changed (low CPU on a
    // static desktop, and the Kotlin keepalive resends it).
    UINT timeout = c->firstFrame ? 500 : 0;

    ComPtr<IDXGIResource>  resource;
    DXGI_OUTDUPL_FRAME_INFO info{};
    HRESULT hr = c->dup->AcquireNextFrame(timeout, &info, &resource);
    if (hr == DXGI_ERROR_WAIT_TIMEOUT) return nullptr;     // no change since last call
    if (hr == DXGI_ERROR_ACCESS_LOST) {                    // mode change / secure desktop
        createDuplication(c);
        return nullptr;
    }
    if (FAILED(hr)) return nullptr;

    ComPtr<ID3D11Texture2D> tex;
    if (FAILED(resource.As(&tex))) { c->dup->ReleaseFrame(); return nullptr; }
    c->context->CopyResource(c->staging.Get(), tex.Get());
    c->dup->ReleaseFrame();

    D3D11_MAPPED_SUBRESOURCE mapped{};
    if (FAILED(c->context->Map(c->staging.Get(), 0, D3D11_MAP_READ, 0, &mapped)))
        return nullptr;

    downscale(reinterpret_cast<const uint8_t*>(mapped.pData), mapped.RowPitch,
              c->srcW, c->srcH, dstW, dstH, c->rgb);
    c->context->Unmap(c->staging.Get(), 0);
    c->firstFrame = false;

    jbyteArray arr = env->NewByteArray(static_cast<jsize>(c->rgb.size()));
    if (!arr) return nullptr;
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(c->rgb.size()),
                            reinterpret_cast<const jbyte*>(c->rgb.data()));
    return arr;
}

JNIEXPORT void JNICALL
Java_com_hyperion_grabber_WindowsCapture_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Capturer*>(handle);
}

}  // extern "C"

#endif  // _WIN32
