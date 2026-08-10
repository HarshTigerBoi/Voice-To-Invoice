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

sealed class BurstFlush {
    data class Ready(val group: CoalescedBurstGroup) : BurstFlush()
    /** Group was discarded before extraction; recorded so Diagnostic Logs shows WHY. */
    data class Dropped(val reason: String, val firstPressMs: Long, val lastReleaseMs: Long) : BurstFlush()
}

class PttBurstCoalescer(
    private val preRollMs: Long = 300L,
    private val postRollMs: Long = 300L,
    private val maxGroupSpanMs: Long = 115000L
) {
    val gapThresholdMs: Long = preRollMs + postRollMs
    private val maxGroupAgeMs: Long = 5_000L

    private val currentGroupPairs = mutableListOf<PressReleasePair>()

    @Synchronized
    fun recordPressRelease(pressMs: Long, releaseMs: Long, lastConsumedEndMs: Long = 0L): BurstFlush? {
        val safeReleaseMs = max(releaseMs, pressMs + 10L)

        if (currentGroupPairs.isNotEmpty()) {
            val firstPressMs = currentGroupPairs.first().pressMs
            val lastReleaseMs = currentGroupPairs.last().releaseMs
            val gapMs = pressMs - lastReleaseMs
            val potentialSpanMs = safeReleaseMs - firstPressMs

            if (gapMs >= gapThresholdMs || potentialSpanMs >= maxGroupSpanMs) {
                val flushedGroup = buildGroupLocked(lastConsumedEndMs, pressMs)
                currentGroupPairs.clear()
                currentGroupPairs.add(PressReleasePair(pressMs, safeReleaseMs))
                return flushedGroup
            }
        }

        currentGroupPairs.add(PressReleasePair(pressMs, safeReleaseMs))
        return null
    }

    @Synchronized
    fun checkAndFlushIfIdle(lastReleaseMs: Long, nowMs: Long, lastConsumedEndMs: Long): BurstFlush? {
        if (currentGroupPairs.isEmpty()) return null
        val groupLastRelease = currentGroupPairs.last().releaseMs
        if (groupLastRelease == lastReleaseMs && (nowMs - groupLastRelease) >= gapThresholdMs) {
            val flushed = buildGroupLocked(lastConsumedEndMs, nowMs)
            currentGroupPairs.clear()
            return flushed
        }
        return null
    }

    @Synchronized
    fun forceFlush(lastConsumedEndMs: Long = 0L): BurstFlush? {
        if (currentGroupPairs.isEmpty()) return null
        val flushed = buildGroupLocked(lastConsumedEndMs, System.currentTimeMillis())
        currentGroupPairs.clear()
        return flushed
    }

    @Synchronized
    fun reset() {
        currentGroupPairs.clear()
    }

    private fun buildGroupLocked(lastConsumedEndMs: Long, nowMs: Long): BurstFlush {
        val firstPressMs = currentGroupPairs.first().pressMs
        val lastReleaseMs = currentGroupPairs.last().releaseMs

        if (nowMs - lastReleaseMs > maxGroupAgeMs) {
            return BurstFlush.Dropped("stale_group_age_${nowMs - lastReleaseMs}ms", firstPressMs, lastReleaseMs)
        }

        val rawStartMs = max(0L, firstPressMs - preRollMs)
        val clampedStartMs = max(rawStartMs, lastConsumedEndMs)
        val rawEndMs = lastReleaseMs + postRollMs

        // Previously this emitted `max(clampedStartMs + 100L, rawEndMs)` -- a 100ms window that
        // is always below MIN_WINDOW_BYTES, i.e. a guaranteed extraction_null. Drop instead.
        if (rawEndMs - clampedStartMs < MIN_USABLE_WINDOW_MS) {
            return BurstFlush.Dropped("window_consumed_by_ledger", firstPressMs, lastReleaseMs)
        }

        val boundaries = currentGroupPairs.map { pair ->
            UtteranceBoundary(
                pressOffsetMs = max(0L, pair.pressMs - clampedStartMs),
                releaseOffsetMs = max(0L, pair.releaseMs - clampedStartMs)
            )
        }

        return BurstFlush.Ready(
            CoalescedBurstGroup(
                startMs = clampedStartMs,
                endMs = rawEndMs,
                firstPressMs = firstPressMs,
                lastReleaseMs = lastReleaseMs,
                pressCount = currentGroupPairs.size,
                boundaries = boundaries
            )
        )
    }

    companion object { const val MIN_USABLE_WINDOW_MS = 400L }
}
