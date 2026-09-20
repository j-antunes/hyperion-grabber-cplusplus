#pragma once

// DXGI Desktop Duplication wrapper shared by the C++ PC grabber
// (pc/windows/dxgi_grabber.cpp) and the desktop JVM app's JNI helper
// (desktop/native/dxgi_jni.cpp). Header-only so the JNI build stays
// standalone (no core/ or flatbuffers dependency).
//
// It fixes three problems the two callers used to share:
//
//  1. Output selection. The old code took output 0 of the *default* adapter.
//     That is not necessarily the primary monitor, and on hybrid laptops the
//     default adapter can have no outputs at all, so init failed and the
//     desktop app silently fell back to Robot (cursor flicker came back).
//     We now enumerate every adapter/output and pick the one whose desktop
//     rectangle contains the origin — the primary monitor by definition —
//     and create the D3D11 device on *that* adapter.
//
//  2. HDR. With HDR enabled, IDXGIOutput1::DuplicateOutput hands back
//     R16G16B16A16_FLOAT frames. Copying those into a hardcoded BGRA8
//     staging texture is a silent no-op (CopyResource needs matching
//     formats), so the LEDs went black. We ask IDXGIOutput5::DuplicateOutput1
//     for BGRA8 (DXGI tone-maps for us), and on older Windows fall back to
//     DuplicateOutput and convert whatever format arrives ourselves.
//
//  3. Recovery. DXGI_ERROR_ACCESS_LOST (lock screen, UAC secure desktop,
//     display sleep, mode change) requires re-creating the duplication, and
//     re-creation *fails* while the secure desktop is up. Callers must keep
//     retrying via recreate() until ready() is true again.
//
// It also rotates portrait/inverted outputs into desktop orientation so the
// LED mapping matches what the user sees.

#ifdef _WIN32

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif

#include <windows.h>
#include <d3d11.h>
#include <dxgi1_5.h>
#include <wrl/client.h>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

namespace hyperion {
namespace win {

class DxgiDuplicator {
public:
    enum class Acquire {
        Frame,     // data()/pitch() valid until release()
        NoChange,  // nothing new on screen (timeout or pointer-only update)
        Lost       // duplication torn down — call recreate() until ready()
    };

    DxgiDuplicator() = default;
    DxgiDuplicator(const DxgiDuplicator&) = delete;
    DxgiDuplicator& operator=(const DxgiDuplicator&) = delete;
    ~DxgiDuplicator() { reset(); }

    // Create the device on the primary output's adapter and start duplicating.
    bool init() {
        reset();
        m_device.Reset();
        m_context.Reset();
        m_output.Reset();

        Microsoft::WRL::ComPtr<IDXGIAdapter1> adapter;
        if (!findPrimaryOutput(adapter, m_output)) return false;

        D3D_FEATURE_LEVEL level;
        HRESULT hr = D3D11CreateDevice(adapter.Get(), D3D_DRIVER_TYPE_UNKNOWN, nullptr, 0,
                                       nullptr, 0, D3D11_SDK_VERSION,
                                       &m_device, &level, &m_context);
        if (FAILED(hr)) return false;
        return recreate();
    }

    // (Re)build the duplication + staging texture on the existing device.
    // Fails while the secure desktop is active or when the display is off;
    // callers retry later.
    bool recreate() {
        m_dup.Reset();
        m_staging.Reset();
        m_frameHeld = false;
        if (!m_device || !m_output) return false;

        // Prefer DuplicateOutput1 with an explicit BGRA8 request (Win10 1703+):
        // DXGI converts HDR/10-bit desktops for us.
        Microsoft::WRL::ComPtr<IDXGIOutput5> out5;
        if (SUCCEEDED(m_output.As(&out5))) {
            const DXGI_FORMAT wanted[] = { DXGI_FORMAT_B8G8R8A8_UNORM };
            HRESULT hr = out5->DuplicateOutput1(m_device.Get(), 0, 1, wanted, &m_dup);
            if (FAILED(hr)) m_dup.Reset();
        }
        if (!m_dup) {
            Microsoft::WRL::ComPtr<IDXGIOutput1> out1;
            if (FAILED(m_output.As(&out1))) return false;
            if (FAILED(out1->DuplicateOutput(m_device.Get(), &m_dup))) { m_dup.Reset(); return false; }
        }

        DXGI_OUTDUPL_DESC desc{};
        m_dup->GetDesc(&desc);
        m_texW     = desc.ModeDesc.Width;
        m_texH     = desc.ModeDesc.Height;
        m_format   = desc.ModeDesc.Format;
        m_rotation = desc.Rotation;
        if (!supportedFormat(m_format)) { m_dup.Reset(); return false; }

        // The staging texture must match the duplicated surface exactly —
        // CopyResource silently does nothing otherwise.
        D3D11_TEXTURE2D_DESC td{};
        td.Width            = m_texW;
        td.Height           = m_texH;
        td.MipLevels        = 1;
        td.ArraySize        = 1;
        td.Format           = m_format;
        td.SampleDesc.Count = 1;
        td.Usage            = D3D11_USAGE_STAGING;
        td.CPUAccessFlags   = D3D11_CPU_ACCESS_READ;
        if (FAILED(m_device->CreateTexture2D(&td, nullptr, &m_staging))) {
            m_dup.Reset();
            return false;
        }
        return true;
    }

    void reset() {
        if (m_frameHeld) release();
        m_dup.Reset();
        m_staging.Reset();
    }

    bool ready() const { return m_dup != nullptr; }

    // Desktop-oriented size (rotation already applied).
    UINT width()  const { return rotated90() ? m_texH : m_texW; }
    UINT height() const { return rotated90() ? m_texW : m_texH; }

    // After Acquire::Frame: packed BGRA8 pixels in desktop orientation.
    const uint8_t* data()  const { return m_data; }
    UINT           pitch() const { return m_pitch; }

    Acquire acquire(UINT timeoutMs) {
        if (!m_dup) return Acquire::Lost;
        if (m_frameHeld) release();

        Microsoft::WRL::ComPtr<IDXGIResource> resource;
        DXGI_OUTDUPL_FRAME_INFO info{};
        HRESULT hr = m_dup->AcquireNextFrame(timeoutMs, &info, &resource);
        if (hr == DXGI_ERROR_WAIT_TIMEOUT) return Acquire::NoChange;
        if (FAILED(hr)) {
            // ACCESS_LOST is the expected one; anything else unexpected is
            // treated the same way — tear down and rebuild, never report it
            // as a network failure.
            m_dup.Reset();
            m_staging.Reset();
            return Acquire::Lost;
        }

        // A pointer-only update (mouse moved, nothing else) carries no new
        // desktop image; skip the copy.
        if (info.LastPresentTime.QuadPart == 0) {
            m_dup->ReleaseFrame();
            return Acquire::NoChange;
        }

        Microsoft::WRL::ComPtr<ID3D11Texture2D> tex;
        if (FAILED(resource.As(&tex))) { m_dup->ReleaseFrame(); return Acquire::NoChange; }
        m_context->CopyResource(m_staging.Get(), tex.Get());
        m_dup->ReleaseFrame();

        D3D11_MAPPED_SUBRESOURCE mapped{};
        if (FAILED(m_context->Map(m_staging.Get(), 0, D3D11_MAP_READ, 0, &mapped)))
            return Acquire::NoChange;
        m_mapped = true;
        m_frameHeld = true;

        const auto* src = static_cast<const uint8_t*>(mapped.pData);
        if (m_format == DXGI_FORMAT_B8G8R8A8_UNORM && !rotated() ) {
            // Fast path: hand out the mapped memory directly.
            m_data  = src;
            m_pitch = mapped.RowPitch;
            return Acquire::Frame;
        }

        // Slow path: convert to BGRA8 and/or rotate into m_frame.
        const uint8_t* bgra = src;
        UINT bgraPitch = mapped.RowPitch;
        if (m_format != DXGI_FORMAT_B8G8R8A8_UNORM) {
            convertToBgra(src, mapped.RowPitch, m_convert);
            bgra = m_convert.data();
            bgraPitch = m_texW * 4;
        }
        if (rotated()) {
            rotateBgra(bgra, bgraPitch, m_frame);
            m_data = m_frame.data();
        } else {
            m_data = bgra;
        }
        m_pitch = width() * 4;
        return Acquire::Frame;
    }

    void release() {
        if (m_mapped && m_staging) m_context->Unmap(m_staging.Get(), 0);
        m_mapped = false;
        m_frameHeld = false;
        m_data = nullptr;
    }

private:
    bool rotated()   const { return m_rotation != DXGI_MODE_ROTATION_IDENTITY &&
                                    m_rotation != DXGI_MODE_ROTATION_UNSPECIFIED; }
    bool rotated90() const { return m_rotation == DXGI_MODE_ROTATION_ROTATE90 ||
                                    m_rotation == DXGI_MODE_ROTATION_ROTATE270; }

    static bool supportedFormat(DXGI_FORMAT f) {
        return f == DXGI_FORMAT_B8G8R8A8_UNORM || f == DXGI_FORMAT_R8G8B8A8_UNORM ||
               f == DXGI_FORMAT_R16G16B16A16_FLOAT || f == DXGI_FORMAT_R10G10B10A2_UNORM;
    }

    // Primary monitor = the attached output whose desktop rect starts at the
    // origin. Falls back to the first attached output on any adapter.
    static bool findPrimaryOutput(Microsoft::WRL::ComPtr<IDXGIAdapter1>& adapterOut,
                                  Microsoft::WRL::ComPtr<IDXGIOutput>& outputOut) {
        Microsoft::WRL::ComPtr<IDXGIFactory1> factory;
        if (FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&factory)))) return false;

        Microsoft::WRL::ComPtr<IDXGIAdapter1> firstAdapter;
        Microsoft::WRL::ComPtr<IDXGIOutput>   firstOutput;
        for (UINT a = 0;; ++a) {
            Microsoft::WRL::ComPtr<IDXGIAdapter1> adapter;
            if (factory->EnumAdapters1(a, &adapter) != S_OK) break;
            for (UINT o = 0;; ++o) {
                Microsoft::WRL::ComPtr<IDXGIOutput> output;
                if (adapter->EnumOutputs(o, &output) != S_OK) break;
                DXGI_OUTPUT_DESC d{};
                if (FAILED(output->GetDesc(&d)) || !d.AttachedToDesktop) continue;
                if (!firstOutput) { firstAdapter = adapter; firstOutput = output; }
                if (d.DesktopCoordinates.left == 0 && d.DesktopCoordinates.top == 0) {
                    adapterOut = adapter;
                    outputOut  = output;
                    return true;
                }
            }
        }
        if (!firstOutput) return false;
        adapterOut = firstAdapter;
        outputOut  = firstOutput;
        return true;
    }

    // ── Format conversion (fallback path when DuplicateOutput1 is unavailable) ──

    static float halfToFloat(uint16_t h) {
        uint32_t sign = (h >> 15) & 1, exp = (h >> 10) & 0x1F, man = h & 0x3FF;
        uint32_t bits;
        if (exp == 0) {
            if (man == 0) bits = sign << 31;
            else {  // subnormal
                exp = 127 - 15 + 1;
                while ((man & 0x400) == 0) { man <<= 1; --exp; }
                man &= 0x3FF;
                bits = (sign << 31) | (exp << 23) | (man << 13);
            }
        } else if (exp == 31) {
            bits = (sign << 31) | 0x7F800000 | (man << 13);
        } else {
            bits = (sign << 31) | ((exp + 112) << 23) | (man << 13);
        }
        float f;
        std::memcpy(&f, &bits, sizeof(f));
        return f;
    }

    // scRGB linear → 8-bit sRGB, clamped to SDR range.
    static uint8_t linearToSrgb8(float v) {
        if (!(v > 0.f)) return 0;
        if (v >= 1.f) return 255;
        float s = v <= 0.0031308f ? 12.92f * v : 1.055f * std::pow(v, 1.f / 2.4f) - 0.055f;
        return static_cast<uint8_t>(s * 255.f + 0.5f);
    }

    void convertToBgra(const uint8_t* src, UINT srcPitch, std::vector<uint8_t>& out) const {
        out.resize(static_cast<size_t>(m_texW) * m_texH * 4);
        for (UINT y = 0; y < m_texH; ++y) {
            const uint8_t* row = src + static_cast<size_t>(y) * srcPitch;
            uint8_t* dst = out.data() + static_cast<size_t>(y) * m_texW * 4;
            switch (m_format) {
            case DXGI_FORMAT_R8G8B8A8_UNORM:
                for (UINT x = 0; x < m_texW; ++x, row += 4, dst += 4) {
                    dst[0] = row[2]; dst[1] = row[1]; dst[2] = row[0]; dst[3] = row[3];
                }
                break;
            case DXGI_FORMAT_R10G10B10A2_UNORM:
                for (UINT x = 0; x < m_texW; ++x, row += 4, dst += 4) {
                    uint32_t p; std::memcpy(&p, row, 4);
                    dst[2] = static_cast<uint8_t>((p        & 0x3FF) >> 2);  // R
                    dst[1] = static_cast<uint8_t>(((p >> 10) & 0x3FF) >> 2); // G
                    dst[0] = static_cast<uint8_t>(((p >> 20) & 0x3FF) >> 2); // B
                    dst[3] = 255;
                }
                break;
            case DXGI_FORMAT_R16G16B16A16_FLOAT:
                for (UINT x = 0; x < m_texW; ++x, row += 8, dst += 4) {
                    uint16_t h[4]; std::memcpy(h, row, 8);
                    dst[2] = linearToSrgb8(halfToFloat(h[0]));
                    dst[1] = linearToSrgb8(halfToFloat(h[1]));
                    dst[0] = linearToSrgb8(halfToFloat(h[2]));
                    dst[3] = 255;
                }
                break;
            default:
                std::memcpy(dst, row, static_cast<size_t>(m_texW) * 4);
                break;
            }
        }
    }

    // Rotate the texture into desktop orientation. Mapping (desktop → texture)
    // follows the DesktopDuplication SDK sample's dirty-rect transforms:
    //   ROTATE90 : tex(x,y) = (ly, texH-1-lx)
    //   ROTATE180: tex(x,y) = (texW-1-lx, texH-1-ly)
    //   ROTATE270: tex(x,y) = (texW-1-ly, lx)
    void rotateBgra(const uint8_t* src, UINT srcPitch, std::vector<uint8_t>& out) const {
        const UINT W = width(), H = height();
        out.resize(static_cast<size_t>(W) * H * 4);
        for (UINT ly = 0; ly < H; ++ly) {
            uint8_t* dst = out.data() + static_cast<size_t>(ly) * W * 4;
            for (UINT lx = 0; lx < W; ++lx, dst += 4) {
                UINT tx, ty;
                switch (m_rotation) {
                case DXGI_MODE_ROTATION_ROTATE90:  tx = ly;              ty = m_texH - 1 - lx; break;
                case DXGI_MODE_ROTATION_ROTATE180: tx = m_texW - 1 - lx; ty = m_texH - 1 - ly; break;
                case DXGI_MODE_ROTATION_ROTATE270: tx = m_texW - 1 - ly; ty = lx;              break;
                default:                           tx = lx;              ty = ly;              break;
                }
                std::memcpy(dst, src + static_cast<size_t>(ty) * srcPitch + static_cast<size_t>(tx) * 4, 4);
            }
        }
    }

    Microsoft::WRL::ComPtr<ID3D11Device>           m_device;
    Microsoft::WRL::ComPtr<ID3D11DeviceContext>    m_context;
    Microsoft::WRL::ComPtr<IDXGIOutput>            m_output;
    Microsoft::WRL::ComPtr<IDXGIOutputDuplication> m_dup;
    Microsoft::WRL::ComPtr<ID3D11Texture2D>        m_staging;

    UINT               m_texW = 0, m_texH = 0;
    DXGI_FORMAT        m_format   = DXGI_FORMAT_B8G8R8A8_UNORM;
    DXGI_MODE_ROTATION m_rotation = DXGI_MODE_ROTATION_IDENTITY;

    bool           m_mapped    = false;
    bool           m_frameHeld = false;
    const uint8_t* m_data      = nullptr;
    UINT           m_pitch     = 0;
    std::vector<uint8_t> m_convert;  // format-converted BGRA8 (fallback path)
    std::vector<uint8_t> m_frame;    // rotated BGRA8
};

} // namespace win
} // namespace hyperion

#endif // _WIN32
