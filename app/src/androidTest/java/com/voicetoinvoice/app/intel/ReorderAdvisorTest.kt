package com.voicetoinvoice.app.intel

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voicetoinvoice.app.data.ShopContext
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.data.local.entity.StockReason
import com.voicetoinvoice.app.data.local.entity.SupplierRecord
import com.voicetoinvoice.app.data.local.entity.TransactionRecord
import com.voicetoinvoice.app.data.repository.StockLedgerRepository
import com.voicetoinvoice.app.domain.intel.ReorderAdvisor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ReorderAdvisor` is deliberately explainable arithmetic, not a model (Docs/master_build_plan.md
 * §4.2) -- these tests check the arithmetic itself, and that suggestions group by supplier, which
 * is the actual unit of work when a shopkeeper places an order.
 */
@RunWith(AndroidJUnit4::class)
class ReorderAdvisorTest {

    private lateinit var db: AppDatabase
    private lateinit var stockLedger: StockLedgerRepository
    private lateinit var advisor: ReorderAdvisor
    private val now = System.currentTimeMillis()
    private val dayMs = 24 * 60 * 60 * 1000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ShopContext.initialize(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        stockLedger = StockLedgerRepository(db)
        advisor = ReorderAdvisor(db)
    }

    private suspend fun sellDaily(itemId: String, itemName: String, qtyPerDay: Double, days: Int) {
        for (d in 0 until days) {
            val ts = now - d * dayMs
            val tx = TransactionRecord(
                shopId = ShopContext.requireShopId(), itemId = itemId, itemName = itemName,
                quantity = qtyPerDay, priceAtSale = 10.0, total = qtyPerDay * 10.0, timestamp = ts
            )
            db.transactionDao().insert(tx)
            stockLedger.recordSale(itemId, itemName, qtyPerDay, tx.id, ts, tx.total, null)
        }
    }

    @Test
    fun lowStockItemWithSteadySalesIsSuggested() = runTest {
        db.catalogDao().insertOrUpdate(CatalogItem(id = "aloo", shopId = ShopContext.requireShopId(), name = "आलू", unitId = "KG", price = 30.0))
        stockLedger.record("aloo", "आलू", 10.0, StockReason.STOCK_IN, unitCost = 15.0, refId = "po-1", occurredAtMs = now - 21 * dayMs)
        // Selling 5/day for 21 days on 10kg stock -> clearly below reorder point.
        sellDaily("aloo", "आलू", 5.0, 20)

        val suggestions = advisor.suggestions(now)
        assertTrue("expected a low-stock fast-moving item to be suggested", suggestions.any { it.itemId == "aloo" })
    }

    @Test
    fun wellStockedItemIsNotSuggested() = runTest {
        db.catalogDao().insertOrUpdate(CatalogItem(id = "chawal", shopId = ShopContext.requireShopId(), name = "चावल", unitId = "KG", price = 40.0))
        stockLedger.record("chawal", "चावल", 500.0, StockReason.STOCK_IN, unitCost = 30.0, refId = "po-2", occurredAtMs = now - 21 * dayMs)
        sellDaily("chawal", "चावल", 1.0, 20) // barely sells, huge stock

        val suggestions = advisor.suggestions(now)
        assertTrue("a well-stocked slow mover should not be suggested", suggestions.none { it.itemId == "chawal" })
    }

    @Test
    fun neverSoldItemIsNotSuggestedEvenAtZeroStock() = runTest {
        // An item genuinely never sold has nothing to project a reorder point from -- suggesting
        // it would be a guess, not arithmetic.
        db.catalogDao().insertOrUpdate(CatalogItem(id = "new-item", shopId = ShopContext.requireShopId(), name = "नया", unitId = "KG", price = 10.0, stockQty = 0.0))
        val suggestions = advisor.suggestions(now)
        assertTrue(suggestions.none { it.itemId == "new-item" })
    }

    @Test
    fun suggestionsGroupBySupplier() = runTest {
        val supplierA = SupplierRecord(shopId = ShopContext.requireShopId(), name = "Supplier A")
        db.supplierDao().insertOrUpdate(supplierA)

        db.catalogDao().insertOrUpdate(CatalogItem(id = "aloo", shopId = ShopContext.requireShopId(), name = "आलू", unitId = "KG", price = 30.0))
        stockLedger.record("aloo", "आलू", 10.0, StockReason.STOCK_IN, unitCost = 15.0, refId = "po-1", occurredAtMs = now - 21 * dayMs)
        sellDaily("aloo", "आलू", 5.0, 20)

        db.stockInDao().insert(
            com.voicetoinvoice.app.data.local.entity.StockInRecord(
                shopId = ShopContext.requireShopId(), itemId = "aloo", itemName = "आलू",
                quantity = 10.0, costPrice = 15.0, supplierId = supplierA.id, timestamp = now - 21 * dayMs
            )
        )

        val grouped = advisor.suggestionsBySupplier(now)
        assertTrue(grouped.isNotEmpty())
        assertEquals("Supplier A", grouped.first { it.lines.any { l -> l.itemId == "aloo" } }.supplierName)
    }
}
