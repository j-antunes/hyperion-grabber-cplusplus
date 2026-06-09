package com.hyperion.grabber

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.lifecycle.MutableLiveData

class ScreenGrabberService : Service() {

    enum class Status { STOPPED, CONNECTING, RUNNING, PAUSED, ERROR }

    // Touched only on ioThread (except in onDestroy after the thread is joined)
    private var nativeHandle: Long = 0
    private var lastFrameSentMs = 0L
    private var consecutiveFailures = 0

    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var ioThread: HandlerThread
    private lateinit var ioHandler: Handler

    private var frameCountStart = 0L

    // Pause/resume state machine — see CaptureStateController for transitions.
    // Mutated on the main thread only (intents + screen broadcasts).
    private var captureState = CaptureStateController.State()

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val event = when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> CaptureStateController.Event.ScreenOff
                Intent.ACTION_SCREEN_ON  -> CaptureStateController.Event.ScreenOn
                else -> return
            }
            if (mediaProjection == null) return
            applyEvent(event, logTagFor(event))
        }
    }

    private fun logTagFor(event: CaptureStateController.Event) = when (event) {
        CaptureStateController.Event.ScreenOff -> "SCREEN_OFF"
        CaptureStateController.Event.ScreenOn  -> "SCREEN_ON"
        CaptureStateController.Event.UserPause  -> "PAUSE"
        CaptureStateController.Event.UserResume -> "RESUME"
    }

    private fun applyEvent(event: CaptureStateController.Event, tag: String) {
        val result = CaptureStateController.transition(captureState, event)
        captureState = result.state
        isPaused = result.state.paused
        when (result.action) {
            CaptureStateController.Action.None -> {
                Log.d(TAG, "$tag — no state change")
            }
            CaptureStateController.Action.Pause -> {
                Log.d(TAG, "$tag — pausing capture")
                pauseCapture()
                updateNotification("Paused — tap Start to resume")
            }
            // Both pause flavors drop TCP here: the VirtualDisplay must stay
            // alive either way (Android 14+ forbids re-creating it), and
            // disconnecting frees the Hyperion priority while paused.
            CaptureStateController.Action.PauseAndDisconnect -> {
                Log.d(TAG, "$tag — pausing capture and dropping connection")
                pauseCapture()
                updateNotification("Paused (TV off)")
            }
            CaptureStateController.Action.Resume -> {
                Log.d(TAG, "$tag — resuming capture")
                resumeCapture()
                updateNotification("Streaming screen to Hyperion…")
            }
        }
    }

    // Runs on ioThread. Sends a keepalive when no frame went out recently
    // (static screen), and owns reconnection: if the native client is gone
    // (connect failed or was torn down after send failures), retry here so
    // a static screen can't strand us disconnected forever.
    private val keepaliveRunnable = object : Runnable {
        override fun run() {
            if (!isPaused) {
                if (nativeHandle == 0L) {
                    if (connectNative()) {
                        Log.d(TAG, "Reconnected to Hyperion at $captureHost:$capturePort")
                        statusLive.postValue(Status.RUNNING)
                    } else {
                        Log.w(TAG, "Reconnect failed — will retry in ${KEEPALIVE_INTERVAL_MS}ms")
                    }
                } else if (System.currentTimeMillis() - lastFrameSentMs >= KEEPALIVE_INTERVAL_MS) {
                    if (HyperionNative.sendKeepalive(nativeHandle)) {
                        Log.d(TAG, "Keepalive sent")
                    } else {
                        Log.w(TAG, "Keepalive failed — dropping connection for reconnect")
                        destroyNative()
                    }
                }
            }
            ioHandler.postDelayed(this, KEEPALIVE_INTERVAL_MS)
        }
    }

    // Persisted so RESUME can restart capture with the same config
    private var captureHost = ""
    private var capturePort = 19400
    private var captureDstW = 64
    private var captureDstH = 36
    private var captureFps  = 25

    companion object {
        private const val TAG           = "ScreenGrabberService"
        private const val NOTIF_CHANNEL = "hyperion_grabber"
        private const val NOTIF_ID      = 1
        private const val FRAME_LOG_INTERVAL    = 100L   // log fps every 100 frames
        private const val KEEPALIVE_INTERVAL_MS = 3000L  // resend last frame if screen is static

        const val ACTION_PAUSE  = "com.hyperion.grabber.PAUSE"
        const val ACTION_RESUME = "com.hyperion.grabber.RESUME"

        const val EXTRA_RESULT_CODE   = "resultCode"
        const val EXTRA_RESULT_DATA   = "resultData"
        const val EXTRA_HOST          = "host"
        const val EXTRA_PORT          = "port"
        const val EXTRA_TARGET_WIDTH  = "targetWidth"
        const val EXTRA_TARGET_HEIGHT = "targetHeight"
        const val EXTRA_FPS           = "fps"

        private const val SRC_WIDTH  = 960
        private const val SRC_HEIGHT = 540

        @Volatile var isRunning  = false
        @Volatile var isPaused   = false
        @Volatile var frameCount = 0L

        // Observed by the UI so it reflects what the service actually did
        // (e.g. connect failure) instead of assuming success.
        val statusLive = MutableLiveData(Status.STOPPED)
        val lastError  = MutableLiveData<String?>(null)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        ioThread  = HandlerThread("HyperionIO").also { it.start() }
        ioHandler = Handler(ioThread.looper)
        registerReceiver(screenStateReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                applyEvent(CaptureStateController.Event.UserPause, "PAUSE")
                return START_STICKY
            }
            ACTION_RESUME -> {
                if (mediaProjection == null) {
                    Log.w(TAG, "RESUME received but no projection — ignoring")
                    return START_STICKY
                }
                applyEvent(CaptureStateController.Event.UserResume, "RESUME")
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
                captureFps  = intent.getIntExtra(EXTRA_FPS, captureFps).coerceIn(1, 60)

                Log.d(TAG, "Starting: target=$captureHost:$capturePort size=${captureDstW}×${captureDstH} fps=$captureFps")

                createNotificationChannel()
                startForeground(
                    NOTIF_ID,
                    buildNotification("Streaming screen to Hyperion…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )

                val projManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projManager.getMediaProjection(resultCode, resultData)
                Log.d(TAG, "MediaProjection obtained")

                captureState = CaptureStateController.State()
                lastError.value  = null
                statusLive.value = Status.CONNECTING

                // TCP connect must not block the main thread (ANR)
                ioHandler.post {
                    if (!connectNative()) {
                        Log.e(TAG, "Native init failed — could not connect to $captureHost:$capturePort")
                        lastError.postValue("Could not connect to $captureHost:$capturePort")
                        statusLive.postValue(Status.ERROR)
                        stopSelf()
                    } else {
                        Log.d(TAG, "Connected to Hyperion at $captureHost:$capturePort")
                        startCapture()
                        isPaused = false
                        statusLive.postValue(Status.RUNNING)
                    }
                }
            }
        }
        return START_STICKY
    }

    // ioThread only
    private fun connectNative(): Boolean {
        nativeHandle = HyperionNative.create(
            captureHost, capturePort,
            SRC_WIDTH, SRC_HEIGHT,
            captureDstW, captureDstH, captureFps
        )
        return nativeHandle != 0L
    }

    // ioThread only
    private fun destroyNative() {
        if (nativeHandle != 0L) {
            HyperionNative.destroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    // ioThread only. Creates the virtual display exactly once per projection:
    // Android 14+ throws if createVirtualDisplay is called a second time on
    // the same MediaProjection, so pause/resume must keep it alive and just
    // gate frame delivery with isPaused.
    private fun startCapture() {
        frameCount = 0L
        frameCountStart = System.currentTimeMillis()
        lastFrameSentMs = 0L
        consecutiveFailures = 0

        imageReader = ImageReader.newInstance(SRC_WIDTH, SRC_HEIGHT, PixelFormat.RGBA_8888, 2)
        val frameIntervalMs = 1000L / captureFps

        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (isPaused || nativeHandle == 0L) return@setOnImageAvailableListener
                val now = System.currentTimeMillis()
                if (now - lastFrameSentMs < frameIntervalMs) return@setOnImageAvailableListener
                lastFrameSentMs = now
                val plane = image.planes[0]
                val ok = HyperionNative.sendFrame(nativeHandle, plane.buffer, plane.rowStride)
                if (!ok) {
                    consecutiveFailures++
                    Log.w(TAG, "sendFrame failed ($consecutiveFailures consecutive)")
                    if (consecutiveFailures >= 3) {
                        consecutiveFailures = 0
                        Log.w(TAG, "Dropping connection — keepalive will reconnect")
                        destroyNative()
                    }
                    return@setOnImageAvailableListener
                }
                consecutiveFailures = 0
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
        }, ioHandler)

        // Required on targetSdk 34+ — createVirtualDisplay throws without it.
        // Also tells us when the user/system revokes the projection.
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped externally — shutting down")
                stopSelf()
            }
        }
        projectionCallback = callback
        mediaProjection!!.registerCallback(callback, ioHandler)

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "HyperionGrabber", SRC_WIDTH, SRC_HEIGHT,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
        Log.d(TAG, "Capture started: ${SRC_WIDTH}×${SRC_HEIGHT} → ${captureDstW}×${captureDstH} @ ${captureFps}fps")

        ioHandler.postDelayed(keepaliveRunnable, KEEPALIVE_INTERVAL_MS)
    }

    // isPaused is owned by applyEvent; this just tears down the connection.
    private fun pauseCapture() {
        statusLive.value = Status.PAUSED
        ioHandler.removeCallbacks(keepaliveRunnable)
        ioHandler.post {
            destroyNative()
            Log.d(TAG, "Capture paused — $frameCount frames sent this session")
        }
    }

    private fun resumeCapture() {
        statusLive.value = Status.CONNECTING
        ioHandler.post {
            val ok = nativeHandle != 0L || connectNative()
            if (ok) {
                Log.d(TAG, "Reconnected to Hyperion on resume")
                statusLive.postValue(Status.RUNNING)
            } else {
                Log.e(TAG, "Reconnect failed on resume — keepalive will retry")
                lastError.postValue("Could not reconnect to $captureHost:$capturePort")
            }
            lastFrameSentMs = 0L
            // (Re)start the keepalive loop either way — it retries the
            // connection until it succeeds or we're paused again.
            ioHandler.removeCallbacks(keepaliveRunnable)
            ioHandler.postDelayed(keepaliveRunnable, KEEPALIVE_INTERVAL_MS)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroying — total frames sent: $frameCount")
        isPaused = true
        try { unregisterReceiver(screenStateReceiver) } catch (_: IllegalArgumentException) {}
        ioHandler.removeCallbacks(keepaliveRunnable)

        projectionCallback?.let { mediaProjection?.unregisterCallback(it) }
        projectionCallback = null
        virtualDisplay?.release(); virtualDisplay = null
        mediaProjection?.stop();   mediaProjection = null

        // Tear down the reader and native client on the IO thread, then join
        // it, so no in-flight sendFrame races the native delete.
        ioHandler.post {
            imageReader?.close()
            imageReader = null
            destroyNative()
        }
        ioThread.quitSafely()
        runCatching { ioThread.join(2000) }

        isRunning = false
        isPaused  = false
        if (statusLive.value != Status.ERROR) statusLive.value = Status.STOPPED
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
