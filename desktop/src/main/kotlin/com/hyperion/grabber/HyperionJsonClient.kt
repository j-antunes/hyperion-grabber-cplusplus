package com.hyperion.grabber

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.ceil
import kotlin.math.min

// What answered on the host, so the UI can say *which* port is missing instead
// of a blanket "cannot reach Hyperion".
data class Reachability(val flatbuffers: Boolean, val jsonApi: Boolean, val web: Boolean) {
    val anything get() = flatbuffers || jsonApi || web
}

object HyperionJsonClient {

    const val JSON_PORT = 19444   // JSON API (serverinfo)
    const val WEB_PORT  = 8090    // web UI / json-rpc over HTTP

    // Try every address the name resolves to, like the C++ client's
    // getaddrinfo loop (core/src/hyperion_client.cpp). InetSocketAddress(host,
    // port) picks one address only: on Windows "localhost" resolves to ::1
    // first, and Hyperion listens on IPv4, so a working server looked dead.
    fun openSocket(host: String, port: Int, timeoutMs: Int): Socket? {
        val addrs = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return null
        for (addr in addrs) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(addr, port), timeoutMs)
                return s
            } catch (e: Exception) {
                runCatching { s.close() }
            }
        }
        return null
    }

    private fun canConnect(host: String, port: Int, timeoutMs: Int = 2000): Boolean =
        openSocket(host, port, timeoutMs)?.use { true } ?: false

    // Probe the port the grabber actually uses (flatbuffers) plus the two
    // service ports, so "unreachable" can distinguish "host is down" from
    // "Hyperion is up but its flatbuffers server is off/firewalled".
    fun probe(host: String, flatbuffersPort: Int): Reachability {
        // The happy path stops after one connect — the other two only matter
        // for telling "host is down" apart from "that one port is shut", and
        // each costs another timeout against an unreachable host.
        if (canConnect(host, flatbuffersPort)) return Reachability(true, false, false)
        return Reachability(
            flatbuffers = false,
            jsonApi     = canConnect(host, JSON_PORT),
            web         = canConnect(host, WEB_PORT),
        )
    }

    // Mirrors Android HyperionJsonClient: connects to JSON TCP port 19444,
    // reads actual LED zone sizes, and calculates minimum resolution needed.
    fun queryResolution(host: String): Pair<Int, Int>? {
        return try {
            val socket = openSocket(host, JSON_PORT, 3000) ?: return null
            socket.use {
                socket.soTimeout = 3000
                val writer = socket.getOutputStream().bufferedWriter()
                val reader = socket.getInputStream().bufferedReader()
                writer.write("{\"command\":\"serverinfo\",\"subscribe\":[]}\n")
                writer.flush()
                val line = reader.readLine() ?: return null
                val zones = extractLedZones(line)
                if (zones.isEmpty()) null else computeResolution(zones)
            }
        } catch (e: Exception) { null }
    }

    // Sent over the raw JSON-RPC socket (19444) rather than HTTP on the web
    // port, matching Android: it keeps brightness working when the web UI is on
    // a non-default port or disabled, and needs one fewer port open.
    fun setBrightness(host: String, brightness: Int) {
        if (host.isBlank()) return
        runCatching {
            (openSocket(host, JSON_PORT, 3000) ?: return).use { socket ->
                socket.soTimeout = 3000
                val writer = socket.getOutputStream().bufferedWriter()
                val reader = socket.getInputStream().bufferedReader()
                writer.write("""{"command":"adjustment","adjustment":{"brightness":$brightness,"id":"default"}}""")
                writer.write("\n")
                writer.flush()
                reader.readLine()  // drain the reply so the server doesn't see a half-closed request
            }
        }
    }

    // Extract (hWidth, vHeight) zone fractions from the JSON serverinfo response.
    // Matches each hmin/hmax/vmin/vmax occurrence in order — one set per LED.
    private fun extractLedZones(json: String): List<Pair<Double, Double>> {
        fun values(key: String) = """"$key"\s*:\s*([\d.]+)""".toRegex()
            .findAll(json).map { it.groupValues[1].toDouble() }.toList()
        val hmins = values("hmin"); val hmaxs = values("hmax")
        val vmins = values("vmin"); val vmaxs = values("vmax")
        val n = minOf(hmins.size, hmaxs.size, vmins.size, vmaxs.size)
        return (0 until n).map { i -> (hmaxs[i] - hmins[i]) to (vmaxs[i] - vmins[i]) }
    }

    // Same algorithm as Android computeRecommendedResolution:
    // 2 pixels minimum per LED zone, rounded up to multiple of 8, capped at 256.
    private fun computeResolution(zones: List<Pair<Double, Double>>): Pair<Int, Int> {
        var minW = Double.MAX_VALUE
        var minH = Double.MAX_VALUE
        for ((w, h) in zones) {
            if (w > 0.001) minW = min(minW, w)
            if (h > 0.001) minH = min(minH, h)
        }
        if (minW == Double.MAX_VALUE || minH == Double.MAX_VALUE) return 64 to 36
        fun roundUp8(v: Int) = min(((v + 7) / 8) * 8, 256)
        return roundUp8(ceil(2.0 / minW).toInt()) to roundUp8(ceil(2.0 / minH).toInt())
    }
}
