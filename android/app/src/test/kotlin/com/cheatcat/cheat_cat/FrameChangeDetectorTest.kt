package com.cheatcat.cheat_cat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameChangeDetectorTest {
    @Test
    fun skipsUnchangedAndMinorNoiseAfterAcceptance() {
        val detector = FrameChangeDetector(
            minimumChangedSamples = 3,
            luminanceDelta = 18,
        )
        val baseline = byteArrayOf(100, 100, 100, 100, 100)

        assertTrue(detector.hasSignificantChange(baseline))
        detector.accept(baseline)
        assertFalse(detector.hasSignificantChange(baseline.copyOf()))
        assertFalse(detector.hasSignificantChange(byteArrayOf(105, 95, 100, 100, 100)))
    }

    @Test
    fun resumesWhenEnoughOfOrderAreaChanges() {
        val detector = FrameChangeDetector(
            minimumChangedSamples = 3,
            luminanceDelta = 18,
        )
        detector.accept(byteArrayOf(100, 100, 100, 100, 100))

        assertTrue(
            detector.hasSignificantChange(byteArrayOf(127, 70, 125, 100, 100)),
        )
    }

    @Test
    fun returningToOldScreenIsNotSkippedAfterChangeInvalidatesBaseline() {
        val detector = FrameChangeDetector(
            minimumChangedSamples = 2,
            luminanceDelta = 18,
        )
        val original = byteArrayOf(100, 100, 100, 100)
        val scrolled = byteArrayOf(125, 75, 100, 100)
        detector.accept(original)

        assertTrue(detector.hasSignificantChange(scrolled))
        detector.reset()
        assertTrue(detector.hasSignificantChange(original))
    }
}
