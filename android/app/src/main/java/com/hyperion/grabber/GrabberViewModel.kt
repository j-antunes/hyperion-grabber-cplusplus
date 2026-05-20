package com.hyperion.grabber

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class ConnectionState {
    object Idle        : ConnectionState()
    object Checking    : ConnectionState()
    data class Connected(val ledCount: Int, val width: Int, val height: Int) : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()
}

class GrabberViewModel(app: Application) : AndroidViewModel(app) {

    enum class GrabberStatus { IDLE, RUNNING }

    private val prefs = app.getSharedPreferences("grabber_prefs", Context.MODE_PRIVATE)

    val host         = MutableLiveData(prefs.getString("host", "")!!)
    val port         = MutableLiveData(prefs.getInt("port", 19400))
    val fps          = MutableLiveData(prefs.getInt("fps", 25))
    val targetWidth  = MutableLiveData(prefs.getInt("targetWidth", 64))
    val targetHeight = MutableLiveData(prefs.getInt("targetHeight", 36))

    val scheduleMode      = MutableLiveData(
        runCatching { ScheduleMode.valueOf(prefs.getString("scheduleMode", "OFF")!!) }
            .getOrDefault(ScheduleMode.OFF)
    )
    val scheduleStartHour = MutableLiveData(prefs.getInt("scheduleStartHour", 19))
    val scheduleEndHour   = MutableLiveData(prefs.getInt("scheduleEndHour", 22))

    val grabberStatus   = MutableLiveData(GrabberStatus.IDLE)
    val connectionState = MutableLiveData<ConnectionState>(ConnectionState.Idle)
    val testLedState    = MutableLiveData<String?>(null)  // null=idle, "..."=message

    private var checkJob: Job? = null

    init { checkConnection() }

    fun saveHost(v: String) {
        host.value = v
        prefs.edit().putString("host", v).apply()
        checkConnection()
    }

    fun savePort(v: Int) {
        port.value = v
        prefs.edit().putInt("port", v).apply()
    }

    fun saveFps(v: Int) {
        fps.value = v
        prefs.edit().putInt("fps", v).apply()
    }

    fun saveResolution(w: Int, h: Int) {
        targetWidth.value  = w
        targetHeight.value = h
        prefs.edit().putInt("targetWidth", w).putInt("targetHeight", h).apply()
    }

    fun saveSchedule(context: Context, mode: ScheduleMode, startHour: Int, endHour: Int) {
        scheduleMode.value      = mode
        scheduleStartHour.value = startHour
        scheduleEndHour.value   = endHour
        prefs.edit()
            .putString("scheduleMode", mode.name)
            .putInt("scheduleStartHour", startHour)
            .putInt("scheduleEndHour", endHour)
            .apply()
        ScheduleManager.apply(context, mode, startHour, endHour)
    }

    fun checkConnection() {
        checkJob?.cancel()
        if (host.value.isNullOrBlank()) {
            connectionState.value = ConnectionState.Idle
            return
        }
        checkJob = viewModelScope.launch {
            connectionState.value = ConnectionState.Checking
            val result = HyperionJsonClient.queryServerInfo(host.value!!)
            connectionState.value = result.fold(
                onSuccess = { info ->
                    saveResolution(info.recommendedWidth, info.recommendedHeight)
                    ConnectionState.Connected(info.ledCount, info.recommendedWidth, info.recommendedHeight)
                },
                onFailure = { ConnectionState.Failed(it.message ?: "Unknown error") }
            )
        }
    }

    // Called after user grants MediaProjection permission (first start)
    fun startGrabber(context: Context, resultCode: Int, data: Intent) {
        val intent = Intent(context, ScreenGrabberService::class.java).apply {
            putExtra(ScreenGrabberService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenGrabberService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenGrabberService.EXTRA_HOST, host.value)
            putExtra(ScreenGrabberService.EXTRA_PORT, port.value ?: 19400)
            putExtra(ScreenGrabberService.EXTRA_TARGET_WIDTH, targetWidth.value ?: 64)
            putExtra(ScreenGrabberService.EXTRA_TARGET_HEIGHT, targetHeight.value ?: 36)
            putExtra(ScreenGrabberService.EXTRA_FPS, fps.value ?: 25)
        }
        context.startForegroundService(intent)
        grabberStatus.value = GrabberStatus.RUNNING
    }

    // Resume a paused service (no permission dialog needed)
    fun resumeGrabber(context: Context) {
        context.startService(
            Intent(context, ScreenGrabberService::class.java).apply {
                action = ScreenGrabberService.ACTION_RESUME
            }
        )
        grabberStatus.value = GrabberStatus.RUNNING
    }

    fun testLeds() {
        viewModelScope.launch {
            testLedState.value = "Sending test frames…"
            val error = withContext(Dispatchers.IO) {
                HyperionNative.testConnection(host.value ?: return@withContext "No host set", port.value ?: 19400)
            }
            testLedState.value = if (error == null) "Test OK — LEDs should have flashed R/G/B" else "Test failed: $error"
        }
    }

    // Pause capture but keep the service (and MediaProjection token) alive
    fun stopGrabber(context: Context) {
        context.startService(
            Intent(context, ScreenGrabberService::class.java).apply {
                action = ScreenGrabberService.ACTION_PAUSE
            }
        )
        grabberStatus.value = GrabberStatus.IDLE
    }
}
