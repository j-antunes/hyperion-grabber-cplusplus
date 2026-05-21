package com.hyperion.grabber

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.math.ceil
import kotlin.math.min

object HyperionJsonClient {

    fun ping(host: String): Boolean = try {
        Socket().use { s -> s.connect(InetSocketAddress(host, 8090), 2000); true }
    } catch (e: Exception) { false }

    // Mirrors Android HyperionJsonClient: connects to JSON TCP port 19444,
    // reads actual LED zone sizes, and calculates minimum resolution needed.
    fun queryResolution(host: String): Pair<Int, Int>? = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, 19444), 3000)
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

    fun setBrightness(host: String, brightness: Int) {
        if (host.isBlank()) return
        runCatching {
            val conn = URL("http://$host:8090/json-rpc").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.requestMethod  = "POST"
            conn.doOutput       = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.write(
                """{"command":"adjustment","adjustment":{"brightness":$brightness,"id":"default"}}""".toByteArray()
            )
            conn.responseCode
            conn.disconnect()
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
