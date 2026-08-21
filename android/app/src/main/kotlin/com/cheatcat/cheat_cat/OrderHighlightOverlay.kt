package com.cheatcat.cheat_cat

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

data class OrderHighlightSpec(
    val box: NormalizedOrderBox,
    val label: String,
    val rank: Int,
)

/** Non-touchable card outlines with a compact evaluation badge on each order. */
class OrderHighlightOverlay(
    private val context: Context,
    private val manager: WindowManager,
) {
    private val views = mutableListOf<View>()

    fun show(
        specs: List<OrderHighlightSpec>,
        screenWidth: Int,
        screenHeight: Int,
        scanRegion: ScanRegion,
    ) {
        hide()
        specs.forEach { spec ->
            addHighlight(spec, screenWidth, screenHeight, scanRegion)
        }
    }

    private fun addHighlight(
        spec: OrderHighlightSpec,
        screenWidth: Int,
        screenHeight: Int,
        scanRegion: ScanRegion,
    ) {
        val box = spec.box
        val left = (box.left / 1000.0 * screenWidth).roundToInt().coerceIn(0, screenWidth - 1)
        val top = scanRegion.screenY(box.top, screenHeight).coerceIn(0, screenHeight - 1)
        val right = (box.right / 1000.0 * screenWidth).roundToInt().coerceIn(left + 1, screenWidth)
        val bottom = scanRegion.screenY(box.bottom, screenHeight).coerceIn(top + 1, screenHeight)
        val thickness = dp(5).coerceAtMost((right - left).coerceAtMost(bottom - top) / 3)
            .coerceAtLeast(1)
        val color = when (spec.rank) {
            0 -> Color.rgb(0, 214, 112)
            1 -> Color.rgb(255, 145, 38)
            else -> Color.rgb(72, 132, 255)
        }

        addBorder(left, top, right - left, thickness, color)
        addBorder(left, bottom - thickness, right - left, thickness, color)
        addBorder(left, top, thickness, bottom - top, color)
        addBorder(right - thickness, top, thickness, bottom - top, color)
        addBadge(spec.label, right, top, color)
    }

    fun hide() {
        views.forEach { view ->
            try {
                manager.removeView(view)
            } catch (_: Exception) {
                // The system can remove overlay windows while capture is stopping.
            }
        }
        views.clear()
    }

    private fun addBorder(x: Int, y: Int, width: Int, height: Int, color: Int) {
        val view = View(context).apply { setBackgroundColor(color) }
        val params = WindowManager.LayoutParams(
            width,
            height,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            alpha = 0.78f
        }
        manager.addView(view, params)
        views += view
    }

    private fun addBadge(label: String, right: Int, top: Int, color: Int) {
        val lineCount = label.count { it == '\n' } + 1
        val width = dp(BADGE_WIDTH_DP).coerceAtMost(right.coerceAtLeast(dp(180)))
        // Long Chinese explanations may wrap even though they contain six explicit lines.
        val heightDp = (lineCount * 18 + 12).coerceIn(32, BADGE_HEIGHT_DP)
        val height = dp(heightDp)
        val view = TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = if (lineCount >= 4) 10.5f else 13f
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            setLineSpacing(dp(1).toFloat(), 1f)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.argb(238, Color.red(color), Color.green(color), Color.blue(color)))
                cornerRadius = dp(7).toFloat()
            }
        }
        val params = WindowManager.LayoutParams(
            width,
            height,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (right - width).coerceAtLeast(0)
            y = top
        }
        manager.addView(view, params)
        views += view
    }

    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    companion object {
        const val BADGE_WIDTH_DP = 360
        const val BADGE_HEIGHT_DP = 124
    }
}
