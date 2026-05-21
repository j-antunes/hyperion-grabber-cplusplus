package com.hyperion.grabber

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.util.prefs.Preferences

enum class GrabStatus { STOPPED, CONNECTING, RUNNING, ERROR }

class GrabberState {
    private val prefs = Preferences.userNodeForPackage(GrabberState::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    var host       by mutableStateOf(prefs.get("host", ""))
    var port       by mutableStateOf(prefs.get("port", "19400"))
    var fps        by mutableStateOf(prefs.get("fps", "25"))
    var brightness by mutableStateOf(prefs.getInt("brightness", 100))
    var status     by mutableStateOf(GrabStatus.STOPPED)
    var fpsActual  by mutableStateOf(0)
    var errorMsg   by mutableStateOf("")

    val isRunning get() = status == GrabStatus.RUNNING || status == GrabStatus.CONNECTING

    fun toggle() = if (isRunning) stop() else start()

    fun setBrightness(v: Int) {
        brightness = v.coerceIn(0, 100)
        prefs.putInt("brightness", brightness)
        scope.launch { HyperionJsonClient.setBrightness(host.trim(), brightness) }
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    private fun start() {
        val h = host.trim()
        val p = port.toIntOrNull() ?: 19400
        val f = fps.toIntOrNull()?.coerceIn(1, 60) ?: 25
        if (h.isEmpty()) { errorMsg = "Host is required"; return }
        prefs.put("host", h); prefs.put("port", port); prefs.put("fps", fps)
        errorMsg = ""
        status = GrabStatus.CONNECTING
        job = scope.launch { runGrabber(h, p, f) }
    }

    private fun stop() {
        job?.cancel(); job = null
        status = GrabStatus.STOPPED
        fpsActual = 0
    }

    private suspend fun runGrabber(host: String, port: Int, targetFps: Int) {
        val client = HyperionClient(host, port)
        if (!client.connect()) {
            status = GrabStatus.ERROR
            errorMsg = "Could not connect to $host:$port"
            return
        }

        val (dstW, dstH) = HyperionJsonClient.queryResolution(host) ?: (216 to 36)
        val grabber = ScreenGrabber(dstW, dstH)
        val frameMs = 1000L / targetFps

        status = GrabStatus.RUNNING
        var frameCount = 0
        var lastStatsMs = System.currentTimeMillis()
        var lastSentMs  = System.currentTimeMillis() - 5000L
        var lastPixels: ByteArray? = null

        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val t0 = System.currentTimeMillis()
                val rgb = grabber.captureRgb()
                val ok = client.sendFrame(rgb, dstW, dstH)

                if (!ok) {
                    client.disconnect()
                    delay(5000)
                    currentCoroutineContext().ensureActive()
                    if (!client.connect()) break
                    lastSentMs = System.currentTimeMillis()
                } else {
                    lastPixels = rgb
                    lastSentMs = System.currentTimeMillis()
                    frameCount++
                }

                // Keepalive: resend last frame if screen was static for 3s
                if (System.currentTimeMillis() - lastSentMs > 3000) {
                    lastPixels?.let { client.sendFrame(it, dstW, dstH) }
                    lastSentMs = System.currentTimeMillis()
                }

                val now = System.currentTimeMillis()
                if (now - lastStatsMs >= 1000) {
                    fpsActual = frameCount
                    frameCount = 0
                    lastStatsMs = now
                }

                val delay = frameMs - (System.currentTimeMillis() - t0)
                if (delay > 0) delay(delay)
            }
        } finally {
            client.disconnect()
            if (status == GrabStatus.RUNNING) { status = GrabStatus.STOPPED; fpsActual = 0 }
        }
    }
}
