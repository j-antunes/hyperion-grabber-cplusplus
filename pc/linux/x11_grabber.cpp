#ifdef __linux__

#include "x11_grabber.h"
#include <X11/Xutil.h>
#include <stdexcept>

namespace hyperion {

X11Grabber::X11Grabber(const FrameConfig& config, std::shared_ptr<HyperionClient> client)
    : GrabberBase(config, std::move(client)) {}

X11Grabber::~X11Grabber() {
    stop();
}

bool X11Grabber::initCapture() {
    m_display = XOpenDisplay(nullptr);
    if (!m_display) return false;
    m_screen = DefaultScreen(m_display);
    m_root   = RootWindow(m_display, m_screen);
    return true;
}

void X11Grabber::deinitCapture() {
    if (m_display) {
        XCloseDisplay(m_display);
        m_display = nullptr;
    }
}

bool X11Grabber::captureFrame(FrameProcessor& processor) {
    XImage* img = XGetImage(m_display, m_root, 0, 0,
                            m_config.sourceWidth, m_config.sourceHeight,
                            AllPlanes, ZPixmap);
    if (!img) return false;

    // XImage data is BGRX on most systems; reorder to RGB
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
    XDestroyImage(img);

    auto pixels = processor.processRGB(rgb.data(), m_config.sourceWidth * 3);
    return m_client->sendFrame(pixels, m_config.targetWidth, m_config.targetHeight);
}

} // namespace hyperion

#endif // __linux__
