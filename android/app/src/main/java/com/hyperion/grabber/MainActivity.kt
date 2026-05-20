package com.hyperion.grabber

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Observer

class MainActivity : FragmentActivity() {

    private val vm: GrabberViewModel by viewModels()
    private lateinit var projManager: MediaProjectionManager

    companion object {
        private const val REQ_MEDIA_PROJECTION = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        projManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, MainFragment())
                .commit()
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
        if (requestCode == REQ_MEDIA_PROJECTION && data != null) {
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
