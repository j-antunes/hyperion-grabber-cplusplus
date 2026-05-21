#include "hyperion_client.h"
#include "generated/hyperion_request_generated.h"
#include "generated/hyperion_reply_generated.h"

#include <flatbuffers/flatbuffers.h>

#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#pragma comment(lib, "ws2_32.lib")
#define CLOSE_SOCKET(s) closesocket(s)
#define MSG_NOSIGNAL    0
#else
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#define CLOSE_SOCKET(s) ::close(s)
#endif

#include <errno.h>
#include <string.h>
#include <mutex>
#include <cstdint>
#ifdef _WIN32
typedef int ssize_t;
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
            LOGE("send() failed: %s (errno=%d)", strerror(errno), errno);
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
#ifdef _WIN32
    static std::once_flag wsaOnce;
    std::call_once(wsaOnce, [] { WSADATA w; WSAStartup(MAKEWORD(2,2), &w); });
#endif

    m_socket = static_cast<int>(::socket(AF_INET, SOCK_STREAM, 0));
    if (m_socket < 0) {
        LOGE("socket() failed: %s", strerror(errno));
        return false;
    }

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port   = htons(m_port);
    if (::inet_pton(AF_INET, m_host.c_str(), &addr.sin_addr) <= 0) {
        LOGE("inet_pton() failed for host '%s'", m_host.c_str());
        CLOSE_SOCKET(m_socket); m_socket = -1; return false;
    }
    if (::connect(m_socket, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
        LOGE("connect() to %s:%d failed: %s", m_host.c_str(), m_port, strerror(errno));
        CLOSE_SOCKET(m_socket); m_socket = -1; return false;
    }
    LOGD("TCP connected to %s:%d", m_host.c_str(), m_port);

    if (!sendRegister("hyperion-grabber-c", m_priority)) {
        LOGE("sendRegister failed");
        CLOSE_SOCKET(m_socket); m_socket = -1; return false;
    }
    LOGD("Register sent, waiting for reply...");

    if (!readReply(m_socket)) {
        LOGE("Register reply indicates failure");
        CLOSE_SOCKET(m_socket); m_socket = -1; return false;
    }
    LOGD("Registration confirmed by Hyperion");
    return true;
}

void HyperionClient::disconnect() {
    if (m_socket >= 0) { CLOSE_SOCKET(m_socket); m_socket = -1; }
}

bool HyperionClient::isConnected() const { return m_socket >= 0; }

bool HyperionClient::sendFrame(const std::vector<Color>& pixels, int width, int height, int /*priority*/) {
    if (!isConnected()) return false;

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
