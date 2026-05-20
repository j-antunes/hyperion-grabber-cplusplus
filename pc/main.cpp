#include "hyperion_client.h"
#include "frame_processor.h"

#ifdef __linux__
#include "x11_grabber.h"
#endif
#ifdef _WIN32
#include "dxgi_grabber.h"
#endif

#include <iostream>
#include <csignal>
#include <atomic>
#include <memory>

static std::atomic<bool> g_running{true};

int main(int argc, char* argv[]) {
    if (argc < 3) {
        std::cerr << "Usage: hyperion_grabber <host> <port>\n";
        return 1;
    }

    const std::string host = argv[1];
    const uint16_t    port = static_cast<uint16_t>(std::stoi(argv[2]));

    std::signal(SIGINT,  [](int) { g_running = false; });
    std::signal(SIGTERM, [](int) { g_running = false; });

    hyperion::FrameConfig config{
        .sourceWidth  = 1920,
        .sourceHeight = 1080,
        .targetWidth  = 64,
        .targetHeight = 64,
        .framerate    = 25,
    };

    auto client = std::make_shared<hyperion::HyperionClient>(host, port);
    if (!client->connect()) {
        std::cerr << "Could not connect to Hyperion at " << host << ":" << port << "\n";
        return 1;
    }

#ifdef __linux__
    hyperion::X11Grabber grabber(config, client);
#elif _WIN32
    hyperion::DXGIGrabber grabber(config, client);
#else
    std::cerr << "No grabber available for this platform\n";
    return 1;
#endif

    if (!grabber.start()) {
        std::cerr << "Failed to start screen capture\n";
        return 1;
    }

    std::cout << "Grabbing " << config.sourceWidth << "x" << config.sourceHeight
              << " → " << config.targetWidth << "x" << config.targetHeight
              << " @ " << config.framerate << " fps\n"
              << "Sending to " << host << ":" << port << "\n"
              << "Press Ctrl+C to stop.\n";

    while (g_running) {
        std::this_thread::sleep_for(std::chrono::seconds(1));
    }

    grabber.stop();
    return 0;
}
