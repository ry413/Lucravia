package com.cheatcat.cheat_cat

import org.json.JSONObject

data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkSha256: String,
    val apkSizeBytes: Long,
    val releaseNotes: String,
    val required: Boolean,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "versionCode" to versionCode,
        "versionName" to versionName,
        "apkSizeBytes" to apkSizeBytes,
        "releaseNotes" to releaseNotes,
        "required" to required,
    )

    companion object {
        private const val MAX_APK_BYTES = 250L * 1024 * 1024

        fun parse(json: String): UpdateManifest {
            val value = JSONObject(json)
            val versionCode = value.optInt("version_code", -1)
            val versionName = value.optString("version_name").trim()
            val sha256 = value.optString("apk_sha256").trim().lowercase()
            val size = value.optLong("apk_size_bytes", -1)
            require(versionCode > 0) { "更新版本号无效" }
            require(versionName.isNotEmpty()) { "更新版本名称无效" }
            require(sha256.matches(Regex("[0-9a-f]{64}"))) { "更新文件摘要无效" }
            require(size in 1..MAX_APK_BYTES) { "更新文件大小无效" }
            return UpdateManifest(
                versionCode = versionCode,
                versionName = versionName,
                apkSha256 = sha256,
                apkSizeBytes = size,
                releaseNotes = value.optString("release_notes").trim(),
                required = value.optBoolean("required", false),
            )
        }
    }
}
