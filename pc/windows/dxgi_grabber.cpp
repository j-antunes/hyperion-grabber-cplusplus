#ifdef _WIN32

#include "dxgi_grabber.h"

namespace hyperion {

DXGIGrabber::DXGIGrabber(const FrameConfig& config, std::shared_ptr<HyperionClient> client)
    : GrabberBase(config, std::move(client)) {}

DXGIGrabber::~DXGIGrabber() {
    stop();
}

// All the DXGI work (primary-output selection, HDR-safe format, rotation,
// ACCESS_LOST handling) lives in the shared pc/windows/dxgi_duplicator.h so
// the desktop JNI helper behaves identically.
bool DXGIGrabber::initCapture() {
    if (!m_dup.init()) return false;   // fails on the secure desktop → base retries
    // The duplicated frames come in at the real desktop resolution (and
    // orientation); the frame processor caches the source size, so keep
    // m_config in sync. The base rebuilds the processor after each init.
    m_config.sourceWidth  = static_cast<int>(m_dup.width());
    m_config.sourceHeight = static_cast<int>(m_dup.height());
    return true;
}

void DXGIGrabber::deinitCapture() {
    m_dup.reset();
}

bool DXGIGrabber::isDisplayOn() {
    return m_power.isDisplayOn();
}

CaptureResult DXGIGrabber::captureFrame(FrameProcessor& processor) {
    switch (m_dup.acquire(100)) {
    case win::DxgiDuplicator::Acquire::NoChange:
        return CaptureResult::NoFrame;  // static screen / pointer-only update
    case win::DxgiDuplicator::Acquire::Lost:
        // Resolution change, fullscreen switch, UAC prompt, Win+L: the
        // duplication is gone. The base tears down and re-inits capture; the
        // TCP link stays up.
        return CaptureResult::Lost;
    case win::DxgiDuplicator::Acquire::Frame:
        break;
    }

    // Defensive: never feed the processor a frame of a size it wasn't built for.
    if (static_cast<int>(m_dup.width())  != processor.config().sourceWidth ||
        static_cast<int>(m_dup.height()) != processor.config().sourceHeight) {
        m_dup.release();
        return CaptureResult::Lost;
    }

    auto pixels = processor.processBGRA(m_dup.data(), m_dup.pitch());
    m_dup.release();

    return m_client->sendFrame(pixels, m_config.targetWidth, m_config.targetHeight)
        ? CaptureResult::Sent : CaptureResult::Failed;
}

} // namespace hyperion

#endif // _WIN32
