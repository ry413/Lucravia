package com.cheatcat.cheat_cat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UpdateManifestTest {
    @Test
    fun parsesValidatedServerManifest() {
        val manifest = UpdateManifest.parse(
            """
            {
              "version_code": 3,
              "version_name": "1.2.0",
              "apk_sha256": "${"a".repeat(64)}",
              "apk_size_bytes": 123456,
              "release_notes": "修复地图匹配",
              "required": false
            }
            """.trimIndent(),
        )

        assertEquals(3, manifest.versionCode)
        assertEquals("1.2.0", manifest.versionName)
        assertEquals(123456, manifest.apkSizeBytes)
        assertEquals("修复地图匹配", manifest.releaseNotes)
        assertFalse(manifest.required)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidDigest() {
        UpdateManifest.parse(
            """{"version_code":3,"version_name":"1.2.0","apk_sha256":"bad","apk_size_bytes":12}""",
        )
    }
}
