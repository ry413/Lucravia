package com.cheatcat.cheat_cat

import kotlin.math.ceil
import kotlin.math.floor

/** Watches the page outside the rendered highlight border for a real screen change. */
class HighlightFrameGuard(
    baseline: ByteArray,
    private val boxes: List<NormalizedOrderBox>,
    private val signatureWidth: Int = 64,
    private val signatureHeight: Int = 64,
    private val cropTopRatio: Double = 0.15,
    private val cropBottomRatio: Double = 0.92,
    private val badgeWidthRatio: Double = 0.0,
    private val badgeHeightRatio: Double = 0.0,
) {
    private val detector = FrameChangeDetector()

    init {
        require(baseline.size == signatureWidth * signatureHeight)
        detector.accept(maskHighlightBorder(baseline))
    }

    fun hasPageChanged(signature: ByteArray): Boolean =
        detector.hasSignificantChange(maskHighlightBorder(signature))

    internal fun maskHighlightBorder(signature: ByteArray): ByteArray {
        require(signature.size == signatureWidth * signatureHeight)
        val masked = signature.copyOf()
        boxes.forEach { box -> maskBox(masked, box) }
        return masked
    }

    private fun maskBox(masked: ByteArray, box: NormalizedOrderBox) {
        val left = floor(box.left / 1000.0 * signatureWidth).toInt()
        val right = ceil(box.right / 1000.0 * signatureWidth).toInt() - 1
        val cropSpan = cropBottomRatio - cropTopRatio
        val top = floor(
            (box.top / 1000.0 - cropTopRatio) / cropSpan * signatureHeight,
        ).toInt()
        val bottom = ceil(
            (box.bottom / 1000.0 - cropTopRatio) / cropSpan * signatureHeight,
        ).toInt() - 1
        // Include scaling/filtering spill and small display-inset coordinate offsets.
        val thickness = 3

        for (y in (top - thickness)..(bottom + thickness)) {
            for (x in (left - thickness)..(right + thickness)) {
                val onVerticalEdge = x in (left - thickness)..(left + thickness) ||
                    x in (right - thickness)..(right + thickness)
                val onHorizontalEdge = y in (top - thickness)..(top + thickness) ||
                    y in (bottom - thickness)..(bottom + thickness)
                if (onVerticalEdge || onHorizontalEdge) {
                    if (x in 0 until signatureWidth && y in 0 until signatureHeight) {
                        masked[y * signatureWidth + x] = 0
                    }
                }
            }
        }

        if (badgeWidthRatio > 0.0 && badgeHeightRatio > 0.0) {
            val badgeLeft = floor(
                (box.right / 1000.0 - badgeWidthRatio) * signatureWidth,
            ).toInt() - 1
            val badgeRight = right + 1
            val badgeBottom = ceil(
                (box.top / 1000.0 + badgeHeightRatio - cropTopRatio) /
                    cropSpan * signatureHeight,
            ).toInt() + 1
            for (y in (top - 1)..badgeBottom) {
                for (x in badgeLeft..badgeRight) {
                    if (x in 0 until signatureWidth && y in 0 until signatureHeight) {
                        masked[y * signatureWidth + x] = 0
                    }
                }
            }
        }
    }
}
