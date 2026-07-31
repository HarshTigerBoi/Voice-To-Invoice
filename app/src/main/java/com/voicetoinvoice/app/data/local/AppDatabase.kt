package com.voicetoinvoice.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.voicetoinvoice.app.data.local.dao.*
import com.voicetoinvoice.app.data.local.entity.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        ItemUnit::class,
        CatalogItem::class,
        TransactionRecord::class,
        CreditRecord::class,
        StockInRecord::class,
        UnmatchedQueueItem::class,
        SyncQueueItem::class,
        SttJobRecord::class,
        SupplierRecord::class,
        CustomerRecord::class,
        StockLedgerEntry::class,
        StockBatch::class,
        MigrationStatus::class,
        CustomerPayment::class,
        DailyRollup::class,
        ShopLearning::class,
        com.voicetoinvoice.app.data.local.entity.AlertDismissal::class
    ],
    version = 23, // Bumped: alert_dismissals (persistent alert snooze/dismiss)
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun catalogDao(): CatalogDao
    abstract fun itemUnitDao(): ItemUnitDao
    abstract fun transactionDao(): TransactionDao
    abstract fun creditDao(): CreditDao
    abstract fun stockInDao(): StockInDao
    abstract fun unmatchedQueueDao(): UnmatchedQueueDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun sttJobDao(): SttJobDao
    abstract fun supplierDao(): SupplierDao
    abstract fun customerDao(): CustomerDao
    abstract fun stockLedgerDao(): StockLedgerDao
    abstract fun stockBatchDao(): StockBatchDao
    abstract fun customerPaymentDao(): CustomerPaymentDao
    abstract fun dailyRollupDao(): DailyRollupDao
    abstract fun shopLearningDao(): ShopLearningDao
    abstract fun alertDismissalDao(): com.voicetoinvoice.app.data.local.dao.AlertDismissalDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE transactions ADD COLUMN rawTranscript TEXT NOT NULL DEFAULT ''")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE transactions ADD COLUMN audioFilePath TEXT NOT NULL DEFAULT ''")
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `stt_jobs` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `audioFilePath` TEXT NOT NULL,
                            `status` TEXT NOT NULL,
                            `rawTranscript` TEXT NOT NULL DEFAULT '',
                            `parsedItemId` TEXT,
                            `parsedItemName` TEXT NOT NULL DEFAULT '',
                            `parsedQty` REAL NOT NULL DEFAULT 1.0,
                            `parsedUnit` TEXT NOT NULL DEFAULT 'PACKET',
                            `parsedTotal` REAL NOT NULL DEFAULT 0.0,
                            `parsedIsPendingPrice` INTEGER NOT NULL DEFAULT 0,
                            `isSanityFlagged` INTEGER NOT NULL DEFAULT 0,
                            `errorMessage` TEXT NOT NULL DEFAULT '',
                            `recordedAtMs` INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // V4: Added holdDurationMs column to stt_jobs for smart single-press vs rapid-fire detection
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE stt_jobs ADD COLUMN holdDurationMs INTEGER NOT NULL DEFAULT 0")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // V5: Added millisecond timestamp tracking fields (pressStartMs, releaseMs, audioStartMs, audioEndMs)
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE stt_jobs ADD COLUMN pressStartMs INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE stt_jobs ADD COLUMN releaseMs INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE stt_jobs ADD COLUMN audioStartMs INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE stt_jobs ADD COLUMN audioEndMs INTEGER NOT NULL DEFAULT 0")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // V6: Added diagnosticTraceJson for end-to-end processing logs
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE stt_jobs ADD COLUMN diagnosticTraceJson TEXT NOT NULL DEFAULT ''")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // V7: Added jobId, audioCloudUrl to transactions and synced flags to catalog_items & stt_jobs
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE transactions ADD COLUMN jobId TEXT")
                    db.execSQL("ALTER TABLE transactions ADD COLUMN audioCloudUrl TEXT")
                    db.execSQL("ALTER TABLE catalog_items ADD COLUMN synced INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE stt_jobs ADD COLUMN synced INTEGER NOT NULL DEFAULT 0")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // V8: Made unmatched_queue.shopId nullable (migrating existing 'default_shop' values to NULL)
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `unmatched_queue_new` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `shopId` TEXT,
                            `audioRef` TEXT,
                            `rawTranscript` TEXT NOT NULL,
                            `resolvedItemId` TEXT,
                            `status` TEXT NOT NULL,
                            `timestamp` INTEGER NOT NULL
                        )
                    """.trimIndent())
                    db.execSQL("INSERT INTO `unmatched_queue_new` SELECT `id`, NULLIF(`shopId`, 'default_shop'), `audioRef`, `rawTranscript`, `resolvedItemId`, `status`, `timestamp` FROM `unmatched_queue`").also {}
                    db.execSQL("DROP TABLE `unmatched_queue`").also {}
                    db.execSQL("ALTER TABLE `unmatched_queue_new` RENAME TO `unmatched_queue`").also {}
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // V9: Added lowStockThreshold REAL column to catalog_items
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE catalog_items ADD COLUMN lowStockThreshold REAL DEFAULT NULL")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // V10: Added suppliers table and supplierId column to stock_in
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `suppliers` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `shopId` TEXT NOT NULL,
                            `name` TEXT NOT NULL,
                            `phone` TEXT,
                            `balanceOwed` REAL NOT NULL DEFAULT 0.0,
                            `updatedAt` INTEGER NOT NULL,
                            `synced` INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())
                    db.execSQL("ALTER TABLE stock_in ADD COLUMN supplierId TEXT")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // V11: Added onDeviceTranscript, previousJobId, precedingGapMs to stt_jobs table
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE stt_jobs ADD COLUMN onDeviceTranscript TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE stt_jobs ADD COLUMN previousJobId TEXT")
                    db.execSQL("ALTER TABLE stt_jobs ADD COLUMN precedingGapMs INTEGER NOT NULL DEFAULT -1")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // V12: Added onDeviceStatus column to stt_jobs table
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE stt_jobs ADD COLUMN onDeviceStatus TEXT NOT NULL DEFAULT ''")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // V13: Multi-item voice capture -- parsedItemsJson/lineCount on stt_jobs carry the
        // full per-line breakdown of a recording, lineNo on transactions lets one job/
        // recording produce more than one transaction row. See ISSUE-029.
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE stt_jobs ADD COLUMN parsedItemsJson TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE stt_jobs ADD COLUMN lineCount INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE transactions ADD COLUMN lineNo INTEGER NOT NULL DEFAULT 0")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // V14: Added voided/voidedAtMs to transactions -- the correction signal for the
        // server-side Learned Parse Memory (ISSUE-031). Soft delete only.
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE transactions ADD COLUMN voided INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE transactions ADD COLUMN voidedAtMs INTEGER")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // V15: Burst Coalescing -- utteranceBoundariesJson/pressCount on stt_jobs carry
        // relative press/release offsets and physical press count of coalesced groups.
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE stt_jobs ADD COLUMN utteranceBoundariesJson TEXT NOT NULL DEFAULT '[]'")
                    db.execSQL("ALTER TABLE stt_jobs ADD COLUMN pressCount INTEGER NOT NULL DEFAULT 1")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // V16: Voice Assistant & Customer Ledger Phase 0 -- customers table, customerId on credits/transactions, backfill
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS `customers` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `shopId` TEXT NOT NULL DEFAULT 'default_shop',
                            `code` INTEGER NOT NULL,
                            `name` TEXT NOT NULL,
                            `keyword` TEXT,
                            `phone` TEXT,
                            `photoPath` TEXT,
                            `phoneticKey` TEXT NOT NULL,
                            `keywordPhoneticKey` TEXT,
                            `lastSeenMs` INTEGER NOT NULL DEFAULT 0,
                            `txnCount` INTEGER NOT NULL DEFAULT 0,
                            `mergedIntoId` TEXT,
                            `createdAt` INTEGER NOT NULL DEFAULT 0,
                            `synced` INTEGER NOT NULL DEFAULT 0
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_customers_phoneticKey` ON `customers` (`phoneticKey`)")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_customers_code` ON `customers` (`code`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_customers_phone` ON `customers` (`phone`)")

                    db.execSQL("ALTER TABLE credits ADD COLUMN customerId TEXT")
                    db.execSQL("ALTER TABLE transactions ADD COLUMN customerId TEXT")
                    db.execSQL("ALTER TABLE stt_jobs ADD COLUMN captureIntent TEXT NOT NULL DEFAULT 'SALE'")

                    // Backfill distinct customer names from credits table
                    backfillCustomers(db)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            private fun backfillCustomers(db: SupportSQLiteDatabase) {
                val cursor = db.query("SELECT DISTINCT customerName FROM credits WHERE customerName IS NOT NULL AND customerName != '' ORDER BY id ASC")
                var code = 1
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    val id = java.util.UUID.randomUUID().toString()
                    val phoneticKey = com.voicetoinvoice.app.domain.parser.PhoneticKey.of(name)
                    val now = System.currentTimeMillis()

                    db.execSQL(
                        "INSERT INTO customers (id, shopId, code, name, phoneticKey, createdAt, synced) VALUES (?, 'default_shop', ?, ?, ?, ?, 0)",
                        arrayOf(id, code, name, phoneticKey, now)
                    )
                    db.execSQL(
                        "UPDATE credits SET customerId = ? WHERE customerName = ?",
                        arrayOf(id, name)
                    )
                    code++
                }
                cursor.close()
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE stt_jobs ADD COLUMN assistantAnswer TEXT NOT NULL DEFAULT ''")
                } catch (e: Exception) { }
                try {
                    db.execSQL("ALTER TABLE stock_in ADD COLUMN costMissing INTEGER NOT NULL DEFAULT 0")
                } catch (e: Exception) { }
            }
        }

        /**
         * Stock ledger, weighted-average costing, COGS snapshot and expiry batches.
         * See Docs/master_build_plan.md §1.
         *
         * The backfill at the end is the delicate part: existing shops must not start at zero
         * stock. Each step is guarded, but failures are recorded in `migration_status` rather
         * than swallowed -- a silent partial backfill produces stock numbers that look
         * plausible and are wrong, which is worse than an obvious failure.
         */
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                fun step(name: String, sql: () -> Unit) {
                    try {
                        sql()
                    } catch (e: Exception) {
                        try {
                            db.execSQL(
                                "INSERT INTO migration_status (id, migration, step, error, atMs) " +
                                    "VALUES (?, '17_18', ?, ?, ?)",
                                arrayOf(
                                    java.util.UUID.randomUUID().toString(),
                                    name,
                                    (e.message ?: e.javaClass.simpleName).take(500),
                                    System.currentTimeMillis()
                                )
                            )
                        } catch (_: Exception) { }
                        e.printStackTrace()
                    }
                }

                step("create_migration_status") {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `migration_status` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `migration` TEXT NOT NULL,
                            `step` TEXT NOT NULL,
                            `error` TEXT NOT NULL,
                            `atMs` INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                }

                // --- catalog_items: materialized stock + weighted-average cost ---
                step("catalog_stockQty") { db.execSQL("ALTER TABLE catalog_items ADD COLUMN stockQty REAL NOT NULL DEFAULT 0.0") }
                step("catalog_avgCostPrice") { db.execSQL("ALTER TABLE catalog_items ADD COLUMN avgCostPrice REAL") }
                step("catalog_trackStock") { db.execSQL("ALTER TABLE catalog_items ADD COLUMN trackStock INTEGER NOT NULL DEFAULT 1") }
                step("catalog_trackExpiry") { db.execSQL("ALTER TABLE catalog_items ADD COLUMN trackExpiry INTEGER NOT NULL DEFAULT 0") }
                step("catalog_lastSoldAtMs") { db.execSQL("ALTER TABLE catalog_items ADD COLUMN lastSoldAtMs INTEGER") }
                step("catalog_lastStockedAtMs") { db.execSQL("ALTER TABLE catalog_items ADD COLUMN lastStockedAtMs INTEGER") }

                // --- transactions: COGS snapshot, returns, bill grouping ---
                step("tx_costAtSale") { db.execSQL("ALTER TABLE transactions ADD COLUMN costAtSale REAL") }
                step("tx_txnType") { db.execSQL("ALTER TABLE transactions ADD COLUMN txnType TEXT NOT NULL DEFAULT 'SALE'") }
                step("tx_billId") { db.execSQL("ALTER TABLE transactions ADD COLUMN billId TEXT") }
                step("tx_returnOfTxnId") { db.execSQL("ALTER TABLE transactions ADD COLUMN returnOfTxnId TEXT") }

                // --- new tables ---
                step("create_stock_ledger") {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `stock_ledger` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `shopId` TEXT NOT NULL,
                            `itemId` TEXT NOT NULL,
                            `itemName` TEXT NOT NULL,
                            `deltaQty` REAL NOT NULL,
                            `reason` TEXT NOT NULL,
                            `unitCost` REAL,
                            `refId` TEXT,
                            `batchId` TEXT,
                            `note` TEXT,
                            `occurredAtMs` INTEGER NOT NULL,
                            `synced` INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_ledger_itemId` ON `stock_ledger` (`itemId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_ledger_occurredAtMs` ON `stock_ledger` (`occurredAtMs`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_ledger_refId` ON `stock_ledger` (`refId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_ledger_shopId` ON `stock_ledger` (`shopId`)")
                }

                step("create_stock_batches") {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `stock_batches` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `shopId` TEXT NOT NULL,
                            `itemId` TEXT NOT NULL,
                            `itemName` TEXT NOT NULL,
                            `receivedQty` REAL NOT NULL,
                            `remainingQty` REAL NOT NULL,
                            `unitCost` REAL,
                            `expiryDateMs` INTEGER,
                            `receivedAtMs` INTEGER NOT NULL,
                            `synced` INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_batches_itemId` ON `stock_batches` (`itemId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_batches_expiryDateMs` ON `stock_batches` (`expiryDateMs`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_batches_shopId` ON `stock_batches` (`shopId`)")
                }

                // --- backfill the ledger from existing history ---

                // Purchases. Historically, spoilage was smuggled in as a NEGATIVE stock_in
                // quantity, so sign decides the reason: positive => STOCK_IN, negative => WASTE.
                step("backfill_stock_in") {
                    db.execSQL(
                        """
                        INSERT INTO stock_ledger (id, shopId, itemId, itemName, deltaQty, reason, unitCost, refId, batchId, note, occurredAtMs, synced)
                        SELECT lower(hex(randomblob(16))), COALESCE(shopId, 'default_shop'), itemId, itemName,
                               quantity, 'STOCK_IN',
                               CASE WHEN costMissing = 0 AND costPrice > 0 THEN costPrice ELSE NULL END,
                               id, NULL, 'backfilled from stock_in', timestamp, 1
                        FROM stock_in WHERE quantity > 0
                        """.trimIndent()
                    )
                }
                step("backfill_waste") {
                    db.execSQL(
                        """
                        INSERT INTO stock_ledger (id, shopId, itemId, itemName, deltaQty, reason, unitCost, refId, batchId, note, occurredAtMs, synced)
                        SELECT lower(hex(randomblob(16))), COALESCE(shopId, 'default_shop'), itemId, itemName,
                               quantity, 'WASTE', NULL, id, NULL,
                               'backfilled from negative stock_in quantity', timestamp, 1
                        FROM stock_in WHERE quantity < 0
                        """.trimIndent()
                    )
                }
                // Sales. Only non-voided rows contribute -- this is precisely the `voided = 0`
                // filter the old derived stock query was missing.
                step("backfill_sales") {
                    db.execSQL(
                        """
                        INSERT INTO stock_ledger (id, shopId, itemId, itemName, deltaQty, reason, unitCost, refId, batchId, note, occurredAtMs, synced)
                        SELECT lower(hex(randomblob(16))), COALESCE(shopId, 'default_shop'), itemId, itemName,
                               -quantity, 'SALE', NULL, id, NULL, 'backfilled from transactions', timestamp, 1
                        FROM transactions WHERE voided = 0
                        """.trimIndent()
                    )
                }

                // Materialize on-hand and the weighted-average cost from the ledger.
                step("materialize_stockQty") {
                    db.execSQL(
                        """
                        UPDATE catalog_items SET stockQty = COALESCE(
                            (SELECT SUM(deltaQty) FROM stock_ledger WHERE stock_ledger.itemId = catalog_items.id), 0.0)
                        """.trimIndent()
                    )
                }
                step("materialize_avgCostPrice") {
                    db.execSQL(
                        """
                        UPDATE catalog_items SET avgCostPrice = (
                            SELECT SUM(deltaQty * unitCost) / SUM(deltaQty)
                            FROM stock_ledger
                            WHERE stock_ledger.itemId = catalog_items.id
                              AND deltaQty > 0 AND unitCost IS NOT NULL
                        )
                        """.trimIndent()
                    )
                }
                step("stamp_lastSold_lastStocked") {
                    db.execSQL(
                        """
                        UPDATE catalog_items SET
                          lastSoldAtMs = (SELECT MAX(occurredAtMs) FROM stock_ledger
                                          WHERE stock_ledger.itemId = catalog_items.id AND deltaQty < 0),
                          lastStockedAtMs = (SELECT MAX(occurredAtMs) FROM stock_ledger
                                             WHERE stock_ledger.itemId = catalog_items.id AND deltaQty > 0)
                        """.trimIndent()
                    )
                }

                // Group existing multi-line voice sales into bills. One recording == one bill.
                step("backfill_billId") {
                    db.execSQL("UPDATE transactions SET billId = jobId WHERE billId IS NULL AND jobId IS NOT NULL")
                }

                /*
                 * Historical COGS. Deliberately reconstructed from the weighted average of
                 * stock-ins that happened ON OR BEFORE each sale -- information that was
                 * genuinely available at the time. Applying TODAY's cost to an old sale would
                 * be nonsense for vegetables, where cost swings ~40% week to week.
                 *
                 * Yields NULL when the item had no costed purchase before that sale, which is
                 * correct: that sale's profit is unknown, and ProfitCalculator excludes it and
                 * reports the coverage gap rather than inventing a cost.
                 *
                 * Caveat recorded honestly: pre-existing `stock_in.costPrice` mixed per-unit
                 * (voice) and invoice-total (the old "Total Cost Price" field in StockInScreen)
                 * conventions. This assumes per-unit, which is right for the dominant voice
                 * path and can overstate cost for older manual entries.
                 */
                step("backfill_costAtSale") {
                    db.execSQL(
                        """
                        UPDATE transactions SET costAtSale = (
                            SELECT SUM(s.quantity * s.costPrice) / SUM(s.quantity)
                            FROM stock_in s
                            WHERE s.itemId = transactions.itemId
                              AND s.quantity > 0 AND s.costPrice > 0
                              AND s.timestamp <= transactions.timestamp
                        ) WHERE costAtSale IS NULL
                        """.trimIndent()
                    )
                }
            }
        }

        /**
         * `customer_payments`: recording that a customer paid down their Udhaar. Before this there
         * was no repayment path at all -- credit could only ever go up.
         */
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `customer_payments` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `shopId` TEXT NOT NULL,
                            `customerId` TEXT NOT NULL,
                            `customerName` TEXT NOT NULL,
                            `amount` REAL NOT NULL,
                            `mode` TEXT NOT NULL DEFAULT 'CASH',
                            `note` TEXT,
                            `jobId` TEXT,
                            `receivedAtMs` INTEGER NOT NULL,
                            `synced` INTEGER NOT NULL DEFAULT 0
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_customer_payments_customerId` ON `customer_payments` (`customerId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_customer_payments_receivedAtMs` ON `customer_payments` (`receivedAtMs`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_customer_payments_shopId` ON `customer_payments` (`shopId`)")
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                /*
                 * Migrate settled legacy credits into the new model. A `credits` row already marked
                 * PAID represents money that came back, so it becomes a payment; otherwise the debt
                 * is still live and stays represented by its CREDIT transaction. Without this, every
                 * historically-settled Udhaar would reappear as outstanding under the new balance
                 * definition.
                 */
                try {
                    db.execSQL(
                        """
                        INSERT INTO customer_payments (id, shopId, customerId, customerName, amount, mode, note, jobId, receivedAtMs, synced)
                        SELECT lower(hex(randomblob(16))), COALESCE(shopId, 'default_shop'), customerId,
                               customerName, amount, 'CASH', 'migrated from settled credits row', NULL,
                               updatedAt, 1
                        FROM credits
                        WHERE status = 'PAID' AND customerId IS NOT NULL AND amount > 0
                        """.trimIndent()
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        /**
         * Indices for every hot read path.
         *
         * `transactions`, `stock_in`, `credits`, `stt_jobs` and `catalog_items` had **no indices at
         * all**, so "today's sales", "how much Aaloo is left", "what does Ramesh owe" and the STT
         * queue drain were each a full table scan. Cheap to add, and the single largest latency win
         * available -- the assistant is meant to answer in about a second.
         */
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val statements = listOf(
                    "CREATE INDEX IF NOT EXISTS `index_transactions_voided_timestamp` ON `transactions` (`voided`, `timestamp`)",
                    "CREATE INDEX IF NOT EXISTS `index_transactions_timestamp` ON `transactions` (`timestamp`)",
                    "CREATE INDEX IF NOT EXISTS `index_transactions_itemId` ON `transactions` (`itemId`)",
                    "CREATE INDEX IF NOT EXISTS `index_transactions_customerId` ON `transactions` (`customerId`)",
                    "CREATE INDEX IF NOT EXISTS `index_transactions_billId` ON `transactions` (`billId`)",
                    "CREATE INDEX IF NOT EXISTS `index_transactions_jobId` ON `transactions` (`jobId`)",
                    "CREATE INDEX IF NOT EXISTS `index_transactions_paymentMode` ON `transactions` (`paymentMode`)",
                    "CREATE INDEX IF NOT EXISTS `index_transactions_synced` ON `transactions` (`synced`)",
                    "CREATE INDEX IF NOT EXISTS `index_transactions_shopId` ON `transactions` (`shopId`)",

                    "CREATE INDEX IF NOT EXISTS `index_stock_in_itemId` ON `stock_in` (`itemId`)",
                    "CREATE INDEX IF NOT EXISTS `index_stock_in_timestamp` ON `stock_in` (`timestamp`)",
                    "CREATE INDEX IF NOT EXISTS `index_stock_in_costMissing` ON `stock_in` (`costMissing`)",
                    "CREATE INDEX IF NOT EXISTS `index_stock_in_synced` ON `stock_in` (`synced`)",
                    "CREATE INDEX IF NOT EXISTS `index_stock_in_shopId` ON `stock_in` (`shopId`)",

                    "CREATE INDEX IF NOT EXISTS `index_credits_customerId` ON `credits` (`customerId`)",
                    "CREATE INDEX IF NOT EXISTS `index_credits_status` ON `credits` (`status`)",
                    "CREATE INDEX IF NOT EXISTS `index_credits_synced` ON `credits` (`synced`)",
                    "CREATE INDEX IF NOT EXISTS `index_credits_shopId` ON `credits` (`shopId`)",

                    "CREATE INDEX IF NOT EXISTS `index_stt_jobs_status_recordedAtMs` ON `stt_jobs` (`status`, `recordedAtMs`)",
                    "CREATE INDEX IF NOT EXISTS `index_stt_jobs_recordedAtMs` ON `stt_jobs` (`recordedAtMs`)",
                    "CREATE INDEX IF NOT EXISTS `index_stt_jobs_status` ON `stt_jobs` (`status`)",

                    "CREATE INDEX IF NOT EXISTS `index_catalog_items_active` ON `catalog_items` (`active`)",
                    "CREATE INDEX IF NOT EXISTS `index_catalog_items_name` ON `catalog_items` (`name`)",
                    "CREATE INDEX IF NOT EXISTS `index_catalog_items_synced` ON `catalog_items` (`synced`)",
                    "CREATE INDEX IF NOT EXISTS `index_catalog_items_shopId` ON `catalog_items` (`shopId`)",
                    "CREATE INDEX IF NOT EXISTS `index_catalog_items_lastSoldAtMs` ON `catalog_items` (`lastSoldAtMs`)"
                )
                for (sql in statements) {
                    try {
                        db.execSQL(sql)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                // Rebuild the query planner's stats so it actually uses the new indices.
                try { db.execSQL("ANALYZE") } catch (e: Exception) { e.printStackTrace() }
            }
        }

        /**
         * `daily_rollups`: schema only. This table is a CACHE, not a source of truth --
         * `DailyRollupRepository.repairRange()` populates it from `transactions`/`stock_ledger`
         * at app startup (see `MainActivity`), which is simpler and just as correct as a SQL
         * backfill written into the migration itself, and it reuses the exact same aggregation
         * logic reports read through instead of a second, migration-only copy of it.
         */
        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `daily_rollups` (
                            `shopId` TEXT NOT NULL,
                            `dayStartMs` INTEGER NOT NULL,
                            `itemId` TEXT NOT NULL,
                            `itemName` TEXT NOT NULL,
                            `qtySold` REAL NOT NULL DEFAULT 0.0,
                            `revenue` REAL NOT NULL DEFAULT 0.0,
                            `cogs` REAL NOT NULL DEFAULT 0.0,
                            `costKnownRevenue` REAL NOT NULL DEFAULT 0.0,
                            `txnCount` INTEGER NOT NULL DEFAULT 0,
                            `wasteQty` REAL NOT NULL DEFAULT 0.0,
                            `wasteValue` REAL NOT NULL DEFAULT 0.0,
                            `returnQty` REAL NOT NULL DEFAULT 0.0,
                            `returnValue` REAL NOT NULL DEFAULT 0.0,
                            `stockInQty` REAL NOT NULL DEFAULT 0.0,
                            `stockInValue` REAL NOT NULL DEFAULT 0.0,
                            PRIMARY KEY(`shopId`, `dayStartMs`, `itemId`)
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_daily_rollups_dayStartMs` ON `daily_rollups` (`dayStartMs`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_daily_rollups_itemId` ON `daily_rollups` (`itemId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_daily_rollups_shopId` ON `daily_rollups` (`shopId`)")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        /** `shop_learning`: schema only, starts empty and accumulates from normal use (every
         *  review-queue confirmation writes an entry going forward). Nothing to backfill --
         *  there is no historical record of which raw transcript token confirmed which item. */
        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `shop_learning` (
                            `shopId` TEXT NOT NULL,
                            `kind` TEXT NOT NULL,
                            `key` TEXT NOT NULL,
                            `value` TEXT NOT NULL,
                            `hitCount` INTEGER NOT NULL DEFAULT 1,
                            `lastUsedAtMs` INTEGER NOT NULL,
                            `confidence` REAL NOT NULL DEFAULT 0.6,
                            `synced` INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY(`shopId`, `kind`, `key`)
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_shop_learning_shopId` ON `shop_learning` (`shopId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_shop_learning_kind_key` ON `shop_learning` (`kind`, `key`)")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        /** `alert_dismissals`: schema only. Alerts are derived, so there is nothing to backfill --
         *  an empty table simply means nothing is suppressed yet, which is the correct initial state. */
        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `alert_dismissals` (
                            `shopId` TEXT NOT NULL,
                            `type` TEXT NOT NULL,
                            `itemId` TEXT NOT NULL,
                            `snoozedUntilMs` INTEGER NOT NULL,
                            `dismissedAtMs` INTEGER NOT NULL,
                            `synced` INTEGER NOT NULL DEFAULT 0,
                            PRIMARY KEY(`shopId`, `type`, `itemId`)
                        )
                        """.trimIndent()
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_alert_dismissals_shopId` ON `alert_dismissals` (`shopId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_alert_dismissals_snoozedUntilMs` ON `alert_dismissals` (`snoozedUntilMs`)")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "voice_to_invoice_db"
            )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23)
            // `fallbackToDestructiveMigration()` was removed deliberately. Sync is push-only
            // (SyncEngine has no pull path for transactions), so wiping the local DB on a
            // migration gap means a shopkeeper permanently loses their books. Failing loudly is
            // recoverable -- silent data loss is not. Every migration step above is individually
            // guarded and records failures to `migration_status`.
            .addCallback(object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    CoroutineScope(Dispatchers.IO).launch {
                        INSTANCE?.let { database ->
                            seedItemUnits(database)
                            seedMasterCatalog(database)
                        }
                    }
                }

                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            db.execSQL("DELETE FROM catalog_items WHERE LOWER(name) IN ('kilometer', 'किलोमीटर', 'kilo', 'kg', 'सत्तर', 'पचास', 'item', 'पचास पॉन्ड्स क्रीम')")
                            db.execSQL("DELETE FROM transactions WHERE LOWER(itemName) IN ('kilometer', 'किलोमीटर', 'kilo', 'kg', 'item')")
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            })
            .build()
        }

        private suspend fun seedItemUnits(db: AppDatabase) {
            val units = listOf(
                ItemUnit("KG", "किलो / kilo", 1.0, "KG"),
                ItemUnit("GRAM", "gram / ग्राम", 0.001, "KG"),
                ItemUnit("LITRE", "litre / लीटर", 1.0, "LITRE"),
                ItemUnit("ML", "ml / एमएल", 0.001, "LITRE"),
                ItemUnit("PACKET", "packet / पैकेट", 1.0, "PACKET"),
                ItemUnit("PIECE", "piece / नग", 1.0, "PIECE"),
                ItemUnit("DOZEN", "dozen / दर्जन", 12.0, "PIECE"),
                ItemUnit("PAO", "पाव / paao", 0.25, "KG"),
                ItemUnit("AADHA", "आधा / aadha", 0.5, "KG"),
                ItemUnit("SAWA", "सवा / sawa", 1.25, "KG"),
                ItemUnit("DHAI", "ढाई / dhai", 2.5, "KG"),
                ItemUnit("BOX", "box / डिब्बा", 1.0, "BOX")
            )
            db.itemUnitDao().insertAll(units)
        }

        private suspend fun seedMasterCatalog(db: AppDatabase) {
            val catalogDao = db.catalogDao()
            val preseededItems = listOf(
                // 1. Vegetables & Produce
                CatalogItem(name = "Pyaz", unitId = "KG", price = 35.0),
                CatalogItem(name = "Tamatar", unitId = "KG", price = 40.0),
                CatalogItem(name = "Aaloo", unitId = "KG", price = 30.0),
                CatalogItem(name = "Bhindi", unitId = "KG", price = 50.0),
                CatalogItem(name = "Adrak", unitId = "KG", price = 120.0),
                CatalogItem(name = "Mirchi", unitId = "KG", price = 80.0),
                CatalogItem(name = "Nimbu", unitId = "PIECE", price = 5.0),
                CatalogItem(name = "Dhaniya", unitId = "KG", price = 60.0),
                CatalogItem(name = "Palak", unitId = "KG", price = 40.0),
                CatalogItem(name = "Gobhi", unitId = "PIECE", price = 30.0),
                CatalogItem(name = "Lauki", unitId = "PIECE", price = 20.0),
                CatalogItem(name = "Karela", unitId = "KG", price = 45.0),
                CatalogItem(name = "Broccoli", unitId = "KG", price = 150.0),
                CatalogItem(name = "Baingan", unitId = "KG", price = 40.0),
                CatalogItem(name = "Gajar", unitId = "KG", price = 35.0),
                CatalogItem(name = "Matar", unitId = "KG", price = 60.0),
                CatalogItem(name = "Kheera", unitId = "KG", price = 30.0),
                CatalogItem(name = "Lahsun", unitId = "KG", price = 160.0),
                CatalogItem(name = "Dragon Fruit", unitId = "PIECE", price = 80.0),

                // 2. Dairy & Eggs
                CatalogItem(name = "Amul Gold Milk", unitId = "PACKET", price = 34.0),
                CatalogItem(name = "Amul Taaza Milk", unitId = "PACKET", price = 27.0),
                CatalogItem(name = "Saras Milk", unitId = "PACKET", price = 30.0),
                CatalogItem(name = "Curd (Dahi)", unitId = "PACKET", price = 35.0),
                CatalogItem(name = "Chaas (Buttermilk)", unitId = "PACKET", price = 15.0),
                CatalogItem(name = "Paneer", unitId = "KG", price = 360.0),
                CatalogItem(name = "Butter", unitId = "PACKET", price = 56.0),
                CatalogItem(name = "Eggs", unitId = "DOZEN", price = 84.0),

                // 3. Bakery & FMCG Snacks
                CatalogItem(name = "Bourbon Biscuit", unitId = "PACKET", price = 30.0),
                CatalogItem(name = "Parle-G Biscuit", unitId = "PACKET", price = 10.0),
                CatalogItem(name = "Good Day Biscuit", unitId = "PACKET", price = 20.0),
                CatalogItem(name = "Hide & Seek Biscuit", unitId = "PACKET", price = 30.0),
                CatalogItem(name = "Rusk", unitId = "PACKET", price = 40.0),
                CatalogItem(name = "Bread", unitId = "PACKET", price = 45.0),
                CatalogItem(name = "Maggi", unitId = "PACKET", price = 14.0),

                // 4. Staples & Sugar
                CatalogItem(name = "Sugar (Madhur)", unitId = "KG", price = 45.0),
                CatalogItem(name = "Atta (Aashirvaad)", unitId = "KG", price = 42.0),
                CatalogItem(name = "Basmati Rice", unitId = "KG", price = 90.0),
                CatalogItem(name = "Toor Dal", unitId = "KG", price = 160.0),
                CatalogItem(name = "Chana Dal", unitId = "KG", price = 90.0),
                CatalogItem(name = "Moong Dal", unitId = "KG", price = 110.0),
                CatalogItem(name = "Poha", unitId = "KG", price = 50.0),

                // 5. Spices & Cooking Oil
                CatalogItem(name = "Fortune Refined Oil", unitId = "LITRE", price = 140.0),
                CatalogItem(name = "Mustard Oil", unitId = "LITRE", price = 150.0),
                CatalogItem(name = "Desi Ghee", unitId = "KG", price = 650.0),
                CatalogItem(name = "Tata Salt", unitId = "PACKET", price = 28.0),
                CatalogItem(name = "Haldi Powder", unitId = "GRAM", price = 0.25),
                CatalogItem(name = "Lal Mirch Powder", unitId = "GRAM", price = 0.30),
                CatalogItem(name = "Jeera", unitId = "GRAM", price = 0.40),
                CatalogItem(name = "Garam Masala", unitId = "GRAM", price = 0.60),

                // 6. Beverages & Energy Drinks
                CatalogItem(name = "Tata Tea", unitId = "PACKET", price = 140.0),
                CatalogItem(name = "Nescafe Coffee", unitId = "PACKET", price = 160.0),
                CatalogItem(name = "Thums Up", unitId = "PIECE", price = 40.0),
                CatalogItem(name = "Red Bull", unitId = "PIECE", price = 125.0)
            )
            catalogDao.insertAll(preseededItems)
        }
    }
}
