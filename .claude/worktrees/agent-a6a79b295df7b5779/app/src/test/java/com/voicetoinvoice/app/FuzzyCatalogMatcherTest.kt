package com.voicetoinvoice.app

import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.domain.matcher.FuzzyCatalogMatcher
import org.junit.Assert.*
import org.junit.Test

class FuzzyCatalogMatcherTest {

    private val matcher = FuzzyCatalogMatcher()
    private val sampleCatalog = listOf(
        CatalogItem(id = "1", name = "Tamatar", price = 40.0),
        CatalogItem(id = "2", name = "Aaloo", price = 30.0),
        CatalogItem(id = "3", name = "Pyaz", price = 35.0),
        CatalogItem(id = "4", name = "Bhindi", price = 50.0)
    )

    @Test
    fun testDirectMatching() {
        val result = matcher.findBestMatch("2 kilo tamatar", sampleCatalog)
        assertNotNull(result)
        assertEquals("Tamatar", result?.item?.name)
        assertTrue(result!!.confidence > 0.8f)
    }

    @Test
    fun testIndicPhoneticTransliterationMatching() {
        val result1 = matcher.findBestMatch("do tmatar", sampleCatalog)
        assertNotNull(result1)
        assertEquals("Tamatar", result1?.item?.name)

        val result2 = matcher.findBestMatch("1 kilo alu", sampleCatalog)
        assertNotNull(result2)
        assertEquals("Aaloo", result2?.item?.name)

        val result3 = matcher.findBestMatch("ek paao bindi", sampleCatalog)
        assertNotNull(result3)
        assertEquals("Bhindi", result3?.item?.name)
    }
}
