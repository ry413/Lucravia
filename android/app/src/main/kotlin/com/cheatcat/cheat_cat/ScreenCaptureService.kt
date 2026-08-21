package com.cheatcat.cheat_cat

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.media.Image
import android.media.ImageReader
import android.media.ToneGenerator
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.WindowManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_START = "com.cheatcat.cheat_cat.START_CAPTURE"
        const val ACTION_STOP = "com.cheatcat.cheat_cat.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_VLM_SERVER_URL = "vlm_server_url"
        const val EXTRA_DRIVER_LATITUDE = "driver_latitude"
        const val EXTRA_DRIVER_LONGITUDE = "driver_longitude"

        private const val NOTIFICATION_CHANNEL = "screen_analysis"
        private const val NOTIFICATION_ID = 3107
        private const val FRAME_SAMPLE_INTERVAL_MS = 450L
        private const val VLM_IMAGE_MAX_WIDTH = 720
        // Ignore the target app's changing header/countdown and its bottom navigation.
        private const val FINGERPRINT_TOP_RATIO = 0.18
        private const val FINGERPRINT_BOTTOM_RATIO = 0.92

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var vlmClient: VlmServerClient? = null
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler
    private lateinit var networkExecutor: ExecutorService
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null
    @Volatile
    private var vlmBusy = false
    @Volatile
    private var activeVlmCall: VlmServerClient.Call? = null
    private var lastFrameSampleAt = 0L
    private var stopping = false
    private var statusOverlay: AnalyzerStatusOverlay? = null
    private var highlightOverlay: OrderHighlightOverlay? = null
    @Volatile
    private var highlightFrameGuard: HighlightFrameGuard? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var scanRegion = ScanRegion.FULL_SCREEN
    @Volatile
    private var driverLatitude = Double.NaN
    @Volatile
    private var driverLongitude = Double.NaN
    private var locationManager: LocationManager? = null
    private var locationListener: LocationListener? = null
    private var fingerprintTopRatio = FINGERPRINT_TOP_RATIO
    private var fingerprintBottomRatio = FINGERPRINT_BOTTOM_RATIO
    private val stableFrameGate = StableFrameGate(requiredStableFrames = 2)
    private val inFlightChangeDetector = FrameChangeDetector()
    @Volatile
    private var inFlightScreenChanged = false

    override fun onCreate() {
        super.onCreate()
        captureThread = HandlerThread("cheat-cat-screen-capture").also { it.start() }
        captureHandler = Handler(captureThread.looper)
        // A cancelled HttpURLConnection normally unwinds immediately. Keep one spare worker so a
        // freshly stable page is never queued behind an old cloud request while it is unwinding.
        networkExecutor = Executors.newFixedThreadPool(2)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopAnalysis("屏幕分析已停止")
            ACTION_START -> startAnalysis(intent)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAnalysis(intent: Intent) {
        if (isRunning) return
        stopping = false
        startAsForeground()

        val serverUrl = intent.getStringExtra(EXTRA_VLM_SERVER_URL)?.trim().orEmpty()
        if (serverUrl.isEmpty()) {
            stopAnalysis("缺少局域网 VLM 服务地址")
            return
        }
        val driverLatitude = intent.getDoubleExtra(EXTRA_DRIVER_LATITUDE, Double.NaN)
        val driverLongitude = intent.getDoubleExtra(EXTRA_DRIVER_LONGITUDE, Double.NaN)
        if (!driverLatitude.isFinite() || !driverLongitude.isFinite()) {
            stopAnalysis("缺少司机当前位置")
            return
        }
        this.driverLatitude = driverLatitude
        this.driverLongitude = driverLongitude
        vlmClient = VlmServerClient(serverUrl)
        startLocationUpdates()
        scanRegion = ScanRegionPreferences.load(this).region
        scanRegion.fingerprintRatios(
            ignoredScreenTop = FINGERPRINT_TOP_RATIO,
            ignoredScreenBottom = FINGERPRINT_BOTTOM_RATIO,
        ).also { (top, bottom) ->
            fingerprintTopRatio = top
            fingerprintBottomRatio = bottom
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopAnalysis("屏幕授权数据无效")
            return
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            val activeProjection = manager.getMediaProjection(resultCode, resultData)
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    stopAnalysis("系统已结束屏幕捕获", stopProjection = false)
                }
            }
            activeProjection.registerCallback(callback, captureHandler)
            projection = activeProjection
            projectionCallback = callback

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi
            captureWidth = width
            captureHeight = height
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader = reader
            reader.setOnImageAvailableListener({ source -> onImage(source) }, captureHandler)
            virtualDisplay = activeProjection.createVirtualDisplay(
                "CheatCatOrderAnalysis",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                captureHandler,
            )

            isRunning = true
            prepareOverlays()
            val event = mapOf<String, Any>(
                "status" to "scanning",
                "message" to "等待订单画面稳定后调用 VLM",
            )
            AnalyzerEventBus.emit(event)
        } catch (error: Exception) {
            stopAnalysis("无法启动屏幕分析：${error.message}")
        }
    }

    private fun onImage(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameSampleAt < FRAME_SAMPLE_INTERVAL_MS) {
            image.close()
            return
        }
        lastFrameSampleAt = now

        val bitmap = try {
            image.toBitmap(maxWidth = VLM_IMAGE_MAX_WIDTH, region = scanRegion)
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }
        if (bitmap == null) {
            return
        }

        val frameSignature = OverlaySignatureMask.apply(
            signature = FrameChangeDetector.signature(
                bitmap,
                topRatio = fingerprintTopRatio,
                bottomRatio = fingerprintBottomRatio,
            ),
            bounds = statusOverlay?.bounds,
            screenWidth = captureWidth,
            screenHeight = captureHeight,
            scanRegion = scanRegion,
            fingerprintTopRatio = fingerprintTopRatio,
            fingerprintBottomRatio = fingerprintBottomRatio,
        )
        val activeHighlightGuard = highlightFrameGuard
        if (activeHighlightGuard != null) {
            if (activeHighlightGuard.hasPageChanged(frameSignature)) {
                clearHighlight()
                stableFrameGate.reset()
                updateStatus("画面变化 · 等待稳定")
            }
            bitmap.recycle()
            return
        }
        if (vlmBusy) {
            if (inFlightChangeDetector.hasSignificantChange(frameSignature)) {
                inFlightScreenChanged = true
                cancelInFlightAnalysis()
                stableFrameGate.reset()
                inFlightChangeDetector.reset()
                val message = "请求期间画面已变化，已取消旧分析并等待新画面稳定"
                AnalyzerEventBus.emit(mapOf("status" to "scanning", "message" to message))
                updateStatus("画面已变化 · 已取消旧分析")
            }
            bitmap.recycle()
            return
        }
        if (!stableFrameGate.shouldSubmit(frameSignature, now)) {
            bitmap.recycle()
            return
        }
        val client = vlmClient
        if (client == null) {
            bitmap.recycle()
            return
        }
        val call = client.newCall(
            bitmap,
            scanRegion,
            driverLatitude,
            driverLongitude,
        )
        activeVlmCall = call
        vlmBusy = true
        inFlightScreenChanged = false
        inFlightChangeDetector.accept(frameSignature)
        val pendingMessage = "发现稳定的新画面，正在请求局域网 VLM 服务…"
        AnalyzerEventBus.emit(mapOf("status" to "scanning", "message" to pendingMessage))
        updateStatus("识别中 · VLM + 高德")
        networkExecutor.execute {
            try {
                val result = call.execute()
                // A cancelled call may finish concurrently with its replacement. Only the call
                // that still owns the active slot is allowed to update UI or retry state.
                if (activeVlmCall !== call) return@execute
                when (result) {
                    is VlmServerClient.Result.Success -> {
                        if (!isRunning) return@execute
                        if (inFlightScreenChanged) {
                            val message = "请求期间画面已变化，已丢弃过期结果"
                            AnalyzerEventBus.emit(
                                mapOf("status" to "scanning", "message" to message),
                            )
                            updateStatus("画面已变化 · 丢弃结果")
                            return@execute
                        }
                        val analysis = result.analysis
                        val best = analysis.bestOrder
                        if (best == null) {
                            val message = if (analysis.orders.isEmpty()) {
                                "VLM 未发现订单"
                            } else {
                                "VLM 看到 ${analysis.orders.size} 张卡片，但没有字段完整的订单"
                            }
                            AnalyzerEventBus.emit(
                                mapOf("status" to "scanning", "message" to message),
                            )
                            updateStatus(
                                if (analysis.orders.isEmpty()) {
                                    "未发现订单"
                                } else {
                                    "看到 ${analysis.orders.size} 张 · 无完整单"
                                },
                            )
                        } else {
                            val count = analysis.completeOrders.size
                            val message = "本屏识别到 $count 张完整订单，已按高德有效时薪排序"
                            AnalyzerEventBus.emit(analysis.toEvent(message))
                            updateStatus("已识别 $count 单 · 已标注")
                            showOrderHighlights(analysis, frameSignature)
                        }
                    }
                    is VlmServerClient.Result.Failure -> {
                        stableFrameGate.markFailed(
                            frameSignature,
                            SystemClock.elapsedRealtime(),
                        )
                        if (!isRunning) return@execute
                        val message = "VLM 请求失败：${result.message}，稍后重试"
                        AnalyzerEventBus.emit(
                            mapOf("status" to "scanning", "message" to message),
                        )
                        updateStatus("请求失败 · 稍后重试")
                    }
                    VlmServerClient.Result.Cancelled -> Unit
                }
            } finally {
                bitmap.recycle()
                if (activeVlmCall === call) {
                    activeVlmCall = null
                    vlmBusy = false
                }
            }
        }
    }

    private fun cancelInFlightAnalysis() {
        val call = activeVlmCall ?: return
        activeVlmCall = null
        vlmBusy = false
        call.cancel()
    }

    private fun Image.toBitmap(maxWidth: Int, region: ScanRegion): Bitmap {
        val plane = planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        val regionTop = region.topPixel(height)
        val regionBottom = region.bottomPixel(height)
        val cropped = Bitmap.createBitmap(
            padded,
            0,
            regionTop,
            width,
            regionBottom - regionTop,
        )
        if (cropped !== padded) padded.recycle()
        if (width <= maxWidth) return cropped

        val scaledHeight = (cropped.height * maxWidth.toFloat() / width).roundToInt()
        val scaled = Bitmap.createScaledBitmap(cropped, maxWidth, scaledHeight, true)
        if (scaled !== cropped) cropped.recycle()
        return scaled
    }

    private fun prepareOverlays() {
        if (!Settings.canDrawOverlays(this)) return
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (highlightOverlay == null) highlightOverlay = OrderHighlightOverlay(this, manager)
        if (statusOverlay == null) {
            statusOverlay = AnalyzerStatusOverlay(this, manager).also {
                it.show("运行中 · 等待稳定")
            }
        }
    }

    private fun updateStatus(status: String) {
        statusOverlay?.update(status)
    }

    private fun showOrderHighlights(
        analysis: VlmScreenAnalysis,
        baselineSignature: ByteArray,
    ) {
        val overlay = highlightOverlay ?: return
        val specs = analysis.rankedCompleteOrders.take(3).mapIndexedNotNull { index, order ->
            val box = order.boundingBox ?: return@mapIndexedNotNull null
            val label = OrderHighlightLabelFormatter.format(order, index)
            OrderHighlightSpec(box = box, label = label, rank = index)
        }
        if (specs.isEmpty()) return
        val guard = HighlightFrameGuard(
            baseline = baselineSignature,
            boxes = specs.map { it.box },
            cropTopRatio = fingerprintTopRatio,
            cropBottomRatio = fingerprintBottomRatio,
            badgeWidthRatio = OrderHighlightOverlay.BADGE_WIDTH_DP *
                resources.displayMetrics.density.toDouble() / captureWidth,
            badgeHeightRatio = OrderHighlightOverlay.BADGE_HEIGHT_DP *
                resources.displayMetrics.density.toDouble() /
                scanRegion.heightPixels(captureHeight),
        )
        highlightFrameGuard = guard
        Handler(mainLooper).post {
            if (!isRunning || highlightFrameGuard !== guard) return@post
            overlay.show(specs, captureWidth, captureHeight, scanRegion)
            playStrongAlert()
        }
    }

    private fun clearHighlight() {
        highlightFrameGuard = null
        val overlay = highlightOverlay
        Handler(mainLooper).post { overlay?.hide() }
    }

    private fun playStrongAlert() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 350)
            Handler(mainLooper).postDelayed({ tone.release() }, 500)
        }
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(longArrayOf(0, 180, 100, 260), -1),
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 180, 100, 260), -1)
            }
        }
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        val granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                driverLatitude = location.latitude
                driverLongitude = location.longitude
            }
        }
        locationManager = manager
        locationListener = listener
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { manager.isProviderEnabled(it) }
            .forEach { provider ->
                manager.requestLocationUpdates(provider, 5_000L, 20f, listener, mainLooper)
            }
    }

    private fun startAsForeground() {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("跑单助手正在等待稳定订单画面")
            .setContentText("新画面将发送到局域网 VLM 服务")
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "停止", stopPendingIntent).build())
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                "屏幕订单分析",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    @Synchronized
    private fun stopAnalysis(message: String, stopProjection: Boolean = true) {
        if (stopping) return
        stopping = true
        isRunning = false
        stableFrameGate.reset()
        inFlightChangeDetector.reset()
        inFlightScreenChanged = false
        cancelInFlightAnalysis()
        clearHighlight()
        locationListener?.let { listener -> locationManager?.removeUpdates(listener) }
        locationListener = null
        locationManager = null
        vlmClient?.close()
        vlmClient = null
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        val activeProjection = projection
        val callback = projectionCallback
        projection = null
        projectionCallback = null
        if (callback != null) activeProjection?.unregisterCallback(callback)
        if (stopProjection) activeProjection?.stop()
        statusOverlay?.hide()
        statusOverlay = null
        highlightOverlay = null
        AnalyzerEventBus.emit(mapOf("status" to "stopped", "message" to message))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        if (!stopping) stopAnalysis("屏幕分析已结束")
        networkExecutor.shutdownNow()
        captureThread.quitSafely()
        super.onDestroy()
    }
}
