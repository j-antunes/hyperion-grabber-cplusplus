package com.hyperion.grabber

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.util.prefs.Preferences

enum class GrabStatus   { STOPPED, CONNECTING, RUNNING, ERROR }
enum class ReachStatus  { IDLE, CHECKING, OK, FAIL }

class GrabberState {
    private val prefs = Preferences.userNodeForPackage(GrabberState::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    var host           by mutableStateOf(prefs.get("host", ""))
    var port           by mutableStateOf(prefs.get("port", "19400"))
    var fps            by mutableStateOf(prefs.get("fps", "25"))
    var priority       by mutableStateOf(prefs.getInt("priority", 150))
    var brightness     by mutableStateOf(prefs.getInt("brightness", 100))
    var minimizeToTray by mutableStateOf(prefs.getBoolean("minimizeToTray", true))
    var startOnBoot    by mutableStateOf(prefs.getBoolean("startOnBoot", false))

    var grabStatus    by mutableStateOf(GrabStatus.STOPPED)
    var reachStatus   by mutableStateOf(ReachStatus.IDLE)
    var fpsActual     by mutableStateOf(0)
    var errorMsg      by mutableStateOf("")

    val isRunning get() = grabStatus == GrabStatus.RUNNING || grabStatus == GrabStatus.CONNECTING

    // Strip scheme so user can paste full URLs
    fun normalizeHost(raw: String = host): String = raw.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .trimEnd('/')

    fun toggle() = if (isRunning) stop() else start()

    fun testConnection() {
        val h = normalizeHost()
        if (h.isEmpty()) { reachStatus = ReachStatus.FAIL; return }
        scope.launch {
            reachStatus = ReachStatus.CHECKING
            reachStatus = if (HyperionJsonClient.ping(h)) ReachStatus.OK else ReachStatus.FAIL
        }
    }

    fun applyBrightness(v: Int) {
        brightness = v.coerceIn(0, 100)
        prefs.putInt("brightness", brightness)
        scope.launch { HyperionJsonClient.setBrightness(normalizeHost(), brightness) }
    }

    fun applyMinimizeToTray(v: Boolean) {
        minimizeToTray = v
        prefs.putBoolean("minimizeToTray", v)
    }

    fun applyStartOnBoot(v: Boolean) {
        startOnBoot = v
        prefs.putBoolean("startOnBoot", v)
        val os = System.getProperty("os.name", "").lowercase()
        when {
            os.contains("windows") -> setWindowsAutostart(v)
            os.contains("linux")   -> setLinuxAutostart(v)
        }
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    private fun start() {
        val h = normalizeHost()
        val p = port.toIntOrNull() ?: 19400
        val f = fps.toIntOrNull()?.coerceIn(1, 60) ?: 25
        val pr = priority.coerceIn(1, 255)
        if (h.isEmpty()) { errorMsg = "Host is required"; return }
        prefs.put("host", host); prefs.put("port", port)
        prefs.put("fps", fps);   prefs.putInt("priority", pr)
        errorMsg = ""
        grabStatus = GrabStatus.CONNECTING
        job = scope.launch { runGrabber(h, p, pr, f) }
    }

    private fun stop() {
        job?.cancel(); job = null
        grabStatus = GrabStatus.STOPPED
        fpsActual = 0
    }

    // Monotonic millis — wall clock (currentTimeMillis) can jump under NTP
    // sync and would stall or burst the frame pacing below.
    private fun monoMs() = System.nanoTime() / 1_000_000

    private suspend fun runGrabber(host: String, port: Int, priority: Int, targetFps: Int) {
        val client = HyperionClient(host, port, priority)
        if (!client.connect()) {
            grabStatus = GrabStatus.ERROR
            errorMsg = "Could not connect to $host:$port"
            return
        }

        val (dstW, dstH) = HyperionJsonClient.queryResolution(host) ?: (216 to 36)
        val grabber = ScreenGrabber(dstW, dstH)
        val frameMs = 1000L / targetFps

        grabStatus = GrabStatus.RUNNING
        var frameCount = 0
        var lastStatsMs = monoMs()
        var lastSentMs  = monoMs() - 5000L
        var lastPixels: ByteArray? = null
        var nextFrameDueMs = monoMs()

        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val rgb = grabber.captureRgb()
                val ok  = client.sendFrame(rgb, dstW, dstH)

                if (!ok) {
                    client.disconnect()
                    delay(5000)
                    currentCoroutineContext().ensureActive()
                    if (!client.connect()) break
                    lastSentMs = monoMs()
                    nextFrameDueMs = monoMs()
                } else {
                    lastPixels = rgb
                    lastSentMs = monoMs()
                    frameCount++
                }

                if (monoMs() - lastSentMs > 3000) {
                    lastPixels?.let { client.sendFrame(it, dstW, dstH) }
                    lastSentMs = monoMs()
                }

                val now = monoMs()
                if (now - lastStatsMs >= 1000) {
                    fpsActual = frameCount; frameCount = 0; lastStatsMs = now
                }

                // Sleep toward an absolute deadline instead of anchoring to
                // this iteration's start: per-iteration anchoring lets sleep
                // overshoot accumulate, landing the real rate below targetFps
                // (same class of bug as the Android vsync-quantization fix).
                // The clamp keeps a stall (slow capture) from bursting to
                // catch up afterwards.
                nextFrameDueMs = maxOf(nextFrameDueMs + frameMs, now)
                val sleepMs = nextFrameDueMs - monoMs()
                if (sleepMs > 0) delay(sleepMs)
            }
        } finally {
            client.disconnect()
            if (grabStatus == GrabStatus.RUNNING) { grabStatus = GrabStatus.STOPPED; fpsActual = 0 }
        }
    }

    private fun setWindowsAutostart(enabled: Boolean) {
        val exe = ProcessHandle.current().info().command().orElse(null) ?: return
        val key = """HKCU\Software\Microsoft\Windows\CurrentVersion\Run"""
        val args = if (enabled)
            arrayOf("reg", "add", key, "/v", "HyperionGrabber", "/t", "REG_SZ", "/d", "\"$exe\"", "/f")
        else
            arrayOf("reg", "delete", key, "/v", "HyperionGrabber", "/f")
        runCatching { Runtime.getRuntime().exec(args) }
    }

    private fun setLinuxAutostart(enabled: Boolean) {
        val file = java.io.File(System.getProperty("user.home"), ".config/autostart/hyperion-grabber.desktop")
        if (enabled) {
            val exe = ProcessHandle.current().info().command().orElse("hyperion-grabber") ?: return
            file.parentFile.mkdirs()
            file.writeText(
                "[Desktop Entry]\nType=Application\nName=Hyperion Grabber\nExec=$exe\n" +
                "Hidden=false\nNoDisplay=false\nX-GNOME-Autostart-enabled=true\n"
            )
        } else {
            file.delete()
        }
    }
}
