package com.cheatcat.cheat_cat

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

/** Compact draggable runtime status. Only its own small window consumes touches. */
class AnalyzerStatusOverlay(
    private val context: Context,
    private val manager: WindowManager,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    @Volatile
    var bounds: ScreenPixelBounds? = null
        private set

    fun show(initialStatus: String) {
        if (view != null) return
        val statusView = TextView(context).apply {
            text = "跑单助手  ·  $initialStatus"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            setPadding(dp(12), dp(7), dp(12), dp(7))
            elevation = dp(7).toFloat()
            background = GradientDrawable().apply {
                setColor(Color.argb(232, 23, 33, 58))
                cornerRadius = dp(13).toFloat()
                setStroke(dp(1), Color.argb(105, 255, 255, 255))
            }
        }
        val layoutParams = WindowManager.LayoutParams(
            dp(190),
            dp(52),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (context.resources.displayMetrics.widthPixels - dp(200)).coerceAtLeast(0)
            y = dp(92)
        }
        statusView.setOnTouchListener(DragTouchListener(statusView, layoutParams))
        manager.addView(statusView, layoutParams)
        view = statusView
        params = layoutParams
        statusView.post { updateBounds(statusView) }
    }

    fun update(status: String) {
        mainHandler.post {
            view?.text = "跑单助手  ·  $status"
        }
    }

    fun hide() {
        val active = view ?: return
        view = null
        params = null
        bounds = null
        try {
            manager.removeView(active)
        } catch (_: Exception) {
            // The system may already have removed the overlay.
        }
    }

    private fun updateBounds(active: View) {
        val location = IntArray(2)
        active.getLocationOnScreen(location)
        bounds = ScreenPixelBounds(
            left = location[0],
            top = location[1],
            right = location[0] + active.width,
            bottom = location[1] + active.height,
        )
    }

    private inner class DragTouchListener(
        private val active: View,
        private val layoutParams: WindowManager.LayoutParams,
    ) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = layoutParams.x
                    startY = layoutParams.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxX = (context.resources.displayMetrics.widthPixels - active.width)
                        .coerceAtLeast(0)
                    val maxY = (context.resources.displayMetrics.heightPixels - active.height)
                        .coerceAtLeast(0)
                    layoutParams.x = (startX + (event.rawX - downRawX).roundToInt())
                        .coerceIn(0, maxX)
                    layoutParams.y = (startY + (event.rawY - downRawY).roundToInt())
                        .coerceIn(0, maxY)
                    manager.updateViewLayout(active, layoutParams)
                    active.post { updateBounds(active) }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    active.post { updateBounds(active) }
                    return true
                }
            }
            return false
        }
    }

    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}
