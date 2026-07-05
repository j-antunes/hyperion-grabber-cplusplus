#include "frame_processor.h"
#include <cstring>
#include <algorithm>

namespace hyperion {

FrameProcessor::FrameProcessor(const FrameConfig& config)
    : m_config(config) {
    m_buffer.resize(config.targetWidth * config.targetHeight);
    m_crop = {0, 0, config.sourceWidth, config.sourceHeight};
}

// Luminance of a 4-byte pixel. Weights are for R/G/B; `bgra` selects which of
// p[0]/p[2] is red so BGRA (DXGI, 32-bpp X11) is weighted the same as RGBA.
static inline int luma4(const uint8_t* p, bool bgra) {
    const int w0 = bgra ? 29 : 77;  // p[0] is B (BGRA) or R (RGBA)
    const int w2 = bgra ? 77 : 29;  // p[2] is R (BGRA) or B (RGBA)
    return (p[0] * w0 + p[1] * 150 + p[2] * w2) >> 8;
}

// Sample 8 evenly-spaced columns across a row; return true if all are below threshold.
static bool isBlackRow(const uint8_t* pix, int rowStride, int srcW, int y, int threshold, bool bgra) {
    constexpr int SAMPLES = 8;
    for (int i = 0; i < SAMPLES; ++i) {
        int x = srcW * (i + 1) / (SAMPLES + 1);
        if (luma4(pix + y * rowStride + x * 4, bgra) > threshold) return false;
    }
    return true;
}

// Sample 8 evenly-spaced rows across a column; return true if all are below threshold.
static bool isBlackCol(const uint8_t* pix, int rowStride, int srcH, int x, int threshold, bool bgra) {
    constexpr int SAMPLES = 8;
    for (int i = 0; i < SAMPLES; ++i) {
        int y = srcH * (i + 1) / (SAMPLES + 1);
        if (luma4(pix + y * rowStride + x * 4, bgra) > threshold) return false;
    }
    return true;
}

CropRect FrameProcessor::detectBlackBars(const uint8_t* pix, int rowStride,
                                          int srcW, int srcH,
                                          int threshold, bool bgra) {
    const int maxBarH = srcH / 4;  // never crop more than 25% from any edge
    const int maxBarW = srcW / 4;

    int top = 0;
    for (int y = 0; y < maxBarH; ++y) {
        if (isBlackRow(pix, rowStride, srcW, y, threshold, bgra)) top = y + 1;
        else break;
    }

    int bottom = srcH;
    for (int y = srcH - 1; y >= srcH - maxBarH; --y) {
        if (isBlackRow(pix, rowStride, srcW, y, threshold, bgra)) bottom = y;
        else break;
    }

    int left = 0;
    for (int x = 0; x < maxBarW; ++x) {
        if (isBlackCol(pix, rowStride, srcH, x, threshold, bgra)) left = x + 1;
        else break;
    }

    int right = srcW;
    for (int x = srcW - 1; x >= srcW - maxBarW; --x) {
        if (isBlackCol(pix, rowStride, srcH, x, threshold, bgra)) right = x;
        else break;
    }

    int w = std::max(1, right - left);
    int h = std::max(1, bottom - top);
    return {left, top, w, h};
}

// Shared crop + downscale for the two 4-byte layouts. `bgra` swaps R/B on both
// the black-bar detection and the stored pixel so RGBA (Android) and BGRA
// (DXGI, 32-bpp X11) produce identical output.
std::vector<Color> FrameProcessor::process4(const uint8_t* data, int rowStride, bool bgra) {
    // Refresh crop every N frames
    if (m_framesSinceCropUpdate == 0) {
        m_crop = detectBlackBars(data, rowStride, m_config.sourceWidth, m_config.sourceHeight, 16, bgra);
    }
    if (++m_framesSinceCropUpdate >= CROP_UPDATE_INTERVAL) {
        m_framesSinceCropUpdate = 0;
    }

    const float xScale = static_cast<float>(m_crop.w) / m_config.targetWidth;
    const float yScale = static_cast<float>(m_crop.h) / m_config.targetHeight;
    const int rIdx = bgra ? 2 : 0;
    const int bIdx = bgra ? 0 : 2;

    for (int y = 0; y < m_config.targetHeight; ++y) {
        int srcY = m_crop.y + static_cast<int>(y * yScale);
        for (int x = 0; x < m_config.targetWidth; ++x) {
            int srcX = m_crop.x + static_cast<int>(x * xScale);
            const uint8_t* px = data + srcY * rowStride + srcX * 4;
            m_buffer[y * m_config.targetWidth + x] = { px[rIdx], px[1], px[bIdx] };
        }
    }
    return m_buffer;
}

std::vector<Color> FrameProcessor::processRGBA(const uint8_t* data, int rowStride) {
    return process4(data, rowStride, /*bgra=*/false);
}

// DXGI captures BGRA — swap R and B before storing
std::vector<Color> FrameProcessor::processBGRA(const uint8_t* data, int rowStride) {
    return process4(data, rowStride, /*bgra=*/true);
}

std::vector<Color> FrameProcessor::processRGB(const uint8_t* data, int rowStride) {
    const float xScale = static_cast<float>(m_config.sourceWidth)  / m_config.targetWidth;
    const float yScale = static_cast<float>(m_config.sourceHeight) / m_config.targetHeight;

    for (int y = 0; y < m_config.targetHeight; ++y) {
        int srcY = static_cast<int>(y * yScale);
        for (int x = 0; x < m_config.targetWidth; ++x) {
            int srcX = static_cast<int>(x * xScale);
            const uint8_t* px = data + srcY * rowStride + srcX * 3;
            m_buffer[y * m_config.targetWidth + x] = { px[0], px[1], px[2] };
        }
    }
    return m_buffer;
}

} // namespace hyperion
