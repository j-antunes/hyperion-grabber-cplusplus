// Behavioural tests for GrabberBase::runLoop — the loop shared by the Linux
// X11 and Windows DXGI grabbers (and mirrored by the desktop JVM app).
//
//  * Lost must re-initialise capture and rebuild the FrameProcessor with the
//    grabber's corrected source size, and must NOT touch the TCP connection.
//    Before this existed, a lost capture source (lock screen, mode change)
//    was reported as a send failure, so the loop reconnected TCP forever
//    while the capture stayed dead.
//  * isDisplayOn()==false must drop the connection (so Hyperion frees the
//    priority, like Android's SCREEN_OFF path) and reconnect when the display
//    comes back.

#include "grabber_base.h"
#include "generated/hyperion_reply_generated.h"

#include <flatbuffers/flatbuffers.h>
#include <gtest/gtest.h>

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <functional>
#include <mutex>
#include <thread>
#include <vector>

namespace {

using namespace std::chrono_literals;

// Localhost server that accepts any number of sequential clients, completes
// the Register handshake for each, then counts Image frames until the client
// closes. Exposes counters the tests poll.
class MultiClientServer {
public:
    MultiClientServer() {
        m_listen = ::socket(AF_INET, SOCK_STREAM, 0);
        int yes = 1;
        ::setsockopt(m_listen, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));
        sockaddr_in addr{};
        addr.sin_family      = AF_INET;
        addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        EXPECT_EQ(0, ::bind(m_listen, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)));
        socklen_t len = sizeof(addr);
        ::getsockname(m_listen, reinterpret_cast<sockaddr*>(&addr), &len);
        m_port = ntohs(addr.sin_port);
        EXPECT_EQ(0, ::listen(m_listen, 4));
        m_acceptThread = std::thread(&MultiClientServer::acceptLoop, this);
    }

    ~MultiClientServer() {
        m_stop = true;
        if (m_acceptThread.joinable()) m_acceptThread.join();
        {
            std::lock_guard<std::mutex> lk(m_mutex);
            for (int fd : m_clients) ::shutdown(fd, SHUT_RDWR);
        }
        for (auto& t : m_clientThreads) if (t.joinable()) t.join();
        {
            std::lock_guard<std::mutex> lk(m_mutex);
            for (int fd : m_clients) ::close(fd);
        }
        ::close(m_listen);
    }

    uint16_t port() const { return m_port; }
    int accepted() const { return m_accepted.load(); }
    int closed()   const { return m_closed.load(); }
    int frames()   const { return m_frames.load(); }

private:
    void acceptLoop() {
        while (!m_stop) {
            fd_set rfds; FD_ZERO(&rfds); FD_SET(m_listen, &rfds);
            timeval tv{0, 100'000};
            if (::select(m_listen + 1, &rfds, nullptr, nullptr, &tv) <= 0) continue;
            int client = ::accept(m_listen, nullptr, nullptr);
            if (client < 0) continue;
            {
                std::lock_guard<std::mutex> lk(m_mutex);
                m_clients.push_back(client);
            }
            m_accepted++;
            m_clientThreads.emplace_back(&MultiClientServer::serve, this, client);
        }
    }

    void serve(int fd) {
        if (readSizedFrame(fd)) {  // Register
            flatbuffers::FlatBufferBuilder fbb(256);
            fbb.Finish(hyperionnet::CreateReply(fbb, 0, 0, 150));
            uint32_t sizeBe = htonl(static_cast<uint32_t>(fbb.GetSize()));
            ::send(fd, &sizeBe, 4, 0);
            ::send(fd, fbb.GetBufferPointer(), fbb.GetSize(), 0);
            while (readSizedFrame(fd)) m_frames++;
        }
        m_closed++;
    }

    static bool readSizedFrame(int fd) {
        uint32_t sizeBe = 0;
        if (!readExact(fd, &sizeBe, 4)) return false;
        uint32_t size = ntohl(sizeBe);
        if (size == 0 || size > 16 * 1024 * 1024) return false;
        std::vector<uint8_t> buf(size);
        return readExact(fd, buf.data(), size);
    }

    static bool readExact(int fd, void* dst, size_t n) {
        auto* p = static_cast<uint8_t*>(dst);
        while (n > 0) {
            ssize_t got = ::recv(fd, p, n, 0);
            if (got <= 0) return false;
            p += got; n -= static_cast<size_t>(got);
        }
        return true;
    }

    int m_listen = -1;
    uint16_t m_port = 0;
    std::atomic<bool> m_stop{false};
    std::atomic<int>  m_accepted{0}, m_closed{0}, m_frames{0};
    std::thread m_acceptThread;
    std::vector<std::thread> m_clientThreads;
    std::mutex m_mutex;
    std::vector<int> m_clients;
};

// Grabber whose captureFrame/isDisplayOn are scripted by the test.
class FakeGrabber : public hyperion::GrabberBase {
public:
    using GrabberBase::GrabberBase;
    ~FakeGrabber() override { stop(); }

    std::atomic<bool> displayOn{true};
    std::atomic<int>  lostToReport{0};
    std::atomic<int>  newSourceWidth{0};
    std::atomic<int>  captures{0};
    std::atomic<int>  inits{0};
    std::atomic<int>  processorSourceWidth{0};  // as seen by the last captureFrame

protected:
    // Like a real grabber, (re)reads the "screen size" on every init.
    bool initCapture() override {
        inits++;
        if (newSourceWidth > 0) m_config.sourceWidth = newSourceWidth;
        return true;
    }
    void deinitCapture() override {}
    bool isDisplayOn() override { return displayOn; }

    hyperion::CaptureResult captureFrame(hyperion::FrameProcessor& p) override {
        captures++;
        processorSourceWidth = p.config().sourceWidth;
        if (lostToReport > 0) {
            lostToReport--;
            return hyperion::CaptureResult::Lost;
        }
        std::vector<hyperion::Color> px(
            static_cast<size_t>(m_config.targetWidth * m_config.targetHeight),
            hyperion::Color{1, 2, 3});
        return m_client->sendFrame(px, m_config.targetWidth, m_config.targetHeight)
            ? hyperion::CaptureResult::Sent : hyperion::CaptureResult::Failed;
    }
};

bool waitFor(const std::function<bool()>& cond, std::chrono::milliseconds timeout = 15s) {
    auto deadline = std::chrono::steady_clock::now() + timeout;
    while (std::chrono::steady_clock::now() < deadline) {
        if (cond()) return true;
        std::this_thread::sleep_for(10ms);
    }
    return cond();
}

} // namespace

TEST(GrabberBase, LostReinitialisesCaptureAndProcessorWithoutReconnecting) {
    MultiClientServer server;
    auto client = std::make_shared<hyperion::HyperionClient>("127.0.0.1", server.port());
    ASSERT_TRUE(client->connect());

    hyperion::FrameConfig cfg{640, 360, 8, 4, 50};
    FakeGrabber grabber(cfg, client);
    grabber.lostToReport = 1;
    ASSERT_TRUE(grabber.start());           // initCapture #1 (width stays 640)
    ASSERT_EQ(1, grabber.inits);

    // The first capture reports Lost. Meanwhile the "screen" changes size:
    // the loop must deinit, re-init (picking up 1280) and rebuild the
    // processor so the next capture sees sourceWidth == 1280.
    grabber.newSourceWidth = 1280;
    ASSERT_TRUE(waitFor([&] { return grabber.processorSourceWidth == 1280; }));
    ASSERT_TRUE(waitFor([&] { return server.frames() >= 1; }));
    EXPECT_EQ(2, grabber.inits);

    EXPECT_EQ(1, server.accepted()) << "Lost must not reconnect TCP";
    EXPECT_EQ(0, server.closed());
    EXPECT_TRUE(client->isConnected());
    grabber.stop();
}

TEST(GrabberBase, DisplayOffDropsConnectionAndDisplayOnReconnects) {
    MultiClientServer server;
    auto client = std::make_shared<hyperion::HyperionClient>("127.0.0.1", server.port());
    ASSERT_TRUE(client->connect());

    hyperion::FrameConfig cfg{640, 360, 8, 4, 50};
    FakeGrabber grabber(cfg, client);
    ASSERT_TRUE(grabber.start());
    ASSERT_TRUE(waitFor([&] { return server.frames() >= 2; }));

    grabber.displayOn = false;
    ASSERT_TRUE(waitFor([&] { return server.closed() == 1; }))
        << "display off must disconnect so Hyperion releases the priority";
    EXPECT_FALSE(client->isConnected());
    int capturesWhileOff = grabber.captures;
    std::this_thread::sleep_for(600ms);
    EXPECT_EQ(capturesWhileOff, grabber.captures) << "no capture while the display is off";

    int framesBefore = server.frames();
    grabber.displayOn = true;
    ASSERT_TRUE(waitFor([&] { return server.accepted() == 2; })) << "display on must reconnect";
    ASSERT_TRUE(waitFor([&] { return server.frames() > framesBefore; }));
    EXPECT_TRUE(client->isConnected());
    grabber.stop();
}
