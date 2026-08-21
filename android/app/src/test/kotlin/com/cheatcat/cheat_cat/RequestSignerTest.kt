package com.cheatcat.cheat_cat

import org.junit.Assert.assertEquals
import org.junit.Test

class RequestSignerTest {
    @Test
    fun `matches Python server signature format`() {
        val signature = RequestSigner.sign(
            secret = "test-shared-secret-with-at-least-32-chars",
            method = "POST",
            path = "/v1/analyze",
            timestampSeconds = 1_700_000_000,
            requestId = "request-test-123",
            body = "jpeg".toByteArray(),
        )

        assertEquals(
            "2cfd7c6ade76615be831a6880555b7649223fc822948852047a6d751a0f5d0e1",
            signature,
        )
    }
}
