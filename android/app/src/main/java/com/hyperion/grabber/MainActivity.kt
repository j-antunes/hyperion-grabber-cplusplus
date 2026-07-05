package com.hyperion.grabber

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Observer

class MainActivity : FragmentActivity() {

    private val vm: GrabberViewModel by viewModels()
    private lateinit var projManager: MediaProjectionManager

    companion object {
        private const val REQ_MEDIA_PROJECTION = 100
        private const val REQ_POST_NOTIFICATIONS = 101
        const val EXTRA_AUTO_START = "auto_start"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        projManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        maybeRequestNotificationPermission()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, MainFragment())
                .commit()

            // Only on a fresh launch — the auto-start extra survives recreation,
            // so without this guard a config change would silently re-request
            // projection (and resume a grabber the user had paused).
            if (intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true) {
                requestProjection()
            }
        }
    }

    // singleTask launchMode: a second launch with the auto-start extra (e.g. from
    // ScheduleReceiver after boot) lands here instead of in onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
            requestProjection()
        }
    }

    // On Android 13+ the FGS notification (and its pause-state updates) is hidden
    // unless the user grants POST_NOTIFICATIONS at runtime.
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
        }
    }

    fun requestProjection() {
        when {
            // Service running and paused → resume without asking for permission again
            ScreenGrabberService.isRunning && ScreenGrabberService.isPaused ->
                vm.resumeGrabber(this)

            // Service already capturing → nothing to do
            ScreenGrabberService.isRunning && !ScreenGrabberService.isPaused ->
                Unit

            // Service not running → need fresh MediaProjection consent
            else ->
                startActivityForResult(projManager.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MEDIA_PROJECTION && resultCode == RESULT_OK && data != null) {
            vm.startGrabber(this, resultCode, data)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // If the service is paused (not actively grabbing), stop it fully so
        // no stale notification appears after the user leaves the app.
        if (ScreenGrabberService.isRunning && ScreenGrabberService.isPaused) {
            stopService(Intent(this, ScreenGrabberService::class.java))
        }
        super.onBackPressed()
    }
}
