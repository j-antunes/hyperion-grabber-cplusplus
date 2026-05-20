package com.hyperion.grabber

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class ScreenGrabberService : Service() {

    private var nativeHandle: Long = 0
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var imageThread: HandlerThread? = null

    // Frame rate tracking
    private var frameCount = 0L
    private var frameCountStart = 0L

    // Persisted so RESUME can restart capture with the same config
    private var captureHost = "192.168.14.253"
    private var capturePort = 19400
    private var captureDstW = 64
    private var captureDstH = 36
    private var captureFps  = 25

    companion object {
        private const val TAG           = "ScreenGrabberService"
        private const val NOTIF_CHANNEL = "hyperion_grabber"
        private const val NOTIF_ID      = 1
        private const val FRAME_LOG_INTERVAL = 100L  // log fps every 100 frames

        const val ACTION_PAUSE  = "com.hyperion.grabber.PAUSE"
        const val ACTION_RESUME = "com.hyperion.grabber.RESUME"

        const val EXTRA_RESULT_CODE   = "resultCode"
        const val EXTRA_RESULT_DATA   = "resultData"
        const val EXTRA_HOST          = "host"
        const val EXTRA_PORT          = "port"
        const val EXTRA_TARGET_WIDTH  = "targetWidth"
        const val EXTRA_TARGET_HEIGHT = "targetHeight"
        const val EXTRA_FPS           = "fps"

        private const val SRC_WIDTH  = 1920
        private const val SRC_HEIGHT = 1080

        @Volatile var isRunning = false
        @Volatile var isPaused  = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                Log.d(TAG, "PAUSE received — stopping capture")
                pauseCapture()
                isPaused = true
                updateNotification("Paused — tap Start to resume")
                return START_STICKY
            }
            ACTION_RESUME -> {
                if (mediaProjection != null && isPaused) {
                    Log.d(TAG, "RESUME received — restarting capture")
                    resumeCapture()
                    isPaused = false
                    updateNotification("Streaming screen to Hyperion…")
                } else {
                    Log.w(TAG, "RESUME received but projection=${mediaProjection != null} paused=$isPaused — ignoring")
                }
                return START_STICKY
            }
            else -> {
                val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: return START_NOT_STICKY
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                    ?: return START_NOT_STICKY

                captureHost = intent.getStringExtra(EXTRA_HOST) ?: captureHost
                capturePort = intent.getIntExtra(EXTRA_PORT, capturePort)
                captureDstW = intent.getIntExtra(EXTRA_TARGET_WIDTH, captureDstW)
                captureDstH = intent.getIntExtra(EXTRA_TARGET_HEIGHT, captureDstH)
                captureFps  = intent.getIntExtra(EXTRA_FPS, captureFps)

                Log.d(TAG, "Starting: target=$captureHost:$capturePort size=${captureDstW}×${captureDstH} fps=$captureFps")

                createNotificationChannel()
                startForeground(
                    NOTIF_ID,
                    buildNotification("Streaming screen to Hyperion…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )

                nativeHandle = HyperionNative.create(
                    captureHost, capturePort,
                    SRC_WIDTH, SRC_HEIGHT,
                    captureDstW, captureDstH, captureFps
                )
                if (nativeHandle == 0L) {
                    Log.e(TAG, "Native init failed — could not connect to $captureHost:$capturePort")
                    stopSelf()
                    return START_NOT_STICKY
                }
                Log.d(TAG, "Connected to Hyperion at $captureHost:$capturePort")

                val projManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projManager.getMediaProjection(resultCode, resultData)
                Log.d(TAG, "MediaProjection obtained")

                startCapture()
                isPaused = false
            }
        }
        return START_STICKY
    }

    private fun startCapture() {
        frameCount = 0
        frameCountStart = System.currentTimeMillis()

        imageThread = HandlerThread("HyperionImageReader").also { it.start() }
        imageReader = ImageReader.newInstance(SRC_WIDTH, SRC_HEIGHT, PixelFormat.RGBA_8888, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val ok = HyperionNative.sendFrame(nativeHandle, plane.buffer, plane.rowStride)
                if (!ok) {
                    Log.w(TAG, "sendFrame returned false — Hyperion connection may have dropped")
                    return@setOnImageAvailableListener
                }
                frameCount++
                if (frameCount == 1L) {
                    Log.d(TAG, "First frame sent successfully")
                }
                if (frameCount % FRAME_LOG_INTERVAL == 0L) {
                    val elapsed = (System.currentTimeMillis() - frameCountStart) / 1000.0
                    val fps = if (elapsed > 0) frameCount / elapsed else 0.0
                    Log.d(TAG, "Sent $frameCount frames, avg %.1f fps".format(fps))
                }
            } finally {
                image.close()
            }
        }, Handler(imageThread!!.looper))

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "HyperionGrabber", SRC_WIDTH, SRC_HEIGHT,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
        Log.d(TAG, "Capture started: ${SRC_WIDTH}×${SRC_HEIGHT} → ${captureDstW}×${captureDstH} @ ${captureFps}fps")
    }

    private fun pauseCapture() {
        val sent = frameCount
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close();      imageReader    = null
        imageThread?.quitSafely(); imageThread    = null
        Log.d(TAG, "Capture paused — $sent frames sent this session")
    }

    private fun resumeCapture() {
        if (nativeHandle == 0L) {
            nativeHandle = HyperionNative.create(
                captureHost, capturePort,
                SRC_WIDTH, SRC_HEIGHT,
                captureDstW, captureDstH, captureFps
            )
            if (nativeHandle == 0L) {
                Log.e(TAG, "Reconnect failed on resume — $captureHost:$capturePort unreachable")
                return
            }
            Log.d(TAG, "Reconnected to Hyperion on resume")
        }
        if (mediaProjection != null) startCapture()
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroying — total frames sent: $frameCount")
        pauseCapture()
        mediaProjection?.stop()
        if (nativeHandle != 0L) {
            HyperionNative.destroy(nativeHandle)
            nativeHandle = 0
        }
        isRunning = false
        isPaused  = false
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(NOTIF_CHANNEL, "Screen Grabber", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("Hyperion Grabber")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }
}
