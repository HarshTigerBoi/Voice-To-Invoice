package com.voicetoinvoice.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PttBurstCoalescerTest {

    @Test
    fun singlePressFlushesReadyGroup() {
        val coalescer = PttBurstCoalescer(preRollMs = 300L, postRollMs = 300L)
        val pressMs = 10_000L
        val releaseMs = 12_000L

        val immediate = coalescer.recordPressRelease(pressMs, releaseMs)
        assertNull(immediate)

        val flush = coalescer.checkAndFlushIfIdle(lastReleaseMs = releaseMs, nowMs = 12_600L, lastConsumedEndMs = 0L)
        assertNotNull(flush)
        assertTrue(flush is BurstFlush.Ready)
        val ready = flush as BurstFlush.Ready
        assertEquals(9_700L, ready.group.startMs)
        assertEquals(12_300L, ready.group.endMs)
        assertEquals(1, ready.group.pressCount)
    }

    @Test
    fun groupOlderThanFiveSecondsIsDropped() {
        val coalescer = PttBurstCoalescer(preRollMs = 300L, postRollMs = 300L)
        val pressMs = 10_000L
        val releaseMs = 12_000L

        coalescer.recordPressRelease(pressMs, releaseMs)

        val flush = coalescer.checkAndFlushIfIdle(lastReleaseMs = releaseMs, nowMs = 18_000L, lastConsumedEndMs = 0L)
        assertNotNull(flush)
        assertTrue(flush is BurstFlush.Dropped)
        val dropped = flush as BurstFlush.Dropped
        assertEquals("stale_group_age_6000ms", dropped.reason)
        assertEquals(10_000L, dropped.firstPressMs)
        assertEquals(12_000L, dropped.lastReleaseMs)
    }

    @Test
    fun windowAlreadyConsumedByLedgerIsDropped() {
        val coalescer = PttBurstCoalescer(preRollMs = 300L, postRollMs = 300L)
        val pressMs = 10_000L
        val releaseMs = 12_000L

        coalescer.recordPressRelease(pressMs, releaseMs)

        // Ledger has consumed past releaseMs + postRollMs (12,300ms)
        val flush = coalescer.checkAndFlushIfIdle(lastReleaseMs = releaseMs, nowMs = 12_600L, lastConsumedEndMs = 13_000L)
        assertNotNull(flush)
        assertTrue(flush is BurstFlush.Dropped)
        val dropped = flush as BurstFlush.Dropped
        assertEquals("window_consumed_by_ledger", dropped.reason)
    }

    @Test
    fun twoPressesWithinThresholdCoalesceIntoOneGroup() {
        val coalescer = PttBurstCoalescer(preRollMs = 300L, postRollMs = 300L)
        val press1 = 10_000L
        val release1 = 11_000L
        val press2 = 11_400L // 400ms gap < 600ms threshold
        val release2 = 12_500L

        assertNull(coalescer.recordPressRelease(press1, release1))
        assertNull(coalescer.recordPressRelease(press2, release2))

        val flush = coalescer.checkAndFlushIfIdle(lastReleaseMs = release2, nowMs = 13_100L, lastConsumedEndMs = 0L)
        assertNotNull(flush)
        assertTrue(flush is BurstFlush.Ready)
        val ready = flush as BurstFlush.Ready
        assertEquals(9_700L, ready.group.startMs)
        assertEquals(12_800L, ready.group.endMs)
        assertEquals(2, ready.group.pressCount)
    }
}
