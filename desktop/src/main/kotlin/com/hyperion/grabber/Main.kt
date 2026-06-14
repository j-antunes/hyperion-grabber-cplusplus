package com.hyperion.grabber

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Color
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JWindow
import javax.swing.UIManager
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import kotlin.system.exitProcess

fun main() {
    // Use the native look-and-feel so the tray menu (a Swing JPopupMenu below)
    // matches the OS instead of the dated cross-platform default. Must be set
    // before any Swing/AWT component is created.
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

    val state = GrabberState()
    val windowVisible = mutableStateOf(true)

    installTray(
        onOpen = { windowVisible.value = true },
        onExit = { state.shutdown(); exitProcess(0) },
    )

    application {
        Window(
            onCloseRequest = {
                if (state.minimizeToTray) windowVisible.value = false
                else { state.shutdown(); exitApplication() }
            },
            visible = windowVisible.value,
            title = "Hyperion Grabber",
            state = rememberWindowState(width = 500.dp, height = 640.dp),
        ) {
            App(state)
        }
    }
}

// The Compose Tray uses a heavyweight java.awt.PopupMenu, which renders as the
// dated classic menu. Build the tray manually so we can show a Swing JPopupMenu
// (styled by the system look-and-feel) on right-click instead.
private fun installTray(onOpen: () -> Unit, onExit: () -> Unit) {
    if (!SystemTray.isSupported()) return

    val popup = JPopupMenu()
    popup.add(JMenuItem("Open").apply { addActionListener { onOpen() } })
    popup.addSeparator()
    popup.add(JMenuItem("Exit").apply { addActionListener { onExit() } })

    // A JPopupMenu needs a visible owner window to take focus, otherwise it
    // won't dismiss when the user clicks elsewhere. This 1px invisible anchor
    // serves as that owner and hides itself once the menu closes.
    val anchor = JWindow().apply { setSize(1, 1) }
    popup.addPopupMenuListener(object : PopupMenuListener {
        override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {}
        override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) { anchor.isVisible = false }
        override fun popupMenuCanceled(e: PopupMenuEvent) { anchor.isVisible = false }
    })

    val trayIcon = TrayIcon(buildTrayImage(), "Hyperion Grabber").apply {
        isImageAutoSize = true
        addMouseListener(object : MouseAdapter() {
            private fun showPopup(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    anchor.setLocation(e.xOnScreen, e.yOnScreen)
                    anchor.isVisible = true
                    popup.show(anchor, 0, 0)
                }
            }
            override fun mousePressed(e: MouseEvent) = showPopup(e)
            override fun mouseReleased(e: MouseEvent) = showPopup(e)
            override fun mouseClicked(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) onOpen()
            }
        })
    }

    runCatching { SystemTray.getSystemTray().add(trayIcon) }
}

private fun buildTrayImage(): BufferedImage {
    val size = 16
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color(0x1E, 0x1E, 0x2E)   // dark background circle
    g.fillOval(0, 0, size, size)
    g.color = Color(0x7E, 0xC8, 0xE3)   // cyan inner circle
    g.fillOval(3, 3, size - 6, size - 6)
    g.dispose()
    return img
}
