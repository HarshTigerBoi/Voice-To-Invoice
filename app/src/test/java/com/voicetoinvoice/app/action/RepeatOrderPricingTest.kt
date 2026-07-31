package com.voicetoinvoice.app.action

import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.data.local.entity.TransactionRecord
import com.voicetoinvoice.app.ui.components.buildRepeatLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepeatOrderPricingTest {

    private fun catalogItem(id: String, name: String, price: Double, active: Boolean = true) = CatalogItem(
        id = id,
        name = name,
        unitId = "KG",
        price = price,
        active = active
    )

    private fun txLine(itemId: String, name: String, qty: Double, priceAtSale: Double) = TransactionRecord(
        itemId = itemId,
        itemName = name,
        quantity = qty,
        priceAtSale = priceAtSale,
        total = qty * priceAtSale
    )

    @Test
    fun usesTodaysPriceNotHistoricalPrice() {
        val catalog = listOf(catalogItem("item-aloo", "आलू", 50.0))
        val previous = listOf(txLine("item-aloo", "आलू", 2.0, 40.0))

        val result = buildRepeatLines(previous, catalog)

        assertEquals(1, result.size)
        assertEquals(50.0, result[0].todayPrice, 1e-9)
        assertEquals(40.0, result[0].previousPrice, 1e-9)
    }

    @Test
    fun dropsItemsNoLongerInCatalog() {
        val catalog = listOf(catalogItem("item-aloo", "आलू", 50.0))
        val previous = listOf(
            txLine("item-aloo", "आलू", 2.0, 40.0),
            txLine("item-deleted", "पुराना सामान", 1.0, 30.0)
        )

        val result = buildRepeatLines(previous, catalog)

        assertEquals(1, result.size)
        assertEquals("item-aloo", result[0].item.id)
    }

    @Test
    fun dropsInactiveCatalogItems() {
        val catalog = listOf(
            catalogItem("item-aloo", "आलू", 50.0, active = true),
            catalogItem("item-pyaz", "प्याज", 40.0, active = false)
        )
        val previous = listOf(
            txLine("item-aloo", "आलू", 2.0, 40.0),
            txLine("item-pyaz", "प्याज", 1.0, 30.0)
        )

        val result = buildRepeatLines(previous, catalog)

        assertEquals(1, result.size)
        assertEquals("item-aloo", result[0].item.id)
    }

    @Test
    fun preservesQuantities() {
        val catalog = listOf(catalogItem("item-aloo", "आलू", 50.0))
        val previous = listOf(txLine("item-aloo", "आलू", 3.5, 40.0))

        val result = buildRepeatLines(previous, catalog)

        assertEquals(1, result.size)
        assertEquals(3.5, result[0].quantity, 1e-9)
    }

    @Test
    fun unchangedPriceStillPopulatesBothFields() {
        val catalog = listOf(catalogItem("item-aloo", "आलू", 40.0))
        val previous = listOf(txLine("item-aloo", "आलू", 2.0, 40.0))

        val result = buildRepeatLines(previous, catalog)

        assertEquals(1, result.size)
        assertEquals(40.0, result[0].todayPrice, 1e-9)
        assertEquals(40.0, result[0].previousPrice, 1e-9)
    }
}
