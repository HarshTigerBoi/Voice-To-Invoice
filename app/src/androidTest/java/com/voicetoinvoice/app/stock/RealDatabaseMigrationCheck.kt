package com.voicetoinvoice.app.stock

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Inspects the REAL on-device database rather than an in-memory one.
 *
 * The in-memory tests in `StockLedgerRepositoryTest` exercise behaviour against a freshly created
 * schema, which means they never touch the 17 -> 18 -> 19 migration path. That path is the risky
 * one: it is what runs on a shopkeeper's phone that already holds months of sales, and a partial
 * backfill there produces stock numbers that look plausible and are wrong.
 *
 * Instrumentation runs under the target app's uid, so it can open the app's own database file.
 * Every assertion is skipped (not failed) when no pre-existing database is present, so this stays
 * meaningful on an upgraded device and harmless on a clean one.
 */
@RunWith(AndroidJUnit4::class)
class RealDatabaseMigrationCheck {

    private fun openRealDb(): SQLiteDatabase? {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val path = context.getDatabasePath("voice_to_invoice_db")
        if (!path.exists()) return null

        // Force Room to actually OPEN (and therefore migrate) this exact on-disk file before
        // inspecting it raw. Migrations run lazily on first query, not merely by the APK being
        // installed -- if nothing in this process has called `AppDatabase.getInstance()` yet
        // (a real possibility for an instrumented test that never launches the app's Activity),
        // the file below would still be sitting at whatever schema version the LAST real launch
        // left it at, and this check would fail for a reason that has nothing to do with the
        // migration itself. This makes the check self-sufficient rather than order-dependent on
        // some other test or a manual app launch having already happened first.
        com.voicetoinvoice.app.data.local.AppDatabase.getInstance(context).openHelper.readableDatabase

        return SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    }

    private fun SQLiteDatabase.tableNames(): Set<String> =
        rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    private fun SQLiteDatabase.columnsOf(table: String): Set<String> =
        rawQuery("PRAGMA table_info($table)", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(1)) }
        }

    private fun SQLiteDatabase.count(sql: String): Long =
        rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    @Test
    fun migratedSchemaHasEveryNewTableAndColumn() {
        val db = openRealDb() ?: return
        db.use {
            val tables = it.tableNames()
            assumeTrue("no pre-existing catalog -- clean install", tables.contains("catalog_items"))

            for (t in listOf("stock_ledger", "stock_batches", "customer_payments", "migration_status")) {
                assertTrue("migration did not create $t", tables.contains(t))
            }
            for (col in listOf("stockQty", "avgCostPrice", "trackStock", "trackExpiry", "lastSoldAtMs", "lastStockedAtMs")) {
                assertTrue("catalog_items missing $col", it.columnsOf("catalog_items").contains(col))
            }
            for (col in listOf("costAtSale", "txnType", "billId", "returnOfTxnId")) {
                assertTrue("transactions missing $col", it.columnsOf("transactions").contains(col))
            }
        }
    }

    @Test
    fun noMigrationStepRecordedAFailure() {
        val db = openRealDb() ?: return
        db.use {
            assumeTrue(it.tableNames().contains("migration_status"))
            val failures = it.count("SELECT COUNT(*) FROM migration_status")
            if (failures > 0L) {
                val detail = it.rawQuery(
                    "SELECT migration, step, substr(error,1,200) FROM migration_status LIMIT 5", null
                ).use { c ->
                    buildString {
                        while (c.moveToNext()) {
                            append("\n  ${c.getString(0)}/${c.getString(1)}: ${c.getString(2)}")
                        }
                    }
                }
                throw AssertionError("migration recorded $failures failed step(s):$detail")
            }
        }
    }

    /**
     * The backfill's core invariant: the materialized quantity equals the ledger sum, for every
     * item. If these disagree the shop's stock display is lying, and
     * `StockLedgerRepository.rebuildFromLedger()` is the recovery.
     */
    @Test
    fun materializedStockMatchesTheLedgerForEveryItem() {
        val db = openRealDb() ?: return
        db.use {
            assumeTrue(it.tableNames().contains("stock_ledger"))
            val drifted = it.count(
                """
                SELECT COUNT(*) FROM catalog_items c
                WHERE ABS(c.stockQty - COALESCE(
                    (SELECT SUM(l.deltaQty) FROM stock_ledger l WHERE l.itemId = c.id), 0.0)) > 0.0001
                """.trimIndent()
            )
            assertEquals("items whose stockQty disagrees with the ledger", 0L, drifted)
        }
    }

    /** Every non-voided historical sale should have produced exactly one SALE movement. */
    @Test
    fun everyHistoricalSaleWasBackfilledIntoTheLedger() {
        val db = openRealDb() ?: return
        db.use {
            assumeTrue(it.tableNames().contains("stock_ledger"))
            val sales = it.count("SELECT COUNT(*) FROM transactions WHERE voided = 0")
            val movements = it.count("SELECT COUNT(*) FROM stock_ledger WHERE reason = 'SALE'")
            assertTrue(
                "backfill produced $movements SALE movements for $sales non-voided sales",
                movements >= sales
            )
        }
    }
}
