package com.cheatcat.cheat_cat

import kotlin.math.abs

/** Cheap local gate that submits only stable screen content not sent before. */
class StableFrameGate(
    private val requiredStableFrames: Int = 2,
    private val minimumChangedSamples: Int = 10,
    private val luminanceDelta: Int = 18,
    private val retryDelayMs: Long = 3_000,
) {
    private var previous: ByteArray? = null
    private var stableFrames = 0
    private var lastSubmitted: ByteArray? = null
    private var retryNotBefore = 0L

    init {
        require(requiredStableFrames >= 2)
    }

    @Synchronized
    fun shouldSubmit(signature: ByteArray, nowMs: Long): Boolean {
        val prior = previous
        previous = signature.copyOf()
        if (prior == null || isDifferent(prior, signature)) {
            stableFrames = 1
            return false
        }
        stableFrames += 1
        if (stableFrames < requiredStableFrames || nowMs < retryNotBefore) return false
        val submitted = lastSubmitted
        if (submitted != null && !isDifferent(submitted, signature)) return false
        lastSubmitted = signature.copyOf()
        return true
    }

    @Synchronized
    fun markFailed(signature: ByteArray, nowMs: Long) {
        val submitted = lastSubmitted ?: return
        if (!isDifferent(submitted, signature)) {
            lastSubmitted = null
            retryNotBefore = nowMs + retryDelayMs
        }
    }

    @Synchronized
    fun reset() {
        previous = null
        stableFrames = 0
        lastSubmitted = null
        retryNotBefore = 0L
    }

    private fun isDifferent(first: ByteArray, second: ByteArray): Boolean {
        if (first.size != second.size) return true
        var changes = 0
        for (index in first.indices) {
            val delta = abs(
                (first[index].toInt() and 0xFF) -
                    (second[index].toInt() and 0xFF),
            )
            if (delta >= luminanceDelta && ++changes >= minimumChangedSamples) {
                return true
            }
        }
        return false
    }
}
