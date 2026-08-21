package com.cheatcat.cheat_cat

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Compares a low-resolution luminance signature of the cropped order area.
 * Produces and compares low-resolution luminance signatures. The VLM runtime
 * uses [signature], while the stateful comparator remains useful in tests.
 */
class FrameChangeDetector(
    private val minimumChangedSamples: Int = 10,
    private val luminanceDelta: Int = 18,
) {
    private var acceptedSignature: ByteArray? = null

    fun hasSignificantChange(signature: ByteArray): Boolean {
        val baseline = acceptedSignature ?: return true
        if (baseline.size != signature.size) return true
        var changes = 0
        for (index in signature.indices) {
            val difference = abs(
                (signature[index].toInt() and 0xFF) -
                    (baseline[index].toInt() and 0xFF),
            )
            if (difference >= luminanceDelta) {
                changes += 1
                if (changes >= minimumChangedSamples) return true
            }
        }
        return false
    }

    fun accept(signature: ByteArray) {
        acceptedSignature = signature.copyOf()
    }

    fun reset() {
        acceptedSignature = null
    }

    companion object {
        private const val SIGNATURE_WIDTH = 64
        private const val SIGNATURE_HEIGHT = 64

        fun signature(
            bitmap: Bitmap,
            topRatio: Double = 0.0,
            bottomRatio: Double = 1.0,
        ): ByteArray {
            require(topRatio >= 0.0 && topRatio < bottomRatio && bottomRatio <= 1.0)
            val cropTop = (bitmap.height * topRatio).toInt().coerceIn(0, bitmap.height - 2)
            val cropBottom = (bitmap.height * bottomRatio)
                .toInt()
                .coerceIn(cropTop + 1, bitmap.height)
            val content = Bitmap.createBitmap(
                bitmap,
                0,
                cropTop,
                bitmap.width,
                cropBottom - cropTop,
            )
            val sample = Bitmap.createScaledBitmap(
                content,
                SIGNATURE_WIDTH,
                SIGNATURE_HEIGHT,
                false,
            )
            val pixels = IntArray(SIGNATURE_WIDTH * SIGNATURE_HEIGHT)
            sample.getPixels(
                pixels,
                0,
                SIGNATURE_WIDTH,
                0,
                0,
                SIGNATURE_WIDTH,
                SIGNATURE_HEIGHT,
            )
            if (sample !== content) sample.recycle()
            if (content !== bitmap) content.recycle()
            return ByteArray(pixels.size) { index ->
                val color = pixels[index]
                val red = color shr 16 and 0xFF
                val green = color shr 8 and 0xFF
                val blue = color and 0xFF
                ((red * 3 + green * 6 + blue) / 10).toByte()
            }
        }
    }
}
