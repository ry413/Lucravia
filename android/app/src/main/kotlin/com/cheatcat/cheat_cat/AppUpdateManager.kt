package com.cheatcat.cheat_cat

import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

class AppUpdateManager(
    private val context: Context,
    serverUrl: String,
    private val sharedSecret: String,
) {
    private val baseUrl = serverUrl.trimEnd('/')

    fun check(): UpdateManifest? {
        val response = get("/v1/update/latest")
        if (response.status == HttpURLConnection.HTTP_NOT_FOUND) return null
        require(response.status in 200..299) {
            "更新服务 HTTP ${response.status}${response.bodyText()?.let { "：$it" } ?: ""}"
        }
        val manifest = UpdateManifest.parse(response.bytes.toString(Charsets.UTF_8))
        return manifest.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
    }

    fun canRequestInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }

    fun download(manifest: UpdateManifest): File {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val temporary = File(directory, "lucravia-${manifest.versionCode}.apk.part")
        val destination = File(directory, "lucravia-${manifest.versionCode}.apk")
        val connection = openConnection("/v1/update/apk")
        try {
            val status = connection.responseCode
            require(status in 200..299) { "更新下载 HTTP $status" }
            val contentLength = connection.contentLengthLong
            require(contentLength < 0 || contentLength == manifest.apkSizeBytes) {
                "更新文件大小与发布清单不一致"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            temporary.outputStream().buffered().use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        require(written <= manifest.apkSizeBytes) { "更新文件超过预期大小" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(written == manifest.apkSizeBytes) { "更新文件下载不完整" }
            val actualSha256 = digest.digest()
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(actualSha256 == manifest.apkSha256) { "更新文件校验失败，请重新下载" }
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
        require(destination.isFile) { "无法保存更新文件" }
        return destination
    }

    fun launchInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = ClipData.newRawUri("Lucravia update", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }

    private fun get(path: String): HttpResponse {
        val connection = openConnection(path)
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            HttpResponse(status, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(path: String): HttpURLConnection {
        val requestId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis() / 1000
        return (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 120_000
            setRequestProperty("Accept", "application/json, application/vnd.android.package-archive")
            setRequestProperty("X-Request-Id", requestId)
            setRequestProperty("X-Request-Timestamp", timestamp.toString())
            setRequestProperty(
                "X-Request-Signature",
                RequestSigner.sign(
                    secret = sharedSecret,
                    method = "GET",
                    path = path,
                    timestampSeconds = timestamp,
                    requestId = requestId,
                    body = ByteArray(0),
                ),
            )
        }
    }

    private data class HttpResponse(val status: Int, val bytes: ByteArray) {
        fun bodyText(): String? = bytes
            .takeIf { it.isNotEmpty() }
            ?.toString(Charsets.UTF_8)
            ?.take(200)
    }
}
