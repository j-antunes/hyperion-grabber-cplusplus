#pragma once

#include <string>
#include <vector>
#include <cstdint>
#include <functional>

namespace hyperion {

struct Color {
    uint8_t r, g, b;
};

// Opens a TCP connection with hostname resolution (getaddrinfo) and a
// bounded connect timeout; also sets send/recv timeouts on the socket.
// Returns the socket fd, or -1 on failure. Shared with the PC JSON query.
int connectTcp(const std::string& host, uint16_t port, int timeoutMs);

class HyperionClient {
public:
    HyperionClient(const std::string& host, uint16_t port, int priority = 150);
    ~HyperionClient();

    // Owns a raw socket fd; copying would double-close it.
    HyperionClient(const HyperionClient&)            = delete;
    HyperionClient& operator=(const HyperionClient&) = delete;

    bool connect();
    void disconnect();
    bool isConnected() const;

    // Send a full frame (width * height RGB pixels).
    // Priority is fixed at registration time (Register command).
    bool sendFrame(const std::vector<Color>& pixels, int width, int height);

    void setOnError(std::function<void(const std::string&)> cb) { m_onError = cb; }

private:
    static constexpr int CONNECT_TIMEOUT_MS = 5000;

    bool sendRegister(const std::string& origin, int priority);

    std::string m_host;
    uint16_t    m_port;
    int         m_priority;
    int         m_socket = -1;
    std::function<void(const std::string&)> m_onError;
};

} // namespace hyperion
