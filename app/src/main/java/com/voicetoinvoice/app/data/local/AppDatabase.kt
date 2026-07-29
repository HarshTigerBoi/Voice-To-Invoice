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
        SupplierRecord::class
    ],
    version = 14, // Bumped: Added voided/voidedAtMs to transactions (correction signal for Learned Parse Memory, ISSUE-031)
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
            .fallbackToDestructiveMigration()
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
