#ifdef __linux__

#include "x11_grabber.h"
#include <X11/Xutil.h>
#include <X11/extensions/dpms.h>
#include <atomic>
#include <cstdio>

namespace hyperion {

namespace {

// Xlib's default error handler calls exit() on a protocol error such as the
// BadMatch that XGetImage raises when the cached size no longer matches the
// root window (e.g. after an xrandr resolution change). Swallow it and record
// the error so captureFrame can recover by re-initialising at the new size.
std::atomic<bool> g_xError{false};

int nonFatalXErrorHandler(Display*, XErrorEvent* ev) {
    g_xError = true;
    fprintf(stderr, "[x11] X error %d (request %d) — reinitialising capture\n",
            ev->error_code, ev->request_code);
    return 0;
}

} // namespace

X11Grabber::X11Grabber(const FrameConfig& config, std::shared_ptr<HyperionClient> client)
    : GrabberBase(config, std::move(client)) {}

X11Grabber::~X11Grabber() {
    stop();
}

bool X11Grabber::initCapture() {
    XSetErrorHandler(nonFatalXErrorHandler);
    m_display = XOpenDisplay(nullptr);
    if (!m_display) return false;
    m_screen = DefaultScreen(m_display);
    m_root   = RootWindow(m_display, m_screen);

    int ev = 0, err = 0;
    m_hasDpms = DPMSQueryExtension(m_display, &ev, &err) && DPMSCapable(m_display);

    // Capture whatever the screen actually is — a hardcoded size makes
    // XGetImage fail (BadMatch) on smaller screens.
    readScreenSize();
    return true;
}

void X11Grabber::deinitCapture() {
    if (m_display) {
        XCloseDisplay(m_display);
        m_display = nullptr;
    }
}

// Query the root window geometry with a round trip: DisplayWidth/Height only
// reflect the values cached when the connection was opened, so they miss a
// RandR mode change made while we're running.
bool X11Grabber::readScreenSize() {
    Window rootRet; int x, y; unsigned w = 0, h = 0, border, depth;
    if (!XGetGeometry(m_display, m_root, &rootRet, &x, &y, &w, &h, &border, &depth) || w == 0 || h == 0) {
        w = static_cast<unsigned>(DisplayWidth(m_display, m_screen));
        h = static_cast<unsigned>(DisplayHeight(m_display, m_screen));
    }
    m_lastSizeCheck = std::chrono::steady_clock::now();
    bool changed = static_cast<int>(w) != m_config.sourceWidth ||
                   static_cast<int>(h) != m_config.sourceHeight;
    m_config.sourceWidth  = static_cast<int>(w);
    m_config.sourceHeight = static_cast<int>(h);
    return changed;
}

// X11 DPMS: Standby/Suspend/Off all mean the panel is dark. Mirrors the
// Android SCREEN_OFF pause and Windows GUID_CONSOLE_DISPLAY_STATE.
bool X11Grabber::isDisplayOn() {
    if (!m_hasDpms || !m_display) return true;
    CARD16 level = DPMSModeOn;
    BOOL   enabled = False;
    if (!DPMSInfo(m_display, &level, &enabled)) return true;
    if (!enabled) return true;
    return level == DPMSModeOn;
}

CaptureResult X11Grabber::captureFrame(FrameProcessor& processor) {
    // A resolution *increase* keeps XGetImage happy (we'd silently capture
    // just the top-left corner), so poll the geometry periodically as well.
    if (std::chrono::steady_clock::now() - m_lastSizeCheck >
        std::chrono::milliseconds(SIZE_CHECK_MS)) {
        if (readScreenSize()) return CaptureResult::Lost;
    }

    g_xError = false;
    XImage* img = XGetImage(m_display, m_root, 0, 0,
                            m_config.sourceWidth, m_config.sourceHeight,
                            AllPlanes, ZPixmap);
    if (!img || g_xError) {
        // Almost always a resolution change made the cached size invalid.
        // Re-initialise capture (reopens the display and re-queries the size)
        // rather than tearing down the healthy TCP connection.
        if (img) XDestroyImage(img);
        return CaptureResult::Lost;
    }

    std::vector<Color> pixels;
    if (img->bits_per_pixel == 32) {
        // 32-bit ZPixmap on little-endian is B,G,R,X in memory — same layout
        // as BGRA, so we can feed it straight to the processor (which also
        // runs black-bar detection on this path).
        pixels = processor.processBGRA(
            reinterpret_cast<const uint8_t*>(img->data),
            img->bytes_per_line);
    } else {
        // Fallback for unusual visuals: slow per-pixel read, no crop detection
        std::vector<uint8_t> rgb(m_config.sourceWidth * m_config.sourceHeight * 3);
        for (int y = 0; y < m_config.sourceHeight; ++y) {
            for (int x = 0; x < m_config.sourceWidth; ++x) {
                unsigned long px = XGetPixel(img, x, y);
                size_t idx = (y * m_config.sourceWidth + x) * 3;
                rgb[idx + 0] = (px >> 16) & 0xFF; // R
                rgb[idx + 1] = (px >>  8) & 0xFF; // G
                rgb[idx + 2] = (px >>  0) & 0xFF; // B
            }
        }
        pixels = processor.processRGB(rgb.data(), m_config.sourceWidth * 3);
    }
    XDestroyImage(img);

    return m_client->sendFrame(pixels, m_config.targetWidth, m_config.targetHeight)
        ? CaptureResult::Sent : CaptureResult::Failed;
}

} // namespace hyperion

#endif // __linux__
