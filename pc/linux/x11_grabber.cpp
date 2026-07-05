#ifdef __linux__

#include "x11_grabber.h"
#include <X11/Xutil.h>
#include <cstdio>

namespace hyperion {

// Xlib's default error handler calls exit() on a protocol error such as the
// BadMatch that XGetImage raises when the cached size no longer matches the root
// window (e.g. after an xrandr resolution change). Swallow it and record the
// error so captureFrame can recover by re-initialising at the new resolution.
static bool g_xError = false;
static int  xErrorHandler(Display*, XErrorEvent*) { g_xError = true; return 0; }

X11Grabber::X11Grabber(const FrameConfig& config, std::shared_ptr<HyperionClient> client)
    : GrabberBase(config, std::move(client)) {}

X11Grabber::~X11Grabber() {
    stop();
}

bool X11Grabber::initCapture() {
    XSetErrorHandler(xErrorHandler);
    m_display = XOpenDisplay(nullptr);
    if (!m_display) return false;
    m_screen = DefaultScreen(m_display);
    m_root   = RootWindow(m_display, m_screen);

    // Capture whatever the screen actually is — a hardcoded size makes
    // XGetImage fail (BadMatch) on smaller screens.
    m_config.sourceWidth  = DisplayWidth(m_display, m_screen);
    m_config.sourceHeight = DisplayHeight(m_display, m_screen);
    return true;
}

void X11Grabber::deinitCapture() {
    if (m_display) {
        XCloseDisplay(m_display);
        m_display = nullptr;
    }
}

CaptureResult X11Grabber::captureFrame(FrameProcessor& processor) {
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
