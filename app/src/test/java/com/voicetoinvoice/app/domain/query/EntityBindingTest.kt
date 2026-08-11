package com.voicetoinvoice.app.domain.query

import com.voicetoinvoice.app.domain.parser.PhoneticKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntityBindingTest {

    private val fixtureCatalog = listOf(
        "Baingan", "Lahsun", "Palak", "kela", "Kheera", "Aaloo", "Tamatar", "Paneer"
    )

    private fun findCatalogItem(query: String, catalog: List<String>): String? {
        if (catalog.isEmpty()) return null
        catalog.find { it.equals(query, ignoreCase = true) }?.let { return it }
        if (query.length >= 3) {
            catalog.find { it.contains(query, ignoreCase = true) }?.let { return it }
        }
        val queryKey = PhoneticKey.of(query)
        if (queryKey.length < 3) return null
        val ranked = catalog
            .map { it to PhoneticKey.normalizedDistance(queryKey, PhoneticKey.of(it)) }
            .sortedBy { it.second }
        val best = ranked.firstOrNull() ?: return null
        if (best.second > 0.34) return null
        val rival = ranked.firstOrNull { !it.first.equals(best.first, ignoreCase = true) }
        val margin = if (rival != null) rival.second - best.second else 1.0
        if (margin < 0.08) return null
        return best.first
    }

    @Test
    fun `par saman returns no match due to low margin against Lahsun`() {
        val match = findCatalogItem("पर सामान", fixtureCatalog)
        assertNull(match)
    }

    @Test
    fun `kul returns no match due to tie between kela and Kheera`() {
        val match = findCatalogItem("कुल", fixtureCatalog)
        assertNull(match)
    }

    @Test
    fun `valid item queries bind correctly to catalog`() {
        assertEquals("Aaloo", findCatalogItem("आलू", fixtureCatalog))
        assertEquals("Baingan", findCatalogItem("बैंगन", fixtureCatalog))
        assertEquals("Tamatar", findCatalogItem("टमाटर", fixtureCatalog))
        assertEquals("Paneer", findCatalogItem("पनीर", fixtureCatalog))
    }
}
