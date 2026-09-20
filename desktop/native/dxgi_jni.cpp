// Windows Desktop Duplication (DXGI) screen capture exposed to the JVM desktop
// app via JNI. The capture itself lives in the shared header
// pc/windows/dxgi_duplicator.h (same code as the C++ PC grabber); this file
// only adds the downscale and the JNI plumbing. The Kotlin GrabberState owns
// pacing, sending, keepalive and reconnect.
//
// Why this exists: java.awt.Robot captures via GDI BitBlt, which makes the
// Windows hardware mouse cursor flicker during continuous capture. Desktop
// Duplication composites the desktop image without the cursor overlay, so the
// pointer stays solid. Linux/macOS keep using Robot (see ScreenGrabber.kt).

#ifdef _WIN32

#include "dxgi_duplicator.h"
#include "display_power.h"

#include <jni.h>
#include <cstdint>
#include <vector>

namespace {

// How long to wait before retrying duplication after it was lost. Re-creation
// fails for as long as the secure desktop (lock screen / UAC) is up, so we
// must keep trying rather than give up after the first failure.
constexpr ULONGLONG RECREATE_RETRY_MS = 1000;

struct Capturer {
    hyperion::win::DxgiDuplicator       dup;
    hyperion::win::DisplayPowerMonitor  power;
    std::vector<uint8_t>                rgb;   // reused frame buffer
    ULONGLONG lastRecreateMs = 0;
    bool      firstFrame     = true;
};

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
    if (!c->dup.init()) {
        delete c;          // headless/GPU-less host (e.g. CI) → caller uses Robot
        return 0;
    }
    return reinterpret_cast<jlong>(c);
}

// Returns dstW*dstH*3 RGB bytes for a fresh frame, or null when there is
// nothing new to send: the desktop hasn't changed, or the duplication is
// being rebuilt. The Kotlin side reuses its previous frame and its keepalive
// resends it while nothing changes.
JNIEXPORT jbyteArray JNICALL
Java_com_hyperion_grabber_WindowsCapture_nativeCapture(JNIEnv* env, jobject,
                                                       jlong handle, jint dstW, jint dstH) {
    auto* c = reinterpret_cast<Capturer*>(handle);
    if (!c || dstW <= 0 || dstH <= 0) return nullptr;

    if (!c->dup.ready()) {
        ULONGLONG now = GetTickCount64();
        if (now - c->lastRecreateMs >= RECREATE_RETRY_MS) {
            c->lastRecreateMs = now;
            if (c->dup.recreate()) c->firstFrame = true;
        }
        return nullptr;
    }

    // Wait briefly for the first frame; afterwards return immediately.
    UINT timeout = c->firstFrame ? 500 : 0;
    switch (c->dup.acquire(timeout)) {
    case hyperion::win::DxgiDuplicator::Acquire::NoChange:
        return nullptr;
    case hyperion::win::DxgiDuplicator::Acquire::Lost:
        c->lastRecreateMs = GetTickCount64();
        if (c->dup.recreate()) c->firstFrame = true;
        return nullptr;
    case hyperion::win::DxgiDuplicator::Acquire::Frame:
        break;
    }

    downscale(c->dup.data(), c->dup.pitch(), c->dup.width(), c->dup.height(),
              dstW, dstH, c->rgb);
    c->dup.release();
    c->firstFrame = false;

    jbyteArray arr = env->NewByteArray(static_cast<jsize>(c->rgb.size()));
    if (!arr) return nullptr;
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(c->rgb.size()),
                            reinterpret_cast<const jbyte*>(c->rgb.data()));
    return arr;
}

// Console display power state (GUID_CONSOLE_DISPLAY_STATE). The Kotlin loop
// pauses capture and drops the TCP connection while this is false.
JNIEXPORT jboolean JNICALL
Java_com_hyperion_grabber_WindowsCapture_nativeIsDisplayOn(JNIEnv*, jobject, jlong handle) {
    auto* c = reinterpret_cast<Capturer*>(handle);
    if (!c) return JNI_TRUE;
    return c->power.isDisplayOn() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_hyperion_grabber_WindowsCapture_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Capturer*>(handle);
}

}  // extern "C"

#endif  // _WIN32
