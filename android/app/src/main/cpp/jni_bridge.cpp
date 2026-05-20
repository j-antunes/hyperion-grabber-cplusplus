#include <jni.h>
#include "hyperion_client.h"
#include "frame_processor.h"

#include <memory>
#include <android/log.h>
#include <unistd.h>

#define LOG_TAG "HyperionGrabber"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct NativeContext {
    std::unique_ptr<hyperion::HyperionClient>  client;
    std::unique_ptr<hyperion::FrameProcessor>  processor;
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_hyperion_grabber_HyperionNative_create(
        JNIEnv* env, jobject,
        jstring host, jint port,
        jint srcW, jint srcH,
        jint dstW, jint dstH,
        jint fps) {

    const char* hostStr = env->GetStringUTFChars(host, nullptr);
    auto* ctx = new NativeContext();

    ctx->client = std::make_unique<hyperion::HyperionClient>(
            std::string(hostStr), static_cast<uint16_t>(port), 150);
    env->ReleaseStringUTFChars(host, hostStr);

    if (!ctx->client->connect()) {
        LOGE("Failed to connect to Hyperion server");
        delete ctx;
        return 0;
    }

    hyperion::FrameConfig config{srcW, srcH, dstW, dstH, fps};
    ctx->processor = std::make_unique<hyperion::FrameProcessor>(config);

    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jboolean JNICALL
Java_com_hyperion_grabber_HyperionNative_sendFrame(
        JNIEnv* env, jobject,
        jlong handle,
        jobject buffer,
        jint rowStride) {

    auto* ctx = reinterpret_cast<NativeContext*>(handle);
    if (!ctx) return JNI_FALSE;

    const auto* data = reinterpret_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!data) return JNI_FALSE;

    const auto& cfg = ctx->processor->config();
    auto pixels = ctx->processor->processRGBA(data, rowStride);
    return ctx->client->sendFrame(pixels, cfg.targetWidth, cfg.targetHeight)
           ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_hyperion_grabber_HyperionNative_destroy(
        JNIEnv* env, jobject,
        jlong handle) {

    auto* ctx = reinterpret_cast<NativeContext*>(handle);
    if (ctx) {
        ctx->client->disconnect();
        delete ctx;
    }
}

// Sends solid-colour test frames without needing MediaProjection.
// Returns null on success, or an error string on failure.
JNIEXPORT jstring JNICALL
Java_com_hyperion_grabber_HyperionNative_testConnection(
        JNIEnv* env, jobject,
        jstring host, jint port) {

    const char* hostStr = env->GetStringUTFChars(host, nullptr);
    hyperion::HyperionClient client(std::string(hostStr), static_cast<uint16_t>(port), 150);
    env->ReleaseStringUTFChars(host, hostStr);

    if (!client.connect()) {
        return env->NewStringUTF("Could not connect to Hyperion");
    }

    // Small frame — enough to exercise all LEDs
    const int W = 32, H = 18;
    const int FRAMES_PER_COLOR = 8;    // ~320ms at 25fps
    const int PAUSE_US         = 40000; // ~25fps

    struct { uint8_t r, g, b; const char* name; } colors[] = {
        {255,   0,   0, "red"},
        {  0, 255,   0, "green"},
        {  0,   0, 255, "blue"},
        {  0,   0,   0, "black"},  // clear at the end
    };

    for (const auto& c : colors) {
        std::vector<hyperion::Color> pixels(W * H, {c.r, c.g, c.b});
        for (int i = 0; i < FRAMES_PER_COLOR; i++) {
            if (!client.sendFrame(pixels, W, H)) {
                std::string msg = std::string("Failed sending ") + c.name + " frame";
                return env->NewStringUTF(msg.c_str());
            }
            usleep(PAUSE_US);
        }
        usleep(200000); // 200ms pause between colours
    }

    client.disconnect();
    return nullptr; // null = success
}

} // extern "C"
