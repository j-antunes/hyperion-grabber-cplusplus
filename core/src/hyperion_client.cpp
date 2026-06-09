#include "hyperion_client.h"
#include "generated/hyperion_request_generated.h"
#include "generated/hyperion_reply_generated.h"

#include <flatbuffers/flatbuffers.h>

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <winsock2.h>
#include <ws2tcpip.h>
#pragma comment(lib, "ws2_32.lib")
#define CLOSE_SOCKET(s) closesocket(s)
#define MSG_NOSIGNAL    0
#else
#include <sys/socket.h>
#include <sys/select.h>
#include <netinet/in.h>
#include <netdb.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>
#define CLOSE_SOCKET(s) ::close(s)
#endif

#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <mutex>
#include <cstdint>
#ifdef _WIN32
typedef int ssize_t;
#  define SOCK_ERRNO()  WSAGetLastError()
#  define IS_EINTR(e)   ((e) == WSAEINTR)
#else
#  define SOCK_ERRNO()  errno
#  define IS_EINTR(e)   ((e) == EINTR)
#endif

#ifdef __ANDROID__
#include <android/log.h>
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "HyperionClient", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "HyperionClient", __VA_ARGS__)
#else
#include <cstdio>
#define LOGD(...) printf("[D] " __VA_ARGS__)
#define LOGE(...) fprintf(stderr, "[E] " __VA_ARGS__)
#endif

namespace hyperion {

static bool setNonBlocking(int fd, bool nonBlocking) {
#ifdef _WIN32
    u_long mode = nonBlocking ? 1 : 0;
    return ioctlsocket(fd, FIONBIO, &mode) == 0;
#else
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) return false;
    flags = nonBlocking ? (flags | O_NONBLOCK) : (flags & ~O_NONBLOCK);
    return fcntl(fd, F_SETFL, flags) == 0;
#endif
}

static void setSocketTimeouts(int fd, int timeoutMs) {
#ifdef _WIN32
    DWORD t = static_cast<DWORD>(timeoutMs);
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&t), sizeof(t));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<const char*>(&t), sizeof(t));
#else
    timeval tv{ timeoutMs / 1000, (timeoutMs % 1000) * 1000 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
#endif
}

int connectTcp(const std::string& host, uint16_t port, int timeoutMs) {
#ifdef _WIN32
    static std::once_flag wsaOnce;
    std::call_once(wsaOnce, [] { WSADATA w; WSAStartup(MAKEWORD(2,2), &w); });
#endif

    addrinfo hints{};
    hints.ai_family   = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    addrinfo* res = nullptr;
    char portStr[8];
    snprintf(portStr, sizeof(portStr), "%u", static_cast<unsigned>(port));
    int rc = ::getaddrinfo(host.c_str(), portStr, &hints, &res);
    if (rc != 0 || !res) {
        LOGE("could not resolve host '%s' (getaddrinfo rc=%d)", host.c_str(), rc);
        return -1;
    }

    int fd = -1;
    for (addrinfo* ai = res; ai; ai = ai->ai_next) {
        fd = static_cast<int>(::socket(ai->ai_family, ai->ai_socktype, ai->ai_protocol));
        if (fd < 0) continue;

        // Non-blocking connect so an unreachable host fails after timeoutMs
        // instead of hanging for the OS default (often minutes).
        setNonBlocking(fd, true);
        int c = ::connect(fd, ai->ai_addr, static_cast<int>(ai->ai_addrlen));
#ifdef _WIN32
        bool pending = (c < 0 && WSAGetLastError() == WSAEWOULDBLOCK);
#else
        bool pending = (c < 0 && errno == EINPROGRESS);
#endif
        if (c == 0 || pending) {
            bool ok = true;
            if (pending) {
                fd_set wfds;
                FD_ZERO(&wfds);
                FD_SET(fd, &wfds);
                timeval tv{ timeoutMs / 1000, (timeoutMs % 1000) * 1000 };
                int sel = ::select(fd + 1, nullptr, &wfds, nullptr, &tv);
                int soErr = -1;
                socklen_t len = sizeof(soErr);
                if (sel > 0)
                    ::getsockopt(fd, SOL_SOCKET, SO_ERROR, reinterpret_cast<char*>(&soErr), &len);
                ok = (sel > 0 && soErr == 0);
            }
            if (ok) {
                setNonBlocking(fd, false);
                setSocketTimeouts(fd, timeoutMs);
                break;
            }
        }
        CLOSE_SOCKET(fd);
        fd = -1;
    }
    ::freeaddrinfo(res);

    if (fd < 0)
        LOGE("connect to %s:%u failed or timed out", host.c_str(), static_cast<unsigned>(port));
    return fd;
}

static bool recvAll(int fd, uint8_t* buf, size_t len) {
    while (len > 0) {
        ssize_t n = ::recv(fd, reinterpret_cast<char*>(buf), static_cast<int>(len), 0);
        if (n <= 0) return false;
        buf += n;
        len -= static_cast<size_t>(n);
    }
    return true;
}

static bool sendAll(int fd, const uint8_t* data, size_t len) {
    while (len > 0) {
        ssize_t n = ::send(fd, reinterpret_cast<const char*>(data), static_cast<int>(len), MSG_NOSIGNAL);
        if (n <= 0) {
            LOGE("send() failed: err=%d", SOCK_ERRNO());
            return false;
        }
        data += n;
        len  -= static_cast<size_t>(n);
    }
    return true;
}

static bool sendFbb(int fd, flatbuffers::FlatBufferBuilder& fbb) {
    uint32_t size = htonl(static_cast<uint32_t>(fbb.GetSize()));
    return sendAll(fd, reinterpret_cast<uint8_t*>(&size), 4)
        && sendAll(fd, fbb.GetBufferPointer(), fbb.GetSize());
}

// Non-blocking drain of any data Hyperion has sent us (replies to Image frames).
// Returns false if the peer closed the socket or an error occurred.
// Without this, the receive buffer fills up over time, TCP window goes to zero,
// and Hyperion times out the connection — but our send() still succeeds against the
// kernel buffer, so the caller never notices and reconnect never fires.
static bool drainReplies(int fd) {
    while (true) {
        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(fd, &rfds);
        timeval tv{0, 0};
        int sel = ::select(fd + 1, &rfds, nullptr, nullptr, &tv);
        if (sel < 0) {
            int e = SOCK_ERRNO();
            if (IS_EINTR(e)) continue;
            LOGE("drainReplies: select() failed: err=%d", e);
            return false;
        }
        if (sel == 0) return true; // no data pending

        uint8_t buf[4096];
        ssize_t n = ::recv(fd, reinterpret_cast<char*>(buf), sizeof(buf), 0);
        if (n > 0) continue;
        if (n == 0) {
            LOGE("drainReplies: peer closed connection");
            return false;
        }
        int e = SOCK_ERRNO();
        if (IS_EINTR(e)) continue;
        LOGE("drainReplies: recv() failed: err=%d", e);
        return false;
    }
}

static bool readReply(int fd) {
    uint32_t sizeBe = 0;
    if (!recvAll(fd, reinterpret_cast<uint8_t*>(&sizeBe), 4)) {
        LOGE("readReply: failed to read size prefix");
        return false;
    }
    uint32_t size = ntohl(sizeBe);
    if (size == 0 || size > 65536) {
        LOGE("readReply: implausible reply size %u", size);
        return false;
    }

    std::vector<uint8_t> buf(size);
    if (!recvAll(fd, buf.data(), size)) {
        LOGE("readReply: failed to read %u reply bytes", size);
        return false;
    }

    auto reply = flatbuffers::GetRoot<hyperionnet::Reply>(buf.data());
    if (reply->error()) {
        LOGE("readReply: Hyperion error: %s", reply->error()->c_str());
        return false;
    }
    LOGD("readReply: OK, registered=%d", reply->registered());
    return true;
}

HyperionClient::HyperionClient(const std::string& host, uint16_t port, int priority)
    : m_host(host), m_port(port), m_priority(priority) {}

HyperionClient::~HyperionClient() { disconnect(); }

bool HyperionClient::connect() {
    disconnect();

    m_socket = connectTcp(m_host, m_port, CONNECT_TIMEOUT_MS);
    if (m_socket < 0) return false;
    LOGD("TCP connected to %s:%d", m_host.c_str(), m_port);

    if (!sendRegister("hyperion-grabber-c", m_priority)) {
        LOGE("sendRegister failed");
        disconnect();
        return false;
    }
    LOGD("Register sent, waiting for reply...");

    if (!readReply(m_socket)) {
        LOGE("Register reply indicates failure");
        disconnect();
        return false;
    }
    LOGD("Registration confirmed by Hyperion");
    return true;
}

void HyperionClient::disconnect() {
    if (m_socket >= 0) { CLOSE_SOCKET(m_socket); m_socket = -1; }
}

bool HyperionClient::isConnected() const { return m_socket >= 0; }

bool HyperionClient::sendFrame(const std::vector<Color>& pixels, int width, int height) {
    if (!isConnected()) return false;

    if (!drainReplies(m_socket)) {
        LOGE("sendFrame: server closed the connection");
        disconnect();
        return false;
    }

    std::vector<uint8_t> rgb(pixels.size() * 3);
    for (size_t i = 0; i < pixels.size(); ++i) {
        rgb[i*3+0] = pixels[i].r;
        rgb[i*3+1] = pixels[i].g;
        rgb[i*3+2] = pixels[i].b;
    }

    flatbuffers::FlatBufferBuilder fbb(rgb.size() + 512);

    // Build RawImage (nested inside ImageType union inside Image)
    auto data_vec = fbb.CreateVector(rgb);
    auto raw_img  = hyperionnet::CreateRawImage(fbb, data_vec, width, height);

    auto img = hyperionnet::CreateImage(
        fbb,
        hyperionnet::ImageType::RawImage,
        raw_img.Union(),
        -1);  // duration = indefinite

    auto req = hyperionnet::CreateRequest(
        fbb,
        hyperionnet::Command::Image,
        img.Union());
    fbb.Finish(req);

    if (!sendFbb(m_socket, fbb)) {
        LOGE("sendFrame: sendFbb failed");
        disconnect();
        return false;
    }

    if (!drainReplies(m_socket)) {
        disconnect();
        return false;
    }
    return true;
}

bool HyperionClient::sendRegister(const std::string& origin, int priority) {
    flatbuffers::FlatBufferBuilder fbb(256);
    auto org = fbb.CreateString(origin);
    auto reg = hyperionnet::CreateRegister(fbb, org, priority);
    auto req = hyperionnet::CreateRequest(
        fbb,
        hyperionnet::Command::Register,
        reg.Union());
    fbb.Finish(req);
    return sendFbb(m_socket, fbb);
}

} // namespace hyperion
