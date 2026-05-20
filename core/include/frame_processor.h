#pragma once

#include "hyperion_client.h"
#include <cstdint>
#include <vector>

namespace hyperion {

struct FrameConfig {
    int sourceWidth;
    int sourceHeight;
    int targetWidth;   // downscaled resolution sent to Hyperion
    int targetHeight;
    int framerate;     // target capture FPS
};

class FrameProcessor {
public:
    explicit FrameProcessor(const FrameConfig& config);

    // Input: raw RGBA or RGBX bytes from platform capture API
    // Output: downscaled RGB pixels ready for HyperionClient::sendFrame
    std::vector<Color> processRGBA(const uint8_t* data, int rowStride);
    std::vector<Color> processRGB(const uint8_t* data, int rowStride);

    const FrameConfig& config() const { return m_config; }

private:
    FrameConfig m_config;
    std::vector<Color> m_buffer;
};

} // namespace hyperion
