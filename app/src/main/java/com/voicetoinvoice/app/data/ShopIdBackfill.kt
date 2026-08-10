package com.voicetoinvoice.app.data

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.voicetoinvoice.app.data.local.AppDatabase

/**
 * One-time repair of rows stranded under [ShopContext.LEGACY_SHOP_ID].
 *
 * ## Why this exists
 *
 * Before [ShopContext], every entity hard-coded `shopId = "default_shop"`. [ShopContext] replaced
 * that with a per-install UUID, but nothing ever rewrote the rows already on disk -- so upgrading
 * silently split the shop in two: the whole pre-upgrade ledger and catalog stayed on
 * `"default_shop"` while every new row got the UUID.
 *
 * That was not cosmetic. `CloudSyncManager.syncCatalogItemToCloud` maps `"default_shop"` to a NULL
 * `shop_id` server-side ([com.voicetoinvoice.app.network.SupabaseConfig.getNullSafeShopId]), and
 * `process-voice-job` fetches the catalog with a strict `shop_id = <uuid>` filter. The shop's
 * priced catalog was therefore invisible to the parser: every spoken item resolved as unmatched,
 * catalog-learning re-added it server-side at price 0, and every sale priced out at ₹0 -- which
 * never clears the auto-confirm bar, so no transaction was ever booked. Observed on device
 * 2026-08-06: 74 local catalog rows on `"default_shop"`, 0 rows in `transactions`, and every
 * `stt_job_logs.parsed_total` = 0.
 *
 * ## What it does
 *
 * Rewrites `shopId` from the legacy literal to [ShopContext.requireShopId] across every table that
 * carries one, and clears `catalog_items.synced` so the priced catalog re-uploads under the real
 * shop id. Guarded by a SharedPreferences flag, mirroring the existing `rollup_backfill` pattern.
 *
 * Idempotent and safe to run on a shop that never had legacy rows -- every statement is a no-op
 * when nothing matches.
 */
object ShopIdBackfill {

    private const val TAG = "ShopIdBackfill"
    private const val PREFS = "shop_id_backfill"
    private const val KEY_DONE = "legacy_shop_id_migrated_v1"

    /** Tables whose primary key is `id`, so rewriting `shopId` can never collide. */
    private val SIMPLE_TABLES = listOf(
        "catalog_items",
        "credits",
        "customer_payments",
        "customers",
        "stock_batches",
        "stock_in",
        "stock_ledger",
        "suppliers",
        "transactions",
        "unmatched_queue"
    )

    /**
     * Tables with a composite PRIMARY KEY led by `shopId`, where a legacy row and a current-shop
     * row can already collide on the rest of the key. `daily_rollups` is merged additively (its
     * columns are per-day running totals); the other two carry only recoverable state, so the
     * current-shop row wins and the legacy row is dropped.
     */
    private const val ROLLUPS = "daily_rollups"
    private val KEEP_CURRENT_ON_CONFLICT = listOf(
        "shop_learning" to listOf("kind", "key"),
        "alert_dismissals" to listOf("type", "itemId")
    )

    fun runIfNeeded(context: Context, database: AppDatabase) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return

        val current = ShopContext.shopIdOrNull()
        if (current == null || current == ShopContext.LEGACY_SHOP_ID) {
            // Identity not resolved yet -- do not brand rows with a guess. Retry next launch.
            Log.w(TAG, "Shop identity unresolved; deferring legacy shopId backfill.")
            return
        }

        try {
            val db = database.openHelper.writableDatabase
            db.beginTransaction()
            try {
                var moved = 0
                for (table in SIMPLE_TABLES) {
                    moved += rewrite(db, table, current)
                }
                moved += mergeRollups(db, current)
                for ((table, restOfKey) in KEEP_CURRENT_ON_CONFLICT) {
                    moved += rewriteKeepingCurrent(db, table, restOfKey, current)
                }

                // Force the (now correctly tenanted) catalog back through the sync sweep. Without
                // this the rows stay synced=1 and the server keeps serving the price-0 shadow
                // catalog it built while the real one was invisible.
                if (moved > 0) {
                    db.execSQL("UPDATE `catalog_items` SET `synced` = 0")
                }

                db.setTransactionSuccessful()
                Log.i(TAG, "Legacy shopId backfill complete: $moved row(s) moved to $current")
            } finally {
                db.endTransaction()
            }
            prefs.edit().putBoolean(KEY_DONE, true).apply()
        } catch (e: Exception) {
            // Leave the flag unset so the next launch retries rather than stranding the shop.
            Log.e(TAG, "Legacy shopId backfill failed; will retry on next launch.", e)
        }
    }

    private fun rewrite(db: SupportSQLiteDatabase, table: String, shopId: String): Int = try {
        db.execSQL(
            "UPDATE `$table` SET `shopId` = ? WHERE `shopId` = ?",
            arrayOf(shopId, ShopContext.LEGACY_SHOP_ID)
        )
        changes(db)
    } catch (e: Exception) {
        Log.w(TAG, "Backfill skipped for $table: ${e.message}")
        0
    }

    /**
     * Drops the legacy row wherever the current shop already holds one for the same key, then
     * adopts the rest. Both callers store only regenerable state (learned aliases re-learn, alert
     * snoozes re-arm), so preferring the current-shop row loses nothing that matters.
     */
    private fun rewriteKeepingCurrent(
        db: SupportSQLiteDatabase,
        table: String,
        restOfKey: List<String>,
        shopId: String
    ): Int = try {
        val cols = restOfKey.joinToString(", ") { "`$it`" }
        db.execSQL(
            """
            DELETE FROM `$table` WHERE `shopId` = ? AND ($cols) IN (
                SELECT $cols FROM `$table` WHERE `shopId` = ?
            )
            """.trimIndent(),
            arrayOf(ShopContext.LEGACY_SHOP_ID, shopId)
        )
        db.execSQL(
            "UPDATE `$table` SET `shopId` = ? WHERE `shopId` = ?",
            arrayOf(shopId, ShopContext.LEGACY_SHOP_ID)
        )
        changes(db)
    } catch (e: Exception) {
        Log.w(TAG, "Backfill skipped for $table: ${e.message}")
        0
    }

    /**
     * Rollup columns are per-(shop, day, item) running totals, so a legacy row and a current row
     * for the same day+item must be added together rather than one discarded.
     */
    private fun mergeRollups(db: SupportSQLiteDatabase, shopId: String): Int = try {
        db.execSQL(
            """
            UPDATE `$ROLLUPS` SET
                qtySold = qtySold + COALESCE((SELECT l.qtySold FROM `$ROLLUPS` l
                    WHERE l.shopId = ? AND l.dayStartMs = `$ROLLUPS`.dayStartMs AND l.itemId = `$ROLLUPS`.itemId), 0.0),
                revenue = revenue + COALESCE((SELECT l.revenue FROM `$ROLLUPS` l
                    WHERE l.shopId = ? AND l.dayStartMs = `$ROLLUPS`.dayStartMs AND l.itemId = `$ROLLUPS`.itemId), 0.0),
                cogs = cogs + COALESCE((SELECT l.cogs FROM `$ROLLUPS` l
                    WHERE l.shopId = ? AND l.dayStartMs = `$ROLLUPS`.dayStartMs AND l.itemId = `$ROLLUPS`.itemId), 0.0),
                costKnownRevenue = costKnownRevenue + COALESCE((SELECT l.costKnownRevenue FROM `$ROLLUPS` l
                    WHERE l.shopId = ? AND l.dayStartMs = `$ROLLUPS`.dayStartMs AND l.itemId = `$ROLLUPS`.itemId), 0.0),
                txnCount = txnCount + COALESCE((SELECT l.txnCount FROM `$ROLLUPS` l
                    WHERE l.shopId = ? AND l.dayStartMs = `$ROLLUPS`.dayStartMs AND l.itemId = `$ROLLUPS`.itemId), 0),
                wasteQty = wasteQty + COALESCE((SELECT l.wasteQty FROM `$ROLLUPS` l
                    WHERE l.shopId = ? AND l.dayStartMs = `$ROLLUPS`.dayStartMs AND l.itemId = `$ROLLUPS`.itemId), 0.0),
                wasteValue = wasteValue + COALESCE((SELECT l.wasteValue FROM `$ROLLUPS` l
                    WHERE l.shopId = ? AND l.dayStartMs = `$ROLLUPS`.dayStartMs AND l.itemId = `$ROLLUPS`.itemId), 0.0),
                returnQty = returnQty + COALESCE((SELECT l.returnQty FROM `$ROLLUPS` l
                    WHERE l.shopId = ? AND l.dayStartMs = `$ROLLUPS`.dayStartMs AND l.itemId = `$ROLLUPS`.itemId), 0.0),
                returnValue = returnValue + COALESCE((SELECT l.returnValue FROM `$ROLLUPS` l
                    WHERE l.shopId = ? AND l.dayStartMs = `$ROLLUPS`.dayStartMs AND l.itemId = `$ROLLUPS`.itemId), 0.0),
                stockInQty = stockInQty + COALESCE((SELECT l.stockInQty FROM `$ROLLUPS` l
                    WHERE l.shopId = ? AND l.dayStartMs = `$ROLLUPS`.dayStartMs AND l.itemId = `$ROLLUPS`.itemId), 0.0),
                stockInValue = stockInValue + COALESCE((SELECT l.stockInValue FROM `$ROLLUPS` l
                    WHERE l.shopId = ? AND l.dayStartMs = `$ROLLUPS`.dayStartMs AND l.itemId = `$ROLLUPS`.itemId), 0.0)
            WHERE shopId = ?
            """.trimIndent(),
            Array(11) { ShopContext.LEGACY_SHOP_ID } + arrayOf(shopId)
        )
        // Legacy rows that merged into an existing current-shop row are now double-counted there.
        db.execSQL(
            """
            DELETE FROM `$ROLLUPS` WHERE shopId = ? AND EXISTS (
                SELECT 1 FROM `$ROLLUPS` c WHERE c.shopId = ?
                  AND c.dayStartMs = `$ROLLUPS`.dayStartMs AND c.itemId = `$ROLLUPS`.itemId
            )
            """.trimIndent(),
            arrayOf(ShopContext.LEGACY_SHOP_ID, shopId)
        )
        // Whatever legacy rows remain have no current-shop counterpart -- adopt them outright.
        db.execSQL(
            "UPDATE `$ROLLUPS` SET `shopId` = ? WHERE `shopId` = ?",
            arrayOf(shopId, ShopContext.LEGACY_SHOP_ID)
        )
        changes(db)
    } catch (e: Exception) {
        Log.w(TAG, "Backfill skipped for $ROLLUPS: ${e.message}")
        0
    }

    private fun changes(db: SupportSQLiteDatabase): Int = try {
        db.query("SELECT changes()").use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    } catch (_: Exception) {
        0
    }
}
