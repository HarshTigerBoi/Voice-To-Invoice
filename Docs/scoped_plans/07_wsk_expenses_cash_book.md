# Scoped plan 7/11 — WS-K: Expenses & net profit (ISSUE-121)

**Scope:** this file ONLY. Client (Kotlin) + Room migration + one new entity/DAO. **No voice intent, no server change, no sync** (see "Deliberately deferred" below).

**Prerequisite already met:** WS-J (ISSUE-120) is deployed. That matters because it fixed the trigger/quantity collision class — but this plan still adds **no** new voice triggers, so the risk does not arise here.

## Why this exists

Verified: zero matches for `Expense|expense|kharcha|CashDrawer|cashInHand|openingBalance` anywhere in `app/src/main/java`. Every "मुनाफ़ा" the app shows — `ProfitCalculator`, `ReportsScreen`, `QuestionTemplates`' spoken profit answer — is **gross** profit. Rent, bijli, staff, transport, chai are invisible. A shopkeeper who paid ₹800 rent sees a profit figure that silently ignores it.

---

## K1. Entity — `data/local/entity/ExpenseRecord.kt` (new file)

```kotlin
package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class ExpenseCategory { RENT, ELECTRICITY, SALARY, TRANSPORT, SUPPLIES, TEA, OTHER }

/**
 * A shop expense. Deliberately separate from `transactions`: an expense is not a sale with a
 * negative sign — it has no item, no quantity, no customer, and must never reach any
 * item-level or revenue aggregate.
 *
 * Soft-delete only (`voided`), matching the append-only rule the ledger already follows.
 */
@Entity(
    tableName = "expenses",
    indices = [Index("timestamp"), Index("category"), Index("synced"), Index("shopId")]
)
data class ExpenseRecord(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val shopId: String = com.voicetoinvoice.app.data.ShopContext.currentOrLegacy(),
    val category: ExpenseCategory,
    val amount: Double,
    val note: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val source: TransactionSource = TransactionSource.MANUAL,
    val voided: Boolean = false,
    val synced: Boolean = false
)
```

## K2. DAO — `data/local/dao/ExpenseDao.kt` (new file)

```kotlin
@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expenses WHERE voided = 0 AND timestamp >= :startMs AND timestamp < :endMs ORDER BY timestamp DESC")
    fun getBetween(startMs: Long, endMs: Long): Flow<List<ExpenseRecord>>

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM expenses WHERE voided = 0 AND timestamp >= :startMs AND timestamp < :endMs")
    suspend fun getPeriodTotal(startMs: Long, endMs: Long): Double

    @Query("SELECT category AS category, COALESCE(SUM(amount), 0.0) AS total FROM expenses WHERE voided = 0 AND timestamp >= :startMs AND timestamp < :endMs GROUP BY category ORDER BY total DESC")
    suspend fun getTotalsByCategory(startMs: Long, endMs: Long): List<ExpenseCategoryTotal>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(expense: ExpenseRecord)

    @Query("UPDATE expenses SET voided = 1, synced = 0 WHERE id = :id")
    suspend fun void(id: String)

    @Query("SELECT * FROM expenses WHERE synced = 0")
    suspend fun getUnsynced(): List<ExpenseRecord>

    @Query("UPDATE expenses SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}

data class ExpenseCategoryTotal(val category: ExpenseCategory, val total: Double)
```

## K3. Room migration

`data/local/AppDatabase.kt`

⚠️ **Check the current `version =` value on line 36 before editing.** WS-A (ISSUE-122) also bumps it. If it already reads `28`, this migration is **28 → 29**; if it still reads `27`, WS-A has not landed — **stop and say so** rather than renumbering around it.

Assuming 28 → 29:
1. `version = 29, // ISSUE-121: expenses table`
2. Add `ExpenseRecord::class` to the `@Database(entities = [...])` list.
3. Add `abstract fun expenseDao(): ExpenseDao`.
4. Add the migration, matching the existing try/catch'd style:

```kotlin
/** New `expenses` table. ISSUE-121. */
private val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS expenses (
                    id TEXT NOT NULL PRIMARY KEY,
                    shopId TEXT NOT NULL,
                    category TEXT NOT NULL,
                    amount REAL NOT NULL,
                    note TEXT,
                    timestamp INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    voided INTEGER NOT NULL DEFAULT 0,
                    synced INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_timestamp ON expenses(timestamp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_category ON expenses(category)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_synced ON expenses(synced)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_shopId ON expenses(shopId)")
        } catch (e: Exception) {
            android.util.Log.w("AppDatabase", "MIGRATION_28_29 expenses: ${e.message}")
        }
    }
}
```
5. Register `MIGRATION_28_29` in `.addMigrations(...)`.

**Index names must match what Room generates** (`index_<table>_<column>`) or Room's schema validation fails at open time. If KSP reports a schema mismatch, fix the DDL to match Room's expectation — do **not** add `fallbackToDestructiveMigration()`, which would wipe the shopkeeper's ledger.

## K4. Net profit — added alongside gross, never replacing it

`domain/query/ProfitCalculator.kt`

Add fields to `ProfitResult` **with defaults**, so every existing construction site (including `ProfitResult.EMPTY` and `ReportsScreen`) keeps compiling:

```kotlin
val expenses: Double = 0.0,
val hasExpenseData: Boolean = false
) {
    /** Gross profit minus period expenses. Only meaningful when [hasExpenseData]. */
    val netProfit: Double get() = grossProfit - expenses
```

In `compute(...)`, read `db.expenseDao().getPeriodTotal(startMs, endMs)` and set `expenses` + `hasExpenseData = expenses > 0.0`.

**The hard rule — do not violate it.** The existing `grossProfit` field keeps its exact current meaning and stays labelled "मुनाफ़ा (कच्चा)" wherever it was previously "मुनाफ़ा". Net profit is a **new, separately labelled** figure "मुनाफ़ा (खर्चा घटाकर)". Never silently redefine the number the shopkeeper has been reading for weeks — a figure that quietly changes meaning between versions destroys trust permanently, which is the same principle `costCoveragePct` already exists to protect.

In `ReportsScreen.RevenueProfitCard`, show net **only when `hasExpenseData`**, directly beneath gross, coloured `LedgerColors.MoneyIn` / `LedgerColors.MoneyOut` via `LedgerColors.forDelta(netProfit)`.

## K5. Expense entry UI — icon-first

New `ui/screens/expense/ExpenseScreen.kt`:
- A grid of large category tiles (≥ 96.dp), each an icon + a Hindi label: किराया (RENT, `Icons.Default.Home`), बिजली (ELECTRICITY, `Icons.Default.Bolt`), तनख्वाह (SALARY, `Icons.Default.Person`), भाड़ा (TRANSPORT, `Icons.Default.LocalShipping`), सामान (SUPPLIES, `Icons.Default.Inventory`), चाय (TEA, `Icons.Default.LocalCafe`), अन्य (OTHER, `Icons.Default.MoreHoriz`).
- Tapping a tile opens a numeric-keypad amount entry (`KeyboardType.Number`) and an optional note.
- Below: today's expenses list with swipe-to-void, and a total at the bottom.
- Icon-first because a shopkeeper who cannot read must be able to pick a category by picture alone (same rule as WS-A/WS-B).

Wire it in: add `EXPENSE` to the `Screen` enum in `MainActivity.kt`, a `when` branch, and an entry point button on `ReportsScreen` (or `HomeScreen`'s overflow — pick one, do not add two).

## Deliberately DEFERRED — do not build in this pass

- **Voice expense capture** (`खर्चा`, `kharcha`, `किराया` triggers). Adding short trigger phrases is exactly the collision class ISSUE-120 just fixed; new triggers must be checked against `QUANTITY_KEYS` and the whole trigger lexicon first. Separate pass.
- **Cloud sync of expenses.** Needs a Supabase table + RLS decision, and WS-E (RLS) was explicitly skipped by the user. The `synced` flag and `getUnsynced()` exist so sync can be added later without a migration.
- **Cash book / cash-in-hand.** Depends on expenses existing first; build after this lands.

---

## Verification (do it yourself)

1. `./gradlew.bat assembleDebug` compiles.
2. `./gradlew.bat :app:testDebugUnitTest` passes. (Note: `test --tests` does **not** work on this Gradle version; the task is `:app:testDebugUnitTest`.)
3. **Migration must be non-destructive.** Confirm `MIGRATION_28_29` is registered and no `fallbackToDestructiveMigration()` was introduced. Room validates the `expenses` schema at open time — a column-order or index-name mismatch throws at runtime, not compile time, so re-read the generated schema expectation if KSP complains.
4. State plainly that no expense was entered on a device and that net profit was not observed on screen.

## Audit log

Add a 🟢 RESOLVED entry for **ISSUE-121** in `Docs/audit.md`'s single `### 🟢 RESOLVED ISSUES` section (exactly one exists — do not create another). Must state explicitly: gross profit's meaning is unchanged, net profit is a new separately-labelled figure, and voice capture + sync + cash book were deliberately deferred (not forgotten).

## Deviations

End with a "Deviations" section. If none, say "None."
