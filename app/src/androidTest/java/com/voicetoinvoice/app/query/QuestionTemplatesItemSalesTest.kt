package com.voicetoinvoice.app.query

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voicetoinvoice.app.data.ShopContext
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.data.local.entity.StockReason
import com.voicetoinvoice.app.data.local.entity.TransactionRecord
import com.voicetoinvoice.app.data.repository.StockLedgerRepository
import com.voicetoinvoice.app.domain.query.LedgerQueries
import com.voicetoinvoice.app.domain.query.QuestionTemplates
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ISSUE-118: Regression test for item-scoped sales queries ("आज कितने आलू बिके").
 *
 * Verifies that:
 * 1. "आज कितने आलू बिके" returns the item name and NOT the whole-shop revenue phrasing ("का व्यापार हुआ").
 * 2. "आज कितना बिका" (no item named) falls through to whole-shop revenue ("का व्यापार हुआ").
 * 3. "aaj kitne aaloo bike" (Latin) returns the item name and NOT whole-shop revenue.
 */
@RunWith(AndroidJUnit4::class)
class QuestionTemplatesItemSalesTest {

    private lateinit var db: AppDatabase
    private lateinit var stockLedger: StockLedgerRepository
    private lateinit var templates: QuestionTemplates

    private val aalooId = "item-aloo"

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ShopContext.initialize(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        stockLedger = StockLedgerRepository(db)
        templates = QuestionTemplates(LedgerQueries(db))

        db.catalogDao().insertOrUpdate(
            CatalogItem(id = aalooId, shopId = ShopContext.requireShopId(), name = "आलू", unitId = "KG", price = 30.0)
        )
        stockLedger.record(aalooId, "आलू", 50.0, StockReason.STOCK_IN, unitCost = 20.0, refId = "po-1")

        val saleTx = TransactionRecord(
            shopId = ShopContext.requireShopId(), itemId = aalooId, itemName = "आलू",
            quantity = 5.0, priceAtSale = 30.0, total = 150.0, costAtSale = 20.0
        )
        db.transactionDao().insert(saleTx)
        stockLedger.recordSale(aalooId, "आलू", 5.0, saleTx.id, total = 150.0, costAtSale = 20.0)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun itemSalesQuery_devanagari_returnsItemNameAndNotWholeShopRevenue() = runTest {
        val answer = templates.answerQuestion("आज कितने आलू बिके")
        assertTrue("Expected answer to contain item name 'आलू', got: $answer", answer.contains("आलू"))
        assertFalse("Expected answer NOT to contain whole-shop revenue phrasing 'का व्यापार हुआ', got: $answer", answer.contains("का व्यापार हुआ"))
    }

    @Test
    fun genericRevenueQuery_noItemNamed_returnsWholeShopRevenue() = runTest {
        val answer = templates.answerQuestion("आज कितना बिका")
        assertTrue("Expected generic query to contain whole-shop revenue phrasing 'का व्यापार हुआ', got: $answer", answer.contains("का व्यापार हुआ"))
    }

    @Test
    fun itemSalesQuery_latin_returnsItemNameAndNotWholeShopRevenue() = runTest {
        val answer = templates.answerQuestion("aaj kitne aaloo bike")
        assertTrue("Expected answer to contain item name 'आलू', got: $answer", answer.contains("आलू"))
        assertFalse("Expected answer NOT to contain whole-shop revenue phrasing 'का व्यापार हुआ', got: $answer", answer.contains("का व्यापार हुआ"))
    }
}
