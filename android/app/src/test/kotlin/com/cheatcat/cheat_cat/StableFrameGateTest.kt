package com.cheatcat.cheat_cat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StableFrameGateTest {
    @Test
    fun submitsOnlyAfterTwoStableFrames() {
        val gate = gate()
        val frame = byteArrayOf(100, 100, 100, 100)

        assertFalse(gate.shouldSubmit(frame, 0))
        assertTrue(gate.shouldSubmit(frame, 500))
    }

    @Test
    fun doesNotResubmitTheSameStableScreen() {
        val gate = gate()
        val frame = byteArrayOf(100, 100, 100, 100)

        gate.shouldSubmit(frame, 0)
        gate.shouldSubmit(frame, 500)

        assertFalse(gate.shouldSubmit(frame, 1_000))
        assertFalse(gate.shouldSubmit(frame, 1_500))
    }

    @Test
    fun waitsForScrollingToSettleBeforeSubmittingNewScreen() {
        val gate = gate()
        val first = byteArrayOf(100, 100, 100, 100)
        val moving = byteArrayOf(130.toByte(), 70, 100, 100)
        val settled = byteArrayOf(150.toByte(), 50, 100, 100)

        gate.shouldSubmit(first, 0)
        assertTrue(gate.shouldSubmit(first, 500))
        assertFalse(gate.shouldSubmit(moving, 1_000))
        assertFalse(gate.shouldSubmit(settled, 1_500))
        assertTrue(gate.shouldSubmit(settled, 2_000))
    }

    @Test
    fun retriesFailedStableScreenAfterBackoff() {
        val gate = gate(retryDelayMs = 3_000)
        val frame = byteArrayOf(100, 100, 100, 100)

        gate.shouldSubmit(frame, 0)
        assertTrue(gate.shouldSubmit(frame, 500))
        gate.markFailed(frame, 600)

        assertFalse(gate.shouldSubmit(frame, 3_000))
        assertTrue(gate.shouldSubmit(frame, 3_600))
    }

    private fun gate(retryDelayMs: Long = 3_000) = StableFrameGate(
        requiredStableFrames = 2,
        minimumChangedSamples = 2,
        luminanceDelta = 18,
        retryDelayMs = retryDelayMs,
    )
}
