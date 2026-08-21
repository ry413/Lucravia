package com.cheatcat.cheat_cat

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanRegionTest {
    @Test
    fun mapsModelCoordinatesBackIntoSelectedScreenBand() {
        val region = ScanRegion(0.2, 0.8)

        assertEquals(200, region.topPixel(1000))
        assertEquals(800, region.bottomPixel(1000))
        assertEquals(200, region.screenY(0.0, 1000))
        assertEquals(500, region.screenY(500.0, 1000))
        assertEquals(800, region.screenY(1000.0, 1000))
    }

    @Test
    fun usesWholeCustomBandWhenItAlreadyExcludesDynamicChrome() {
        val ratios = ScanRegion(0.2, 0.8).fingerprintRatios()

        assertEquals(0.0, ratios.first, 0.0001)
        assertEquals(1.0, ratios.second, 0.0001)
    }

    @Test
    fun translatesGlobalIgnoredAreasIntoLocalCropRatios() {
        val ratios = ScanRegion.FULL_SCREEN.fingerprintRatios()

        assertEquals(0.18, ratios.first, 0.0001)
        assertEquals(0.92, ratios.second, 0.0001)
    }
}
