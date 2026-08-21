package com.cheatcat.cheat_cat

import android.content.Context

data class StoredScanRegion(
    val region: ScanRegion,
    val configured: Boolean,
)

object ScanRegionPreferences {
    private const val FILE_NAME = "scan_region"
    private const val KEY_CONFIGURED = "configured"
    private const val KEY_TOP = "top_ratio"
    private const val KEY_BOTTOM = "bottom_ratio"

    fun load(context: Context): StoredScanRegion {
        val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(KEY_CONFIGURED, false)) {
            return StoredScanRegion(ScanRegion.FULL_SCREEN, configured = false)
        }
        val top = preferences.getFloat(KEY_TOP, 0f).toDouble()
        val bottom = preferences.getFloat(KEY_BOTTOM, 1f).toDouble()
        val region = runCatching { ScanRegion(top, bottom) }.getOrNull()
            ?: return StoredScanRegion(ScanRegion.FULL_SCREEN, configured = false)
        return StoredScanRegion(region, configured = true)
    }

    fun save(context: Context, region: ScanRegion) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CONFIGURED, true)
            .putFloat(KEY_TOP, region.topRatio.toFloat())
            .putFloat(KEY_BOTTOM, region.bottomRatio.toFloat())
            .apply()
    }
}
