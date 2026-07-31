package com.voicetoinvoice.app.stock

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voicetoinvoice.app.data.ShopContext
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.data.local.entity.StockReason
import com.voicetoinvoice.app.data.local.entity.TransactionRecord
import com.voicetoinvoice.app.data.repository.StockLedgerRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Behaviour of the stock ledger against a real Room database.
 *
 * The headline case is [voidingASaleReturnsTheStock]: before the ledger existed, on-hand was
 * derived as `SUM(stock_in) - SUM(transactions)` WITHOUT a `voided = 0` filter, so voiding a
 * wrongly-booked sale never gave the stock back and the item stayed permanently short. Voiding
 * is the correction signal for Learned Parse Memory (ISSUE-031), so that path is hot.
 */
@RunWith(AndroidJUnit4::class)
class StockLedgerRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: StockLedgerRepository

    private val itemId = "item-aloo"
    private val itemName = "आलू"

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ShopContext.initialize(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = StockLedgerRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedItem(price: Double = 30.0) {
        db.catalogDao().insertOrUpdate(
            CatalogItem(
                id = itemId,
                shopId = ShopContext.requireShopId(),
                name = itemName,
                unitId = "KG",
                price = price
            )
        )
    }

    private suspend fun onHand(): Double = db.catalogDao().getStockQty(itemId) ?: 0.0
    private suspend fun avgCost(): Double? = db.catalogDao().getById(itemId)?.avgCostPrice

    private suspend fun bookSale(qty: Double, unitPrice: Double = 30.0): TransactionRecord {
        val tx = TransactionRecord(
            shopId = ShopContext.requireShopId(),
            itemId = itemId,
            itemName = itemName,
            quantity = qty,
            priceAtSale = unitPrice,
            total = qty * unitPrice,
            costAtSale = avgCost()
        )
        db.transactionDao().insert(tx)
        repo.recordSale(itemId, itemName, qty, tx.id)
        return tx
    }

    @Test
    fun stockInThenSaleLeavesTheDifference() = runTest {
        seedItem()
        repo.record(itemId, itemName, 10.0, StockReason.STOCK_IN, unitCost = 20.0, refId = "po-1")
        assertEquals(10.0, onHand(), 1e-9)

        bookSale(3.0)
        assertEquals(7.0, onHand(), 1e-9)
    }

    /**
     * The regression this whole phase exists for.
     */
    @Test
    fun voidingASaleReturnsTheStock() = runTest {
        seedItem()
        repo.record(itemId, itemName, 10.0, StockReason.STOCK_IN, unitCost = 20.0, refId = "po-1")
        val sale = bookSale(3.0)
        assertEquals(7.0, onHand(), 1e-9)

        val voided = repo.voidSale(sale.id)
        assertTrue("voidSale should report success", voided)

        // 10, not 7: the sale is reversed and the goods are back on the shelf.
        assertEquals(10.0, onHand(), 1e-9)
        assertEquals(true, db.transactionDao().getById(sale.id)?.voided)
    }

    @Test
    fun voidingTheSameSaleTwiceDoesNotDoubleCredit() = runTest {
        seedItem()
        repo.record(itemId, itemName, 10.0, StockReason.STOCK_IN, unitCost = 20.0, refId = "po-1")
        val sale = bookSale(4.0)

        assertTrue(repo.voidSale(sale.id))
        assertEquals(10.0, onHand(), 1e-9)

        // Second void must be a no-op, not another +4.
        assertEquals(false, repo.voidSale(sale.id))
        assertEquals(10.0, onHand(), 1e-9)
    }

    @Test
    fun duplicateSaleMovementForTheSameTransactionIsIgnored() = runTest {
        seedItem()
        repo.record(itemId, itemName, 10.0, StockReason.STOCK_IN, unitCost = 20.0, refId = "po-1")
        val tx = bookSale(2.0)
        assertEquals(8.0, onHand(), 1e-9)

        // Simulates a WorkManager retry re-delivering the same job (ISSUE-045 saw one recording
        // reach the server twice). The refId guard must stop a second deduction.
        repo.recordSale(itemId, itemName, 2.0, tx.id)
        assertEquals(8.0, onHand(), 1e-9)
    }

    @Test
    fun wasteReducesStockWithoutCreatingAPurchase() = runTest {
        seedItem()
        repo.record(itemId, itemName, 10.0, StockReason.STOCK_IN, unitCost = 20.0, refId = "po-1")

        repo.record(itemId, itemName, -2.0, StockReason.WASTE, refId = "job-1-0")

        assertEquals(8.0, onHand(), 1e-9)
        // Spoilage must not look like a purchase: no stock_in row, and cost is unaffected.
        assertEquals(0, db.stockInDao().getAllStockInRecordsList().size)
        assertEquals(20.0, avgCost()!!, 1e-9)
    }

    @Test
    fun weightedAverageCostTracksPurchasesAndIgnoresUnknownCost() = runTest {
        seedItem()
        repo.record(itemId, itemName, 10.0, StockReason.STOCK_IN, unitCost = 20.0, refId = "po-1")
        assertEquals(20.0, avgCost()!!, 1e-9)

        repo.record(itemId, itemName, 10.0, StockReason.STOCK_IN, unitCost = 30.0, refId = "po-2")
        assertEquals(25.0, avgCost()!!, 1e-9)

        // A costMissing stock-in: quantity is trusted, cost is unknown and must not move the
        // average toward zero.
        repo.record(itemId, itemName, 5.0, StockReason.STOCK_IN, unitCost = null, refId = "po-3")
        assertEquals(25.0, avgCost()!!, 1e-9)
        assertEquals(25.0, onHand(), 1e-9)
    }

    @Test
    fun costRemainsNullUntilSomethingIsActuallyCosted() = runTest {
        seedItem()
        repo.record(itemId, itemName, 6.0, StockReason.STOCK_IN, unitCost = null, refId = "po-1")
        // Null, not 0.0 -- a zero cost would report 100% margin on these goods.
        assertNull(avgCost())
        assertEquals(6.0, onHand(), 1e-9)
    }

    @Test
    fun saleSnapshotsCostAtSaleAndSurvivesLaterCostChanges() = runTest {
        seedItem()
        repo.record(itemId, itemName, 10.0, StockReason.STOCK_IN, unitCost = 20.0, refId = "po-1")
        val sale = bookSale(2.0)
        assertEquals(20.0, db.transactionDao().getById(sale.id)?.costAtSale!!, 1e-9)

        // Cost rises later; the historical sale must keep its original snapshot, because
        // applying today's cost to an old sale is nonsense for produce.
        repo.record(itemId, itemName, 10.0, StockReason.STOCK_IN, unitCost = 40.0, refId = "po-2")
        assertEquals(20.0, db.transactionDao().getById(sale.id)?.costAtSale!!, 1e-9)
    }

    @Test
    fun rebuildFromLedgerReproducesMaterializedQuantity() = runTest {
        seedItem()
        repo.record(itemId, itemName, 12.0, StockReason.STOCK_IN, unitCost = 10.0, refId = "po-1")
        bookSale(5.0)
        repo.record(itemId, itemName, -1.0, StockReason.WASTE, refId = "w-1")
        assertEquals(6.0, onHand(), 1e-9)

        // Simulate drift: a process killed between the ledger insert and the counter update.
        db.catalogDao().setStockQty(itemId, 999.0)
        assertEquals(999.0, onHand(), 1e-9)

        repo.rebuildFromLedger()
        assertEquals(6.0, onHand(), 1e-9)
        assertEquals(10.0, avgCost()!!, 1e-9)
    }

    @Test
    fun zeroDeltaIsRejectedRatherThanWritingAnEmptyMovement() = runTest {
        seedItem()
        assertNull(repo.record(itemId, itemName, 0.0, StockReason.MANUAL_ADJUST))
        assertEquals(0, db.stockLedgerDao().getForItem(itemId).size)
    }

    /**
     * The manual stock-in screen collects an INVOICE TOTAL, but `StockInRecord.costPrice` stores a
     * per-unit cost. Mixing those two conventions in one column was ISSUE-054, and it made voice
     * stock-in costs wrong by a factor of quantity (which inflated reported profit).
     */
    @Test
    fun invoiceTotalIsConvertedToPerUnitCost() = runTest {
        seedItem()
        // ₹200 for 10 kg -> ₹20/kg, not ₹200/kg.
        val unitCost = repo.recordPurchaseFromInvoiceTotal(itemId, itemName, qty = 10.0, invoiceTotal = 200.0)
        assertEquals(20.0, unitCost!!, 1e-9)
        assertEquals(20.0, avgCost()!!, 1e-9)
        assertEquals(10.0, onHand(), 1e-9)

        val stored = db.stockInDao().getAllStockInRecordsList().first()
        assertEquals(20.0, stored.costPrice, 1e-9)
        assertEquals(false, stored.costMissing)
    }

    @Test
    fun purchaseWithNoStatedCostIsFlaggedRatherThanCostedAtZero() = runTest {
        seedItem()
        val unitCost = repo.recordPurchaseFromInvoiceTotal(itemId, itemName, qty = 5.0, invoiceTotal = 0.0)
        assertNull(unitCost)
        // Quantity is trusted, cost is unknown -- and unknown must not become 0.0, which would
        // report 100% margin on these goods.
        assertEquals(5.0, onHand(), 1e-9)
        assertNull(avgCost())
        assertEquals(true, db.stockInDao().getAllStockInRecordsList().first().costMissing)
    }

    @Test
    fun ledgerRecordsAnAuditTrailWithReasons() = runTest {
        seedItem()
        repo.record(itemId, itemName, 10.0, StockReason.STOCK_IN, unitCost = 15.0, refId = "po-1")
        val sale = bookSale(3.0)
        repo.voidSale(sale.id)

        val entries = db.stockLedgerDao().getForItem(itemId)
        assertEquals(3, entries.size)
        val reasons = entries.map { it.reason }.toSet()
        assertTrue(reasons.contains(StockReason.STOCK_IN))
        assertTrue(reasons.contains(StockReason.SALE))
        assertTrue(reasons.contains(StockReason.SALE_VOID))
        assertNotNull(entries.first { it.reason == StockReason.STOCK_IN }.unitCost)
    }

    @Test
    fun expiryWriteOffReducesStockAndZeroesBatch() = runTest {
        val expiryItem = "item-expiry-1"
        db.catalogDao().insertOrUpdate(
            CatalogItem(
                id = expiryItem,
                shopId = ShopContext.requireShopId(),
                name = "Expiry Item",
                unitId = "KG",
                price = 40.0,
                trackExpiry = true
            )
        )
        val yesterday = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        repo.recordPurchaseFromInvoiceTotal(expiryItem, "Expiry Item", 10.0, 400.0, expiryDateMs = yesterday)

        val alertEngine = com.voicetoinvoice.app.domain.alert.AlertEngine(db)
        val writtenOff = alertEngine.writeOffExpired(expiryItem, repo)

        assertEquals(10.0, writtenOff, 1e-9)
        assertEquals(0.0, db.catalogDao().getById(expiryItem)?.stockQty ?: 0.0, 1e-9)
        val batch = db.stockBatchDao().getExpiringBefore(System.currentTimeMillis()).first { it.itemId == expiryItem }
        assertEquals(0.0, batch.remainingQty, 1e-9)
        val ledger = db.stockLedgerDao().getForItem(expiryItem)
        assertTrue(ledger.any { it.reason == StockReason.EXPIRY })
    }

    @Test
    fun expiryWriteOffIsIdempotent() = runTest {
        val expiryItem = "item-expiry-2"
        db.catalogDao().insertOrUpdate(
            CatalogItem(
                id = expiryItem,
                shopId = ShopContext.requireShopId(),
                name = "Expiry Item 2",
                unitId = "KG",
                price = 40.0,
                trackExpiry = true
            )
        )
        val yesterday = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        repo.recordPurchaseFromInvoiceTotal(expiryItem, "Expiry Item 2", 10.0, 400.0, expiryDateMs = yesterday)

        val alertEngine = com.voicetoinvoice.app.domain.alert.AlertEngine(db)
        alertEngine.writeOffExpired(expiryItem, repo)
        val secondCall = alertEngine.writeOffExpired(expiryItem, repo)

        assertEquals(0.0, secondCall, 1e-9)
        val expiryEntries = db.stockLedgerDao().getForItem(expiryItem).filter { it.reason == StockReason.EXPIRY }
        assertEquals(1, expiryEntries.size)
    }

    @Test
    fun expiryWriteOffIgnoresUnexpiredBatches() = runTest {
        val expiryItem = "item-expiry-3"
        db.catalogDao().insertOrUpdate(
            CatalogItem(
                id = expiryItem,
                shopId = ShopContext.requireShopId(),
                name = "Expiry Item 3",
                unitId = "KG",
                price = 40.0,
                trackExpiry = true
            )
        )
        val nextMonth = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000L
        repo.recordPurchaseFromInvoiceTotal(expiryItem, "Expiry Item 3", 10.0, 400.0, expiryDateMs = nextMonth)

        val alertEngine = com.voicetoinvoice.app.domain.alert.AlertEngine(db)
        val writtenOff = alertEngine.writeOffExpired(expiryItem, repo)

        assertEquals(0.0, writtenOff, 1e-9)
        assertEquals(10.0, db.catalogDao().getById(expiryItem)?.stockQty ?: 0.0, 1e-9)
    }

    @Test
    fun stockInWithExpiryCreatesBatch() = runTest {
        val optInItem = "opt-in"
        val optOutItem = "opt-out"
        db.catalogDao().insertOrUpdate(CatalogItem(id = optInItem, shopId = ShopContext.requireShopId(), name = "Opt In", unitId = "KG", price = 10.0, trackExpiry = true))
        db.catalogDao().insertOrUpdate(CatalogItem(id = optOutItem, shopId = ShopContext.requireShopId(), name = "Opt Out", unitId = "KG", price = 10.0, trackExpiry = false))

        val nextMonth = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000L
        repo.recordPurchaseFromInvoiceTotal(optInItem, "Opt In", 5.0, 50.0, expiryDateMs = nextMonth)
        repo.recordPurchaseFromInvoiceTotal(optOutItem, "Opt Out", 5.0, 50.0, expiryDateMs = nextMonth)

        val batchesIn = db.stockBatchDao().getExpiringBefore(nextMonth + 1000).filter { it.itemId == optInItem }
        val batchesOut = db.stockBatchDao().getExpiringBefore(nextMonth + 1000).filter { it.itemId == optOutItem }

        assertEquals(1, batchesIn.size)
        assertEquals(nextMonth, batchesIn.first().expiryDateMs)
        assertEquals(0, batchesOut.size)
    }
}
