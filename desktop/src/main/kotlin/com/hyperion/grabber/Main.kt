package com.hyperion.grabber

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*

fun main() {
    val state = GrabberState()
    application {
        Window(
            onCloseRequest = {
                state.shutdown()
                exitApplication()
            },
            title = "Hyperion Grabber",
            state = rememberWindowState(width = 480.dp, height = 520.dp)
        ) {
            App(state)
        }
    }
}
