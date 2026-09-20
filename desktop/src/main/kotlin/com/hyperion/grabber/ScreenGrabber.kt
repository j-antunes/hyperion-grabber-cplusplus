package com.hyperion.grabber

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage

// Captures the primary screen and downscales it to dstW x dstH packed RGB.
//
// On Windows it uses the native DXGI Desktop Duplication capturer, which does
// not flicker the mouse cursor the way java.awt.Robot's GDI capture does. On
// Linux/macOS (or if DXGI is unavailable) it falls back to Robot.
class ScreenGrabber(private val dstW: Int, private val dstH: Int) {

    private val dxgiHandle: Long = if (WindowsCapture.isAvailable) WindowsCapture.nativeInit() else 0L
    private val useDxgi: Boolean = dxgiHandle != 0L

    // Robot is only created for the fallback path.
    private val robot: Robot? = if (useDxgi) null else Robot()

    // Returns a fresh dstW*dstH*3 RGB frame, or null when there is nothing new
    // to send (DXGI: desktop unchanged or duplication being rebuilt; Robot:
    // capture threw). Callers keep their last frame and let the keepalive
    // resend it — mirroring CaptureResult::NoFrame in the C++ run loop.
    fun captureRgb(): ByteArray? {
        if (useDxgi) return WindowsCapture.nativeCapture(dxgiHandle, dstW, dstH)
        return runCatching { captureWithRobot() }.getOrNull()
    }

    // Display power state. Windows reports GUID_CONSOLE_DISPLAY_STATE through
    // the native helper. The Robot path (Linux/macOS desktop) has no JVM API
    // for DPMS, so it always reports "on" — a deliberate gap; the C++ Linux
    // grabber does implement the DPMS pause.
    fun isDisplayOn(): Boolean =
        if (useDxgi) WindowsCapture.nativeIsDisplayOn(dxgiHandle) else true

    fun close() {
        if (useDxgi && dxgiHandle != 0L) WindowsCapture.nativeDestroy(dxgiHandle)
    }

    // Re-read the bounds every frame: they change on a resolution switch, and
    // a stale rectangle makes createScreenCapture throw or return a cropped
    // image forever.
    private fun primaryScreenBounds(): Rectangle = GraphicsEnvironment
        .getLocalGraphicsEnvironment()
        .defaultScreenDevice
        .defaultConfiguration
        .bounds

    private fun captureWithRobot(): ByteArray {
        val capture = robot!!.createScreenCapture(primaryScreenBounds())
        val scaled  = BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_RGB)
        val g = scaled.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(capture, 0, 0, dstW, dstH, null)
        g.dispose()

        val pixels = scaled.getRGB(0, 0, dstW, dstH, null, 0, dstW)
        val rgb = ByteArray(pixels.size * 3)
        for (i in pixels.indices) {
            rgb[i * 3]     = ((pixels[i] shr 16) and 0xFF).toByte()
            rgb[i * 3 + 1] = ((pixels[i] shr 8)  and 0xFF).toByte()
            rgb[i * 3 + 2] = (pixels[i]           and 0xFF).toByte()
        }
        return rgb
    }
}
