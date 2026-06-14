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

    private var lastFrame: ByteArray? = null

    // Robot is only created for the fallback path.
    private val robot: Robot? = if (useDxgi) null else Robot()
    private val screenRect: Rectangle = GraphicsEnvironment
        .getLocalGraphicsEnvironment()
        .defaultScreenDevice
        .defaultConfiguration
        .bounds

    fun captureRgb(): ByteArray {
        if (useDxgi) {
            WindowsCapture.nativeCapture(dxgiHandle, dstW, dstH)?.let { lastFrame = it }
            // null = desktop unchanged: reuse the last frame (black until the
            // very first frame arrives, which the keepalive will replace).
            return lastFrame ?: ByteArray(dstW * dstH * 3)
        }
        return captureWithRobot()
    }

    fun close() {
        if (useDxgi && dxgiHandle != 0L) WindowsCapture.nativeDestroy(dxgiHandle)
    }

    private fun captureWithRobot(): ByteArray {
        val capture = robot!!.createScreenCapture(screenRect)
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
