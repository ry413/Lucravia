package com.cheatcat.cheat_cat

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Small touchable launcher that leaves the rest of the target app fully interactive. */
class ScanRegionCalibrationController(
    private val context: Context,
    private val onStart: () -> Unit,
    private val onCancel: () -> Unit,
) {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: LinearLayout? = null

    fun show() {
        if (view != null) return
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(5), dp(5), dp(5))
            elevation = dp(8).toFloat()
            background = GradientDrawable().apply {
                setColor(Color.argb(242, 23, 33, 58))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.argb(100, 255, 255, 255))
            }
        }
        val start = TextView(context).apply {
            text = "开始校准"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.rgb(255, 122, 25))
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener {
                hide()
                onStart()
            }
        }
        val cancel = TextView(context).apply {
            text = "×"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setOnClickListener {
                hide()
                onCancel()
            }
        }
        panel.addView(start, LinearLayout.LayoutParams(dp(116), dp(42)))
        panel.addView(cancel, LinearLayout.LayoutParams(dp(42), dp(42)))

        val params = WindowManager.LayoutParams(
            dp(168),
            dp(52),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = dp(10)
        }
        manager.addView(panel, params)
        view = panel
    }

    fun hide() {
        val active = view ?: return
        view = null
        try {
            manager.removeView(active)
        } catch (_: Exception) {
            // Overlay may already have been removed by the system.
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
