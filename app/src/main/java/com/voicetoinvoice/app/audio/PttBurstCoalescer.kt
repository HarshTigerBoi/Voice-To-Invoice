package com.voicetoinvoice.app.audio

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

data class PressReleasePair(
    val pressMs: Long,
    val releaseMs: Long
)

data class UtteranceBoundary(
    val pressOffsetMs: Long,
    val releaseOffsetMs: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("pressOffsetMs", pressOffsetMs)
        put("releaseOffsetMs", releaseOffsetMs)
    }
}

data class CoalescedBurstGroup(
    val startMs: Long,
    val endMs: Long,
    val firstPressMs: Long,
    val lastReleaseMs: Long,
    val pressCount: Int,
    val boundaries: List<UtteranceBoundary>
) {
    fun utteranceBoundariesJson(): String {
        val array = JSONArray()
        boundaries.forEach { array.put(it.toJson()) }
        return array.toString()
    }
}

class PttBurstCoalescer(
    private val preRollMs: Long = 300L,
    private val postRollMs: Long = 300L,
    private val maxGroupSpanMs: Long = 115000L
) {
    val gapThresholdMs: Long = preRollMs + postRollMs

    private val currentGroupPairs = mutableListOf<PressReleasePair>()

    @Synchronized
    fun recordPressRelease(pressMs: Long, releaseMs: Long, lastConsumedEndMs: Long = 0L): CoalescedBurstGroup? {
        val safeReleaseMs = max(releaseMs, pressMs + 10L)

        if (currentGroupPairs.isNotEmpty()) {
            val firstPressMs = currentGroupPairs.first().pressMs
            val lastReleaseMs = currentGroupPairs.last().releaseMs
            val gapMs = pressMs - lastReleaseMs
            val potentialSpanMs = safeReleaseMs - firstPressMs

            if (gapMs >= gapThresholdMs || potentialSpanMs >= maxGroupSpanMs) {
                val flushedGroup = buildGroupLocked(lastConsumedEndMs)
                currentGroupPairs.clear()
                currentGroupPairs.add(PressReleasePair(pressMs, safeReleaseMs))
                return flushedGroup
            }
        }

        currentGroupPairs.add(PressReleasePair(pressMs, safeReleaseMs))
        return null
    }

    @Synchronized
    fun checkAndFlushIfIdle(lastReleaseMs: Long, nowMs: Long, lastConsumedEndMs: Long): CoalescedBurstGroup? {
        if (currentGroupPairs.isEmpty()) return null
        val groupLastRelease = currentGroupPairs.last().releaseMs
        if (groupLastRelease == lastReleaseMs && (nowMs - groupLastRelease) >= gapThresholdMs) {
            val flushed = buildGroupLocked(lastConsumedEndMs)
            currentGroupPairs.clear()
            return flushed
        }
        return null
    }

    @Synchronized
    fun forceFlush(lastConsumedEndMs: Long = 0L): CoalescedBurstGroup? {
        if (currentGroupPairs.isEmpty()) return null
        val flushed = buildGroupLocked(lastConsumedEndMs)
        currentGroupPairs.clear()
        return flushed
    }

    @Synchronized
    fun reset() {
        currentGroupPairs.clear()
    }

    private fun buildGroupLocked(lastConsumedEndMs: Long = 0L): CoalescedBurstGroup {
        val firstPressMs = currentGroupPairs.first().pressMs
        val lastReleaseMs = currentGroupPairs.last().releaseMs

        val rawStartMs = max(0L, firstPressMs - preRollMs)
        val clampedStartMs = max(rawStartMs, lastConsumedEndMs)
        val clampedEndMs = max(clampedStartMs + 100L, lastReleaseMs + postRollMs)

        val boundaries = currentGroupPairs.map { pair ->
            UtteranceBoundary(
                pressOffsetMs = max(0L, pair.pressMs - clampedStartMs),
                releaseOffsetMs = max(0L, pair.releaseMs - clampedStartMs)
            )
        }

        return CoalescedBurstGroup(
            startMs = clampedStartMs,
            endMs = clampedEndMs,
            firstPressMs = firstPressMs,
            lastReleaseMs = lastReleaseMs,
            pressCount = currentGroupPairs.size,
            boundaries = boundaries
        )
    }
}
