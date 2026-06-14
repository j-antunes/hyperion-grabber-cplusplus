package com.hyperion.grabber

import java.io.File

// JNI bridge to the native DXGI Desktop Duplication capturer (dxgi_jni.cpp).
// The .dll is bundled in the MSI under /native and extracted at startup. On
// non-Windows platforms — or if the library/D3D device is unavailable (e.g. a
// GPU-less host) — isAvailable stays false and ScreenGrabber falls back to Robot.
object WindowsCapture {

    val isAvailable: Boolean = loadNative()

    // Returns a native handle (>0) or 0 on failure (no GPU / headless).
    external fun nativeInit(): Long

    // Returns dstW*dstH*3 RGB bytes for a fresh frame, or null when the desktop
    // hasn't changed since the last call (caller should reuse the previous frame).
    external fun nativeCapture(handle: Long, dstW: Int, dstH: Int): ByteArray?

    external fun nativeDestroy(handle: Long)

    private fun loadNative(): Boolean {
        if (!System.getProperty("os.name", "").lowercase().contains("windows")) return false
        return try {
            val res = WindowsCapture::class.java.getResourceAsStream("/native/hyperion_capture_jni.dll")
                ?: return false
            val tmp = File.createTempFile("hyperion_capture_jni", ".dll").apply { deleteOnExit() }
            res.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            System.load(tmp.absolutePath)
            true
        } catch (e: Throwable) {
            false
        }
    }
}
