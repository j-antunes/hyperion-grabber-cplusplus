package com.hyperion.grabber

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

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
            primary      = Cyan,
            surface      = Card,
            background   = Dark,
            onBackground = Color.White,
            onSurface    = Color.White,
        )
    ) {
        Surface(Modifier.fillMaxSize(), color = Dark) {
            Column(
                Modifier
                    .padding(20.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Hyperion Grabber", color = Cyan, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                SettingsCard(state)
                StatusCard(state)
            }
        }
    }
}

@Composable
private fun SettingsCard(state: GrabberState) {
    // Auto-test reachability 1s after host stops changing (mirrors Android behaviour)
    LaunchedEffect(state.host) {
        if (state.host.isNotBlank() && !state.isRunning) {
            delay(1000)
            state.testConnection()
        }
    }

    Surface(shape = RoundedCornerShape(12.dp), color = Card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Settings", color = Cyan, fontWeight = FontWeight.SemiBold)

            // Host row with Test button and reach indicator
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = state.host,
                        onValueChange = {
                            state.host = it
                            state.reachStatus = ReachStatus.IDLE
                        },
                        label = { Text("Hyperion Host") },
                        placeholder = { Text("e.g. 192.168.1.100", color = Grey) },
                        modifier = Modifier.weight(1f),
                        enabled = !state.isRunning,
                        singleLine = true,
                        colors = textFieldColors()
                    )
                    ReachDot(state.reachStatus)
                    Button(
                        onClick = { state.testLeds() },
                        enabled = !state.isRunning && state.host.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A5276)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("Test LEDs", fontSize = 13.sp)
                    }
                }
                Text(
                    "Accepts: 192.168.1.100  ·  http://hyperion.local  ·  hostname",
                    color = Grey,
                    fontSize = 11.sp
                )
                if (state.reachStatus == ReachStatus.OK) {
                    Text("Hyperion is reachable", color = Green, fontSize = 11.sp)
                } else if (state.reachStatus == ReachStatus.FAIL) {
                    Text("Cannot reach Hyperion — check host, port, and that Hyperion is running", color = Red, fontSize = 11.sp)
                }
            }

            // Port / FPS / Priority row
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.port,
                    onValueChange = { state.port = it },
                    label = { Text("Port") },
                    placeholder = { Text("19400", color = Grey) },
                    modifier = Modifier.weight(1f),
                    enabled = !state.isRunning,
                    singleLine = true,
                    colors = textFieldColors()
                )
                OutlinedTextField(
                    value = state.fps,
                    onValueChange = { state.fps = it },
                    label = { Text("FPS") },
                    placeholder = { Text("25", color = Grey) },
                    modifier = Modifier.weight(1f),
                    enabled = !state.isRunning,
                    singleLine = true,
                    colors = textFieldColors()
                )
                OutlinedTextField(
                    value = state.priority.toString(),
                    onValueChange = { state.priority = it.toIntOrNull()?.coerceIn(1, 255) ?: state.priority },
                    label = { Text("Priority") },
                    placeholder = { Text("150", color = Grey) },
                    modifier = Modifier.weight(1f),
                    enabled = !state.isRunning,
                    singleLine = true,
                    colors = textFieldColors()
                )
            }
            Text(
                "Port: Hyperion flatbuffers port (default 19400)  ·  Priority: lower = higher priority (1–255)",
                color = Grey,
                fontSize = 11.sp
            )

            // Brightness
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Brightness", color = Color.White, modifier = Modifier.weight(1f))
                    Text("${state.brightness}%", color = Cyan, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = state.brightness.toFloat(),
                    onValueChange = { state.applyBrightness(it.toInt()) },
                    valueRange = 0f..100f,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = Cyan, activeTrackColor = Cyan, inactiveTrackColor = Grey
                    )
                )
            }

            Divider(color = Grey.copy(alpha = 0.3f))

            // System options
            LabelledCheckbox(
                checked = state.minimizeToTray,
                onCheckedChange = { state.applyMinimizeToTray(it) },
                label = "Minimize to tray on close"
            )
            LabelledCheckbox(
                checked = state.startOnBoot,
                onCheckedChange = { state.applyStartOnBoot(it) },
                label = "Start automatically on system boot"
            )
        }
    }
}

@Composable
private fun StatusCard(state: GrabberState) {
    val dotColor = when (state.grabStatus) {
        GrabStatus.RUNNING    -> Green
        GrabStatus.CONNECTING -> Amber
        GrabStatus.ERROR      -> Red
        GrabStatus.STOPPED    -> Grey
    }
    val statusText = when (state.grabStatus) {
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
                    containerColor = if (state.isRunning) Color(0xFFB71C1C) else Color(0xFF1B5E20)
                )
            ) {
                Text(if (state.isRunning) "Stop" else "Start", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ReachDot(status: ReachStatus) {
    val color = when (status) {
        ReachStatus.OK       -> Green
        ReachStatus.FAIL     -> Red
        ReachStatus.CHECKING -> Amber
        ReachStatus.IDLE     -> Color.Transparent
    }
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

@Composable
private fun LabelledCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = Cyan, uncheckedColor = Grey)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, color = Color.White, fontSize = 14.sp,
            modifier = Modifier.clickable { onCheckedChange(!checked) })
    }
}

@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor    = Cyan,
    unfocusedBorderColor  = Grey,
    focusedLabelColor     = Cyan,
    unfocusedLabelColor   = Grey,
    focusedTextColor      = Color.White,
    unfocusedTextColor    = Color.White,
    disabledTextColor     = Color(0xFF888899),
    disabledBorderColor   = Color(0xFF333344),
    disabledLabelColor    = Color(0xFF555566),
)
