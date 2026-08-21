package com.cheatcat.cheat_cat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

class ScanRegionCalibrationOverlay(
    private val context: Context,
    private val initialRegion: ScanRegion,
    private val onSave: (ScanRegion) -> Unit,
    private val onCancel: () -> Unit,
) {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: CalibrationView? = null

    fun show() {
        if (view != null) return
        val calibrationView = CalibrationView(
            context = context,
            initialRegion = initialRegion,
            onSave = { region ->
                hide()
                onSave(region)
            },
            onCancel = {
                hide()
                onCancel()
            },
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        manager.addView(calibrationView, params)
        view = calibrationView
    }

    fun hide() {
        val active = view ?: return
        view = null
        try {
            manager.removeView(active)
        } catch (_: Exception) {
            // The system can remove overlays when the activity is destroyed.
        }
    }
}

private class CalibrationView(
    context: Context,
    initialRegion: ScanRegion,
    private val onSave: (ScanRegion) -> Unit,
    private val onCancel: () -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(175, 8, 13, 25)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 230, 118)
        strokeWidth = dp(4f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 230, 118)
    }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 23, 33, 58)
    }
    private val savePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 122, 25)
    }
    private val cancelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 73, 81, 102)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(17f)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 226, 239)
        textSize = dp(12f)
    }
    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(15f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private var topRatio = initialRegion.topRatio
    private var bottomRatio = initialRegion.bottomRatio
    private var dragging: DragTarget? = null
    private var pressedButton: ButtonTarget? = null
    private val saveRect = RectF()
    private val cancelRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val topY = (height * topRatio).toFloat()
        val bottomY = (height * bottomRatio).toFloat()
        canvas.drawRect(0f, 0f, width.toFloat(), topY, shadePaint)
        canvas.drawRect(0f, bottomY, width.toFloat(), height.toFloat(), shadePaint)
        canvas.drawLine(0f, topY, width.toFloat(), topY, linePaint)
        canvas.drawLine(0f, bottomY, width.toFloat(), bottomY, linePaint)
        drawHandle(canvas, topY)
        drawHandle(canvas, bottomY)

        val panel = RectF(dp(16f), dp(22f), width - dp(16f), dp(102f))
        canvas.drawRoundRect(panel, dp(14f), dp(14f), panelPaint)
        canvas.drawText("选择订单区域", dp(30f), dp(53f), titlePaint)
        canvas.drawText("拖动上下绿线；只分析亮区内的订单", dp(30f), dp(79f), bodyPaint)

        val buttonTop = height - dp(94f)
        val buttonBottom = height - dp(34f)
        cancelRect.set(dp(20f), buttonTop, width / 2f - dp(8f), buttonBottom)
        saveRect.set(width / 2f + dp(8f), buttonTop, width - dp(20f), buttonBottom)
        canvas.drawRoundRect(cancelRect, dp(14f), dp(14f), cancelPaint)
        canvas.drawRoundRect(saveRect, dp(14f), dp(14f), savePaint)
        val textY = buttonTop + dp(38f)
        canvas.drawText("取消", cancelRect.centerX(), textY, buttonTextPaint)
        canvas.drawText("保存", saveRect.centerX(), textY, buttonTextPaint)
    }

    private fun drawHandle(canvas: Canvas, centerY: Float) {
        val rect = RectF(
            width / 2f - dp(42f),
            centerY - dp(8f),
            width / 2f + dp(42f),
            centerY + dp(8f),
        )
        canvas.drawRoundRect(rect, dp(8f), dp(8f), handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedButton = when {
                    saveRect.contains(event.x, event.y) -> ButtonTarget.SAVE
                    cancelRect.contains(event.x, event.y) -> ButtonTarget.CANCEL
                    else -> null
                }
                if (pressedButton == null) {
                    val topY = height * topRatio
                    val bottomY = height * bottomRatio
                    dragging = if (abs(event.y - topY) <= abs(event.y - bottomY)) {
                        DragTarget.TOP
                    } else {
                        DragTarget.BOTTOM
                    }
                    updateDrag(event.y)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging != null) updateDrag(event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val button = pressedButton
                dragging = null
                pressedButton = null
                when (button) {
                    ButtonTarget.SAVE -> if (saveRect.contains(event.x, event.y)) {
                        onSave(ScanRegion(topRatio, bottomRatio))
                    }
                    ButtonTarget.CANCEL -> if (cancelRect.contains(event.x, event.y)) onCancel()
                    null -> performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = null
                pressedButton = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateDrag(y: Float) {
        if (height <= 0) return
        val ratio = (y / height).toDouble().coerceIn(0.03, 0.97)
        when (dragging) {
            DragTarget.TOP -> topRatio = ratio.coerceAtMost(
                bottomRatio - ScanRegion.MIN_HEIGHT_RATIO,
            )
            DragTarget.BOTTOM -> bottomRatio = ratio.coerceAtLeast(
                topRatio + ScanRegion.MIN_HEIGHT_RATIO,
            )
            null -> return
        }
        invalidate()
    }

    private fun dp(value: Float): Float = value * density

    private enum class DragTarget { TOP, BOTTOM }
    private enum class ButtonTarget { SAVE, CANCEL }
}
