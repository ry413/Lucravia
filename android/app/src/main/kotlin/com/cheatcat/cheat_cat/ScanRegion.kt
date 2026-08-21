package com.cheatcat.cheat_cat

import kotlin.math.roundToInt

/** Full-width vertical region, normalized against the physical captured screen. */
data class ScanRegion(
    val topRatio: Double,
    val bottomRatio: Double,
) {
    init {
        require(topRatio >= 0.0 && topRatio < bottomRatio)
        require(bottomRatio <= 1.0)
        require(bottomRatio - topRatio >= MIN_HEIGHT_RATIO)
    }

    val heightRatio: Double get() = bottomRatio - topRatio

    fun topPixel(screenHeight: Int): Int =
        (screenHeight * topRatio).roundToInt().coerceIn(0, screenHeight - 1)

    fun bottomPixel(screenHeight: Int): Int =
        (screenHeight * bottomRatio).roundToInt().coerceIn(topPixel(screenHeight) + 1, screenHeight)

    fun heightPixels(screenHeight: Int): Int =
        bottomPixel(screenHeight) - topPixel(screenHeight)

    fun screenY(normalizedY: Double, screenHeight: Int): Int =
        ((topRatio + normalizedY / 1000.0 * heightRatio) * screenHeight)
            .roundToInt()
            .coerceIn(0, screenHeight)

    /**
     * Keeps the driver's dynamic header and bottom navigation out of local change detection.
     * If the user-selected band does not overlap those areas, the whole band is compared.
     */
    fun fingerprintRatios(
        ignoredScreenTop: Double = 0.18,
        ignoredScreenBottom: Double = 0.92,
    ): Pair<Double, Double> {
        val localTop = if (topRatio < ignoredScreenTop) {
            (ignoredScreenTop - topRatio) / heightRatio
        } else {
            0.0
        }
        val localBottom = if (bottomRatio > ignoredScreenBottom) {
            (ignoredScreenBottom - topRatio) / heightRatio
        } else {
            1.0
        }
        val clampedTop = localTop.coerceIn(0.0, 0.98)
        val clampedBottom = localBottom.coerceIn(0.02, 1.0)
        return if (clampedBottom - clampedTop >= 0.1) {
            clampedTop to clampedBottom
        } else {
            0.0 to 1.0
        }
    }

    companion object {
        const val MIN_HEIGHT_RATIO = 0.25
        val FULL_SCREEN = ScanRegion(0.0, 1.0)
        val RECOMMENDED = ScanRegion(0.18, 0.92)
    }
}
