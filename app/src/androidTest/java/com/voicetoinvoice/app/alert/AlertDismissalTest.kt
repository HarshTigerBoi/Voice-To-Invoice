package com.voicetoinvoice.app.alert

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voicetoinvoice.app.data.ShopContext
import com.voicetoinvoice.app.data.local.AppDatabase
import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.data.local.entity.StockInRecord
import com.voicetoinvoice.app.domain.alert.AlertEngine
import com.voicetoinvoice.app.domain.alert.AlertType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlertDismissalTest {

    private lateinit var db: AppDatabase
    private lateinit var alertEngine: AlertEngine

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ShopContext.initialize(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        alertEngine = AlertEngine(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedItem(id: String, name: String, stockQty: Double) {
        db.catalogDao().insertOrUpdate(
            CatalogItem(
                id = id,
                shopId = ShopContext.requireShopId(),
                name = name,
                unitId = "KG",
                price = 30.0,
                stockQty = stockQty,
                trackStock = true,
                lastSoldAtMs = System.currentTimeMillis() - 1000
            )
        )
    }

    @Test
    fun snoozedAlertIsHidden() = runTest {
        seedItem("item-1", "आलू", 0.0)
        val initialAlerts = alertEngine.computeAlerts()
        val outOfStockAlert = initialAlerts.first { it.type == AlertType.OUT_OF_STOCK && it.itemId == "item-1" }

        alertEngine.snooze(outOfStockAlert)

        val newAlerts = alertEngine.computeAlerts()
        assertFalse(newAlerts.any { it.type == AlertType.OUT_OF_STOCK && it.itemId == "item-1" })
    }

    @Test
    fun snoozeExpiresAndAlertReturns() = runTest {
        seedItem("item-1", "आलू", 0.0)
        val now = System.currentTimeMillis()
        val initialAlerts = alertEngine.computeAlerts(now)
        val outOfStockAlert = initialAlerts.first { it.type == AlertType.OUT_OF_STOCK && it.itemId == "item-1" }

        alertEngine.snooze(outOfStockAlert, now)

        val after25Hours = now + 25 * 60 * 60 * 1000L
        val alertsAfter25h = alertEngine.computeAlerts(after25Hours)
        assertTrue(alertsAfter25h.any { it.type == AlertType.OUT_OF_STOCK && it.itemId == "item-1" })
    }

    @Test
    fun dismissForeverSurvivesTimeTravel() = runTest {
        seedItem("item-1", "आलू", 0.0)
        val initialAlerts = alertEngine.computeAlerts()
        val outOfStockAlert = initialAlerts.first { it.type == AlertType.OUT_OF_STOCK && it.itemId == "item-1" }

        alertEngine.dismissForever(outOfStockAlert)

        val after1Year = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000L
        val alertsAfter1Year = alertEngine.computeAlerts(after1Year)
        assertFalse(alertsAfter1Year.any { it.type == AlertType.OUT_OF_STOCK && it.itemId == "item-1" })
    }

    @Test
    fun snoozingOneItemDoesNotHideAnother() = runTest {
        seedItem("item-1", "आलू", 0.0)
        seedItem("item-2", "प्याज", 0.0)

        val initialAlerts = alertEngine.computeAlerts()
        val item1Alert = initialAlerts.first { it.itemId == "item-1" }

        alertEngine.snooze(item1Alert)

        val newAlerts = alertEngine.computeAlerts()
        assertFalse(newAlerts.any { it.itemId == "item-1" })
        assertTrue(newAlerts.any { it.itemId == "item-2" })
    }

    @Test
    fun shopWideAlertUsesEmptyItemId() = runTest {
        // Seed 5 stock_in items with cost missing to trigger MISSING_COST alert
        for (i in 1..5) {
            db.stockInDao().insert(
                StockInRecord(
                    id = "stock-$i",
                    shopId = ShopContext.requireShopId(),
                    itemId = "item-$i",
                    itemName = "Item $i",
                    quantity = 10.0,
                    supplier = "Supplier",
                    costPrice = 0.0,
                    costMissing = true
                )
            )
        }

        val initialAlerts = alertEngine.computeAlerts()
        val missingCostAlert = initialAlerts.first { it.type == AlertType.MISSING_COST }

        alertEngine.snooze(missingCostAlert)

        val newAlerts = alertEngine.computeAlerts()
        assertFalse(newAlerts.any { it.type == AlertType.MISSING_COST })
    }
}
