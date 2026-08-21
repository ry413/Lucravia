package com.cheatcat.cheat_cat

import android.graphics.Bitmap
import android.os.SystemClock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class VlmServerClient(serverUrl: String, private val sharedSecret: String) {
    companion object {
        private const val JPEG_QUALITY = 72
    }

    private val baseUrl = serverUrl.trimEnd('/')
    private val endpoint = "$baseUrl/v1/analyze"
    private val cancellationExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    sealed interface Result {
        data class Success(val analysis: VlmScreenAnalysis) : Result
        data class Failure(val message: String) : Result
        object Cancelled : Result
    }

    fun newCall(
        bitmap: Bitmap,
        scanRegion: ScanRegion,
        driverLatitude: Double,
        driverLongitude: Double,
    ): Call = Call(bitmap, scanRegion, driverLatitude, driverLongitude)

    fun close() {
        cancellationExecutor.shutdownNow()
    }

    inner class Call internal constructor(
        private val bitmap: Bitmap,
        private val scanRegion: ScanRegion,
        private val driverLatitude: Double,
        private val driverLongitude: Double,
    ) {
        val requestId: String = UUID.randomUUID().toString()

        @Volatile
        var isCancelled: Boolean = false
            private set

        @Volatile
        private var connection: HttpURLConnection? = null

        fun cancel() {
            if (isCancelled) return
            isCancelled = true
            connection?.disconnect()
            runCatching {
                cancellationExecutor.execute { notifyServerCancelled(requestId) }
            }
        }

        fun execute(): Result {
            if (isCancelled) return Result.Cancelled
            val encodeStarted = SystemClock.elapsedRealtime()
            val jpeg = ByteArrayOutputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    return Result.Failure("无法压缩屏幕图像")
                }
                output.toByteArray()
            }
            if (isCancelled) return Result.Cancelled
            val encodeMs = SystemClock.elapsedRealtime() - encodeStarted
            val activeConnection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 70_000
                doOutput = true
                setFixedLengthStreamingMode(jpeg.size)
                setRequestProperty("Content-Type", "image/jpeg")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Cheat-Cat-Client", "android")
                setRequestProperty("X-Request-Id", requestId)
                setSignedHeaders("/v1/analyze", requestId, jpeg)
                setRequestProperty("X-Capture-Width", bitmap.width.toString())
                setRequestProperty("X-Capture-Height", bitmap.height.toString())
                setRequestProperty("X-Jpeg-Quality", JPEG_QUALITY.toString())
                setRequestProperty("X-Jpeg-Encoding-Ms", encodeMs.toString())
                setRequestProperty("X-Scan-Top", scanRegion.topRatio.toString())
                setRequestProperty("X-Scan-Bottom", scanRegion.bottomRatio.toString())
                setRequestProperty("X-Driver-Latitude", driverLatitude.toString())
                setRequestProperty("X-Driver-Longitude", driverLongitude.toString())
            }
            connection = activeConnection
            if (isCancelled) {
                activeConnection.disconnect()
                connection = null
                return Result.Cancelled
            }

            return try {
                activeConnection.outputStream.use { it.write(jpeg) }
                val status = activeConnection.responseCode
                val response = (if (status in 200..299) {
                    activeConnection.inputStream
                } else {
                    activeConnection.errorStream
                })?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (isCancelled) {
                    Result.Cancelled
                } else if (status !in 200..299) {
                    Result.Failure(serverErrorMessage(status, response))
                } else {
                    Result.Success(VlmOrderResponseParser.parse(response))
                }
            } catch (error: Exception) {
                if (isCancelled) {
                    Result.Cancelled
                } else {
                    Result.Failure(error.localizedMessage ?: "无法连接分析服务")
                }
            } finally {
                connection = null
                activeConnection.disconnect()
            }
        }
    }

    private fun notifyServerCancelled(requestId: String) {
        val cancellation = (URL("$baseUrl/v1/cancel").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 1_500
            readTimeout = 1_500
            doOutput = true
            setFixedLengthStreamingMode(0)
            setRequestProperty("X-Request-Id", requestId)
            setSignedHeaders("/v1/cancel", requestId, ByteArray(0))
        }
        try {
            cancellation.responseCode
        } finally {
            cancellation.disconnect()
        }
    }

    private fun HttpURLConnection.setSignedHeaders(
        path: String,
        requestId: String,
        body: ByteArray,
    ) {
        val timestamp = System.currentTimeMillis() / 1000
        setRequestProperty("X-Request-Timestamp", timestamp.toString())
        setRequestProperty(
            "X-Request-Signature",
            RequestSigner.sign(
                secret = sharedSecret,
                method = "POST",
                path = path,
                timestampSeconds = timestamp,
                requestId = requestId,
                body = body,
            ),
        )
    }

    private fun serverErrorMessage(status: Int, body: String): String {
        val detail = try {
            JSONObject(body).optString("error").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        return "分析服务 HTTP $status${detail?.let { "：$it" } ?: ""}"
    }
}
