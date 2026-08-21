package com.cheatcat.cheat_cat

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HMAC request signing shared by analyze and cancellation calls. */
object RequestSigner {
    fun sign(
        secret: String,
        method: String,
        path: String,
        timestampSeconds: Long,
        requestId: String,
        body: ByteArray,
    ): String {
        val bodyHash = MessageDigest.getInstance("SHA-256").digest(body).toHex()
        val canonical = listOf(
            method.uppercase(),
            path,
            timestampSeconds.toString(),
            requestId,
            bodyHash,
        ).joinToString("\n")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(canonical.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xFF)
    }
}
