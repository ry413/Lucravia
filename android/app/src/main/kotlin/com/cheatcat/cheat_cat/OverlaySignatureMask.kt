package com.cheatcat.cheat_cat

import kotlin.math.ceil
import kotlin.math.floor

data class ScreenPixelBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/** Removes a screen overlay from a fingerprint generated from a cropped scan region. */
object OverlaySignatureMask {
    fun apply(
        signature: ByteArray,
        bounds: ScreenPixelBounds?,
        screenWidth: Int,
        screenHeight: Int,
        scanRegion: ScanRegion,
        fingerprintTopRatio: Double,
        fingerprintBottomRatio: Double,
        signatureWidth: Int = 64,
        signatureHeight: Int = 64,
    ): ByteArray {
        if (bounds == null || screenWidth <= 0 || screenHeight <= 0) return signature
        require(signature.size == signatureWidth * signatureHeight)
        val masked = signature.copyOf()
        val scanTop = scanRegion.topRatio * screenHeight
        val scanHeight = scanRegion.heightRatio * screenHeight
        val fingerprintTop = scanTop + fingerprintTopRatio * scanHeight
        val fingerprintBottom = scanTop + fingerprintBottomRatio * scanHeight
        val fingerprintHeight = fingerprintBottom - fingerprintTop
        if (fingerprintHeight <= 0.0) return masked

        // Two signature pixels cover scaling/filtering spill and small inset offsets.
        val padding = 2
        val left = floor(bounds.left.toDouble() / screenWidth * signatureWidth).toInt() - padding
        val right = ceil(bounds.right.toDouble() / screenWidth * signatureWidth).toInt() + padding
        val top = floor(
            (bounds.top - fingerprintTop) / fingerprintHeight * signatureHeight,
        ).toInt() - padding
        val bottom = ceil(
            (bounds.bottom - fingerprintTop) / fingerprintHeight * signatureHeight,
        ).toInt() + padding

        for (y in top..bottom) {
            for (x in left..right) {
                if (x in 0 until signatureWidth && y in 0 until signatureHeight) {
                    masked[y * signatureWidth + x] = 0
                }
            }
        }
        return masked
    }
}
