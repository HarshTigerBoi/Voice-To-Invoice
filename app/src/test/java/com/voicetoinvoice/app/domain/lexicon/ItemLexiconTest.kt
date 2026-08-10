package com.voicetoinvoice.app.domain.lexicon

import org.junit.Assert.assertEquals
import org.junit.Test

class ItemLexiconTest {

    @Test
    fun `canonicalOf brand variant unit compositional identity test cases`() {
        assertEquals("Saras Milk", ItemLexicon.canonicalOf("saras milk"))
        assertEquals("Amul Doodh", ItemLexicon.canonicalOf("amul milk"))
        assertEquals("Doodh", ItemLexicon.canonicalOf("milk"))
        assertEquals("Sugar (Madhur)", ItemLexicon.canonicalOf("madhur sugar"))
        assertEquals("Tata Sugar", ItemLexicon.canonicalOf("tata sugar"))
        assertEquals("Sugar", ItemLexicon.canonicalOf("sugar"))
        assertEquals("Mirch", ItemLexicon.canonicalOf(" हरी मिर्च "))
        assertEquals("Desi Ghee", ItemLexicon.canonicalOf("desi ghee"))
        assertEquals("Saras Ghee", ItemLexicon.canonicalOf("saras ghee"))
        assertEquals("Amul Ghee", ItemLexicon.canonicalOf("amul ghee"))
        assertEquals("Ghee", ItemLexicon.canonicalOf("ghee"))
        assertEquals("Aaloo", ItemLexicon.canonicalOf("आलू"))
        assertEquals("Adrak", ItemLexicon.canonicalOf("अदरक"))
        assertEquals("Ghee", ItemLexicon.canonicalOf("घी"))
        assertEquals("Amul Gold Milk", ItemLexicon.canonicalOf("अमूल गोल्ड"))
        assertEquals("Green Nimbu", ItemLexicon.canonicalOf("हरी नींबू"))
    }
}
