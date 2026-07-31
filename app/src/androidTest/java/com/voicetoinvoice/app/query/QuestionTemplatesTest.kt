package com.voicetoinvoice.app.query

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voicetoinvoice.app.data.ShopContext
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.data.local.entity.CustomerRecord
import com.voicetoinvoice.app.data.local.entity.PaymentMode
import com.voicetoinvoice.app.data.local.entity.StockReason
import com.voicetoinvoice.app.data.local.entity.TransactionRecord
import com.voicetoinvoice.app.data.repository.StockLedgerRepository
import com.voicetoinvoice.app.domain.parser.PhoneticKey
import com.voicetoinvoice.app.domain.query.LedgerQueries
import com.voicetoinvoice.app.domain.query.QuestionTemplates
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises `QuestionTemplates` against a large, realistic battery of questions in Devanagari,
 * Latin-Hinglish and English -- the kind of full "day in the shop" mix a real shopkeeper produces,
 * not just one phrase per topic. This is the regression guard for the trilingual rewrite: the
 * previous version matched topics with plain Devanagari `String.contains`, so a question the
 * router correctly classified as READ_QUERY (e.g. "aaj ki sale kitni hui") would still fall
 * through every topic branch here and answer "I didn't understand" -- undoing the router's own
 * fix one step later. Every assertion below is against a REAL Room database and the REAL
 * production classes, exercising the same code path a spoken question actually takes (minus only
 * the audio-to-text step, which is Android's own STT API, not this app's code).
 */
@RunWith(AndroidJUnit4::class)
class QuestionTemplatesTest {

    private lateinit var db: AppDatabase
    private lateinit var stockLedger: StockLedgerRepository
    private lateinit var templates: QuestionTemplates

    private val aalooId = "item-aloo"
    private val pyazId = "item-pyaz"

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ShopContext.initialize(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        stockLedger = StockLedgerRepository(db)
        templates = QuestionTemplates(LedgerQueries(db))

        // A realistic day: two items, one sale each, one customer with Udhaar, some waste.
        db.catalogDao().insertOrUpdate(CatalogItem(id = aalooId, shopId = ShopContext.requireShopId(), name = "आलू", unitId = "KG", price = 30.0))
        db.catalogDao().insertOrUpdate(CatalogItem(id = pyazId, shopId = ShopContext.requireShopId(), name = "प्याज", unitId = "KG", price = 25.0))
        stockLedger.record(aalooId, "आलू", 50.0, StockReason.STOCK_IN, unitCost = 20.0, refId = "po-1")
        stockLedger.record(pyazId, "प्याज", 30.0, StockReason.STOCK_IN, unitCost = 15.0, refId = "po-2")

        val ramesh = CustomerRecord(
            shopId = ShopContext.requireShopId(), code = 1, name = "रमेश",
            phoneticKey = PhoneticKey.of("रमेश")
        )
        db.customerDao().insert(ramesh)

        val saleTx = TransactionRecord(
            shopId = ShopContext.requireShopId(), itemId = aalooId, itemName = "आलू",
            quantity = 5.0, priceAtSale = 30.0, total = 150.0, costAtSale = 20.0
        )
        db.transactionDao().insert(saleTx)
        stockLedger.recordSale(aalooId, "आलू", 5.0, saleTx.id, total = 150.0, costAtSale = 20.0)

        val creditTx = TransactionRecord(
            shopId = ShopContext.requireShopId(), itemId = pyazId, itemName = "प्याज",
            quantity = 4.0, priceAtSale = 25.0, total = 100.0, paymentMode = PaymentMode.CREDIT,
            customerId = ramesh.id
        )
        db.transactionDao().insert(creditTx)
        stockLedger.recordSale(pyazId, "प्याज", 4.0, creditTx.id, total = 100.0, costAtSale = null)

        stockLedger.record(aalooId, "आलू", -2.0, StockReason.WASTE, refId = "w-1")
    }

    @After
    fun tearDown() = db.close()

    private fun assertAnswered(transcript: String) = runTest {
        val answer = templates.answerQuestion(transcript)
        assertTrue(
            "\"$transcript\" produced the generic 'unrecognized' fallback instead of an answer",
            answer != "समझ नहीं आया, कृपया फिर से बोलिए"
        )
    }

    // ---------------------------------------------------------------- revenue

    @Test
    fun revenueQuestions_allThreeScripts() {
        assertAnswered("आज कितना कमाया")
        assertAnswered("आज की बिक्री बताओ")
        assertAnswered("aaj kitna kamaya")
        assertAnswered("aaj ki sale kitni hui") // the headline regression
        assertAnswered("what is today sale")
        assertAnswered("tell me today revenue")
        assertAnswered("total business today")
    }

    // ---------------------------------------------------------------- profit

    @Test
    fun profitQuestions_allThreeScripts() {
        assertAnswered("आज का मुनाफ़ा कितना है")
        assertAnswered("aaj ka munafa kitna hai")
        assertAnswered("what is today profit")
        assertAnswered("fayda kitna hua")
    }

    // ---------------------------------------------------------------- stock

    @Test
    fun stockQuestions_allThreeScripts() {
        assertAnswered("आलू कितना बचा है")
        assertAnswered("aaloo kitna bacha hai")
        assertAnswered("how much aaloo is left")
        assertAnswered("pyaz stock kitna hai")
        assertAnswered("onion stock left")
    }

    // ---------------------------------------------------------------- udhaar

    @Test
    fun udhaarQuestions_allThreeScripts() {
        assertAnswered("रमेश का कितना बाकी है")
        assertAnswered("ramesh ka kitna baki hai")
        assertAnswered("how much does ramesh owe")
        assertAnswered("kul udhaar kitna hai")
        assertAnswered("total credit outstanding")
    }

    // ---------------------------------------------------------------- top item

    @Test
    fun topItemQuestions_allThreeScripts() {
        assertAnswered("आज सबसे ज्यादा क्या बिका")
        assertAnswered("sabse zyada kya bika")
        assertAnswered("what is the best selling item today")
    }

    // ---------------------------------------------------------------- waste

    @Test
    fun wasteQuestions_allThreeScripts() {
        assertAnswered("आज कितना माल खराब हुआ")
        assertAnswered("aaj kitna maal kharab hua")
        assertAnswered("how much was wasted today")
    }

    // ---------------------------------------------------------------- generic fallback

    @Test
    fun genericBusinessQuestion_fallsBackToDailySalesRatherThanUnrecognized() {
        assertAnswered("आज कैसा रहा")
        assertAnswered("aaj kaisa raha")
        assertAnswered("how was today")
    }

    /**
     * A full "day in the life" battery -- the exact kind of varied, back-to-back questioning a
     * real shopkeeper produces across a shift, mixing scripts and topics freely. Every one of
     * these must produce a real answer, not the generic fallback.
     */
    @Test
    fun aFullDayOfRealisticQuestions_noneFallBackToUnrecognized() {
        val dayInTheLife = listOf(
            "आज कितना कमाया",
            "aaloo kitna bacha hai",
            "ramesh ka kitna baki hai",
            "what is today profit",
            "pyaz ka stock kitna hai",
            "sabse zyada kya bika aaj",
            "aaj kitna maal kharab hua",
            "kul udhaar kitna hai",
            "how much stock is left for onion",
            "aaj ki sale kitni hui",
            "munafa kitna hua aaj",
            "how was business today",
            "aaj kaisa raha",
            "tell me total revenue today",
            "ramesh owes how much",
        )
        for (q in dayInTheLife) assertAnswered(q)
    }
}
