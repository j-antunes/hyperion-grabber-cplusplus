#include "hyperion_client.h"
#include "frame_processor.h"

#ifdef __linux__
#include "x11_grabber.h"
#endif
#ifdef _WIN32
#include "dxgi_grabber.h"   // brings in winsock2 + WIN32_LEAN_AND_MEAN
#else
#include <sys/socket.h>
#include <unistd.h>
#endif

#include <iostream>
#include <string>
#include <cstring>
#include <csignal>
#include <atomic>
#include <memory>
#include <thread>
#include <chrono>

static std::atomic<bool> g_running{true};

// Query Hyperion's JSON server (port 19444) for LED count + recommended resolution.
// Returns {width, height}; falls back to {64, 36} on failure.
static std::pair<int,int> queryResolution(const std::string& host,
                                          int& outLedCount) {
    outLedCount = 0;

    auto cleanup = [&](int fd) {
#ifdef _WIN32
        closesocket(fd);
#else
        ::close(fd);
#endif
    };

    // connectTcp resolves hostnames and applies a connect timeout
    int fd = hyperion::connectTcp(host, 19444, 3000);
    if (fd < 0) return {64, 36};

    const char* req = "{\"command\":\"serverinfo\",\"subscribe\":[]}\n";
    ::send(fd, req, static_cast<int>(strlen(req)), 0);

    std::string resp;
    char buf[4096];
    int n;
    while ((n = ::recv(fd, buf, sizeof(buf)-1, 0)) > 0) {
        buf[n] = '\0';
        resp += buf;
        if (resp.find('\n') != std::string::npos) break;
    }
    cleanup(fd);

    // Count LEDs: number of "hmin" occurrences = number of LEDs
    int count = 0;
    size_t pos = 0;
    while ((pos = resp.find("\"hmin\"", pos)) != std::string::npos) { ++count; ++pos; }
    if (count == 0) return {64, 36};
    outLedCount = count;

    // Recommended resolution: 2 pixels per LED zone.
    // Approximate: use sqrt(count) scaled to 16:9
    int w = std::max(64, (count / 4) & ~7);  // rough horizontal LEDs * 2, round to 8
    int h = std::max(36, w * 9 / 16);
    w = std::min(w, 256);
    h = std::min(h, 144);
    return {w, h};
}

int main(int argc, char* argv[]) {
    if (argc < 3) {
        std::cerr << "Usage: hyperion_grabber <host> <port> [fps] [width] [height]\n"
                  << "  host   Hyperion server IP or hostname\n"
                  << "  port   Flatbuffers port (default 19400)\n"
                  << "  fps    Capture FPS (default 25)\n"
                  << "  width  Target width  (auto-detected if omitted)\n"
                  << "  height Target height (16:9 of width if omitted)\n";
        return 1;
    }

    const std::string host = argv[1];
    const uint16_t    port = static_cast<uint16_t>(std::stoi(argv[2]));
    const int         fps  = argc > 3 ? std::stoi(argv[3]) : 25;

    int ledCount = 0;
    int dstW, dstH;

    if (argc > 4) {
        dstW = std::stoi(argv[4]);
        dstH = argc > 5 ? std::stoi(argv[5]) : std::max(1, dstW * 9 / 16);
    } else {
        std::cout << "Querying Hyperion at " << host << ":19444…\n";
        auto [w, h] = queryResolution(host, ledCount);
        dstW = w; dstH = h;
        if (ledCount > 0)
            std::cout << ledCount << " LEDs detected, using " << dstW << "×" << dstH << "\n";
        else
            std::cout << "Could not query Hyperion, using default " << dstW << "×" << dstH << "\n";
    }

    std::signal(SIGINT,  [](int) { g_running = false; });
    std::signal(SIGTERM, [](int) { g_running = false; });

    // Source size is a placeholder — the grabber corrects it to the real
    // screen resolution in initCapture().
    hyperion::FrameConfig config{1920, 1080, dstW, dstH, fps};

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

    std::cout << "Capturing " << grabber.config().sourceWidth << "×" << grabber.config().sourceHeight
              << " → " << dstW << "×" << dstH << " @ " << fps << " fps\n"
              << "Streaming to " << host << ":" << port << "\n"
              << "Press Ctrl+C to stop.\n";

    while (g_running)
        std::this_thread::sleep_for(std::chrono::seconds(1));

    grabber.stop();
    return 0;
}
