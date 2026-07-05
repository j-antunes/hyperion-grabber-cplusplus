// Regression tests for the HyperionClient TCP behavior.
//
// Pins down the bug fix from commits 2322738 / 607112e: sendFrame() used to
// never read replies, so when the peer closed the socket the client never
// noticed — frames piled up in the kernel buffer, sockets leaked in
// CLOSE-WAIT, and the LEDs silently went dark. drainReplies() now detects
// the peer-close and forces a disconnect on the next send.

#include "hyperion_client.h"
#include "generated/hyperion_request_generated.h"
#include "generated/hyperion_reply_generated.h"

#include <flatbuffers/flatbuffers.h>
#include <gtest/gtest.h>

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <thread>
#include <vector>

namespace {

// Tiny localhost TCP server that mimics enough of Hyperion to exercise the
// client's handshake + reply-draining behaviour. Each test spins up its own.
class FakeHyperionServer {
public:
    FakeHyperionServer() {
        m_listen = ::socket(AF_INET, SOCK_STREAM, 0);
        EXPECT_GE(m_listen, 0) << "socket() failed";
        int yes = 1;
        ::setsockopt(m_listen, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof(yes));

        sockaddr_in addr{};
        addr.sin_family      = AF_INET;
        addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        addr.sin_port        = 0;  // kernel picks a free port
        EXPECT_EQ(0, ::bind(m_listen, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)));

        socklen_t len = sizeof(addr);
        EXPECT_EQ(0, ::getsockname(m_listen, reinterpret_cast<sockaddr*>(&addr), &len));
        m_port = ntohs(addr.sin_port);

        EXPECT_EQ(0, ::listen(m_listen, 1));
    }

    ~FakeHyperionServer() {
        m_stop = true;
        // Unblock the worker whether it's parked in accept() or recv():
        // shutdown() wakes a blocked socket call immediately, whereas close()
        // alone does not reliably do so on Linux. Without this, teardown only
        // worked by accident of the client being destroyed first.
        if (m_listen >= 0) ::shutdown(m_listen, SHUT_RDWR);
        int c = m_client.exchange(-1);
        if (c >= 0) ::shutdown(c, SHUT_RDWR);
        if (m_thread.joinable()) m_thread.join();
        if (m_listen >= 0) ::close(m_listen);
    }

    uint16_t port() const { return m_port; }

    // Starts a thread that: accepts one client, reads the Register frame,
    // sends a Reply, then optionally reads `framesBeforeClose` Image frames
    // before closing the socket. The forced close exercises drainReplies().
    void runOneClient(int framesBeforeClose) {
        m_thread = std::thread([this, framesBeforeClose] {
            int client = ::accept(m_listen, nullptr, nullptr);
            if (client < 0) return;
            m_client = client;
            // A recv timeout guarantees the worker periodically re-checks m_stop
            // even if the dtor's shutdown() races the accept()→recv() window.
            timeval tv{2, 0};
            ::setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

            // 1. Read Register (size-prefixed flatbuffer).
            if (!readSizedFrame(client)) { ::close(client); return; }

            // 2. Send Reply with registered=150.
            flatbuffers::FlatBufferBuilder fbb(256);
            auto reply = hyperionnet::CreateReply(fbb, /*error=*/0, /*video=*/0, /*registered=*/150);
            fbb.Finish(reply);
            uint32_t sizeBe = htonl(static_cast<uint32_t>(fbb.GetSize()));
            ::send(client, &sizeBe, 4, 0);
            ::send(client, fbb.GetBufferPointer(), fbb.GetSize(), 0);

            // 3. Read N frames (or until the client/test gives up), then close.
            for (int i = 0; i < framesBeforeClose && !m_stop; ++i) {
                if (!readSizedFrame(client)) break;
            }
            ::close(client);
        });
    }

private:
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
            p += got;
            n -= static_cast<size_t>(got);
        }
        return true;
    }

    int               m_listen = -1;
    uint16_t          m_port   = 0;
    std::thread       m_thread;
    std::atomic<bool> m_stop{false};
    std::atomic<int>  m_client{-1};
};

std::vector<hyperion::Color> makeRedFrame(int w, int h) {
    return std::vector<hyperion::Color>(static_cast<size_t>(w * h), hyperion::Color{255, 0, 0});
}

} // namespace

TEST(HyperionClient, ConnectsAndSendsFrameWhenServerIsHealthy) {
    FakeHyperionServer server;
    server.runOneClient(/*framesBeforeClose=*/100);

    hyperion::HyperionClient client("127.0.0.1", server.port());
    ASSERT_TRUE(client.connect());
    EXPECT_TRUE(client.isConnected());

    auto frame = makeRedFrame(16, 9);
    EXPECT_TRUE(client.sendFrame(frame, 16, 9));
    EXPECT_TRUE(client.isConnected());
}

TEST(HyperionClient, DetectsPeerCloseAndDisconnects) {
    // Regression: the original bug. Hyperion closes the socket and the client
    // must detect that on the next sendFrame instead of returning true forever
    // while sockets leak in CLOSE-WAIT.
    FakeHyperionServer server;
    server.runOneClient(/*framesBeforeClose=*/0);  // close immediately after handshake

    hyperion::HyperionClient client("127.0.0.1", server.port());
    ASSERT_TRUE(client.connect());

    auto frame = makeRedFrame(16, 9);

    // The first send may race the FIN; loop until drainReplies sees it.
    bool sawClose = false;
    for (int i = 0; i < 20; ++i) {
        bool ok = client.sendFrame(frame, 16, 9);
        if (!ok) { sawClose = true; break; }
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }

    EXPECT_TRUE(sawClose) << "sendFrame should have failed after peer closed the socket";
    EXPECT_FALSE(client.isConnected()) << "client should have auto-disconnected on detected close";
}

TEST(HyperionClient, IsConnectedFalseBeforeConnect) {
    hyperion::HyperionClient client("127.0.0.1", 1);
    EXPECT_FALSE(client.isConnected());
}

TEST(HyperionClient, ConnectFailsToUnreachablePort) {
    // Port 1 on loopback should refuse connection (or time out quickly).
    hyperion::HyperionClient client("127.0.0.1", 1);
    EXPECT_FALSE(client.connect());
    EXPECT_FALSE(client.isConnected());
}
