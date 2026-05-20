#include <gtest/gtest.h>
#include <flatbuffers/flatbuffers.h>
#include "generated/hyperion_request_generated.h"
#include "generated/hyperion_reply_generated.h"

// ── Command union values ─────────────────────────────────────────────────────
// This test locks in the enum values from Hyperion.ng 2.x schema.
// The original bug was Register=1 in our schema but Register=4 in Hyperion's.
// A failure here means the schema changed and all protocol code must be re-checked.
TEST(FlatbuffersSchema, CommandUnionValuesMatchHyperionNg2x) {
    EXPECT_EQ(static_cast<int>(hyperionnet::Command::Color),    1);
    EXPECT_EQ(static_cast<int>(hyperionnet::Command::Image),    2);
    EXPECT_EQ(static_cast<int>(hyperionnet::Command::Clear),    3);
    EXPECT_EQ(static_cast<int>(hyperionnet::Command::Register), 4);
}

// ── Register message ─────────────────────────────────────────────────────────
TEST(FlatbuffersRegister, RoundTripPreservesOriginAndPriority) {
    flatbuffers::FlatBufferBuilder fbb(256);
    auto origin = fbb.CreateString("hyperion-grabber-c");
    auto reg    = hyperionnet::CreateRegister(fbb, origin, 150);
    auto req    = hyperionnet::CreateRequest(fbb, hyperionnet::Command::Register, reg.Union());
    fbb.Finish(req);

    auto* parsed = flatbuffers::GetRoot<hyperionnet::Request>(fbb.GetBufferPointer());

    EXPECT_EQ(parsed->command_type(), hyperionnet::Command::Register);

    auto* r = parsed->command_as_Register();
    ASSERT_NE(r, nullptr);
    EXPECT_STREQ(r->origin()->c_str(), "hyperion-grabber-c");
    EXPECT_EQ(r->priority(), 150);
}

TEST(FlatbuffersRegister, CommandAsImageReturnsNullForRegister) {
    flatbuffers::FlatBufferBuilder fbb(256);
    auto origin = fbb.CreateString("test");
    auto reg    = hyperionnet::CreateRegister(fbb, origin, 150);
    auto req    = hyperionnet::CreateRequest(fbb, hyperionnet::Command::Register, reg.Union());
    fbb.Finish(req);

    auto* parsed = flatbuffers::GetRoot<hyperionnet::Request>(fbb.GetBufferPointer());
    EXPECT_EQ(parsed->command_as_Image(), nullptr);
}

// ── Image message ─────────────────────────────────────────────────────────────
TEST(FlatbuffersImage, RoundTripPreservesWidthHeightAndData) {
    const int W = 32, H = 18;
    std::vector<uint8_t> rgb(W * H * 3);
    for (size_t i = 0; i < rgb.size(); i++) rgb[i] = static_cast<uint8_t>(i & 0xFF);

    flatbuffers::FlatBufferBuilder fbb(rgb.size() + 512);
    auto data_vec = fbb.CreateVector(rgb);
    auto raw_img  = hyperionnet::CreateRawImage(fbb, data_vec, W, H);
    auto img      = hyperionnet::CreateImage(fbb, hyperionnet::ImageType::RawImage, raw_img.Union(), -1);
    auto req      = hyperionnet::CreateRequest(fbb, hyperionnet::Command::Image, img.Union());
    fbb.Finish(req);

    auto* parsed = flatbuffers::GetRoot<hyperionnet::Request>(fbb.GetBufferPointer());
    EXPECT_EQ(parsed->command_type(), hyperionnet::Command::Image);

    auto* im = parsed->command_as_Image();
    ASSERT_NE(im, nullptr);
    EXPECT_EQ(im->data_type(), hyperionnet::ImageType::RawImage);
    EXPECT_EQ(im->duration(), -1);

    auto* ri = im->data_as_RawImage();
    ASSERT_NE(ri, nullptr);
    EXPECT_EQ(ri->width(),  W);
    EXPECT_EQ(ri->height(), H);
    ASSERT_NE(ri->data(), nullptr);
    EXPECT_EQ(ri->data()->size(), static_cast<flatbuffers::uoffset_t>(W * H * 3));

    // Spot-check a few pixel values survive the round-trip
    EXPECT_EQ((*ri->data())[0],   0);
    EXPECT_EQ((*ri->data())[1],   1);
    EXPECT_EQ((*ri->data())[255], 255);
}

TEST(FlatbuffersImage, DurationDefaultIsMinusOne) {
    flatbuffers::FlatBufferBuilder fbb(256);
    std::vector<uint8_t> rgb(4 * 3, 0);
    auto dv  = fbb.CreateVector(rgb);
    auto ri  = hyperionnet::CreateRawImage(fbb, dv, 4, 1);
    auto img = hyperionnet::CreateImage(fbb, hyperionnet::ImageType::RawImage, ri.Union());
    auto req = hyperionnet::CreateRequest(fbb, hyperionnet::Command::Image, img.Union());
    fbb.Finish(req);

    auto* parsed = flatbuffers::GetRoot<hyperionnet::Request>(fbb.GetBufferPointer());
    EXPECT_EQ(parsed->command_as_Image()->duration(), -1);
}

// ── Reply parsing ─────────────────────────────────────────────────────────────
TEST(FlatbuffersReply, SuccessReplyHasNoError) {
    flatbuffers::FlatBufferBuilder fbb(128);
    auto reply = hyperionnet::CreateReply(fbb, /*error=*/0, /*video=*/-1, /*registered=*/150);
    fbb.Finish(reply);

    auto* parsed = flatbuffers::GetRoot<hyperionnet::Reply>(fbb.GetBufferPointer());
    EXPECT_EQ(parsed->error(), nullptr);
    EXPECT_EQ(parsed->registered(), 150);
}

TEST(FlatbuffersReply, ErrorReplyContainsMessage) {
    flatbuffers::FlatBufferBuilder fbb(128);
    auto err   = fbb.CreateString("Priority out of range");
    auto reply = hyperionnet::CreateReply(fbb, err, -1, -1);
    fbb.Finish(reply);

    auto* parsed = flatbuffers::GetRoot<hyperionnet::Reply>(fbb.GetBufferPointer());
    ASSERT_NE(parsed->error(), nullptr);
    EXPECT_STREQ(parsed->error()->c_str(), "Priority out of range");
}
