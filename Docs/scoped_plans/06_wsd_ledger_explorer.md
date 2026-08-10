# Scoped plan 6/11 — WS-D: Ledger Explorer (ISSUE-119)

**Scope:** this file ONLY. Client (Kotlin/Compose) + one DAO addition. No schema change, no migration, no server change.

**The user's requirement, verbatim:** *"for excel main thing is total in the end written always and option to filter by items by date by time by other stuff"*. Two things: (1) a total that is **always visible**, (2) filtering by item / date / time / other.

**Decision already made — do not revisit:** upgrade the existing `DailySummaryScreen` in place. Do **not** add a new `Screen` enum value or a new screen file. That screen already owns the transaction list, the range chips, the void flow and a TSV export; a parallel screen would duplicate all of it.

---

## D1. Filter state

`app/src/main/java/com/voicetoinvoice/app/ui/screens/summary/DailySummaryScreen.kt`

Add above the composable (`RangeMode` already lives in this file — keep it):

```kotlin
data class LedgerFilter(
    val itemId: String? = null,
    val paymentMode: PaymentMode? = null,
    val txnType: TxnType? = null,
    val fromHour: Int? = null,   // 0..23 local, inclusive
    val toHour: Int? = null      // 0..23 local, inclusive
) {
    val isActive: Boolean
        get() = itemId != null || paymentMode != null || txnType != null ||
                fromHour != null || toHour != null
}
```

Date range stays on the existing `RangeMode` + `onRangeModeChange` already hoisted in `MainActivity` — **do not move it into `LedgerFilter`**, and do not change `MainActivity`'s existing range plumbing.

## D2. DAO — filtered rows and filtered totals

`app/src/main/java/com/voicetoinvoice/app/data/local/dao/TransactionDao.kt`

Add a result type next to the existing `PeriodTotals` / `ItemSales`:

```kotlin
data class FilteredTotals(
    val revenue: Double,
    val cash: Double,
    val upi: Double,
    val credit: Double,
    val qty: Double,
    val lineCount: Int
)
```

Add both queries. The `(:param IS NULL OR col = :param)` idiom keeps this one static Room query instead of `@RawQuery`. Enums are stored as their `name` strings — the existing `getTotalByPaymentMode(mode: String, ...)` proves it — so pass `String?`.

```kotlin
@Query("""
    SELECT * FROM transactions
    WHERE voided = 0
      AND timestamp >= :startMs AND timestamp < :endMs
      AND (:itemId      IS NULL OR itemId      = :itemId)
      AND (:paymentMode IS NULL OR paymentMode = :paymentMode)
      AND (:txnType     IS NULL OR txnType     = :txnType)
      AND (:fromHour IS NULL OR CAST(strftime('%H', timestamp/1000, 'unixepoch', 'localtime') AS INTEGER) >= :fromHour)
      AND (:toHour   IS NULL OR CAST(strftime('%H', timestamp/1000, 'unixepoch', 'localtime') AS INTEGER) <= :toHour)
    ORDER BY timestamp DESC
""")
fun getFiltered(
    startMs: Long, endMs: Long,
    itemId: String?, paymentMode: String?, txnType: String?,
    fromHour: Int?, toHour: Int?
): Flow<List<TransactionRecord>>

@Query("""
    SELECT
      COALESCE(SUM(total), 0.0)                                                AS revenue,
      COALESCE(SUM(CASE WHEN paymentMode = 'CASH'   THEN total END), 0.0)      AS cash,
      COALESCE(SUM(CASE WHEN paymentMode = 'UPI'    THEN total END), 0.0)      AS upi,
      COALESCE(SUM(CASE WHEN paymentMode = 'CREDIT' THEN total END), 0.0)      AS credit,
      COALESCE(SUM(quantity), 0.0)                                             AS qty,
      COUNT(*)                                                                 AS lineCount
    FROM transactions
    WHERE voided = 0
      AND timestamp >= :startMs AND timestamp < :endMs
      AND (:itemId      IS NULL OR itemId      = :itemId)
      AND (:paymentMode IS NULL OR paymentMode = :paymentMode)
      AND (:txnType     IS NULL OR txnType     = :txnType)
      AND (:fromHour IS NULL OR CAST(strftime('%H', timestamp/1000, 'unixepoch', 'localtime') AS INTEGER) >= :fromHour)
      AND (:toHour   IS NULL OR CAST(strftime('%H', timestamp/1000, 'unixepoch', 'localtime') AS INTEGER) <= :toHour)
""")
fun getFilteredTotals(
    startMs: Long, endMs: Long,
    itemId: String?, paymentMode: String?, txnType: String?,
    fromHour: Int?, toHour: Int?
): Flow<FilteredTotals>
```

**The `WHERE` clauses must stay byte-identical between the two queries.** If they drift, the footer total stops describing the rows on screen — which is the exact failure this feature exists to prevent.

Totals are computed **in SQL, not by summing the rendered list**, so the footer stays correct when the filtered set is larger than what is on screen.

## D3. The always-visible total — the core requirement

`DailySummaryScreen` currently computes `totalRevenue` etc. by summing `rangeTransactions` in Kotlin (lines ~57-73). Keep the screen's existing signature working, but drive the footer from the new SQL totals.

Wrap the screen body in `Scaffold(bottomBar = { LedgerTotalBar(...) })` so the total is **on screen at all times regardless of scroll position** and recomputes live as filters change.

New private composable in the same file:

```kotlin
@Composable
private fun LedgerTotalBar(totals: FilteredTotals, filter: LedgerFilter, onClearFilter: () -> Unit)
```

Contents, in this order:
1. `₹<revenue>` — the largest element on the bar, `headlineSmall`, `FontWeight.Bold`.
2. A row: `💵 <cash>` · `📲 <upi>` · `📜 <credit>`, coloured `LedgerColors.MoneyIn` / `LedgerColors.Upi` / `LedgerColors.Udhaar` respectively (import `com.voicetoinvoice.app.ui.theme.LedgerColors` — it exists as of ISSUE-123; do not introduce new colour literals).
3. `<lineCount> लाइन · <qty> मात्रा` at `labelSmall`.
4. **When `filter.isActive`**, a visible "फ़िल्टर हटाएँ" chip calling `onClearFilter`, plus the label `फ़िल्टर लगा है` — a filtered total that looks like the whole-shop total is a way for the shopkeeper to mislead themselves about their own day. This is not optional.

## D4. Filter bar

A horizontally scrollable `Row` of `FilterChip`s directly above the list:

- **Date** — the existing आज / 7 दिन / 30 दिन `RangeMode` chips, unchanged.
- **Item** — opens a `ModalBottomSheet` containing a photo grid: `LazyVerticalGrid(GridCells.Adaptive(minSize = 96.dp))` of active catalog items rendered with `ItemIcon(itemName = it.name, imageUrl = it.imageUrl, imagePath = it.imagePath, size = 64.dp)`. Tapping one sets `filter.itemId`. This is the filter a shopkeeper who cannot read can actually operate, so it **must** be photo-first, not a list of names. (`imagePath` exists as of ISSUE-122.)
- **Time** — chip opening presets: सुबह (6-11), दोपहर (12-16), शाम (17-21), रात (22-5). Map each to `fromHour`/`toHour`. **रात wraps midnight** (22..5) which a single `>= AND <=` pair cannot express — for रात only, set `fromHour = 22, toHour = 23` and state in a code comment that the 0-5 half is deliberately excluded for now rather than silently returning nothing. Do not attempt a wrapping query in this pass.
- **Payment** — नकद / UPI / उधार → `PaymentMode.CASH/UPI/CREDIT`.
- **Type** — बिक्री / वापसी → `TxnType.SALE/RETURN`.

Selected chips use `FilterChipDefaults.filterChipColors(selectedContainerColor = ...)` with the matching `LedgerColors` value; unselected stay default.

The screen needs the catalog for the item sheet. `DailySummaryScreen` currently receives only transactions — add a parameter `catalog: List<CatalogItem> = emptyList()` and pass `catalogState` from `MainActivity` at the `Screen.SUMMARY` call site (a catalog flow is already collected there for other screens; reuse it rather than opening a second one).

Also, now that a catalog is in scope, fix the icon at line ~326 to `ItemIcon(itemName = tx.itemName, imageUrl = m?.imageUrl, imagePath = m?.imagePath, size = 40.dp)` where `m` comes from a `remember(catalog) { catalog.associateBy { it.id } }` lookup by `tx.itemId`.

## D5. Export the filtered set

Extend the existing `performCopy` (line ~89):
- Export exactly the currently-filtered rows, not the unfiltered range.
- Prepend a header line naming the active filter (e.g. `Filter: item=आलू, payment=CASH, 06:00-11:00`) so a pasted table is self-describing.
- Keep the existing trailing TOTAL/Cash/UPI/Udhaar/Margin block (lines ~100-104) — that is the same "total at the end" requirement in the export surface.
- Keep the existing pending-price safeguard dialog exactly as-is. It is the reason a ₹0 line cannot silently enter someone's spreadsheet.

---

## Out of scope — do not do

- No new `Screen` enum value, no new screen file.
- No Room migration (this workstream adds queries only).
- No change to the void flow, the price-set dialog, or the export safeguard logic.
- Do not delete the existing Kotlin-side `totalRevenue`/`cashTotal`/… computations if other parts of the screen still read them; the footer uses the SQL totals, the rest can keep working as-is.

## Verification (do it yourself)

1. `./gradlew.bat assembleDebug` compiles.
2. `./gradlew.bat :app:testDebugUnitTest` still passes (note: `test --tests` does **not** work on this Gradle version — the task is `:app:testDebugUnitTest`).
3. **Room will fail the build at compile time if a `@Query` column name doesn't match `FilteredTotals`.** If KSP errors on the aggregate, fix the SQL aliases — do not switch to `@RawQuery` to dodge it.
4. State plainly that on-device behaviour was not exercised. The real acceptance test, for later on the phone: filter to one item, confirm the footer total equals the sum of visible rows; then clear filters and confirm the footer matches `ReportsScreen`'s "कुल बिक्री" for the same range — two independent code paths agreeing.

## Audit log

Add a 🟢 RESOLVED entry for **ISSUE-119** in `Docs/audit.md`'s single `### 🟢 RESOLVED ISSUES` section (there is exactly one — do not create another). Record what was verified (compile, unit tests) vs. what was not (on-device totals agreement). Note the रात-wrap limitation explicitly rather than leaving it as a silent gap.

## Deviations

End with a "Deviations" section. If none, say "None."
