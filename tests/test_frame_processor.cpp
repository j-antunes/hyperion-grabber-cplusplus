#include <gtest/gtest.h>
#include "frame_processor.h"

// 1-pixel sample: nearest-neighbour picks top-left source pixel
TEST(FrameProcessor, ExtractsRGBFromRGBA) {
    hyperion::FrameConfig cfg{2, 2, 1, 1, 25};
    hyperion::FrameProcessor proc(cfg);

    // 2×2 RGBA: TL=red, TR=green, BL=blue, BR=yellow
    uint8_t rgba[] = {
        255,   0,   0, 255,
          0, 255,   0, 255,
          0,   0, 255, 255,
        255, 255,   0, 255,
    };

    auto result = proc.processRGBA(rgba, /*rowStride=*/8);

    ASSERT_EQ(result.size(), 1u);
    EXPECT_EQ(result[0].r, 255);
    EXPECT_EQ(result[0].g,   0);
    EXPECT_EQ(result[0].b,   0);
}

// Alpha channel must be discarded
TEST(FrameProcessor, AlphaChannelIgnored) {
    hyperion::FrameConfig cfg{1, 1, 1, 1, 25};
    hyperion::FrameProcessor proc(cfg);

    uint8_t rgba[] = {10, 20, 30, 99};
    auto result = proc.processRGBA(rgba, /*rowStride=*/4);

    ASSERT_EQ(result.size(), 1u);
    EXPECT_EQ(result[0].r, 10);
    EXPECT_EQ(result[0].g, 20);
    EXPECT_EQ(result[0].b, 30);
}

// Uniform image stays uniform after downscale
TEST(FrameProcessor, UniformColourPreservedAfterDownscale) {
    hyperion::FrameConfig cfg{4, 4, 2, 2, 25};
    hyperion::FrameProcessor proc(cfg);

    std::vector<uint8_t> rgba(4 * 4 * 4);
    for (int i = 0; i < 16; i++) {
        rgba[i*4+0] = 0;
        rgba[i*4+1] = 128;
        rgba[i*4+2] = 255;
        rgba[i*4+3] = 255;
    }

    auto result = proc.processRGBA(rgba.data(), /*rowStride=*/16);

    ASSERT_EQ(result.size(), 4u);
    for (const auto& px : result) {
        EXPECT_EQ(px.r,   0);
        EXPECT_EQ(px.g, 128);
        EXPECT_EQ(px.b, 255);
    }
}

// Output pixel count equals target width × height
TEST(FrameProcessor, OutputSizeMatchesTargetDimensions) {
    hyperion::FrameConfig cfg{1920, 1080, 216, 112, 25};
    hyperion::FrameProcessor proc(cfg);

    std::vector<uint8_t> rgba(1920 * 1080 * 4, 128);
    auto result = proc.processRGBA(rgba.data(), /*rowStride=*/1920 * 4);

    EXPECT_EQ(result.size(), static_cast<size_t>(216 * 112));
}

// rowStride wider than image width must be handled correctly
TEST(FrameProcessor, RespectsRowStride) {
    // 2-pixel wide image with 4 bytes of padding per row (stride=12 instead of 8)
    hyperion::FrameConfig cfg{2, 2, 1, 1, 25};
    hyperion::FrameProcessor proc(cfg);

    // Row 0: [red] [green] [PADDING PADDING PADDING]
    // Row 1: [blue] [yellow] [PADDING PADDING PADDING]
    uint8_t rgba[] = {
        255,   0,   0, 255,   0, 255,   0, 255,   0,   0,   0,   0,
          0,   0, 255, 255, 255, 255,   0, 255,   0,   0,   0,   0,
    };

    auto result = proc.processRGBA(rgba, /*rowStride=*/12);

    ASSERT_EQ(result.size(), 1u);
    EXPECT_EQ(result[0].r, 255); // samples TL = red
    EXPECT_EQ(result[0].g,   0);
    EXPECT_EQ(result[0].b,   0);
}
