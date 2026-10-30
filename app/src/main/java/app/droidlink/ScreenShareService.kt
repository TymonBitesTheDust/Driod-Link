package app.droidlink

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Foreground service on the sharing phone: captures the screen with MediaProjection,
 * encodes frames as JPEG, serves them to the viewer and forwards its input to [RemoteControlService].
 */
class ScreenShareService : Service(), HostServer.Listener {

    companion object {
        const val ACTION_START = "app.droidlink.action.START"
        const val ACTION_STOP = "app.droidlink.action.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val TAG = "ScreenShare"
        private const val CHANNEL_ID = "screen_sharing"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_CAPTURE_DIMENSION = 1280
        private const val FRAME_INTERVAL_MS = 66L // about 15 frames per second
        private const val JPEG_QUALITY = 60

        @Volatile var current: ScreenShareService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    @Volatile private var captureHandler: Handler? = null
    @Volatile private var server: HostServer? = null
    private var advertiser: NsdAdvertiser? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var started = false
    private var stopped = false

    @Volatile private var screenWidth = 0
    @Volatile private var screenHeight = 0
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDpi = 0

    // Only touched on the capture thread.
    private var frameBitmap: Bitmap? = null
    private var copyBuffer: ByteBuffer? = null
    private var contentWidth = 0
    private var contentHeight = 0
    private var dirty = false
    private var encodeScheduled = false
    private var lastEncodeAt = 0L
    private val encodeRunnable = Runnable {
        encodeScheduled = false
        maybeEncode(force = false)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSharing()
            ACTION_START -> {
                if (started) return START_NOT_STICKY
                started = true
                ShareState.update {
                    pin = generatePin()
                    lastError = null
                }
                // Must be in the foreground with the mediaProjection type before asking for the projection.
                goForeground()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = resultDataOf(intent)
                if (data == null) {
                    stopSharing()
                    return START_NOT_STICKY
                }
                try {
                    startSharing(resultCode, data)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not start sharing", e)
                    ShareState.update { lastError = e.message ?: "Could not start screen sharing" }
                    stopSharing()
                }
            }
            else -> if (!started) stopSelf()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun resultDataOf(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

    override fun onDestroy() {
        stopSharing()
        super.onDestroy()
    }

    // ---- Notification ----

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
            channel.description = getString(R.string.channel_description)
            nm.createNotificationChannel(channel)
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, HostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP),
            flags
        )
        val text = if (ShareState.status == ShareState.Status.CONNECTED) {
            getString(R.string.notif_connected, ShareState.peer ?: "")
        } else {
            getString(R.string.notif_waiting, formatPin(ShareState.pin))
        }
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_close),
            getString(R.string.stop_sharing),
            stopIntent
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_share)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(stopAction)
            .build()
    }

    private fun updateNotification() {
        if (stopped) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    // ---- Capture ----

    private fun startSharing(resultCode: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val mp = manager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("Screen capture permission was not granted")
        projection = mp

        val thread = HandlerThread("droidlink-capture").also { it.start() }
        captureThread = thread
        val handler = Handler(thread.looper)
        captureHandler = handler

        // Android 14+ requires the callback before creating the virtual display.
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post { stopSharing() }
            }
        }, handler)

        readDisplay()
        val reader = newImageReader(handler)
        imageReader = reader
        virtualDisplay = mp.createVirtualDisplay(
            "DroidLink",
            captureWidth, captureHeight, captureDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )

        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) onScreenGeometryChanged()
            }
        }
        getSystemService(DisplayManager::class.java).registerDisplayListener(listener, handler)
        displayListener = listener

        val srv = HostServer({ ShareState.pin }, this)
        val port = srv.start()
        server = srv
        advertiser = NsdAdvertiser(this).also { it.register(deviceName(), port) }
        current = this

        ShareState.update {
            status = ShareState.Status.WAITING
            this.port = port
            peer = null
        }
        updateNotification()
    }

    private fun deviceName(): String {
        val name = try {
            Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
        } catch (e: Exception) {
            null
        }
        return if (name.isNullOrBlank()) Build.MODEL else name
    }

    @Suppress("DEPRECATION")
    private fun readDisplay() {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        val scale = min(1f, MAX_CAPTURE_DIMENSION.toFloat() / max(screenWidth, screenHeight))
        captureWidth = evenAtLeast2((screenWidth * scale).roundToInt())
        captureHeight = evenAtLeast2((screenHeight * scale).roundToInt())
        captureDpi = max(120, (metrics.densityDpi * scale).roundToInt())
    }

    private fun evenAtLeast2(value: Int): Int = max(2, value - value % 2)

    private fun newImageReader(handler: Handler): ImageReader =
        ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ onImageAvailable(it) }, handler)
        }

    /** Rotation or resolution change: resize the capture to match. Runs on the capture thread. */
    private fun onScreenGeometryChanged() {
        val oldWidth = screenWidth
        val oldHeight = screenHeight
        readDisplay()
        if (screenWidth == oldWidth && screenHeight == oldHeight) return
        val handler = captureHandler ?: return
        val vd = virtualDisplay ?: return
        val newReader = newImageReader(handler)
        vd.surface = newReader.surface
        vd.resize(captureWidth, captureHeight, captureDpi)
        imageReader?.close()
        imageReader = newReader
        server?.sendInfo(screenInfo())
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: Exception) {
            null
        } ?: return
        try {
            val plane = image.planes[0]
            val width = image.width
            val height = image.height
            val paddedWidth = plane.rowStride / plane.pixelStride
            val bitmap = frameBitmap?.takeIf { it.width == paddedWidth && it.height == height }
                ?: run {
                    frameBitmap?.recycle()
                    Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888).also { frameBitmap = it }
                }
            val source = plane.buffer
            source.rewind()
            val needed = bitmap.byteCount
            if (source.remaining() >= needed) {
                bitmap.copyPixelsFromBuffer(source)
            } else {
                // Some devices leave out the padding after the last row.
                val tmp = copyBuffer?.takeIf { it.capacity() >= needed }
                    ?: ByteBuffer.allocateDirect(needed).also { copyBuffer = it }
                tmp.clear()
                tmp.put(source)
                tmp.rewind()
                bitmap.copyPixelsFromBuffer(tmp)
            }
            contentWidth = width
            contentHeight = height
            dirty = true
        } catch (e: Exception) {
            Log.w(TAG, "Frame copy failed", e)
        } finally {
            image.close()
        }
        scheduleEncode()
    }

    private fun scheduleEncode() {
        val handler = captureHandler ?: return
        if (encodeScheduled) return
        encodeScheduled = true
        val wait = (lastEncodeAt + FRAME_INTERVAL_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        handler.postDelayed(encodeRunnable, wait)
    }

    private fun maybeEncode(force: Boolean) {
        val srv = server ?: return
        if (!srv.hasClient) return
        if (!dirty && !force) return
        val bitmap = frameBitmap ?: return
        if (contentWidth <= 0 || contentHeight <= 0) return
        dirty = false
        lastEncodeAt = SystemClock.uptimeMillis()
        try {
            val frame = if (bitmap.width == contentWidth && bitmap.height == contentHeight) bitmap
            else Bitmap.createBitmap(bitmap, 0, 0, contentWidth, contentHeight)
            val out = ByteArrayOutputStream(96 * 1024)
            frame.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (frame !== bitmap) frame.recycle()
            srv.publishFrame(out.toByteArray())
        } catch (e: Exception) {
            Log.w(TAG, "Encode failed", e)
        }
    }

    // ---- HostServer.Listener ----

    override fun screenInfo(): HostServer.ScreenInfo =
        HostServer.ScreenInfo(screenWidth, screenHeight, RemoteControlService.instance != null)

    override fun onClientConnected(address: String) {
        ShareState.update {
            status = ShareState.Status.CONNECTED
            peer = address
        }
        mainHandler.post { updateNotification() }
        // Send the current screen straight away, even if nothing is changing on it.
        captureHandler?.post { maybeEncode(force = true) }
    }

    override fun onClientDisconnected() {
        if (stopped) return
        ShareState.update {
            status = ShareState.Status.WAITING
            peer = null
        }
        mainHandler.post { updateNotification() }
    }

    override fun onGesture(points: FloatArray, durationMs: Long) {
        val control = RemoteControlService.instance ?: return
        val w = screenWidth
        val h = screenHeight
        if (w <= 0 || h <= 0) return
        val pixels = FloatArray(points.size)
        var i = 0
        while (i + 1 < points.size) {
            pixels[i] = points[i].coerceIn(0f, 1f) * (w - 1)
            pixels[i + 1] = points[i + 1].coerceIn(0f, 1f) * (h - 1)
            i += 2
        }
        control.performGesture(pixels, durationMs)
    }

    override fun onGlobalAction(action: Int) {
        RemoteControlService.instance?.performRemoteAction(action)
    }

    override fun onText(mode: Int, text: String) {
        RemoteControlService.instance?.typeText(mode, text)
    }

    override fun onTooManyBadPins() {
        // Someone keeps guessing: change the PIN so guessing gets nowhere.
        ShareState.update { pin = generatePin() }
        mainHandler.post { updateNotification() }
    }

    /** Called when remote control is switched on or off while sharing. */
    fun onControlAvailabilityChanged() {
        server?.sendInfo(screenInfo())
    }

    // ---- Stop ----

    private fun stopSharing() {
        if (stopped) return
        stopped = true
        current = null

        advertiser?.unregister()
        advertiser = null
        server?.stop()
        server = null
        displayListener?.let { getSystemService(DisplayManager::class.java).unregisterDisplayListener(it) }
        displayListener = null

        val vd = virtualDisplay
        val reader = imageReader
        val mp = projection
        virtualDisplay = null
        imageReader = null
        projection = null
        val handler = captureHandler
        val thread = captureThread
        captureHandler = null
        captureThread = null

        val release = Runnable {
            try { vd?.release() } catch (e: Exception) { Log.w(TAG, "release", e) }
            try { reader?.close() } catch (e: Exception) { Log.w(TAG, "close", e) }
            try { mp?.stop() } catch (e: Exception) { Log.w(TAG, "stop", e) }
            frameBitmap?.recycle()
            frameBitmap = null
            thread?.quitSafely()
        }
        if (handler != null) handler.post(release) else release.run()

        ShareState.update {
            status = ShareState.Status.IDLE
            peer = null
            pin = ""
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
