package com.cheatcat.cheat_cat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightFrameGuardTest {
    private val box = NormalizedOrderBox(100.0, 300.0, 900.0, 700.0)

    @Test
    fun ignoresPixelsAlongTheRenderedHighlightBorder() {
        val baseline = ByteArray(64 * 64) { 100.toByte() }
        val guard = HighlightFrameGuard(baseline, listOf(box))
        val withBorder = baseline.copyOf()
        val maskedBaseline = guard.maskHighlightBorder(baseline)
        for (index in withBorder.indices) {
            if (maskedBaseline[index].toInt() == 0) withBorder[index] = 255.toByte()
        }

        assertFalse(guard.hasPageChanged(withBorder))
    }

    @Test
    fun detectsChangesInsideAndOutsideTheCard() {
        val baseline = ByteArray(64 * 64) { 100.toByte() }
        val guard = HighlightFrameGuard(baseline, listOf(box))
        val changed = baseline.copyOf()
        for (y in 30..34) {
            for (x in 25..34) changed[y * 64 + x] = 200.toByte()
        }

        assertTrue(guard.hasPageChanged(changed))
    }

    @Test
    fun ignoresBordersForMultipleOrderCards() {
        val baseline = ByteArray(64 * 64) { 100.toByte() }
        val boxes = listOf(
            NormalizedOrderBox(80.0, 210.0, 920.0, 470.0),
            NormalizedOrderBox(80.0, 530.0, 920.0, 820.0),
        )
        val guard = HighlightFrameGuard(baseline, boxes)
        val withBorders = baseline.copyOf()
        val maskedBaseline = guard.maskHighlightBorder(baseline)
        for (index in withBorders.indices) {
            if (maskedBaseline[index].toInt() == 0) withBorders[index] = 255.toByte()
        }

        assertFalse(guard.hasPageChanged(withBorders))
    }

    @Test
    fun ignoresExpandedEvaluationBadge() {
        val baseline = ByteArray(64 * 64) { 100.toByte() }
        val guard = HighlightFrameGuard(
            baseline,
            listOf(box),
            badgeWidthRatio = 0.8,
            badgeHeightRatio = 0.1,
        )
        val withBadge = baseline.copyOf()
        val maskedBaseline = guard.maskHighlightBorder(baseline)
        for (index in withBadge.indices) {
            if (maskedBaseline[index].toInt() == 0) withBadge[index] = 255.toByte()
        }

        assertFalse(guard.hasPageChanged(withBadge))
    }
}
