#pragma once

#include <string>
#include <vector>
#include <cstdint>
#include <functional>

namespace hyperion {

struct Color {
    uint8_t r, g, b;
};

class HyperionClient {
public:
    HyperionClient(const std::string& host, uint16_t port, int priority = 150);
    ~HyperionClient();

    bool connect();
    void disconnect();
    bool isConnected() const;

    // Send a full frame (width * height RGB pixels)
    bool sendFrame(const std::vector<Color>& pixels, int width, int height, int priority = 50);

    void setOnError(std::function<void(const std::string&)> cb) { m_onError = cb; }

private:
    bool sendRegister(const std::string& origin, int priority);

    std::string m_host;
    uint16_t    m_port;
    int         m_priority;
    int         m_socket = -1;
    std::function<void(const std::string&)> m_onError;
};

} // namespace hyperion
