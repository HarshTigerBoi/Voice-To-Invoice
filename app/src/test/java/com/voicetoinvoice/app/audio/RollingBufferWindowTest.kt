package com.voicetoinvoice.app.audio

import com.voicetoinvoice.app.audio.RollingAudioBuffer.Companion.MIN_WINDOW_BYTES
import com.voicetoinvoice.app.audio.RollingAudioBuffer.Companion.resolveWindowBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RollingBufferWindowTest {

    private val bytesPerSecond = 32_000 // 16-bit 16kHz mono
    private val bufferDurationSeconds = 120
    private val bufferCapacity = bytesPerSecond * bufferDurationSeconds // 3,840,000 bytes

    @Test
    fun windowMapsToCorrectRangeMidBuffer() {
        val anchorMs = 100_000L
        val totalWritten = 320_000L // 10s of audio written
        val startMs = 95_000L // 5s before anchor
        val endMs = 100_000L  // at anchor (5s duration = 160,000 bytes)

        val range = resolveWindowBytes(
            startMs = startMs,
            endMs = endMs,
            anchorMs = anchorMs,
            totalWritten = totalWritten,
            bufferCapacity = bufferCapacity,
            bytesPerSecond = bytesPerSecond
        )

        assertNotNull(range)
        val expectedLength = (endMs - startMs) * bytesPerSecond / 1000L
        assertEquals(expectedLength.toInt(), range!!.last - range.first + 1)
        assertEquals(160_000L, range.first.toLong())
    }

    @Test
    fun windowOlderThanBufferReturnsNull() {
        val anchorMs = 200_000L
        val totalWritten = bufferCapacity.toLong() + 100_000L // Overwritten past capacity
        val startMs = 10_000L // 190s ago (exceeds 120s buffer capacity)
        val endMs = 20_000L

        val range = resolveWindowBytes(
            startMs = startMs,
            endMs = endMs,
            anchorMs = anchorMs,
            totalWritten = totalWritten,
            bufferCapacity = bufferCapacity,
            bytesPerSecond = bytesPerSecond
        )

        assertNull(range)
    }

    @Test
    fun windowIsAnchoredToLastWriteNotStart() {
        val anchorMs = 50_000L
        val totalWritten = 640_000L // 20s of audio
        // Simulate drift where startedAt was 30s ago but totalWritten is only 20s
        val startMs = 48_000L // 2s before anchor
        val endMs = 50_000L   // at anchor (2s = 64,000 bytes)

        val range = resolveWindowBytes(
            startMs = startMs,
            endMs = endMs,
            anchorMs = anchorMs,
            totalWritten = totalWritten,
            bufferCapacity = bufferCapacity,
            bytesPerSecond = bytesPerSecond
        )

        assertNotNull(range)
        val expectedLength = (endMs - startMs) * bytesPerSecond / 1000L
        assertEquals(expectedLength.toInt(), range!!.last - range.first + 1)
        assertEquals(576_000L, range.first.toLong())
    }

    @Test
    fun shortWindowReturnsNull() {
        val anchorMs = 10_000L
        val totalWritten = 32_000L
        val startMs = 9_900L // Only 100ms requested (~3,200 bytes < 9,600 MIN_WINDOW_BYTES)
        val endMs = 10_000L

        val range = resolveWindowBytes(
            startMs = startMs,
            endMs = endMs,
            anchorMs = anchorMs,
            totalWritten = totalWritten,
            bufferCapacity = bufferCapacity,
            bytesPerSecond = bytesPerSecond
        )

        assertNull(range)
    }

    @Test
    fun endBeforeStartReturnsNull() {
        val range = resolveWindowBytes(
            startMs = 10_000L,
            endMs = 5_000L,
            anchorMs = 10_000L,
            totalWritten = 320_000L,
            bufferCapacity = bufferCapacity,
            bytesPerSecond = bytesPerSecond
        )

        assertNull(range)
    }
}
