package com.voicetoinvoice.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PttBurstCoalescerTest {

    @Test
    fun testSinglePressFlushesAfterGap() {
        val coalescer = PttBurstCoalescer(preRollMs = 300L, postRollMs = 300L)
        val pressTs = 1000L
        val releaseTs = 2000L

        val immediateFlush = coalescer.recordPressRelease(pressTs, releaseTs)
        assertTrue("Single press should not flush immediately on release", immediateFlush == null)

        val flushed = coalescer.checkAndFlushIfIdle(releaseTs, releaseTs + 600L, lastConsumedEndMs = 0L)
        assertNotNull("Should flush after gap threshold (600ms)", flushed)
        assertEquals(700L, flushed!!.startMs) // 1000 - 300
        assertEquals(2300L, flushed.endMs)   // 2000 + 300
        assertEquals(1, flushed.pressCount)
        assertEquals(1, flushed.boundaries.size)
        assertEquals(300L, flushed.boundaries[0].pressOffsetMs)   // 1000 - 700
        assertEquals(1300L, flushed.boundaries[0].releaseOffsetMs) // 2000 - 700
    }

    @Test
    fun testRapidPressesCoalescedIntoSingleGroup() {
        val coalescer = PttBurstCoalescer(preRollMs = 300L, postRollMs = 300L)
        
        // Press 1: 1000ms to 2000ms
        coalescer.recordPressRelease(1000L, 2000L)
        
        // Press 2: 2300ms to 3300ms (gap = 300ms < 600ms threshold)
        coalescer.recordPressRelease(2300L, 3300L)
        
        // Press 3: 3500ms to 4500ms (gap = 200ms < 600ms threshold)
        coalescer.recordPressRelease(3500L, 4500L)

        // Flush after Press 3 idle
        val flushed = coalescer.checkAndFlushIfIdle(4500L, 4500L + 600L, lastConsumedEndMs = 0L)
        assertNotNull(flushed)
        assertEquals(700L, flushed!!.startMs)  // 1000 - 300
        assertEquals(4800L, flushed.endMs)    // 4500 + 300
        assertEquals(3, flushed.pressCount)
        assertEquals(3, flushed.boundaries.size)
        
        // Utterance boundaries relative to startMs (700)
        assertEquals(300L, flushed.boundaries[0].pressOffsetMs)   // 1000 - 700
        assertEquals(1300L, flushed.boundaries[0].releaseOffsetMs) // 2000 - 700
        assertEquals(1600L, flushed.boundaries[1].pressOffsetMs)  // 2300 - 700
        assertEquals(2600L, flushed.boundaries[1].releaseOffsetMs)// 3300 - 700
        assertEquals(2800L, flushed.boundaries[2].pressOffsetMs)  // 3500 - 700
        assertEquals(3800L, flushed.boundaries[2].releaseOffsetMs)// 4500 - 700
    }

    @Test
    fun testLargeGapTriggersNewGroup() {
        val coalescer = PttBurstCoalescer(preRollMs = 300L, postRollMs = 300L)

        // Press 1: 1000ms to 2000ms
        coalescer.recordPressRelease(1000L, 2000L)

        // Press 2: 2800ms to 3800ms (gap = 800ms >= 600ms threshold)
        val flushedGroup1 = coalescer.recordPressRelease(2800L, 3800L, lastConsumedEndMs = 0L)
        assertNotNull("Large gap should flush Group 1 when Press 2 arrives", flushedGroup1)
        assertEquals(700L, flushedGroup1!!.startMs)
        assertEquals(2300L, flushedGroup1.endMs)
        assertEquals(1, flushedGroup1.pressCount)

        // Idle after Press 2
        val flushedGroup2 = coalescer.checkAndFlushIfIdle(3800L, 3800L + 600L, lastConsumedEndMs = flushedGroup1.endMs)
        assertNotNull(flushedGroup2)
        assertEquals(2500L, flushedGroup2!!.startMs) // 2800 - 300
        assertEquals(4100L, flushedGroup2.endMs)   // 3800 + 300
        assertEquals(1, flushedGroup2.pressCount)

        // Ensure no overlap between Group 1 end and Group 2 start
        assertTrue(flushedGroup2.startMs >= flushedGroup1.endMs)
    }

    @Test
    fun testMaxGroupSpanSafetyFlush() {
        val coalescer = PttBurstCoalescer(preRollMs = 300L, postRollMs = 300L, maxGroupSpanMs = 5000L) // 5s ceiling for test

        coalescer.recordPressRelease(1000L, 3000L)
        // Next press spans past 5000ms limit
        val flushedGroup1 = coalescer.recordPressRelease(5500L, 7000L)
        assertNotNull("Exceeding max span should trigger safety flush", flushedGroup1)
        assertEquals(1, flushedGroup1!!.pressCount)
        assertEquals(700L, flushedGroup1.startMs)
        assertEquals(3300L, flushedGroup1.endMs)
    }
}
