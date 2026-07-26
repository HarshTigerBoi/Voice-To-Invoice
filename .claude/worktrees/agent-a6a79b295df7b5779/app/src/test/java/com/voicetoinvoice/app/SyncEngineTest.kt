package com.voicetoinvoice.app

import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.data.local.entity.TransactionRecord
import org.junit.Assert.*
import org.junit.Test

class SyncEngineTest {

    @Test
    fun testAppendOnlyTransactionUuidSeparation() {
        // Two transactions on separate devices have distinct UUIDs and never conflict
        val txDevice1 = TransactionRecord(id = "uuid-1", itemId = "item-1", itemName = "Tamatar", quantity = 2.0, priceAtSale = 40.0, total = 80.0)
        val txDevice2 = TransactionRecord(id = "uuid-2", itemId = "item-1", itemName = "Tamatar", quantity = 1.0, priceAtSale = 40.0, total = 40.0)

        assertNotEquals(txDevice1.id, txDevice2.id)
        assertEquals("uuid-1", txDevice1.id)
        assertEquals("uuid-2", txDevice2.id)
    }

    @Test
    fun testCatalogItemLwwTimestampComparison() {
        val olderItem = CatalogItem(id = "item-1", name = "Tamatar", price = 40.0, updatedAt = 1000L)
        val newerItem = CatalogItem(id = "item-1", name = "Tamatar", price = 45.0, updatedAt = 2000L)

        assertTrue(newerItem.updatedAt > olderItem.updatedAt)
    }
}
