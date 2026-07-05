package com.hyperion.grabber

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.util.prefs.Preferences

enum class GrabStatus   { STOPPED, CONNECTING, RUNNING, ERROR }
enum class ReachStatus  { IDLE, CHECKING, OK, FAIL }

class GrabberState {
    private companion object {
        const val KEEPALIVE_MS = 3000L  // resend last frame on a static screen
        const val RECONNECT_MS = 5000L  // backoff between reconnect attempts
    }

    private val prefs = Preferences.userNodeForPackage(GrabberState::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    // The capture coroutine's client, so stop() can close the socket and break
    // any blocked read/write (coroutine cancellation alone can't interrupt those).
    @Volatile private var activeClient: HyperionClient? = null

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

    // Quiet reachability check used by the auto-test when the host field
    // changes — pings the JSON/web port without disturbing the LEDs.
    fun testConnection() {
        val h = normalizeHost()
        if (h.isEmpty()) { reachStatus = ReachStatus.FAIL; return }
        scope.launch {
            reachStatus = ReachStatus.CHECKING
            reachStatus = if (HyperionJsonClient.ping(h)) ReachStatus.OK else ReachStatus.FAIL
        }
    }

    // Test button: flash the LEDs solid red/green/blue/black so the user gets
    // visible confirmation, mirroring Android's "Test LEDs" (8 frames per colour
    // at 25 fps over the flatbuffers port). Disabled while the grabber runs.
    fun testLeds() {
        val h = normalizeHost()
        val p = port.toIntOrNull() ?: 19400
        // Hyperion rejects a Register outside the 100–199 grabber range.
        val pr = priority.coerceIn(100, 199)
        if (h.isEmpty()) { reachStatus = ReachStatus.FAIL; return }
        scope.launch {
            reachStatus = ReachStatus.CHECKING
            val client = HyperionClient(h, p, pr)
            if (!client.connect()) { reachStatus = ReachStatus.FAIL; return@launch }
            val w = 16; val ht = 16
            val colors = listOf(
                byteArrayOf(255.toByte(), 0, 0),  // red
                byteArrayOf(0, 255.toByte(), 0),  // green
                byteArrayOf(0, 0, 255.toByte()),  // blue
                byteArrayOf(0, 0, 0),             // black (clears)
            )
            var ok = true
            try {
                for (c in colors) {
                    val frame = solidColorFrame(c, w, ht)
                    repeat(8) {
                        if (!client.sendFrame(frame, w, ht)) ok = false
                        delay(40)  // 25 fps
                    }
                }
            } finally {
                client.disconnect()
            }
            reachStatus = if (ok) ReachStatus.OK else ReachStatus.FAIL
        }
    }

    private fun solidColorFrame(rgb: ByteArray, w: Int, h: Int): ByteArray {
        val buf = ByteArray(w * h * 3)
        var i = 0
        while (i < buf.size) { buf[i] = rgb[0]; buf[i + 1] = rgb[1]; buf[i + 2] = rgb[2]; i += 3 }
        return buf
    }

    // Update the displayed value while dragging without spawning an HTTP POST
    // per tick — the network call happens once on release (applyBrightness).
    fun setBrightnessLocal(v: Int) {
        brightness = v.coerceIn(0, 100)
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
        // Hyperion reserves priorities outside 100–199 for internal sources and
        // rejects a Register that uses them.
        val pr = priority.coerceIn(100, 199)
        if (h.isEmpty()) { errorMsg = "Host is required"; return }
        prefs.put("host", host); prefs.put("port", port)
        prefs.put("fps", fps);   prefs.putInt("priority", pr)
        errorMsg = ""
        grabStatus = GrabStatus.CONNECTING
        job = scope.launch { runGrabber(h, p, pr, f) }
    }

    private fun stop() {
        job?.cancel(); job = null
        // Close the socket so a coroutine blocked in a socket read/write wakes up
        // immediately instead of lingering until the OS TCP timeout.
        activeClient?.disconnect()
        grabStatus = GrabStatus.STOPPED
        fpsActual = 0
    }

    // Monotonic millis — wall clock (currentTimeMillis) can jump under NTP
    // sync and would stall or burst the frame pacing below.
    private fun monoMs() = System.nanoTime() / 1_000_000

    private suspend fun runGrabber(host: String, port: Int, priority: Int, targetFps: Int) {
        val client = HyperionClient(host, port, priority)
        activeClient = client
        if (!client.connect()) {
            grabStatus = GrabStatus.ERROR
            errorMsg = "Could not connect to $host:$port"
            activeClient = null
            return
        }

        val frameMs = 1000L / targetFps
        var frameCount = 0
        var lastStatsMs = monoMs()
        var lastSentMs  = monoMs()
        var lastPixels: ByteArray? = null
        var nextFrameDueMs = monoMs()

        // Held outside the try so finally can close it (releases the DXGI handle).
        var grabber: ScreenGrabber? = null

        try {
            val (dstW, dstH) = HyperionJsonClient.queryResolution(host) ?: (216 to 36)
            // Robot()/GraphicsEnvironment throw on a headless or broken display —
            // keep them inside the try so the catch reports it and releases the
            // socket instead of wedging the UI at CONNECTING.
            val g = ScreenGrabber(dstW, dstH)
            grabber = g
            grabStatus = GrabStatus.RUNNING

            while (true) {
                currentCoroutineContext().ensureActive()
                val rgb = g.captureRgb()

                // Only transmit when the frame changed; resend the last frame
                // every KEEPALIVE_MS on a static screen so Hyperion's priority
                // doesn't expire. Mirrors the C++/Android behaviour.
                val changed = lastPixels == null || !rgb.contentEquals(lastPixels)
                val keepaliveDue = monoMs() - lastSentMs >= KEEPALIVE_MS
                if (changed || keepaliveDue) {
                    if (!client.sendFrame(rgb, dstW, dstH)) {
                        // Retry the reconnect until it succeeds or we're stopped,
                        // rather than giving up after one attempt.
                        client.disconnect()
                        grabStatus = GrabStatus.CONNECTING
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            delay(RECONNECT_MS)
                            if (client.connect()) break
                        }
                        grabStatus = GrabStatus.RUNNING
                        lastPixels = null
                        lastSentMs = monoMs()
                        nextFrameDueMs = monoMs()
                        continue
                    }
                    lastPixels = rgb
                    lastSentMs = monoMs()
                    if (changed) frameCount++
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            grabStatus = GrabStatus.ERROR
            errorMsg = e.message ?: "Capture failed"
        } finally {
            grabber?.close()
            client.disconnect()
            activeClient = null
            if (grabStatus == GrabStatus.RUNNING || grabStatus == GrabStatus.CONNECTING) {
                grabStatus = GrabStatus.STOPPED; fpsActual = 0
            }
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
