package com.cheatcat.cheat_cat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OverlaySignatureMaskTest {
    @Test
    fun masksOnlyTheMovableOverlayArea() {
        val signature = ByteArray(64 * 64) { 100.toByte() }
        val masked = OverlaySignatureMask.apply(
            signature = signature,
            bounds = ScreenPixelBounds(800, 400, 1080, 520),
            screenWidth = 1080,
            screenHeight = 2400,
            scanRegion = ScanRegion.FULL_SCREEN,
            fingerprintTopRatio = 0.18,
            fingerprintBottomRatio = 0.92,
        )

        assertEquals(100, masked[40 * 64 + 10].toInt() and 0xFF)
        assertNotEquals(100, masked[1 * 64 + 58].toInt() and 0xFF)
        assertEquals(100, signature[1 * 64 + 58].toInt() and 0xFF)
    }
}
