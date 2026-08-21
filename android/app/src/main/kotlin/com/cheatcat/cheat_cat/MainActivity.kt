package com.cheatcat.cheat_cat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    companion object {
        private const val CAPTURE_REQUEST = 9071
        private const val LOCATION_PERMISSION_REQUEST = 9072
        private const val METHOD_CHANNEL = "cheat_cat/screen_analyzer"
        private const val EVENT_CHANNEL = "cheat_cat/screen_analyzer_events"
    }

    private var pendingVlmServerUrl: String? = null
    private var pendingDriverLocation: Location? = null
    private var calibrationController: ScanRegionCalibrationController? = null
    private var calibrationOverlay: ScanRegionCalibrationOverlay? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "capabilities" -> {
                        val storedRegion = ScanRegionPreferences.load(this)
                        result.success(
                            mapOf(
                                "overlayGranted" to Settings.canDrawOverlays(this),
                                "locationGranted" to hasLocationPermission(),
                                "running" to ScreenCaptureService.isRunning,
                                "vlmServerConfigured" to
                                    (BuildConfig.VLM_SERVER_URL.isNotBlank() &&
                                        BuildConfig.SHARED_SECRET.trim().length >= 32),
                                "scanRegionConfigured" to storedRegion.configured,
                                "scanTopRatio" to storedRegion.region.topRatio,
                                "scanBottomRatio" to storedRegion.region.bottomRatio,
                            ),
                        )
                    }
                    "requestOverlayPermission" -> {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                        result.success(null)
                    }
                    "requestLocationPermission" -> {
                        requestPermissions(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                            LOCATION_PERMISSION_REQUEST,
                        )
                        result.success(null)
                    }
                    "startCapture" -> {
                        val serverUrl = BuildConfig.VLM_SERVER_URL.trim()
                        if (
                            serverUrl.isEmpty() ||
                            BuildConfig.SHARED_SECRET.trim().length < 32
                        ) {
                            result.error(
                                "server_url_required",
                                "当前版本未连接分析服务，请联系提供者更新应用",
                                null,
                            )
                        } else if (!Settings.canDrawOverlays(this)) {
                            result.error("overlay_required", "请先授予悬浮窗权限", null)
                        } else if (!hasLocationPermission()) {
                            result.error("location_required", "请先授予定位权限", null)
                        } else {
                            resolveCurrentLocation { location ->
                                if (location == null) {
                                    result.error(
                                        "location_unavailable",
                                        "无法取得当前位置，请开启系统定位后重试",
                                        null,
                                    )
                                } else {
                                    pendingVlmServerUrl = serverUrl
                                    pendingDriverLocation = location
                                    startProjectionPreparation()
                                    val manager = getSystemService(
                                        Context.MEDIA_PROJECTION_SERVICE,
                                    ) as MediaProjectionManager
                                    @Suppress("DEPRECATION")
                                    startActivityForResult(
                                        manager.createScreenCaptureIntent(),
                                        CAPTURE_REQUEST,
                                    )
                                    result.success(null)
                                }
                            }
                        }
                    }
                    "stopCapture" -> {
                        startService(
                            Intent(this, ScreenCaptureService::class.java)
                                .setAction(ScreenCaptureService.ACTION_STOP),
                        )
                        result.success(null)
                    }
                    "calibrateScanRegion" -> {
                        when {
                            ScreenCaptureService.isRunning -> result.error(
                                "capture_running",
                                "请先停止订单分析，再重新选择订单区域",
                                null,
                            )
                            !Settings.canDrawOverlays(this) -> result.error(
                                "overlay_required",
                                "请先授予悬浮窗权限",
                                null,
                            )
                            calibrationController != null || calibrationOverlay != null -> result.error(
                                "calibration_active",
                                "订单区域选择已经打开",
                                null,
                            )
                            else -> {
                                showScanRegionCalibrationController()
                                result.success(null)
                            }
                        }
                    }
                    else -> result.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    AnalyzerEventBus.attach(events)
                }

                override fun onCancel(arguments: Any?) {
                    AnalyzerEventBus.detach()
                }
            })
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startProjectionPreparation() {
        val preparationIntent = Intent(this, ScreenCaptureService::class.java)
            .setAction(ScreenCaptureService.ACTION_PREPARE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(preparationIntent)
        } else {
            startService(preparationIntent)
        }
    }

    @Suppress("MissingPermission")
    private fun resolveCurrentLocation(onResult: (Location?) -> Unit) {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = manager.getProviders(true).filter {
            it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER
        }
        val lastKnown = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time <= 120_000) {
            onResult(lastKnown)
            return
        }
        if (providers.isEmpty()) {
            onResult(null)
            return
        }

        val handler = Handler(Looper.getMainLooper())
        var completed = false
        lateinit var listener: LocationListener
        fun finish(location: Location?) {
            if (completed) return
            completed = true
            handler.removeCallbacksAndMessages(listener)
            manager.removeUpdates(listener)
            onResult(location)
        }
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = finish(location)
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
            @Deprecated("Legacy callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        providers.forEach { provider ->
            manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        }
        handler.postAtTime(
            { finish(lastKnown?.takeIf { System.currentTimeMillis() - it.time <= 600_000 }) },
            listener,
            android.os.SystemClock.uptimeMillis() + 8_000,
        )
    }

    private fun showScanRegionCalibrationController() {
        lateinit var controller: ScanRegionCalibrationController
        controller = ScanRegionCalibrationController(
            context = this,
            onStart = {
                if (calibrationController === controller) calibrationController = null
                showScanRegionCalibration()
            },
            onCancel = {
                if (calibrationController === controller) calibrationController = null
                Toast.makeText(this, "已取消选择", Toast.LENGTH_SHORT).show()
            },
        )
        calibrationController = controller
        controller.show()
        Toast.makeText(
            this,
            "请打开司机端订单页，再点悬浮按钮选择区域",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun showScanRegionCalibration() {
        val stored = ScanRegionPreferences.load(this)
        val initial = if (stored.configured) stored.region else ScanRegion.RECOMMENDED
        lateinit var overlay: ScanRegionCalibrationOverlay
        overlay = ScanRegionCalibrationOverlay(
            context = this,
            initialRegion = initial,
            onSave = { region ->
                ScanRegionPreferences.save(this, region)
                if (calibrationOverlay === overlay) calibrationOverlay = null
                Toast.makeText(
                    this,
                    "订单区域已保存：${(region.topRatio * 100).toInt()}%–" +
                        "${(region.bottomRatio * 100).toInt()}%",
                    Toast.LENGTH_SHORT,
                ).show()
            },
            onCancel = {
                if (calibrationOverlay === overlay) calibrationOverlay = null
                Toast.makeText(this, "已取消选择", Toast.LENGTH_SHORT).show()
            },
        )
        calibrationOverlay = overlay
        overlay.show()
    }

    override fun onDestroy() {
        calibrationController?.hide()
        calibrationController = null
        calibrationOverlay?.hide()
        calibrationOverlay = null
        super.onDestroy()
    }

    @Deprecated("Deprecated Android callback retained for FlutterActivity compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CAPTURE_REQUEST) return

        if (resultCode != Activity.RESULT_OK || data == null) {
            pendingVlmServerUrl = null
            pendingDriverLocation = null
            startService(
                Intent(this, ScreenCaptureService::class.java)
                    .setAction(ScreenCaptureService.ACTION_CANCEL_PREPARATION),
            )
            AnalyzerEventBus.emit(
                mapOf("status" to "idle", "message" to "用户取消了屏幕捕获授权"),
            )
            return
        }

        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(
                ScreenCaptureService.EXTRA_VLM_SERVER_URL,
                pendingVlmServerUrl,
            )
            pendingDriverLocation?.let { location ->
                putExtra(ScreenCaptureService.EXTRA_DRIVER_LATITUDE, location.latitude)
                putExtra(ScreenCaptureService.EXTRA_DRIVER_LONGITUDE, location.longitude)
            }
        }
        pendingVlmServerUrl = null
        pendingDriverLocation = null
        // ACTION_PREPARE has already made this an active foreground service while our Activity
        // was visible. Delivering the granted token to that service is reliable even when the
        // single-app picker has moved a cold-started target task to the foreground.
        try {
            startService(serviceIntent)
        } catch (error: IllegalStateException) {
            // Defensive fallback if an OEM killed the preparation service during task selection.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                throw error
            }
        }
    }
}
