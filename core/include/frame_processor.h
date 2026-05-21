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

struct CropRect {
    int x, y, w, h;
};

class FrameProcessor {
public:
    explicit FrameProcessor(const FrameConfig& config);

    // Input: raw RGBA or RGBX bytes from platform capture API
    // Output: downscaled RGB pixels ready for HyperionClient::sendFrame
    std::vector<Color> processRGBA(const uint8_t* data, int rowStride);
    std::vector<Color> processRGB(const uint8_t* data, int rowStride);

    const FrameConfig& config() const { return m_config; }

    // Exposed for unit tests
    static CropRect detectBlackBars(const uint8_t* rgba, int rowStride,
                                    int srcW, int srcH,
                                    int luminanceThreshold = 16);

private:
    FrameConfig m_config;
    std::vector<Color> m_buffer;

    CropRect   m_crop{};         // cached crop, updated every N frames
    int        m_framesSinceCropUpdate = 0;
    static constexpr int CROP_UPDATE_INTERVAL = 60;
};

} // namespace hyperion
