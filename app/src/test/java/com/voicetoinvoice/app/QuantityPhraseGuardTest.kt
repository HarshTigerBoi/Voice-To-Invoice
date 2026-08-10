package com.voicetoinvoice.app

import com.voicetoinvoice.app.domain.matcher.FuzzyCatalogMatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the catalog against leftover quantities being stored as products.
 *
 * The live device catalog had accumulated "पंद्रह" (15), "सत्रह की" (17), "सत्ताईस" (27) and
 * "अठारह के लोग" (18) as ₹0 items, each rendered to the shopkeeper as a real product. The
 * pre-existing denylist covered only unit words, so every new numeral misfire had to be patched
 * by hand in `AppDatabase.onOpen`.
 */
class QuantityPhraseGuardTest {

    @Test
    fun `bare hindi number words are rejected`() {
        for (name in listOf("पंद्रह", "सत्ताईस", "अठारह", "सत्रह", "दस", "एक")) {
            assertTrue("expected '$name' to be rejected", FuzzyCatalogMatcher.isBlacklistedItemName(name))
        }
    }

    @Test
    fun `phrases opening with a number word are rejected`() {
        for (name in listOf("अठारह के लोग", "सत्रह की", "दस किलो", "two packet")) {
            assertTrue("expected '$name' to be rejected", FuzzyCatalogMatcher.isBlacklistedItemName(name))
        }
    }

    @Test
    fun `digit-only names are rejected`() {
        for (name in listOf("15", "27", "1.5")) {
            assertTrue("expected '$name' to be rejected", FuzzyCatalogMatcher.isBlacklistedItemName(name))
        }
    }

    @Test
    fun `transliterated number words are rejected`() {
        for (name in listOf("pandrah", "das", "ek", "ten")) {
            assertTrue("expected '$name' to be rejected", FuzzyCatalogMatcher.isBlacklistedItemName(name))
        }
    }

    /** The guard must not swallow real stock — only the LEADING token is a quantity signal. */
    @Test
    fun `real product names are accepted`() {
        for (name in listOf("Aaloo", "Tamatar", "आलू", "Amul Gold Milk", "Amul Gold 500", "Parle-G")) {
            assertFalse("expected '$name' to be accepted", FuzzyCatalogMatcher.isBlacklistedItemName(name))
        }
    }

    @Test
    fun `unit words remain rejected`() {
        for (name in listOf("kilo", "किलो", "packet", "dozen", "kilometer")) {
            assertTrue("expected '$name' to be rejected", FuzzyCatalogMatcher.isBlacklistedItemName(name))
        }
    }

    @Test
    fun `blank names are rejected`() {
        assertTrue(FuzzyCatalogMatcher.isBlacklistedItemName(""))
        assertTrue(FuzzyCatalogMatcher.isBlacklistedItemName("   "))
    }
}
