package com.hyperion.grabber

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage

class ScreenGrabber(private val dstW: Int, private val dstH: Int) {
    private val robot = Robot()
    private val screenRect: Rectangle = GraphicsEnvironment
        .getLocalGraphicsEnvironment()
        .defaultScreenDevice
        .defaultConfiguration
        .bounds

    fun captureRgb(): ByteArray {
        val capture = robot.createScreenCapture(screenRect)
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
