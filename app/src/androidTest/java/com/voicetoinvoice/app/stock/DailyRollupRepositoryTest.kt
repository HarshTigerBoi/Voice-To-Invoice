package com.voicetoinvoice.app.stock

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voicetoinvoice.app.data.ShopContext
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.data.local.entity.StockReason
import com.voicetoinvoice.app.data.local.entity.TransactionRecord
import com.voicetoinvoice.app.data.repository.DailyRollupRepository
import com.voicetoinvoice.app.data.repository.StockLedgerRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

/**
 * `DailyRollupRepository` is a CACHE over `transactions`/`stock_ledger` -- these tests check
 * both that the incremental writer produces the same totals as a from-scratch aggregate, and
 * that [DailyRollupRepository.repairRange] (the self-healing path, see its class doc for why
 * there is no separate scheduled repair worker) reproduces the same numbers from source data.
 */
@RunWith(AndroidJUnit4::class)
class DailyRollupRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var stockLedger: StockLedgerRepository
    private lateinit var rollups: DailyRollupRepository

    private val itemId = "item-aloo"
    private val itemName = "आलू"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ShopContext.initialize(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        stockLedger = StockLedgerRepository(db)
        rollups = DailyRollupRepository(db)
    }

    private fun midnightOf(ts: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ts
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private suspend fun seedItem() {
        db.catalogDao().insertOrUpdate(
            CatalogItem(id = itemId, shopId = ShopContext.requireShopId(), name = itemName, unitId = "KG", price = 30.0)
        )
    }

    private suspend fun bookSale(qty: Double, total: Double, costAtSale: Double?, ts: Long): TransactionRecord {
        val tx = TransactionRecord(
            shopId = ShopContext.requireShopId(), itemId = itemId, itemName = itemName,
            quantity = qty, priceAtSale = total / qty, total = total, costAtSale = costAtSale, timestamp = ts
        )
        db.transactionDao().insert(tx)
        stockLedger.recordSale(itemId, itemName, qty, tx.id, ts, total, costAtSale)
        return tx
    }

    @Test
    fun saleIncrementsTodaysRollup() = runTest {
        seedItem()
        val now = System.currentTimeMillis()
        bookSale(3.0, 90.0, 20.0, now)

        val totals = rollups.getTotals(midnightOf(now), midnightOf(now) + 86_400_000L)
        assertEquals(90.0, totals.revenue, 1e-9)
        assertEquals(60.0, totals.cogs, 1e-9) // 3 * 20
        assertEquals(90.0, totals.costKnownRevenue, 1e-9)
        assertEquals(1, totals.txnCount)
    }

    @Test
    fun voidingASaleReversesTheOriginalDaysRollupNotTodays() = runTest {
        seedItem()
        // Sale happened "yesterday"; void happens "now" (today). The reversal must land on
        // YESTERDAY's rollup, or both days end up wrong -- see StockLedgerRepository.voidSale.
        val yesterday = System.currentTimeMillis() - 25 * 60 * 60 * 1000L
        val tx = bookSale(2.0, 60.0, 15.0, yesterday)

        stockLedger.voidSale(tx.id)

        val yesterdayTotals = rollups.getTotals(midnightOf(yesterday), midnightOf(yesterday) + 86_400_000L)
        assertEquals(0.0, yesterdayTotals.revenue, 1e-9)
        assertEquals(0.0, yesterdayTotals.cogs, 1e-9)
        assertEquals(0, yesterdayTotals.txnCount)

        val todayTotals = rollups.getTotals(midnightOf(System.currentTimeMillis()), midnightOf(System.currentTimeMillis()) + 86_400_000L)
        assertEquals("today's rollup must be untouched by yesterday's void", 0.0, todayTotals.revenue, 1e-9)
    }

    @Test
    fun uncostedSaleContributesRevenueButNotCogsOrCostKnownRevenue() = runTest {
        seedItem()
        val now = System.currentTimeMillis()
        bookSale(4.0, 100.0, null, now)

        val totals = rollups.getTotals(midnightOf(now), midnightOf(now) + 86_400_000L)
        assertEquals(100.0, totals.revenue, 1e-9)
        assertEquals(0.0, totals.cogs, 1e-9)
        assertEquals(0.0, totals.costKnownRevenue, 1e-9) // excluded, not treated as free
    }

    @Test
    fun wasteAndStockInAreTrackedSeparatelyFromSales() = runTest {
        seedItem()
        val now = System.currentTimeMillis()
        stockLedger.record(itemId, itemName, 10.0, StockReason.STOCK_IN, unitCost = 12.0, refId = "po-1", occurredAtMs = now)
        stockLedger.record(itemId, itemName, -2.0, StockReason.WASTE, refId = "w-1", occurredAtMs = now)

        val totals = rollups.getTotals(midnightOf(now), midnightOf(now) + 86_400_000L)
        assertEquals(120.0, totals.stockInValue, 1e-9) // 10 * 12
        assertEquals(2.0 * 12.0, totals.wasteValue, 1e-9) // valued at avgCostPrice since no explicit unitCost
        assertEquals(0.0, totals.revenue, 1e-9) // waste/stock-in are not sales
    }

    @Test
    fun topByRevenueRanksCorrectly() = runTest {
        seedItem()
        db.catalogDao().insertOrUpdate(CatalogItem(id = "item-b", shopId = ShopContext.requireShopId(), name = "प्याज", unitId = "KG", price = 20.0))
        val now = System.currentTimeMillis()
        bookSale(3.0, 90.0, 20.0, now)
        val tx2 = TransactionRecord(shopId = ShopContext.requireShopId(), itemId = "item-b", itemName = "प्याज", quantity = 10.0, priceAtSale = 20.0, total = 200.0, timestamp = now)
        db.transactionDao().insert(tx2)
        stockLedger.recordSale("item-b", "प्याज", 10.0, tx2.id, now, 200.0, null)

        val top = rollups.getTopByRevenue(midnightOf(now), midnightOf(now) + 86_400_000L)
        assertEquals("प्याज", top.first().itemName) // 200 > 90
        assertEquals(2, top.size)
    }

    @Test
    fun repairRangeReproducesTheSameTotalsAsIncrementalWrites() = runTest {
        seedItem()
        val now = System.currentTimeMillis()
        stockLedger.record(itemId, itemName, 10.0, StockReason.STOCK_IN, unitCost = 20.0, refId = "po-1", occurredAtMs = now)
        val tx = bookSale(3.0, 90.0, 20.0, now)
        stockLedger.record(itemId, itemName, -1.0, StockReason.WASTE, refId = "w-1", occurredAtMs = now)

        val start = midnightOf(now)
        val end = start + 86_400_000L
        val incremental = rollups.getTotals(start, end)

        // Simulate drift: corrupt the cached rollup directly.
        db.dailyRollupDao().deleteRange(start, end)
        val afterDelete = rollups.getTotals(start, end)
        assertEquals(0.0, afterDelete.revenue, 1e-9)

        rollups.repairRange(start, end)
        val repaired = rollups.getTotals(start, end)

        assertEquals(incremental.revenue, repaired.revenue, 1e-9)
        assertEquals(incremental.cogs, repaired.cogs, 1e-9)
        assertEquals(incremental.stockInValue, repaired.stockInValue, 1e-9)
        assertEquals(incremental.wasteValue, repaired.wasteValue, 1e-9)
        assertTrue("sanity: tx actually exists", db.transactionDao().getById(tx.id) != null)
    }

    @Test
    fun repairIsIdempotentWhenRunTwice() = runTest {
        seedItem()
        val now = System.currentTimeMillis()
        bookSale(5.0, 150.0, 30.0, now)
        val start = midnightOf(now)
        val end = start + 86_400_000L

        rollups.repairRange(start, end)
        val first = rollups.getTotals(start, end)
        rollups.repairRange(start, end)
        val second = rollups.getTotals(start, end)

        assertEquals(first.revenue, second.revenue, 1e-9)
        assertEquals(150.0, second.revenue, 1e-9)
    }
}
