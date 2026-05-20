#include "frame_processor.h"
#include <cstring>

namespace hyperion {

FrameProcessor::FrameProcessor(const FrameConfig& config)
    : m_config(config) {
    m_buffer.resize(config.targetWidth * config.targetHeight);
}

std::vector<Color> FrameProcessor::processRGBA(const uint8_t* data, int rowStride) {
    const float xScale = static_cast<float>(m_config.sourceWidth)  / m_config.targetWidth;
    const float yScale = static_cast<float>(m_config.sourceHeight) / m_config.targetHeight;

    for (int y = 0; y < m_config.targetHeight; ++y) {
        int srcY = static_cast<int>(y * yScale);
        for (int x = 0; x < m_config.targetWidth; ++x) {
            int srcX = static_cast<int>(x * xScale);
            const uint8_t* px = data + srcY * rowStride + srcX * 4;
            m_buffer[y * m_config.targetWidth + x] = { px[0], px[1], px[2] };
        }
    }
    return m_buffer;
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
