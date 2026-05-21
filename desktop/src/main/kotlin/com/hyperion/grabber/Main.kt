package com.hyperion.grabber

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import java.awt.RenderingHints
import java.awt.image.BufferedImage

fun main() {
    val state = GrabberState()
    application {
        var isVisible by remember { mutableStateOf(true) }
        val trayIcon = remember { buildTrayIcon() }

        Tray(
            icon = trayIcon,
            tooltip = "Hyperion Grabber",
            menu = {
                Item("Open") { isVisible = true }
                Separator()
                Item("Exit") { state.shutdown(); exitApplication() }
            }
        )

        Window(
            onCloseRequest = {
                if (state.minimizeToTray) isVisible = false
                else { state.shutdown(); exitApplication() }
            },
            visible = isVisible,
            title = "Hyperion Grabber",
            state = rememberWindowState(width = 500.dp, height = 640.dp)
        ) {
            App(state)
        }
    }
}

private fun buildTrayIcon(): BitmapPainter {
    val size = 64
    val img  = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g    = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    // Dark background circle
    g.color = java.awt.Color(0x1E, 0x1E, 0x2E)
    g.fillOval(0, 0, size, size)
    // Cyan inner circle
    g.color = java.awt.Color(0x7E, 0xC8, 0xE3)
    g.fillOval(10, 10, size - 20, size - 20)
    g.dispose()
    return BitmapPainter(img.toComposeImageBitmap())
}
