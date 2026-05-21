#include <gtest/gtest.h>
#include "frame_processor.h"
#include <vector>

using namespace hyperion;

// Build a synthetic RGBA frame where rows [0, topBars) and [h-bottomBars, h) are black,
// and columns [0, leftBars) and [w-rightBars, w) are black; the rest is white.
static std::vector<uint8_t> makeFrame(int w, int h,
                                       int topBars, int bottomBars,
                                       int leftBars = 0, int rightBars = 0) {
    std::vector<uint8_t> frame(w * h * 4, 0);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            bool black = (y < topBars) || (y >= h - bottomBars && bottomBars > 0)
                      || (x < leftBars) || (x >= w - rightBars && rightBars > 0);
            uint8_t val = black ? 0 : 200;
            frame[(y * w + x) * 4 + 0] = val;
            frame[(y * w + x) * 4 + 1] = val;
            frame[(y * w + x) * 4 + 2] = val;
            frame[(y * w + x) * 4 + 3] = 255;
        }
    }
    return frame;
}

TEST(BlackBarDetection, NoBlackBars) {
    auto f = makeFrame(960, 540, 0, 0);
    auto crop = FrameProcessor::detectBlackBars(f.data(), 960 * 4, 960, 540);
    EXPECT_EQ(crop.x, 0);
    EXPECT_EQ(crop.y, 0);
    EXPECT_EQ(crop.w, 960);
    EXPECT_EQ(crop.h, 540);
}

TEST(BlackBarDetection, LetterboxTopAndBottom) {
    // 16:9 content in a 4:3 frame — ~67px bars top and bottom on 540-high frame
    auto f = makeFrame(960, 540, 67, 67);
    auto crop = FrameProcessor::detectBlackBars(f.data(), 960 * 4, 960, 540);
    EXPECT_EQ(crop.y, 67);
    EXPECT_EQ(crop.h, 540 - 134);
    EXPECT_EQ(crop.x, 0);
    EXPECT_EQ(crop.w, 960);
}

TEST(BlackBarDetection, PillarboxLeftAndRight) {
    auto f = makeFrame(960, 540, 0, 0, 120, 120);
    auto crop = FrameProcessor::detectBlackBars(f.data(), 960 * 4, 960, 540);
    EXPECT_EQ(crop.x, 120);
    EXPECT_EQ(crop.w, 960 - 240);
    EXPECT_EQ(crop.y, 0);
    EXPECT_EQ(crop.h, 540);
}

TEST(BlackBarDetection, NeverCropsMoreThan25Percent) {
    // Huge bars (40%) — detection should cap at 25%
    auto f = makeFrame(960, 540, 216, 216); // 216/540 = 40%
    auto crop = FrameProcessor::detectBlackBars(f.data(), 960 * 4, 960, 540);
    EXPECT_GE(crop.h, 540 / 2); // must keep at least half the frame
}

TEST(BlackBarDetection, AllBlack) {
    std::vector<uint8_t> black(960 * 540 * 4, 0);
    auto crop = FrameProcessor::detectBlackBars(black.data(), 960 * 4, 960, 540);
    // Caps at 25% from each edge, so content area should still be valid
    EXPECT_GT(crop.w, 0);
    EXPECT_GT(crop.h, 0);
}
