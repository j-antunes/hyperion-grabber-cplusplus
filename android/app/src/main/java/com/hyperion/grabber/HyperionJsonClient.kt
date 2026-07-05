package com.hyperion.grabber

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.ceil
import kotlin.math.min

// Pure function — no Android deps, fully unit-testable.
// zones: list of (zoneWidth, zoneHeight) as fractions of the image (0.0–1.0).
internal fun computeRecommendedResolution(
    zones: List<Pair<Double, Double>>,
    minPixelsPerZone: Int = 2,
    maxDim: Int = 256
): Pair<Int, Int> {
    var minZoneW = Double.MAX_VALUE
    var minZoneH = Double.MAX_VALUE

    for ((zoneW, zoneH) in zones) {
        if (zoneW > 0.001) minZoneW = min(minZoneW, zoneW)
        if (zoneH > 0.001) minZoneH = min(minZoneH, zoneH)
    }

    if (minZoneW == Double.MAX_VALUE || minZoneH == Double.MAX_VALUE) return 64 to 36

    val rawW = ceil(minPixelsPerZone / minZoneW).toInt()
    val rawH = ceil(minPixelsPerZone / minZoneH).toInt()
    fun roundUp8(v: Int) = min(((v + 7) / 8) * 8, maxDim)
    return roundUp8(rawW) to roundUp8(rawH)
}

data class HyperionServerInfo(
    val ledCount: Int,
    val recommendedWidth: Int,
    val recommendedHeight: Int
)

object HyperionJsonClient {

    private const val TAG        = "HyperionJsonClient"
    private const val JSON_PORT  = 19444
    private const val TIMEOUT_MS = 3_000

    // Minimum pixels we want covering each LED's zone in the sent image.
    // 2 means even the smallest LED gets at least a 2×2 pixel sample.
    private const val MIN_PIXELS_PER_ZONE = 2

    // Hard cap — no point sending more than this regardless of LED density
    private const val MAX_DIM = 256

    suspend fun queryServerInfo(host: String): Result<HyperionServerInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, JSON_PORT), TIMEOUT_MS)
                    socket.soTimeout = TIMEOUT_MS

                    val writer = socket.getOutputStream().bufferedWriter()
                    val reader = socket.getInputStream().bufferedReader()

                    writer.write("{\"command\":\"serverinfo\",\"subscribe\":[]}\n")
                    writer.flush()

                    val line = reader.readLine()
                        ?: error("Empty response from Hyperion JSON server")

                    val json = JSONObject(line)
                    check(json.optBoolean("success", false)) {
                        json.optString("error", "Hyperion returned success=false")
                    }

                    val leds = json.getJSONObject("info").getJSONArray("leds")
                    val (w, h) = recommendedResolution(leds)
                    Log.d(TAG, "Server info: ${leds.length()} LEDs, recommended resolution ${w}×${h}")
                    HyperionServerInfo(ledCount = leds.length(), recommendedWidth = w, recommendedHeight = h)
                }
            }
        }

    suspend fun setBrightness(host: String, brightness: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Send over the raw JSON-RPC socket (19444), not cleartext HTTP on
                // 8090: HttpURLConnection is blocked by the platform's default
                // no-cleartext policy on API 28+, which silently broke this.
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, JSON_PORT), TIMEOUT_MS)
                    socket.soTimeout = TIMEOUT_MS

                    val writer = socket.getOutputStream().bufferedWriter()
                    val reader = socket.getInputStream().bufferedReader()

                    val body = """{"command":"adjustment","adjustment":{"brightness":$brightness,"id":"default"}}"""
                    writer.write(body); writer.write("\n"); writer.flush()

                    val line = reader.readLine() ?: error("Empty response from Hyperion JSON server")
                    val json = JSONObject(line)
                    check(json.optBoolean("success", false)) {
                        json.optString("error", "Hyperion rejected brightness change")
                    }
                    Log.d(TAG, "Brightness set to $brightness")
                    Unit
                }
            }
        }

    private fun recommendedResolution(leds: JSONArray): Pair<Int, Int> {
        val zones = (0 until leds.length()).map {
            val led = leds.getJSONObject(it)
            (led.getDouble("hmax") - led.getDouble("hmin")) to
            (led.getDouble("vmax") - led.getDouble("vmin"))
        }
        return computeRecommendedResolution(zones, MIN_PIXELS_PER_ZONE, MAX_DIM)
    }
}
