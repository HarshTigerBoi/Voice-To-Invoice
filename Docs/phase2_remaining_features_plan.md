# Phase 2 Remaining Features — Implementation Plan

**Written**: 2026-07-31 (Claude Code) · **For**: Antigravity · **Baseline**: `VoiceToInvoice_v91.apk`, Room v22, edge function deployed at `lyowklxsbfznnqridtgr`

This plan covers everything left in `Docs/remaining_work_plan.md` §2 (§1 is done — ISSUE-058/059), plus the verification debt those two issues left behind.

**Execute steps in the order given.** Step 2 introduces the only DB migration; later steps assume it landed. Each step ends with a "Verify" block — run it before moving on, don't batch all verification to the end.

**Stop and ask** if any step names a file, symbol, or column that doesn't exist, or contradicts what the code actually does. A silent deviation is worse than a paused implementation, because the next planning session will assume this plan was followed.

---

## Step 0 — Preconditions (do first, ~5 min)

1. Confirm you are on branch `master` and the working tree matches the baseline: `Docs/audit.md` has entries through **ISSUE-059**, and `supabase/migrations/20260731010000_stock_ledger_batches_payments_learning.sql` exists.
2. Confirm build works before you change anything:
   ```bash
   ./gradlew.bat compileDebugKotlin
   ```
3. Note the build-directory quirk: builds output to `%LOCALAPPDATA%\VoiceToInvoiceBuild`, not `app/build`, unless `VTI_BUILD_DIR` overrides it. The APK still lands at `app/build/outputs/apk/debug/app-debug.apk`.
4. **`./gradlew test --tests <class>` does not work** — the aggregate task rejects `--tests`. Always use `./gradlew.bat testDebugUnitTest --tests "..."`. (CLAUDE.md's documented form is wrong; this plan supersedes it.)

---

## Step 1 — Close the verification debt from ISSUE-058/059 (do on a real device)

Both issues shipped with honest "not verified" caveats. Clear them before adding features, because everything below writes to the same tables.

### 1.1 Verify the new sync paths actually reach Supabase

1. Install `VoiceToInvoice_v91.apk` (or a fresh `assembleDebug`) on the real device (`23049PCD8I`).
2. Perform one action of each kind so there is at least one unsynced row in each new table:
   - a stock-in from `StockInScreen` (writes `stock_ledger`)
   - a voice sale, then void it from `DailySummaryScreen` (writes `stock_ledger` twice: `SALE`, `SALE_VOID`)
   - a review-queue confirmation (writes `shop_learning`)
   - if a customer with a balance exists, record a payment (writes `customer_payments`)
3. Open any screen to trigger `SyncEngine.syncAllUnsynced()` (MainActivity triggers a sweep on every screen load).
4. Check logcat for the `✅ Synced ...` / `❌ ... HTTP <code>` lines from `CloudSyncManager`:
   ```bash
   adb logcat -d -s CloudSyncManager:* SyncEngine:*
   ```
5. Confirm rows landed server-side. Report the counts you actually see:
   ```sql
   SELECT 'stock_ledger' t, count(*) FROM stock_ledger
   UNION ALL SELECT 'stock_batches', count(*) FROM stock_batches
   UNION ALL SELECT 'customer_payments', count(*) FROM customer_payments
   UNION ALL SELECT 'shop_learning', count(*) FROM shop_learning
   UNION ALL SELECT 'customers', count(*) FROM customers;
   ```

**If any sync returns HTTP 4xx**: the most likely cause is a column-name mismatch between the Kotlin payload in `CloudSyncManager` and the SQL migration (both were written in one pass and only the schema was verified live). Fix the payload to match the table, not the other way round — the table is already deployed. Report which column disagreed.

### 1.2 Verify ISSUE-056's retry-backlog fix actually drains

ISSUE-056 fixed `syncTransactionToCloud` to upsert on `job_id,line_no`. It was never confirmed that the accumulated backlog drains on a real device.

1. Before syncing, count the local backlog. Add a temporary log line or use `adb shell` + `sqlite3` if available; otherwise watch the `Synced N/M transactions` line in logcat.
2. Trigger a sweep, then confirm the same sweep on a second screen load reports **0** unsynced transactions (not the same N again).
3. If it still reports the same N every sweep, the fix did not work — capture the HTTP code and stop; that is a separate bug, not something to work around here.

### 1.3 Verify server-side intent classification (ISSUE-058) with the app closed

1. Force-stop the app (`adb shell am force-stop com.voicetoinvoice.app`).
2. This is the hard part: the app must be closed *while a recording is in flight*. Practical approach — record a RETURN utterance ("दो किलो आलू वापस"), then force-stop within ~1s of releasing the mic, before the client finishes processing.
3. Query the trace for that job:
   ```sql
   SELECT job_id, status,
          diagnostic_trace_json::jsonb -> 'step_2b_intent_classification' AS intent
   FROM stt_job_logs ORDER BY created_at DESC LIMIT 5;
   ```
4. **Expected**: `step_2b_intent_classification` is non-null with `intent: "RETURN"`, `routedToReview: true`, and a matching `unmatched_queue` row whose `implausibility_reason` starts with `ASSISTANT intent=RETURN`.
5. If step 2 proves impractical to trigger reliably, say so plainly and move on — do **not** fake this verification. Record in the audit log exactly what was and wasn't confirmed.

**Verify (Step 1)**: report per sub-step what passed, what failed, and what could not be triggered. Do not proceed to Step 2 if 1.1 shows a 4xx on any table.

---

## Step 2 — Alert dismiss/snooze persistence (the only DB migration; do before Steps 3–6)

Today `AlertEngine.computeAlerts()` recomputes alerts on every call and dismiss/snooze lives in composable state, so a dismissed alert returns on the next screen load. Alerts stay recomputed (that is correct and deliberate — see the `AlertEngine` class doc); only the *suppression* becomes persistent.

### 2.1 New entity — `app/src/main/java/com/voicetoinvoice/app/data/local/entity/AlertDismissal.kt`

```kotlin
package com.voicetoinvoice.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Suppression record for one alert identity. Alerts themselves stay derived (see AlertEngine) --
 * this table only remembers "the shopkeeper has already seen and actioned this one."
 *
 * Keyed on (shopId, type, itemId) rather than a generated id because an alert has no stable id of
 * its own: it is recomputed from scratch each time, so the identity IS the type+item pair. Shop-wide
 * alerts (UDHAAR_OVERDUE, MISSING_COST) carry no item and use the empty string, not null, so the
 * primary key stays non-null.
 */
@Entity(
    tableName = "alert_dismissals",
    primaryKeys = ["shopId", "type", "itemId"],
    indices = [Index("shopId"), Index("snoozedUntilMs")]
)
data class AlertDismissal(
    val shopId: String,
    val type: String,
    /** Empty string for shop-wide alerts that carry no item. */
    val itemId: String,
    /** Absolute ms. `Long.MAX_VALUE` means dismissed permanently, not snoozed. */
    val snoozedUntilMs: Long,
    val dismissedAtMs: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)
```

### 2.2 New DAO — `app/src/main/java/com/voicetoinvoice/app/data/local/dao/AlertDismissalDao.kt`

```kotlin
package com.voicetoinvoice.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.voicetoinvoice.app.data.local.entity.AlertDismissal

@Dao
interface AlertDismissalDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(dismissal: AlertDismissal)

    /** Only still-active suppressions -- an expired snooze is simply absent from the result. */
    @Query("SELECT * FROM alert_dismissals WHERE shopId = :shopId AND snoozedUntilMs > :nowMs")
    suspend fun getActive(shopId: String, nowMs: Long): List<AlertDismissal>

    /** Housekeeping so expired snoozes don't accumulate forever. Safe to call on every launch. */
    @Query("DELETE FROM alert_dismissals WHERE snoozedUntilMs <= :nowMs")
    suspend fun purgeExpired(nowMs: Long)

    @Query("DELETE FROM alert_dismissals WHERE shopId = :shopId AND type = :type AND itemId = :itemId")
    suspend fun clear(shopId: String, type: String, itemId: String)
}
```

### 2.3 Room migration 22 → 23 — `AppDatabase.kt`

Follow the existing pattern exactly (see `MIGRATION_21_22` at `AppDatabase.kt:709` as the template):

1. Change `version = 22` to `version = 23` at `AppDatabase.kt:35` and update the trailing comment to `// Bumped: alert_dismissals (persistent alert snooze/dismiss)`.
2. Add `AlertDismissal::class` to the `entities = [...]` array.
3. Add `abstract fun alertDismissalDao(): AlertDismissalDao`.
4. Add this migration object next to `MIGRATION_21_22`:
   ```kotlin
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
   ```
5. Register it: append `, MIGRATION_22_23` to the `.addMigrations(...)` call at `AppDatabase.kt:747`.

**Do not add `fallbackToDestructiveMigration()`.** It was deliberately removed (ISSUE-052) — a failed migration must crash rather than silently wipe the shopkeeper's books.

### 2.4 Filter in `AlertEngine`

In `app/src/main/java/com/voicetoinvoice/app/domain/alert/AlertEngine.kt`:

1. Add two constants inside the class:
   ```kotlin
   /** A snoozed alert comes back after this long. One day: long enough to stop nagging within a
    *  single shift, short enough that a genuinely unresolved problem resurfaces tomorrow. */
   private val SNOOZE_DURATION_MS = 24 * 60 * 60 * 1000L
   ```
2. At the **end** of `computeAlerts()`, replace the final `return alerts.sortedBy { ... }.take(MAX_ALERTS)` with a filtered version:
   ```kotlin
   // Suppression is applied AFTER computation, not by skipping the query: the alert set stays
   // a pure function of ledger state, and a snooze expiring restores the alert with no extra work.
   val suppressed = db.alertDismissalDao()
       .getActive(ShopContext.requireShopId(), nowMs)
       .map { it.type to it.itemId }
       .toSet()

   return alerts
       .filterNot { (it.type.name to (it.itemId ?: "")) in suppressed }
       .sortedBy { it.severity.ordinal }
       .take(MAX_ALERTS)
   ```
3. Add the import `com.voicetoinvoice.app.data.ShopContext`.
4. Update the class KDoc: the paragraph currently ending "Dismiss/snooze is therefore session-scoped (in-memory, on the composable that shows them) rather than a persisted per-alert table -- a deliberate scope simplification for this pass" is now **wrong**. Replace that sentence with one saying suppression is persisted in `alert_dismissals`, keyed on `(type, itemId)`, and applied as a post-computation filter so the alert set stays a pure function of ledger state.

### 2.5 New methods for dismissing

Add to `AlertEngine`:
```kotlin
/** Hides this alert until [SNOOZE_DURATION_MS] from now. */
suspend fun snooze(alert: Alert, nowMs: Long = System.currentTimeMillis()) {
    db.alertDismissalDao().upsert(
        AlertDismissal(
            shopId = ShopContext.requireShopId(),
            type = alert.type.name,
            itemId = alert.itemId ?: "",
            snoozedUntilMs = nowMs + SNOOZE_DURATION_MS
        )
    )
}

/** Hides this alert permanently (until the underlying condition clears and re-fires it). */
suspend fun dismissForever(alert: Alert) {
    db.alertDismissalDao().upsert(
        AlertDismissal(
            shopId = ShopContext.requireShopId(),
            type = alert.type.name,
            itemId = alert.itemId ?: "",
            snoozedUntilMs = Long.MAX_VALUE
        )
    )
}
```

### 2.6 UI — swipe/tap to snooze in `ReportsScreen.AlertsCard`

In `app/src/main/java/com/voicetoinvoice/app/ui/screens/reports/ReportsScreen.kt`:

1. Change `AlertsCard(alerts: List<Alert>)` to `AlertsCard(alerts: List<Alert>, onSnooze: (Alert) -> Unit)`.
2. In each alert `Row`, after the message `Text`, add `Spacer(Modifier.weight(1f))` and an `IconButton(onClick = { onSnooze(alert) })` containing `Icon(Icons.Default.Close, contentDescription = "बाद में")`, sized `Modifier.size(18.dp)`.
3. At the call site (currently `AlertsCard(data.alerts)`), pass a handler that calls `alertEngine.snooze(alert)` in a coroutine and then re-runs the load. The simplest correct approach: hoist a `var reloadToken by remember { mutableStateOf(0) }`, change `LaunchedEffect(bounds)` to `LaunchedEffect(bounds, reloadToken)`, and have the handler do `scope.launch { withContext(Dispatchers.IO) { alertEngine.snooze(alert) }; reloadToken++ }`. You will need `val scope = rememberCoroutineScope()`.

Purge expired dismissals on launch: in `MainActivity`, inside the existing rollup-backfill `LaunchedEffect(Unit)` block (`MainActivity.kt:111`), add `database.alertDismissalDao().purgeExpired(System.currentTimeMillis())`.

### 2.7 Test — `app/src/androidTest/java/com/voicetoinvoice/app/alert/AlertDismissalTest.kt`

Instrumented (needs Room). Model it on `app/src/androidTest/java/com/voicetoinvoice/app/stock/StockLedgerRepositoryTest.kt` for setup/teardown. Cover exactly these cases:

1. `snoozedAlertIsHidden` — seed an out-of-stock item so `computeAlerts()` returns an `OUT_OF_STOCK` alert; call `snooze(alert)`; assert the next `computeAlerts()` does **not** contain it.
2. `snoozeExpiresAndAlertReturns` — snooze, then call `computeAlerts(nowMs = now + 25h)`; assert the alert is back. (This is why `computeAlerts` takes `nowMs` — use it, don't sleep.)
3. `dismissForeverSurvivesTimeTravel` — `dismissForever`, then `computeAlerts(nowMs = now + 365 days)`; assert still hidden.
4. `snoozingOneItemDoesNotHideAnother` — two low-stock items, snooze one, assert the other still alerts. This is the regression guard for the `itemId` half of the key; without it a bug that keyed only on `type` would pass every other test.
5. `shopWideAlertUsesEmptyItemId` — snooze a `MISSING_COST` alert (which has `itemId == null`) and assert it is hidden. Guards the `?: ""` coercion.

**Verify (Step 2)**:
```bash
./gradlew.bat compileDebugKotlin
./gradlew.bat connectedAndroidTest --tests "com.voicetoinvoice.app.alert.AlertDismissalTest"
```
Then install on the device and confirm the app **launches without crashing** — this is the migration smoke test. `RealDatabaseMigrationCheck` in `app/src/androidTest/.../stock/` runs its assertions against the device's real migrated DB; run the full `connectedAndroidTest` suite once here so a broken 22→23 migration is caught now rather than after four more steps.

---

## Step 3 — Surface the intelligence features in `ReportsScreen`

`ReorderAdvisor`, `DemandForecast` and `ShopLearning` are implemented and tested but no screen calls them. This is the highest value-per-effort item left. All changes are additive to `ReportsScreen.kt` plus one new pure-logic file.

### 3.1 New pure function — `app/src/main/java/com/voicetoinvoice/app/domain/intel/MoverBuckets.kt`

Kept as a **pure function over an already-fetched list** (no DB handle) specifically so it is JVM-testable without an emulator — every other file in `domain/intel/` takes an `AppDatabase` and therefore needs an instrumented test.

```kotlin
package com.voicetoinvoice.app.domain.intel

import com.voicetoinvoice.app.data.local.dao.RollupItemTotal

enum class MoverBucket { FAST, STEADY, SLOW, DEAD }

data class MoverLine(
    val itemId: String,
    val itemName: String,
    val qtySold: Double,
    val revenue: Double,
    val bucket: MoverBucket
)

/**
 * Buckets items by how fast they move, using RANK within the shop's own range rather than absolute
 * thresholds -- a vegetable stall's "fast" is a different number from a kirana's, and any absolute
 * cutoff would be wrong for one of them. Percentile boundaries match Docs/remaining_work_plan.md
 * §2.1: top 20% FAST, bottom 30% SLOW, DEAD is a separate state (never sold at all in the window),
 * not the tail of SLOW -- "sold twice" and "never sold" call for different actions.
 */
object MoverBuckets {

    const val FAST_PERCENTILE = 0.20
    const val SLOW_PERCENTILE = 0.30

    fun classify(items: List<RollupItemTotal>): List<MoverLine> {
        val sold = items.filter { it.qtySold > 0.0 }.sortedByDescending { it.qtySold }
        val dead = items.filter { it.qtySold <= 0.0 }

        val fastCount = Math.ceil(sold.size * FAST_PERCENTILE).toInt()
        val slowCount = Math.ceil(sold.size * SLOW_PERCENTILE).toInt()
        val steadyEnd = (sold.size - slowCount).coerceAtLeast(fastCount)

        val result = sold.mapIndexed { idx, item ->
            val bucket = when {
                idx < fastCount -> MoverBucket.FAST
                idx < steadyEnd -> MoverBucket.STEADY
                else -> MoverBucket.SLOW
            }
            MoverLine(item.itemId, item.itemName, item.qtySold, item.revenue, bucket)
        }
        return result + dead.map { MoverLine(it.itemId, it.itemName, 0.0, it.revenue, MoverBucket.DEAD) }
    }
}
```

### 3.2 Extend `ReportsData` in `ReportsScreen.kt`

Add three fields (keep the existing ones unchanged):
```kotlin
val reorder: List<com.voicetoinvoice.app.domain.intel.SupplierReorderGroup> = emptyList(),
val forecast: List<com.voicetoinvoice.app.domain.intel.ForecastResult> = emptyList(),
val movers: List<com.voicetoinvoice.app.domain.intel.MoverLine> = emptyList()
```

### 3.3 Load them

1. Add `val reorderAdvisor = remember { ReorderAdvisor(db) }` and `val demandForecast = remember { DemandForecast(db) }` next to the existing `remember` blocks.
2. Inside the `LaunchedEffect` `withContext(Dispatchers.IO)`, add to the `ReportsData(...)` construction:
   ```kotlin
   reorder = reorderAdvisor.suggestionsBySupplier(),
   forecast = demandForecast.forecastTopItems().filter {
       // MEDIUM/HIGH only. DemandForecast already refuses to emit below 14 days of history;
       // this second gate keeps a LOW-confidence number from ever reaching the screen if that
       // internal floor is ever relaxed. Showing a 10-day-old shop a forecast that LOOKS as
       // certain as a 90-day one is actively misleading -- see the DemandForecast class doc.
       it.confidence != com.voicetoinvoice.app.domain.intel.ForecastConfidence.LOW
   },
   movers = com.voicetoinvoice.app.domain.intel.MoverBuckets.classify(rollups.getTopByQuantity(start, end))
   ```

**Note**: `ReorderAdvisor.suggestionsBySupplier()` and `DemandForecast.forecastTopItems()` are **not** range-scoped — they use their own internal windows (21d and 56d). That is intentional; do not try to pass `start`/`end` into them. Label their cards so the user isn't misled into thinking they follow the आज/7 दिन/30 दिन chip (see 3.4).

### 3.4 Three new cards

Insert into the `Column` after `PaymentSplitCard(data)` and before the existing `TopItemsCard` calls, each followed by `Spacer(Modifier.height(12.dp))`:

**(a) `ReorderCard(data.reorder)`** — headline "क्या मंगवाएँ?". For each `SupplierReorderGroup`: supplier name as a bold sub-header, then each `ReorderLine` as two rows — item name + `suggestQty` + `unitId` on the first, and the line's existing `reasoning` string in `labelSmall`/`onSurfaceVariant` on the second. **Show `reasoning` verbatim** — it is the whole point of the explainable-arithmetic design; do not summarize or re-word it. Return early (render nothing) when the list is empty.

**(b) `ForecastCard(data.forecast)`** — headline "अगले 7 दिन का अनुमान". One row per `ForecastResult`: item name, then `next7DayQty` rounded to a whole number. Append a confidence marker: `HIGH` → nothing, `MEDIUM` → " (मोटा अनुमान)". Add a footnote in `labelSmall`: `"पिछले हफ़्तों की बिक्री से अनुमान — मौसम/त्योहार शामिल नहीं"`. Return early when empty (this is the common case for a young shop, and an empty card is worse than no card).

**(c) `MoversCard(data.movers)`** — headline "तेज़ और धीमा बिकने वाला". Show at most 5 `FAST` under a "तेज़ 🔥" sub-header and at most 5 `SLOW`+`DEAD` under "धीमा 🐢", each row item name + `fmtQty(qtySold)`. Skip the `STEADY` bucket entirely — it is the majority and carries no action. Return early when both sub-lists are empty.

All three follow the existing `TopItemsCard` structure (`Card` → `Column(Modifier.padding(16.dp))` → title `titleMedium`/`Bold` → `Spacer(8.dp)` → rows). Reuse the file-private `fmtQty` and `IndianCurrency.format` already in this file — do not add new formatters.

Add a single caption line under the range chips reading `"नीचे के सुझाव और अनुमान अपनी-अपनी अवधि पर आधारित हैं"` in `labelSmall`, so the non-range-scoped cards are honest about it.

### 3.5 Test — `app/src/test/java/com/voicetoinvoice/app/intel/MoverBucketsTest.kt`

JVM test (pure function, no Room). Construct `RollupItemTotal` instances directly — it is a plain data class with 13 fields; write a small local helper `fun total(id: String, qty: Double) = RollupItemTotal(id, id, qty, qty * 10, 0.0, 0.0, 1, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)` to keep the cases readable. Cover:

1. `tenItemsSplitTwoFastThreeSlow` — 10 items with distinct descending quantities; assert exactly 2 `FAST`, 3 `SLOW`, 5 `STEADY`.
2. `zeroQuantityItemsAreDeadNotSlow` — mix sold and never-sold; assert the never-sold land in `DEAD` and are **excluded** from the percentile ranking (i.e. the sold items' buckets are identical to what they'd be without the dead ones present). This is the case most likely to be got wrong.
3. `singleItemIsFast` — one item; assert `FAST`, no crash on the `ceil` boundary.
4. `emptyListReturnsEmpty` — no crash, empty result.
5. `allSameQuantityStillPartitions` — 10 items all `qtySold = 5.0`; assert no item is dropped and the counts still sum to 10. Guards against a stable-sort/tie assumption.

**Verify (Step 3)**:
```bash
./gradlew.bat testDebugUnitTest --tests "com.voicetoinvoice.app.intel.MoverBucketsTest"
```
```bash
./gradlew.bat assembleDebug
```
Install, open Reports, and screenshot the three new cards. If `ReorderCard` and `ForecastCard` are both empty on the real device, that is expected for a shop with little history — say so, and confirm `MoversCard` at least renders.

---

## Step 4 — Expiry UI

`StockBatch`, FEFO draw-down, and the `EXPIRING_SOON`/`EXPIRED` alerts all work. `StockLedgerRepository.record(...)` already accepts `expiryDateMs` and creates the batch when `item.trackExpiry` is true (`StockLedgerRepository.kt:143`), and `recordPurchaseFromInvoiceTotal(...)` already forwards it (`:261`). **Nothing in the data layer needs to change** — the gap is purely that no UI collects a date or acts on an alert.

### 4.1 Expiry date field on `StockInScreen`

In `app/src/main/java/com/voicetoinvoice/app/ui/screens/stockin/StockInScreen.kt`:

1. Widen the callback: `onAddStockIn: (CatalogItem, Double, Double, String, String?) -> Unit` becomes `(CatalogItem, Double, Double, String, String?, Long?) -> Unit` — the new trailing `Long?` is `expiryDateMs`.
2. Add state: `var expiryDateMs by remember { mutableStateOf<Long?>(null) }`.
3. Render the field **only** when `selectedItem?.trackExpiry == true` — a vegetable seller must never see an expiry prompt on tomatoes, which is exactly why `trackExpiry` defaults to `false`. Place it directly below the cost field.
4. Use the platform picker rather than a text field (a shopkeeper typing a date is a data-quality problem): an `OutlinedButton` showing either `"एक्सपायरी तारीख चुनें"` or the formatted selected date, opening `android.app.DatePickerDialog`. Set `datePicker.minDate = System.currentTimeMillis()` — a past expiry on incoming stock is always a typo. Normalize the picked value to local midnight before storing.
5. Reset `expiryDateMs = null` whenever `selectedItem` changes, so a date picked for one item cannot leak onto the next.
6. Pass it through at the existing call site (`StockInScreen.kt:265`): `onAddStockIn(item, qty, cost, supplierText, selectedSupplier?.id, expiryDateMs)`.

### 4.2 Thread it through `MainActivity`

At `MainActivity.kt:550`, add the `expiry` parameter to the lambda and forward it:
```kotlin
onAddStockIn = { item, qty, cost, supplier, supplierId, expiry ->
    scope.launch {
        stockLedgerRepo.recordPurchaseFromInvoiceTotal(
            itemId = item.id,
            itemName = item.name,
            qty = qty,
            invoiceTotal = cost,
            supplier = supplier,
            supplierId = supplierId,
            expiryDateMs = expiry
        )
        ...unchanged...
    }
}
```
Leave the surrounding comment block about invoice-total-vs-per-unit intact.

### 4.3 One-tap write-off from an expiry alert

1. Add to `AlertEngine`:
   ```kotlin
   /**
    * Writes off the remaining quantity of every expired batch for one item and zeroes those
    * batches. Returns the quantity written off. Uses StockReason.EXPIRY (not WASTE) so expiry
    * loss stays separable from spoilage in reporting -- they have different causes and different
    * fixes (order less vs. store better).
    */
   suspend fun writeOffExpired(
       itemId: String,
       stockLedger: StockLedgerRepository,
       nowMs: Long = System.currentTimeMillis()
   ): Double {
       val batches = db.stockBatchDao().getExpiringBefore(nowMs).filter { it.itemId == itemId }
       var total = 0.0
       for (batch in batches) {
           if (batch.remainingQty <= 0.0) continue
           stockLedger.record(
               itemId = batch.itemId,
               itemName = batch.itemName,
               deltaQty = -batch.remainingQty,
               reason = StockReason.EXPIRY,
               unitCost = batch.unitCost,
               refId = batch.id,
               batchId = batch.id,
               note = "एक्सपायरी राइट-ऑफ़",
               occurredAtMs = nowMs
           )
           db.stockBatchDao().setRemaining(batch.id, 0.0)
           total += batch.remainingQty
       }
       return total
   }
   ```
   `refId = batch.id` gives the idempotency guard for free — `record()` skips a duplicate `(refId, reason)` pair (`StockLedgerRepository.kt:70`), so a double-tap cannot write off twice.

2. In `ReportsScreen`'s `AlertsCard`, for alerts whose `type` is `EXPIRED`, render an extra `TextButton("हटाएँ")` alongside the snooze icon, wired to a new `onWriteOff: (Alert) -> Unit` parameter. At the call site, run `alertEngine.writeOffExpired(alert.itemId!!, StockLedgerRepository(db))` in a coroutine, then bump the `reloadToken` from Step 2.6 so the alert list and every stock figure on screen refresh.

3. **Confirm before writing off.** Show an `AlertDialog` first — this permanently reduces stock and cannot be undone from this screen. Title `"एक्सपायरी माल हटाएँ?"`, body naming the item and quantity, confirm `"हाँ, हटाएँ"` / dismiss `"रहने दें"`.

### 4.4 Test — extend `app/src/androidTest/java/com/voicetoinvoice/app/stock/StockLedgerRepositoryTest.kt`

Add to the existing class (don't create a new file — the fixture setup is already there):

1. `expiryWriteOffReducesStockAndZeroesBatch` — seed a `trackExpiry` item with a batch expiring yesterday; call `writeOffExpired`; assert `catalog_items.stockQty` dropped by the batch quantity, the batch's `remainingQty` is 0.0, and a `stock_ledger` row exists with `reason = EXPIRY`.
2. `expiryWriteOffIsIdempotent` — call it twice; assert stock dropped **once** and exactly one `EXPIRY` ledger row exists. This is the double-tap guard and the most important case here.
3. `expiryWriteOffIgnoresUnexpiredBatches` — two batches, one expiring next month; assert only the expired one is written off.
4. `stockInWithExpiryCreatesBatch` — call `recordPurchaseFromInvoiceTotal(..., expiryDateMs = <next month>)` on a `trackExpiry` item; assert a `StockBatch` row exists with that `expiryDateMs`. Then repeat with `trackExpiry = false` and assert **no** batch is created.

**Verify (Step 4)**:
```bash
./gradlew.bat connectedAndroidTest --tests "com.voicetoinvoice.app.stock.StockLedgerRepositoryTest"
```
Then on the device: mark one catalog item `trackExpiry = true` (via the catalog screen if it exposes the toggle — if it does not, **stop and ask**, because then there is no way for a shopkeeper to opt an item in and this feature is unreachable), add stock with an expiry date, and confirm the batch appears and the alert fires when the date passes.

> **Open question for the user** — I could not confirm any UI exists to set `CatalogItem.trackExpiry`. If there isn't one, the expiry feature has no entry point regardless of the above. Check `CatalogScreen`; if absent, add a switch there, and say so in your Deviations section.

---

## Step 5 — `BillBuilder` and the bill entry points

`ActionExecutor.sendBill(...)` (text) and `sendBillImage(...)` (PNG) both exist and work. Only the voice path (`"रमेश को बिल भेजो"`) reaches them. `FileProvider` is fully configured — authority `${applicationId}.fileprovider`, and `provider_paths.xml` already exposes `<cache-path name="cache_files" path="." />`, so `context.cacheDir/bills/` is shareable with no manifest change.

### 5.1 New file — `app/src/main/java/com/voicetoinvoice/app/domain/action/BillBuilder.kt`

```kotlin
package com.voicetoinvoice.app.domain.action

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import com.voicetoinvoice.app.data.local.entity.TransactionRecord
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders an itemized bill to a PNG a shopkeeper can send on WhatsApp.
 *
 * An image rather than text because a text bill loses its column alignment in the WhatsApp
 * renderer on most Android fonts, and a misaligned bill reads as unprofessional to the customer
 * receiving it -- which is the entire point of sending one.
 *
 * Drawn with plain Canvas rather than a Compose screenshot: this must work from a background
 * coroutine with no Activity attached (the voice path reaches it from SttWorker), and capturing a
 * composable requires a live window.
 */
object BillBuilder {

    private const val WIDTH = 720
    private const val PADDING = 40f
    private const val LINE_HEIGHT = 44f

    /**
     * @return a content:// Uri for the rendered bill, or null when rendering failed. Callers must
     *   fall back to `ActionExecutor.sendBill` (text) rather than showing an error -- a text bill
     *   is far better than no bill.
     */
    fun render(
        context: Context,
        shopName: String,
        customerName: String?,
        lines: List<TransactionRecord>,
        previousBalance: Double? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Uri? {
        if (lines.isEmpty()) return null
        return try {
            val height = (PADDING * 2 + LINE_HEIGHT * (lines.size + 7)).toInt()
            val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
            // ... draw header (shopName, date), customer line, one row per line item
            //     (qty + name left-aligned, ₹total right-aligned), a divider, the subtotal,
            //     and previousBalance + running total when previousBalance != null ...

            val dir = File(context.cacheDir, "bills").apply { mkdirs() }
            val file = File(dir, "bill_${nowMs}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            android.util.Log.w("BillBuilder", "Bill render failed, caller should fall back to text", e)
            null
        }
    }

    /** Deletes bills older than a day. Call on launch; these are cache files, not records --
     *  the transactions themselves are the durable copy. */
    fun purgeOld(context: Context, olderThanMs: Long = 24 * 60 * 60 * 1000L) { /* ... */ }
}
```

Implement the drawing block: right-align amounts by measuring with `Paint.measureText` and drawing at `WIDTH - PADDING - width`; use `Typeface.DEFAULT_BOLD` for the shop name and the total row. Format money through `IndianCurrency.format` (already in this package) — **do not** introduce a second money formatter. Keep quantities via the same `fmtQty` convention used elsewhere (integer when whole).

Call `BillBuilder.purgeOld(context)` from the same `MainActivity` `LaunchedEffect(Unit)` block as the other housekeeping (Step 2.6).

### 5.2 "बिल भेजें" on Command Feed rows

In `app/src/main/java/com/voicetoinvoice/app/ui/components/CommandFeedSheet.kt`:

1. Add a parameter to both `CommandFeedSheet` and `CommandFeedRow`: `onSendBill: (SttJobRecord) -> Unit`.
2. Show the button only when the row represents a committed sale — `job.status` is `AUTO_CONFIRMED` or `PARTIALLY_CONFIRMED` **and** `job.captureIntent` is not `STOCK_IN`/`WASTE` (nobody sends a customer a bill for a delivery they received). Render it as a `TextButton` with `Icons.Default.Share` next to the existing retry button.
3. Wire the handler in `HomeScreen` where `CommandFeedSheet` is invoked: look up the bill lines with `db.transactionDao().getByBillId(job.id)` — `VoiceCommandHandlers.kt:152` sets `billId = jobId`, so the job id **is** the bill id for voice sales. Resolve the customer via the first line's `customerId` (may be null for a cash sale — that is fine, `sendBill` handles a null customer). Then:
   ```kotlin
   val uri = BillBuilder.render(context, shopName, customer?.name, lines, previousBalance)
   val result = if (uri != null) ActionExecutor.sendBillImage(context, uri, customer?.phone)
                else ActionExecutor.sendBill(context, customer, lines)
   ```
   Show `result` — on `AppMissing`/`Failed`, surface the message in a `Snackbar`. **Do not swallow it**; the whole reason `ActionExecutor` returns a `Result` is that a silent failure used to be indistinguishable from success.

### 5.3 Share icon on `CustomerDetailScreen`

In `app/src/main/java/com/voicetoinvoice/app/ui/screens/customer/CustomerDetailScreen.kt`:

1. Add parameter `onSendBill: (CustomerRecord) -> Unit = {}`.
2. Add an `IconButton` with `Icons.Default.Share` to the `TopAppBar`'s `actions` slot.
3. Wire it in `MainActivity` at the `Screen.CUSTOMER_DETAIL` branch: resolve the customer's most recent bill with `db.transactionDao().getRecentBillIdForCustomer(customer.id)`, then `getByBillId(...)`, then the same render-or-fallback pair as 5.2. When `getRecentBillIdForCustomer` returns null, show a Snackbar `"इस ग्राहक का कोई बिल नहीं मिला"` rather than sending an empty bill.

### 5.4 Test — `app/src/androidTest/java/com/voicetoinvoice/app/action/BillBuilderTest.kt`

Instrumented (needs a real `Context` for Bitmap/FileProvider). Cover:

1. `rendersNonNullUriForValidLines` — 3 transaction lines; assert non-null Uri and that the backing file exists and is > 0 bytes.
2. `emptyLinesReturnsNull` — assert null, no file created, no crash. This is the guard for the fallback path.
3. `purgeOldRemovesStaleBillsOnly` — write two files with manipulated `lastModified`, assert only the old one is deleted.
4. `renderedFileIsUnderCacheDir` — assert the resolved path starts with `context.cacheDir` — a bill written outside the FileProvider-exposed roots would throw at share time, which is a runtime crash rather than a visible test failure.

**Verify (Step 5)**:
```bash
./gradlew.bat connectedAndroidTest --tests "com.voicetoinvoice.app.action.BillBuilderTest"
```
On the device: make a voice sale, open the Command Feed, tap बिल भेजें, and confirm WhatsApp opens with the image attached. **Do not actually send it to anyone** — confirm the prefilled share sheet, then back out.

---

## Step 6 — `RepeatOrderSheet`

Reorder from a previous bill, priced at **today's** rate.

### 6.1 New file — `app/src/main/java/com/voicetoinvoice/app/ui/components/RepeatOrderSheet.kt`

A `ModalBottomSheet` modeled structurally on `CommandFeedSheet.kt`:

1. Signature:
   ```kotlin
   @Composable
   fun RepeatOrderSheet(
       previousLines: List<TransactionRecord>,
       catalog: List<CatalogItem>,
       onConfirm: (List<RepeatLine>) -> Unit,
       onDismiss: () -> Unit
   )
   ```
   with `data class RepeatLine(val item: CatalogItem, val quantity: Double, val todayPrice: Double, val previousPrice: Double)`.
2. For each previous line, resolve the current catalog row by `itemId` and read **today's** `price`. Show: item name, quantity (editable via a small stepper), and the price. When `todayPrice != previousPrice`, show both — `"₹<today> (पिछली बार ₹<previous>)"` — with the delta in the theme's error colour when it went up.
3. **Header must say this explicitly**: `"आज के भाव पर दोबारा ऑर्डर"`. Per `Docs/remaining_work_plan.md` §2.2 this is the #1 support question if it is ambiguous — a customer who thinks they are being charged last week's price and is not will not come back. Do not soften or shorten this label.
4. Drop any previous line whose `itemId` no longer resolves to an active catalog item, and show a footnote naming what was dropped: `"<name> अब कैटलॉग में नहीं है — छोड़ा गया"`. Silently omitting it would produce a short bill the shopkeeper doesn't notice.
5. Confirm button: `"दर्ज करें"`, disabled when every line has quantity 0.

### 6.2 Entry point

Add a "फिर से ऑर्डर" `TextButton` to `CustomerDetailScreen`'s ledger rows (or its top bar, next to the share icon from 5.3 — your call, note which you chose). On tap: `getRecentBillIdForCustomer` → `getByBillId` → open the sheet.

### 6.3 Commit path

`onConfirm` writes the new sale through the **existing** paths — do not add a new write path:
- `stockLedgerRepo.recordSale(...)` per line (this is the single writer for `stockQty`/`avgCostPrice`; bypassing it is how stock drifts),
- with `billId` set to a fresh `UUID.randomUUID().toString()` shared across the lines,
- `paymentMode` matching the original bill's,
- then `syncEngine.syncAllUnsynced()`.

### 6.4 Test — `app/src/test/java/com/voicetoinvoice/app/action/RepeatOrderPricingTest.kt`

Extract the price-resolution logic into a pure function so it is JVM-testable — e.g. `fun buildRepeatLines(previous: List<TransactionRecord>, catalog: List<CatalogItem>): List<RepeatLine>` as a top-level function in `RepeatOrderSheet.kt`, called by the composable. Cover:

1. `usesTodaysPriceNotHistoricalPrice` — previous line at ₹40, catalog now ₹50; assert `todayPrice == 50.0` and `previousPrice == 40.0`. **This is the core guarantee of the whole feature.**
2. `dropsItemsNoLongerInCatalog` — assert the missing item is excluded and the rest survive.
3. `dropsInactiveCatalogItems` — an item with `active = false` is treated the same as missing.
4. `preservesQuantities` — quantities carry over unchanged.
5. `unchangedPriceStillPopulatesBothFields` — assert `todayPrice == previousPrice` rather than `previousPrice` being null, so the UI's "changed?" check is a simple comparison with no null handling.

**Verify (Step 6)**:
```bash
./gradlew.bat testDebugUnitTest --tests "com.voicetoinvoice.app.action.RepeatOrderPricingTest"
```

---

## Step 7 — Final verification, build, and audit log

Run in this order. Do not skip the audit step — an unlogged fix is invisible to the next agent, and CLAUDE.md treats it as part of finishing the work, not an optional extra.

1. Full JVM suite:
   ```bash
   ./gradlew.bat testDebugUnitTest
   ```
2. Full instrumented suite on the real device (this includes `RealDatabaseMigrationCheck`, which validates the 22→23 migration against the device's actual database):
   ```bash
   ./gradlew.bat connectedAndroidTest
   ```
3. Build and export the APK. **Check the folder first** — the version number drifts:
   ```bash
   ls "C:/Users/harsh/OneDrive/Desktop/VoiceToInvoice_APKs"
   ```
   ```bash
   ./gradlew.bat assembleDebug
   ```
   ```bash
   cp app/build/outputs/apk/debug/app-debug.apk "C:/Users/harsh/OneDrive/Desktop/VoiceToInvoice_APKs/VoiceToInvoice_v92.apk"
   ```
   (Increment from whatever the `ls` actually shows; v92 assumes v91 is still the highest.)
4. Update `Docs/audit.md`. Add entries starting at **ISSUE-060** (059 is the highest as of this plan — re-check the file, don't trust this number). One entry per behaviour-affecting change; Steps 3 and 6 are new features rather than bug fixes, so log those as feature entries only if they fixed something observable. At minimum log:
   - the alert-persistence change (Step 2) — including the Room version bump,
   - anything Step 1 uncovered as actually broken,
   - any bug you find and fix along the way.
   Follow the existing format exactly: **Symptom / Root Cause / Resolution / Verification Date**, and state plainly what you verified versus what you did not. Do not imply device testing that didn't happen.
5. Update the **Ground-Truth Source-Code Verified Constants** table (`audit.md` §1) with the new Room version (23) — that table has silently drifted before.
6. Update `Docs/remaining_work_plan.md`: mark §2.1–2.4 done with their issue numbers, and move anything you couldn't finish into a clearly-labelled remaining list.
7. End your final message with a **Deviations** section — anything changed, skipped, or interpreted differently from this plan's literal text, and why. If none, say "None."

---

## What this plan deliberately does NOT include

- **§3.1 phone-OTP auth + RLS** — blocked on a paid SMS provider (MSG91/Twilio). Do not start it. RLS is still off on `transactions`, `unmatched_queue`, `stt_job_logs`, so **do not install this build on a second shop's phone** — one shop can read another's ledger with the client-embedded anon key.
- **§3.2 AI tier-2 Q&A / intent arbitration** — per-utterance LLM cost, needs a spend decision. `IntentRouter.needsArbitration` already exists as the hook; nothing should call it yet.
- **Syncing `daily_rollups`** — deliberately excluded in ISSUE-059. It is a derived cache `DailyRollupRepository` recomputes from `stock_ledger`/`transactions`; syncing it would ship a recomputable value with no server-side reader.

## Mirrored-logic check

**No step in this plan changes parsing logic**, so `supabase/functions/process-voice-job/index.ts` and the `domain/parser/` ↔ edge-function mirror rule are **not** in scope. If you find yourself editing `index.ts`, `phonetic.ts`, `price_intent.ts`, `intent_router.ts`, or `item_resolution.ts`, you have gone outside this plan — stop and ask.

## Open questions (answer before or during, don't guess)

1. **`trackExpiry` has no visible toggle** (Step 4). I could not find UI that sets `CatalogItem.trackExpiry`. If `CatalogScreen` doesn't expose it, the entire expiry feature is unreachable by a shopkeeper. Confirm, and add a switch if missing.
2. **`shopName` source** (Step 5). `ActionExecutor.sendBill` and `BillBuilder.render` both take a `shopName`, but there is no `ShopProfile` entity yet (it is part of the blocked Phase 0 onboarding). For now read it from `SharedPreferences` with a sensible default, or pass `""` — `sendBill` already handles blank. Pick one, say which.
3. **Repeat-order entry point placement** (Step 6.2) — ledger row vs. top bar. Either is defensible; note which you chose.
