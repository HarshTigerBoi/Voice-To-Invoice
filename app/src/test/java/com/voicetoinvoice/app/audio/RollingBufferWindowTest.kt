package com.voicetoinvoice.app.audio

import com.voicetoinvoice.app.audio.RollingAudioBuffer.Companion.resolveSegmentWindowBytes
import com.voicetoinvoice.app.audio.RollingAudioBuffer.WindowResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RollingBufferWindowTest {

    private val bytesPerSecond = 32_000 // 16-bit 16kHz mono
    private val bufferDurationSeconds = 120
    private val bufferCapacity = bytesPerSecond * bufferDurationSeconds // 3,840,000 bytes

    @Test
    fun windowFullyInsideSegmentMapsExactly() {
        val segStartWallMs = 100_000L
        val segStartByteOffset = 0L
        val segEndWallMs = 110_000L
        val segEndByteOffset = 320_000L // 10s of audio

        val startMs = 108_000L // 2s before end
        val endMs = 110_000L

        val result = resolveSegmentWindowBytes(
            startMs = startMs,
            endMs = endMs,
            segStartWallMs = segStartWallMs,
            segStartByteOffset = segStartByteOffset,
            segEndWallMs = segEndWallMs,
            segEndByteOffset = segEndByteOffset,
            totalWritten = segEndByteOffset,
            bufferCapacity = bufferCapacity,
            bytesPerSecond = bytesPerSecond
        )

        assertTrue(result is WindowResolution.Ok)
        val ok = result as WindowResolution.Ok
        assertEquals(320_000L - 64_000L, ok.startByte)
        assertEquals(320_000L, ok.endByte)
        assertEquals(64_000L, ok.endByte - ok.startByte)
        assertEquals(false, ok.clampedToSegmentStart)
    }

    @Test
    fun windowStartingBeforeSegmentIsClampedNotExtrapolated() {
        val segStartWallMs = 100_000L
        val segStartByteOffset = 160_000L
        val segEndWallMs = 110_000L
        val segEndByteOffset = 480_000L // 10s segment

        val startMs = segStartWallMs - 5_000L // 5s before segment started
        val endMs = 105_000L

        val result = resolveSegmentWindowBytes(
            startMs = startMs,
            endMs = endMs,
            segStartWallMs = segStartWallMs,
            segStartByteOffset = segStartByteOffset,
            segEndWallMs = segEndWallMs,
            segEndByteOffset = segEndByteOffset,
            totalWritten = segEndByteOffset,
            bufferCapacity = bufferCapacity,
            bytesPerSecond = bytesPerSecond
        )

        assertTrue(result is WindowResolution.Ok)
        val ok = result as WindowResolution.Ok
        assertEquals(segStartByteOffset, ok.startByte)
        assertEquals(true, ok.clampedToSegmentStart)
    }

    @Test
    fun windowEntirelyBeforeSegmentFails() {
        val segStartWallMs = 100_000L
        val segStartByteOffset = 160_000L
        val segEndWallMs = 110_000L
        val segEndByteOffset = 480_000L

        val result = resolveSegmentWindowBytes(
            startMs = 90_000L,
            endMs = 95_000L,
            segStartWallMs = segStartWallMs,
            segStartByteOffset = segStartByteOffset,
            segEndWallMs = segEndWallMs,
            segEndByteOffset = segEndByteOffset,
            totalWritten = segEndByteOffset,
            bufferCapacity = bufferCapacity,
            bytesPerSecond = bytesPerSecond
        )

        assertTrue(result is WindowResolution.Failed)
        assertEquals("window_before_segment", (result as WindowResolution.Failed).reason)
    }

    @Test
    fun windowOlderThanRingCapacityFails() {
        val totalWritten = bufferCapacity.toLong() + 500_000L
        val segStartWallMs = 100_000L
        val segStartByteOffset = 100_000L // Overwritten offset
        val segEndWallMs = 110_000L
        val segEndByteOffset = 420_000L // Also overwritten

        val result = resolveSegmentWindowBytes(
            startMs = 100_000L,
            endMs = 105_000L,
            segStartWallMs = segStartWallMs,
            segStartByteOffset = segStartByteOffset,
            segEndWallMs = segEndWallMs,
            segEndByteOffset = segEndByteOffset,
            totalWritten = totalWritten,
            bufferCapacity = bufferCapacity,
            bytesPerSecond = bytesPerSecond
        )

        assertTrue(result is WindowResolution.Failed)
        assertEquals("window_overwritten", (result as WindowResolution.Failed).reason)
    }

    @Test
    fun subMinimumWindowFails() {
        val segStartWallMs = 100_000L
        val segStartByteOffset = 0L
        val segEndWallMs = 110_000L
        val segEndByteOffset = 320_000L

        val result = resolveSegmentWindowBytes(
            startMs = 109_900L, // 100ms window
            endMs = 110_000L,
            segStartWallMs = segStartWallMs,
            segStartByteOffset = segStartByteOffset,
            segEndWallMs = segEndWallMs,
            segEndByteOffset = segEndByteOffset,
            totalWritten = segEndByteOffset,
            bufferCapacity = bufferCapacity,
            bytesPerSecond = bytesPerSecond
        )

        assertTrue(result is WindowResolution.Failed)
        assertTrue((result as WindowResolution.Failed).reason.startsWith("window_too_small"))
    }

    @Test
    fun endBeforeStartFails() {
        val result = resolveSegmentWindowBytes(
            startMs = 110_000L,
            endMs = 105_000L,
            segStartWallMs = 100_000L,
            segStartByteOffset = 0L,
            segEndWallMs = 110_000L,
            segEndByteOffset = 320_000L,
            totalWritten = 320_000L,
            bufferCapacity = bufferCapacity,
            bytesPerSecond = bytesPerSecond
        )

        assertTrue(result is WindowResolution.Failed)
        assertEquals("end_before_start", (result as WindowResolution.Failed).reason)
    }
}
