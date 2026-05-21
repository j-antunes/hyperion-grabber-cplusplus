package com.hyperion.grabber

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Cyan  = Color(0xFF7EC8E3)
private val Dark  = Color(0xFF13131A)
private val Card  = Color(0xFF1E1E2E)
private val Green = Color(0xFF4CAF50)
private val Amber = Color(0xFFFF9800)
private val Red   = Color(0xFFF44336)
private val Grey  = Color(0xFF555566)

@Composable
fun App(state: GrabberState) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary     = Cyan,
            surface     = Card,
            background  = Dark,
            onBackground = Color.White,
            onSurface    = Color.White,
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = Dark) {
            Column(
                Modifier.padding(20.dp).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "Hyperion Grabber",
                    color = Cyan,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                SettingsCard(state)
                Spacer(Modifier.weight(1f))
                StatusCard(state)
            }
        }
    }
}

@Composable
private fun SettingsCard(state: GrabberState) {
    Surface(shape = RoundedCornerShape(12.dp), color = Card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Settings", color = Cyan, fontWeight = FontWeight.SemiBold)

            OutlinedTextField(
                value = state.host,
                onValueChange = { state.host = it },
                label = { Text("Hyperion Host") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isRunning,
                singleLine = true,
                colors = outlinedTextFieldColors()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.port,
                    onValueChange = { state.port = it },
                    label = { Text("Port") },
                    modifier = Modifier.weight(1f),
                    enabled = !state.isRunning,
                    singleLine = true,
                    colors = outlinedTextFieldColors()
                )
                OutlinedTextField(
                    value = state.fps,
                    onValueChange = { state.fps = it },
                    label = { Text("FPS") },
                    modifier = Modifier.weight(1f),
                    enabled = !state.isRunning,
                    singleLine = true,
                    colors = outlinedTextFieldColors()
                )
            }

            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Brightness", color = Color.White, modifier = Modifier.weight(1f))
                    Text("${state.brightness}%", color = Cyan, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = state.brightness.toFloat(),
                    onValueChange = { state.setBrightness(it.toInt()) },
                    valueRange = 0f..100f,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = Cyan,
                        activeTrackColor = Cyan,
                        inactiveTrackColor = Grey
                    )
                )
            }
        }
    }
}

@Composable
private fun StatusCard(state: GrabberState) {
    val dotColor = when (state.status) {
        GrabStatus.RUNNING    -> Green
        GrabStatus.CONNECTING -> Amber
        GrabStatus.ERROR      -> Red
        GrabStatus.STOPPED    -> Grey
    }
    val statusText = when (state.status) {
        GrabStatus.RUNNING    -> "Running  ·  ↑ ${state.fpsActual} fps"
        GrabStatus.CONNECTING -> "Connecting…"
        GrabStatus.ERROR      -> "Error"
        GrabStatus.STOPPED    -> "Stopped"
    }

    Surface(shape = RoundedCornerShape(12.dp), color = Card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Status", color = Cyan, fontWeight = FontWeight.SemiBold)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).background(dotColor, CircleShape))
                Text(statusText, color = Color.White)
            }

            if (state.errorMsg.isNotEmpty()) {
                Text(state.errorMsg, color = Red, fontSize = 12.sp)
            }

            Button(
                onClick = { state.toggle() },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isRunning) Color(0xFF6B2D2D) else Color(0xFF1F5E30)
                )
            ) {
                Text(
                    if (state.isRunning) "Stop" else "Start",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun outlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor   = Cyan,
    unfocusedBorderColor = Grey,
    focusedLabelColor    = Cyan,
    unfocusedLabelColor  = Grey,
    focusedTextColor     = Color.White,
    unfocusedTextColor   = Color.White,
    disabledTextColor    = Color(0xFF888899),
    disabledBorderColor  = Color(0xFF333344),
    disabledLabelColor   = Color(0xFF555566),
)
