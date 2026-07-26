package com.voicetoinvoice.app.audio

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max

class PttWindowLedger {
    @Volatile private var lastEndMs: Long = 0L
    private val pressTimestamps = CopyOnWriteArrayList<Long>()

    fun recordPress(pressMs: Long) {
        pressTimestamps.add(pressMs)
        if (pressTimestamps.size > 50) {
            pressTimestamps.removeAt(0)
        }
    }

    fun lastConsumedEndMs(): Long = lastEndMs

    fun nextPressAfter(releaseMs: Long): Long? {
        return pressTimestamps.firstOrNull { it > releaseMs }
    }

    @Synchronized
    fun commitWindow(startMs: Long, endMs: Long) {
        lastEndMs = max(lastEndMs, endMs)
    }

    fun reset() {
        lastEndMs = 0L
        pressTimestamps.clear()
    }
}
