# Implementation Plan: Stock Awareness, Supplier Ledger, Weekly/Profit View (Priorities 3–5)

> **Audience**: This document is written for an implementing agent (Antigravity/Gemini) that
> should execute mechanically, without re-deriving the design. All decisions below are final.
> Every file path, field name, and function signature quoted below was read directly from the
> live repo on 2026-07-25 — do not "improve" or rename anything not explicitly asked for. If a
> quoted line differs from what you actually see when you open the file (drift since this was
> written), trust the live file for line numbers but keep the field/function names as specified
> here — they are load-bearing for the wiring described in later sections.
>
> **Build one priority at a time, in order (3 → 4 → 5). Do not start the next until the current
> one builds clean (`gradlew.bat assembleDebug`) and is logged in `Docs/audit.md` per the
> "Keeping Docs/audit.md in sync" section of `CLAUDE.md`.**

---

## 0. Status check — Priorities 1 & 2 are ALREADY DONE. Do not touch.

Both shipped and are committed at `e137315` on `master`. Confirmed live in the repo as of this
writing:

- **UPI Reconciliation** (`app/src/main/java/com/voicetoinvoice/app/service/UpiNotificationListenerService.kt`):
  `reconcileUpiPayment()` queries `TransactionDao.getRecentTransactionsByAmount(amount, sinceMs)`
  with a **2-minute** window (`System.currentTimeMillis() - (2 * 60 * 1000)`), and only calls
  `markTransactionPaidViaUpi(txId)` when exactly one candidate is returned. The DAO query
  (`TransactionDao.kt` lines 30-34) already filters `paymentMode != 'UPI'`. **Do not add a new
  "awaiting payment" status enum — the reuse of the existing `paymentMode` field, gated by the
  ambiguity rule, is the final design.** Do not change the 2-minute constant unless separately
  asked; it was deliberately shrunk from an initial 15 minutes to cut false-positive risk.
- **Udhaar WhatsApp Reminders** (`UdhaarScreen.kt` + `MainActivity.kt` `sendWhatsAppReminder()`):
  uses a generic `Intent.ACTION_SEND` + `Intent.createChooser`, **not** package-targeted at
  `com.whatsapp`. **`CreditRecord` deliberately has no `phoneNumber` field** — WhatsApp's own
  share-sheet contact picker resolves the recipient. Do not add a phone number column.

If you are asked to revisit either of these, treat it as a bug-fix on existing code, not a
greenfield build — read the current implementation first.

---

## 1. Confirmed current schema state (baseline — read before writing any migration)

`app/src/main/java/com/voicetoinvoice/app/data/local/AppDatabase.kt`:
- `version = 8` right now, comment says "Made unmatched_queue.shopId nullable in v8".
- Migrations registered in order: `MIGRATION_1_2` … `MIGRATION_7_8`, all in
  `.addMigrations(MIGRATION_1_2, ..., MIGRATION_7_8)` at line ~169.
- Entities list (`@Database(entities = [...])`, lines 17-26): `ItemUnit`, `CatalogItem`,
  `TransactionRecord`, `CreditRecord`, `StockInRecord`, `UnmatchedQueueItem`, `SyncQueueItem`,
  `SttJobRecord`.
- Migration pattern to copy exactly (see `MIGRATION_6_7` / `MIGRATION_7_8`): a `private val
  MIGRATION_N_N1 = object : Migration(N, N+1) { override fun migrate(db: SupportSQLiteDatabase) {
  try { db.execSQL("ALTER TABLE ... ADD COLUMN ... ") } catch (e: Exception) { e.printStackTrace() }
  } }`, then add it to both the version-comment and the `.addMigrations(...)` call.

Relevant entities as they exist today (do not assume any other fields):

```kotlin
// CatalogItem.kt
data class CatalogItem(
    val id: String = UUID.randomUUID().toString(),
    val shopId: String = "default_shop",
    val name: String,
    val unitId: String = "KG",
    val price: Double,
    val active: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)

// StockInRecord.kt  (table: stock_in)
data class StockInRecord(
    val id: String = UUID.randomUUID().toString(),
    val shopId: String = "default_shop",
    val itemId: String,
    val itemName: String,
    val quantity: Double,
    val costPrice: Double,
    val supplier: String? = null,   // free text, already nullable
    val timestamp: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)

// CreditRecord.kt  (table: credits) — the model to mirror for Priority 4
data class CreditRecord(
    val id: String = UUID.randomUUID().toString(),
    val shopId: String = "default_shop",
    val customerName: String,
    val amount: Double,
    val dueDate: Long? = null,
    val status: CreditStatus = CreditStatus.PENDING,
    val linkedTransactionId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)
```

`SyncEngine` (`data/sync/SyncEngine.kt`) constructor today:
```kotlin
class SyncEngine(
    private val transactionDao: TransactionDao,
    private val stockInDao: StockInDao,
    private val catalogDao: CatalogDao,
    private val creditDao: CreditDao,
    private val sttJobDao: SttJobDao,
    private val cloudSyncManager: CloudSyncManager = CloudSyncManager()
)
```
**This constructor is called from exactly two places** — `MainActivity.kt` (~line 91) and
`UpiNotificationListenerService.kt` (~line 47, inside `reconcileUpiPayment()`). If Priority 4
adds a `supplierDao` parameter, **both call sites must be updated in the same change** or the
build breaks.

`Screen` enum (`MainActivity.kt` line 63) today:
```kotlin
enum class Screen {
    ONBOARDING, HOME, CATALOG, UDHAAR, PRICE_UPDATE, STOCK_IN, SUMMARY, SETTINGS, DIAGNOSTIC_LOGS
}
```
Bottom `NavigationBar` (lines 111-142) has exactly 5 items: Home, Catalog, Summary, Udhaar,
Settings. `STOCK_IN`, `PRICE_UPDATE`, `DIAGNOSTIC_LOGS` are reached via callbacks passed into
`HomeScreen` (`onNavigateToPriceUpdate`, `onNavigateToLogs`, `onNavigateToSummary`,
`onNavigateToUdhaar` — confirmed at `MainActivity.kt` lines 158-161), **not** the bottom bar.

---

## 2. Priority 3 — Stock Awareness ("what do I have left")

### 2.1 Goal
Show current on-hand quantity per catalog item (`sum(stock_in.quantity) − sum(transactions.quantity)`
for that `itemId`), and let the shopkeeper optionally set a low-stock threshold that highlights
the row when on-hand drops at or below it. No new entity — this is a derived view plus one new
nullable column.

### 2.2 Schema change
Add to `CatalogItem.kt`:
```kotlin
val lowStockThreshold: Double? = null
```
Add `MIGRATION_8_9` in `AppDatabase.kt`, bump `version = 9`, add to `.addMigrations(...)`:
```kotlin
private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            db.execSQL("ALTER TABLE catalog_items ADD COLUMN lowStockThreshold REAL DEFAULT NULL")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
```
**This field is local-only for v1.** Do not add it to `supabase/schema.sql`'s `catalog_items`
table and do not add it to `CloudSyncManager.syncCatalogItemToCloud()`'s payload — it's a
shopkeeper preference, not data the cloud mirror needs. Do not set `synced = false` when updating
it either (see §2.3) — that flag exists to drive cloud sync of fields the cloud actually stores.

### 2.3 DAO changes (`CatalogDao.kt`)
Add a plain (non-`@Entity`) result class in the same file, above the `@Dao interface`:
```kotlin
data class StockLevel(val itemId: String, val onHand: Double)
```
Add two methods inside `CatalogDao`:
```kotlin
@Query("""
    SELECT c.id as itemId,
      COALESCE((SELECT SUM(s.quantity) FROM stock_in s WHERE s.itemId = c.id), 0.0) -
      COALESCE((SELECT SUM(t.quantity) FROM transactions t WHERE t.itemId = c.id), 0.0) as onHand
    FROM catalog_items c
    WHERE c.active = 1
""")
fun getStockLevels(): Flow<List<StockLevel>>

@Query("UPDATE catalog_items SET lowStockThreshold = :threshold WHERE id = :id")
suspend fun updateLowStockThreshold(id: String, threshold: Double?)
```

### 2.4 UI (`CatalogManagementScreen.kt`)
Current signature: `CatalogManagementScreen(catalog: List<CatalogItem>, onAddItem: (String, String,
Double) -> Unit, onNavigateBack: () -> Unit)`. Change to:
```kotlin
fun CatalogManagementScreen(
    catalog: List<CatalogItem>,
    stockLevels: Map<String, Double> = emptyMap(),
    onAddItem: (String, String, Double) -> Unit,
    onSetThreshold: (CatalogItem, Double?) -> Unit = { _, _ -> },
    onNavigateBack: () -> Unit
)
```
In the existing `items(catalog) { item -> Card { Row { ... } } }` block: show `stockLevels[item.id]`
next to the existing `"₹${item.price}/${item.unitId}"` text (e.g. `"On hand: ${onHand} ${item.unitId}"`).
If `item.lowStockThreshold != null && onHand <= item.lowStockThreshold`, tint the `Card`'s
container with `MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)` — this is the exact
pattern already used in `DailySummaryScreen.kt` line 201 for pending-price rows, reuse it for
consistency rather than inventing a new visual language.

Add a tap-to-set-threshold flow mirroring `DailySummaryScreen.kt`'s existing "Set Price" dialog
pattern (lines 300-336: a `selectedTxForPriceSet` state + `AlertDialog` with one
`OutlinedTextField` + Save/Cancel) — same shape, but `selectedItemForThreshold: CatalogItem?` and
calling `onSetThreshold(item, newThreshold)`.

### 2.5 Wiring (`MainActivity.kt`)
Add near `catalogState` (line 72):
```kotlin
val stockLevelsState by database.catalogDao().getStockLevels().collectAsState(initial = emptyList())
val stockLevelsMap = remember(stockLevelsState) { stockLevelsState.associate { it.itemId to it.onHand } }
```
In the `Screen.CATALOG ->` block (line 201), pass `stockLevels = stockLevelsMap` and:
```kotlin
onSetThreshold = { item, threshold ->
    scope.launch { database.catalogDao().updateLowStockThreshold(item.id, threshold) }
}
```
(No `syncEngine.syncAllUnsynced()` call here — this field never syncs, per §2.2.)

---

## 3. Priority 4 — Supplier Ledger

### 3.1 Goal
Track what the shop owes each supplier, mirroring Udhaar but in the opposite money direction.
New entity required (unlike Priority 3).

### 3.2 New entity — `data/local/entity/SupplierRecord.kt`
```kotlin
package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "suppliers")
data class SupplierRecord(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val shopId: String = "default_shop",
    val name: String,
    val phone: String? = null,
    val balanceOwed: Double = 0.0,
    val updatedAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)
```

### 3.3 New DAO — `data/local/dao/SupplierDao.kt`
Mirror `CreditDao.kt` exactly:
```kotlin
package com.voicetoinvoice.app.data.local.dao

import androidx.room.*
import com.voicetoinvoice.app.data.local.entity.SupplierRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface SupplierDao {
    @Query("SELECT * FROM suppliers ORDER BY name ASC")
    fun getAllSuppliers(): Flow<List<SupplierRecord>>

    @Query("SELECT * FROM suppliers ORDER BY name ASC")
    suspend fun getAllSuppliersList(): List<SupplierRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(supplier: SupplierRecord)

    @Query("UPDATE suppliers SET balanceOwed = balanceOwed + :delta, updatedAt = :timestamp, synced = 0 WHERE id = :id")
    suspend fun addToBalance(id: String, delta: Double, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE suppliers SET balanceOwed = 0.0, updatedAt = :timestamp, synced = 0 WHERE id = :id")
    suspend fun settleBalance(id: String, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM suppliers WHERE synced = 0")
    suspend fun getUnsyncedSuppliers(): List<SupplierRecord>

    @Query("UPDATE suppliers SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}
```

### 3.4 Entity change — `StockInRecord.kt`
Add a new nullable field. **Do not rename or remove the existing `supplier: String?` field** —
old rows keep their free text as-is; only new rows populated through the supplier picker (§3.7)
get `supplierId` set.
```kotlin
val supplierId: String? = null
```

### 3.5 `AppDatabase.kt` changes
1. Register the new entity: add `SupplierRecord::class` to the `@Database(entities = [...])` list.
2. Add `abstract fun supplierDao(): SupplierDao`.
3. `MIGRATION_9_10` (chained after Priority 3's `MIGRATION_8_9` — do this priority second, not
   first, so the version numbers line up), bump `version = 10`:
```kotlin
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
```
4. Add `MIGRATION_9_10` to `.addMigrations(...)`.

### 3.6 `SyncEngine` + `CloudSyncManager` + Supabase mirror
Follow the exact `CreditRecord`/`credits` pattern:
1. `SyncEngine`'s constructor gets a new param `private val supplierDao: SupplierDao`, and a new
   method `syncUnsyncedSuppliers()` shaped exactly like `syncUnsyncedCredits()` (lines 93-108),
   called from `syncAllUnsynced()`.
2. **Update both `SyncEngine(...)` call sites** — `MainActivity.kt` (~line 91) and
   `UpiNotificationListenerService.kt` (~line 47) — to pass `database.supplierDao()` /
   `db.supplierDao()`. Forgetting either one is a compile error, not a runtime bug — the build
   will fail immediately if you miss one.
3. `CloudSyncManager.kt`: add `syncSupplierToCloud(supplier: SupplierRecord): Boolean`, modeled on
   `syncCreditToCloud()` (lines 137-166), posting to `/rest/v1/suppliers`.
4. `supabase/schema.sql`: add a `public.suppliers` table mirroring `public.credits` (lines 81-98),
   with RLS enabled and a shop-isolation policy in the same style.

### 3.7 UI
1. New `ui/screens/supplier/SupplierScreen.kt`, structurally a copy of `UdhaarScreen.kt`: search
   field, FAB "+ New" add dialog (name + optional phone), list of suppliers showing
   `balanceOwed`, and a "Settle" button (calls `settleBalance`) where `UdhaarScreen` has "Mark
   Paid". No "Remind" button needed here (Priority 4 doesn't need the WhatsApp feature — that was
   specific to Udhaar's customer-facing collection use case).
2. `MainActivity.kt`: add `SUPPLIER` to the `Screen` enum, add a
   `Screen.SUPPLIER -> { SupplierScreen(...) }` block modeled on the existing
   `Screen.UDHAAR -> { ... }` block, collect `suppliersState` from
   `database.supplierDao().getAllSuppliers()` the same way `creditsState` is already collected.
3. Do **not** add a 6th bottom-nav item — the bar already has 5 (§1). Add a
   `onNavigateToSuppliers` callback to `HomeScreen.kt`, mirroring the existing
   `onNavigateToUdhaar` callback exactly (open `HomeScreen.kt` yourself to find and copy its
   button styling — it is not quoted here since it wasn't read as part of this plan).
4. `StockInScreen.kt`: current signature is `StockInScreen(catalog: List<CatalogItem>,
   onAddStockIn: (CatalogItem, Double, Double, String) -> Unit, onNavigateBack: () -> Unit)` with
   a free-text `supplierText` field (line 20). Add a new param
   `suppliers: List<SupplierRecord> = emptyList()` and change `onAddStockIn`'s signature to
   `(CatalogItem, Double, Double, String, String?) -> Unit` (last param = selected `supplierId`,
   nullable). Add a simple `DropdownMenu` above the existing free-text field, populated from
   `suppliers`; picking one sets the `supplierId` passed to `onAddStockIn` — **keep the existing
   free-text field for shops with no saved suppliers yet**, don't force the picker.
5. `MainActivity.kt`'s `Screen.STOCK_IN ->` block (line 246): when `supplierId != null`, also call
   `database.supplierDao().addToBalance(supplierId, cost)` alongside the existing
   `stockInDao().insert(...)` call, inside the same `scope.launch { }`.

---

## 4. Priority 5 — Weekly/Monthly View + Basic Profit Line

### 4.1 Goal
Extend the existing Daily Summary into a day/week/month range toggle, and add an approximate
per-item margin figure. **Extend `DailySummaryScreen.kt` in place — do not create a new file or
rename the composable.**

### 4.2 Confirmed current state
`MainActivity.kt` collects exactly one transaction list today:
```kotlin
val todayTransactionsState by database.transactionDao().getTodayTransactions(todayMidnight).collectAsState(initial = emptyList())
```
There is no existing week/month or all-transactions state — add it new, don't assume it exists.
`todayMidnight` is computed at lines 75-82 using `Calendar.getInstance()`.

### 4.3 DAO changes
`TransactionDao.kt` — add:
```kotlin
@Query("SELECT * FROM transactions WHERE timestamp >= :startMs AND timestamp < :endMs ORDER BY timestamp DESC")
fun getTransactionsBetween(startMs: Long, endMs: Long): Flow<List<TransactionRecord>>
```
`CatalogDao.kt` — margin approximation uses the **most recent known cost price per item**, not a
point-in-time cost at each transaction's own timestamp. This is a deliberate simplification: a
fully accurate historical join is unnecessary complexity for a "roughly, am I making money"
figure. Add to `StockInDao.kt`:
```kotlin
data class ItemCost(val itemId: String, val costPrice: Double)

@Query("""
    SELECT s.itemId as itemId, s.costPrice as costPrice
    FROM stock_in s
    INNER JOIN (
        SELECT itemId, MAX(timestamp) as maxTs FROM stock_in GROUP BY itemId
    ) latest ON s.itemId = latest.itemId AND s.timestamp = latest.maxTs
""")
fun getLatestCostPricePerItem(): Flow<List<ItemCost>>
```

### 4.4 UI (`DailySummaryScreen.kt`)
Add a `RangeMode` enum (new small file or top of `DailySummaryScreen.kt`): `enum class RangeMode {
DAY, WEEK, MONTH }`. Change the screen signature from:
```kotlin
fun DailySummaryScreen(
    todayTransactions: List<TransactionRecord>,
    onUpdateTxPrice: (TransactionRecord, Double) -> Unit = { _, _ -> },
    onNavigateBack: () -> Unit
)
```
to:
```kotlin
fun DailySummaryScreen(
    rangeTransactions: List<TransactionRecord>,
    rangeMode: RangeMode,
    onRangeModeChange: (RangeMode) -> Unit,
    costPriceByItemId: Map<String, Double> = emptyMap(),
    onUpdateTxPrice: (TransactionRecord, Double) -> Unit = { _, _ -> },
    onNavigateBack: () -> Unit
)
```
Rename the internal `todayTransactions` references to `rangeTransactions` (same variable, just
no longer necessarily "today" — all downstream logic in the file, e.g. `totalRevenue =
todayTransactions.sumOf { it.total }`, stays structurally identical, just reads from the renamed
param). Add three `FilterChip`s or a `SegmentedButton` row (Day/Week/Month) near the top calling
`onRangeModeChange`. Add a margin line under the existing Cash/UPI/Udhaar row:
```kotlin
val totalMargin = rangeTransactions.sumOf { tx ->
    val cost = costPriceByItemId[tx.itemId] ?: 0.0
    tx.total - (cost * tx.quantity)
}
```
displayed as `"📈 Est. Margin: ₹${totalMargin.toInt()}"` — label it "Est." because the cost basis
is the latest known price, not a historical point-in-time value (see §4.3); don't present it as
exact.

### 4.5 Wiring (`MainActivity.kt`)
Add near `todayMidnight` (line 75):
```kotlin
var summaryRangeMode by remember { mutableStateOf(RangeMode.DAY) }
val summaryRangeBounds = remember(summaryRangeMode) {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        when (summaryRangeMode) {
            RangeMode.WEEK -> add(Calendar.DAY_OF_YEAR, -7)
            RangeMode.MONTH -> add(Calendar.DAY_OF_YEAR, -30)
            RangeMode.DAY -> {}
        }
    }
    cal.timeInMillis to (todayMidnight + 24 * 60 * 60 * 1000)
}
val rangeTransactionsState by remember(summaryRangeBounds) {
    database.transactionDao().getTransactionsBetween(summaryRangeBounds.first, summaryRangeBounds.second)
}.collectAsState(initial = emptyList())
val itemCostState by database.stockInDao().getLatestCostPricePerItem().collectAsState(initial = emptyList())
val costPriceMap = remember(itemCostState) { itemCostState.associate { it.itemId to it.costPrice } }
```
In the `Screen.SUMMARY ->` block (line 258), replace `todayTransactions = todayTransactionsState`
with `rangeTransactions = rangeTransactionsState, rangeMode = summaryRangeMode, onRangeModeChange =
{ summaryRangeMode = it }, costPriceByItemId = costPriceMap`. **Leave `todayTransactionsState`
itself in place** — nothing else in this plan removes it, and it's plausible other code depends
on it; only rewire the `Screen.SUMMARY` call site.

---

## 5. Verification checklist (do this before marking any priority done)

1. `gradlew.bat assembleDebug` — clean build, no errors.
2. Confirm the Room schema actually migrated: install the debug APK over an existing install
   (don't uninstall first) and open the affected screen — a crash here means a migration bug,
   not a UI bug.
3. Log the fix in `Docs/audit.md` under the next sequential `ISSUE-NNN`, per `CLAUDE.md`'s
   "Keeping Docs/audit.md in sync" section — name the exact files touched, same format as
   ISSUE-012/ISSUE-013.
4. State plainly in the audit entry what was actually verified (build success, manual on-device
   check) versus what wasn't (e.g. no automated test covers the new DAO queries) — don't imply
   testing that didn't happen.
