# Master Build Plan — Voice-First Business Management (Jaipur Rollout)

> ## Implementation status — updated 2026-07-30
>
> Build **v89** (`VoiceToInvoice_v89.apk`). **128 tests, 0 failures** (111 JVM + 17 instrumented).
> Migration 17→19 verified on a real database carried over from the previous release (v88).
>
> ### ✅ Done and verified
> | Area | What landed |
> |---|---|
> | Build/test infra (ISSUE-051) | Tests could not run at all before. Fixed heap, `org.json`, instrumentation runner, and **relocated the build dir out of OneDrive** so the "fails every few builds" lock issue is gone. |
> | Security (ISSUE-050) | Exported test receiver deleted — closes ISSUE-018. |
> | Data safety (ISSUE-052) | `fallbackToDestructiveMigration()` removed; failures recorded to `migration_status`. |
> | Tenancy, local half (ISSUE-049) | `ShopContext`; no more `"default_shop"` literal. Server half still open. |
> | **Phase 1 — inventory truth (ISSUE-055)** | `stock_ledger`, materialized `stockQty`, WAC costing, `costAtSale` snapshot, expiry batches, FEFO, `rebuildFromLedger()`. Void now returns stock. |
> | **Cost/profit correctness (ISSUE-054)** | Per-unit vs invoice-total confusion fixed; `ProfitCalculator` with honest coverage %. |
> | **Phase 2 — intent layer (ISSUE-053)** | Trilingual phonetic router, 12 intents, scored + gated. Handlers for PRICE_UPDATE, RETURN, PAYMENT_RECEIVED, VOID_LAST, EXPIRY, ACTION_COMMAND. **`ActionExecutor` now has real call sites** — the original bug. |
> | Udhaar | `customer_payments` + single `CustomerBalance` definition; dual-ledger fallback deleted. |
>
> ### ⏸️ Resting — needs money or a decision (per the user's cost constraint)
> - **Phone-OTP auth + server RLS (Phase 0.1–0.2)** — needs a paid SMS provider (MSG91/Twilio). Until then ISSUE-032 stays open and **multi-shop rollout is not safe**. The local half is done, and `ShopContext.bindAuthenticatedShopId` is the single swap point.
> - **Tier-2 AI Q&A + AI intent arbitration (Phase 4.1 step 2)** — per-utterance LLM cost. The free template tier covers the common questions; the AI fallback stays off.
>
> ### ⛔ Not yet built (free, no blocker — next up)
> Server mirror `intent_router.ts` (**important**: commands processed with the app closed still use the old logic), `CommitSequencer` ordering, Command Feed UI, daily rollups + Reports screen, `AlertEngine`, health score, reorder/forecast, `ShopLearning`, bill rendering + the three WhatsApp entry points beyond the voice one.

**Status**: IN PROGRESS — see status block above
**Author**: Claude Code (planning session, 2026-07-30)
**Implementer**: Antigravity — execute phases in order, word-by-word
**Scope**: All 30 features requested, plus the release blockers that must land first

---

## 0. Read this before starting

### 0.1 The one honest correction to the brief

The ask was: *"a really feature packed final app... this time full app not half built"*, in one build. I am going to give you the full plan for every feature — but I have to flag one thing up front, because it is the difference between this working and repeating the assistant experience:

**The assistant "not sending anything anywhere" was not a coding mistake. It was a wiring mistake, and this plan's structure is designed to make that class of failure impossible.** Concretely, right now in your repo:

| File | Lines | Call sites |
|---|---|---|
| [ActionExecutor.kt](app/src/main/java/com/voicetoinvoice/app/domain/action/ActionExecutor.kt) | 42 | **0** |
| [ConversationController.kt](app/src/main/java/com/voicetoinvoice/app/domain/voice/ConversationController.kt) | 57 | **0** |
| [WakeWordController.kt](app/src/main/java/com/voicetoinvoice/app/domain/wakeword/WakeWordController.kt) | 32 | **0** |

`ActionExecutor.sendWhatsAppReminder()` — a fully written WhatsApp function — is called from nowhere. Meanwhile [SttWorker.kt:479](app/src/main/java/com/voicetoinvoice/app/domain/processor/SttWorker.kt#L479) answers every action command with `"यह काम अभी नहीं कर सकता"` ("I can't do this yet"). The code to do it existed. Nothing called it. That is exactly the symptom you described.

So every phase below ends with a **Wiring Gate** — an explicit list of "this new code is called from these exact lines". A phase is not done when the classes compile. It is done when the gate passes. Do not skip these.

The second correction: this is **~5 phases of work, not one build**. But each phase ships a complete, shippable app — no phase leaves a feature half-wired. That is the opposite of "build everything at once and discover at the end that half of it isn't connected." Ship in order.

### 0.2 The release blocker you don't know you have

You said *"all over Jaipur shopkeepers will use it."* Two open issues in [audit.md](Docs/audit.md) make that unsafe **today**:

1. **ISSUE-032 — every live row has `shop_id = NULL`, and RLS is disabled on `transactions`, `unmatched_queue`, `stt_job_logs`.** `SELECT count(*) FROM shops` returns 0. The app is single-tenant in practice. The moment shop #2 installs it, **both shops write into the same undifferentiated tables**, and because RLS is off, the client-embedded anon key in [SupabaseConfig.kt](app/src/main/java/com/voicetoinvoice/app/network/SupabaseConfig.kt) can read and write *every shop's* sales ledger, raw transcripts, and audio URLs. Shop A's catalog learning and learned parses will also contaminate shop B's.
2. **ISSUE-018 — an unconditionally exported `BroadcastReceiver`** in [UpiNotificationListenerService.kt](app/src/main/java/com/voicetoinvoice/app/service/UpiNotificationListenerService.kt) lets *any other app on the shopkeeper's phone* inject fake sales into the real ledger (`SEED_TEST_TX`) or falsely mark an Udhaar as paid (`TEST_UPI`).

Neither is a "nice to have later." A multi-shop rollout on top of this leaks every shop's books to every other shop. **Phase 0 fixes both.** It is not optional and it goes first.

### 0.3 Standing rules for this build

- **The mirror rule.** Parsing/intent logic exists twice: Kotlin ([domain/parser/](app/src/main/java/com/voicetoinvoice/app/domain/parser/), [domain/router/](app/src/main/java/com/voicetoinvoice/app/domain/router/)) and Deno ([process-voice-job/index.ts](supabase/functions/process-voice-job/index.ts), 2015 lines). Every phase below states explicitly **CLIENT / SERVER / BOTH**. If it says BOTH and you only did one, the feature will work when the app is open and silently fail when it's closed — the worst possible bug class for a shopkeeper.
- **Never let AI invent a number.** For every business question, aggregates are computed in SQL/Kotlin and the AI only *phrases* them. An AI that hallucinates today's revenue destroys shopkeeper trust permanently. This is a hard architectural rule, enforced in Phase 4.
- **Room migrations**: current version is **17** ([AppDatabase.kt:36](app/src/main/java/com/voicetoinvoice/app/data/local/AppDatabase.kt#L36)). Follow the existing manual pattern exactly — bump `version`, add `MIGRATION_N_N+1` with try/catch'd `ALTER TABLE`, register in `addMigrations(...)`. No auto-migrations.
- **Supabase migrations**: latest is `20260731000000_stock_cost_missing.sql`. Continue the date-prefix sequence.
- **Audit log**: highest issue is **ISSUE-048**. New entries start at **ISSUE-049**.
- **Stop and ask** if a step names a file/symbol that doesn't exist, or contradicts the code. Do not improvise silently.

---

## Phase 0 — Multi-Tenancy, Auth & Security (RELEASE BLOCKER)

**Goal**: one Jaipur shop's data is cryptographically inaccessible to another. Nothing else in this plan is safe to ship without it.
**Side**: BOTH
**Room**: 17 → 18
**Supabase migration**: `20260801000000_shop_identity_and_rls.sql`

### 0.1 Shop identity

Currently `shopId` defaults to the string literal `"default_shop"` on every entity ([CatalogItem.kt:11](app/src/main/java/com/voicetoinvoice/app/data/local/entity/CatalogItem.kt#L11), `TransactionRecord`, `StockInRecord`, `CreditRecord`, `CustomerRecord`), and NULL on every server row.

**Decision: Supabase Auth with phone OTP.** Indian shopkeepers are phone-first, have no email discipline, and change devices. Phone OTP also gives you a natural `shop_id` anchor and free device-migration story. Do not use email/password. Do not use anonymous auth — it cannot survive a reinstall, and a shopkeeper losing their entire ledger on reinstall is unrecoverable trust damage.

1. New entity `app/src/main/java/com/voicetoinvoice/app/data/local/entity/ShopProfile.kt`:
   ```kotlin
   @Entity(tableName = "shop_profile")
   data class ShopProfile(
       @PrimaryKey val id: String,          // == Supabase auth.uid()
       val shopName: String,
       val ownerName: String,
       val phone: String,
       val shopType: ShopType,              // VEGETABLE, KIRANA, GENERAL, MEDICAL, DAIRY, OTHER
       val city: String = "Jaipur",
       val defaultLeadTimeDays: Int = 2,
       val createdAt: Long = System.currentTimeMillis(),
       val synced: Boolean = false
   )
   enum class ShopType { VEGETABLE, KIRANA, GENERAL, MEDICAL, DAIRY, OTHER }
   ```
   `ShopType` drives feature gating later (expiry tracking defaults on for KIRANA/MEDICAL/DAIRY, off for VEGETABLE — see Phase 1.5).
2. New `data/local/dao/ShopProfileDao.kt` — `getProfile(): Flow<ShopProfile?>`, `getProfileOnce()`, `upsert()`, `getUnsynced()`, `markSynced()`.
3. New `data/ShopContext.kt` — a process-wide singleton holding the resolved `shopId`, loaded once in `MainActivity.onCreate()` before any DAO write. Expose `ShopContext.requireShopId(): String`. It **throws** if unset. Every entity default `shopId = "default_shop"` is replaced by an explicit constructor argument at each call site — remove the default so the compiler finds every site for you.
4. `MIGRATION_17_18`: create `shop_profile`; then `UPDATE <table> SET shopId = '<resolvedId>' WHERE shopId = 'default_shop'` for all 6 tables carrying `shopId`. On a device with no auth session yet, leave `'default_shop'` and let onboarding backfill (step 6).
5. Rework [OnboardingScreen.kt](app/src/main/java/com/voicetoinvoice/app/ui/screens/onboarding/OnboardingScreen.kt) into: phone entry → OTP verify → shop name/owner/type → `ShopProfile` written → local backfill of `'default_shop'` rows to the real `shopId`. Onboarding is **mandatory and blocking**; no ledger screen is reachable without a `ShopProfile`.
6. New `network/AuthManager.kt` — `sendOtp(phone)`, `verifyOtp(phone, code)`, `currentSession()`, `refreshIfNeeded()`, `signOut()`. Store the refresh token in `EncryptedSharedPreferences` (add `androidx.security:security-crypto`). All of [CloudSyncManager.kt](app/src/main/java/com/voicetoinvoice/app/network/CloudSyncManager.kt) switches from bare anon key to `Authorization: Bearer <access_token>`, with a single refresh-and-retry on 401.

### 0.2 Server: real tenancy + RLS on

`20260801000000_shop_identity_and_rls.sql`:

1. `CREATE TABLE IF NOT EXISTS shops (id uuid PRIMARY KEY REFERENCES auth.users(id), shop_name text NOT NULL, owner_name text, phone text, shop_type text NOT NULL DEFAULT 'GENERAL', city text DEFAULT 'Jaipur', default_lead_time_days int DEFAULT 2, created_at timestamptz DEFAULT now());`
2. Backfill: create one shop row for the existing production data, then `UPDATE transactions SET shop_id = '<thatId>' WHERE shop_id IS NULL` — and the same for `catalog_items`, `unmatched_queue`, `stt_job_logs`, `credits`, `stock_in`, `customers`. **Do this before enabling RLS**, or existing rows become invisible to their own owner.
3. Then, per table: `ALTER TABLE <t> ADD CONSTRAINT ... NOT NULL` on `shop_id` (now safe), `ENABLE ROW LEVEL SECURITY`, and replace the permissive `USING (true)` policies from [schema.sql](supabase/schema.sql) with:
   ```sql
   CREATE POLICY tenant_rw ON <t> FOR ALL
     USING (shop_id = auth.uid()) WITH CHECK (shop_id = auth.uid());
   ```
   Apply to: `transactions`, `catalog_items`, `credits`, `stock_in`, `customers`, `unmatched_queue`, `stt_job_logs`, `suppliers`, `term_aliases`, `learned_parses`.
4. **`learned_parses` and `term_aliases` must become shop-scoped.** Today they are global. A shop-specific memoized parse leaking across shops means shop B inherits shop A's mistakes and vocabulary — this actively degrades the per-shop learning moat. Add `shop_id` to both, include it in the lookup key, and drop the global-read path.
5. `process-voice-job` uses the **service-role** client and therefore bypasses RLS — correct and unchanged. But it must now (a) resolve `shop_id` from the authenticated JWT on the incoming request rather than trusting a body field, and (b) stamp `shop_id` on every row it writes. A client-supplied `shop_id` in the request body is a tenancy bypass; reject it. Verify the JWT with `SUPABASE_JWT_SECRET` and take `sub` as the shop id.
6. Update [audit.md](Docs/audit.md): close **ISSUE-032** with an ISSUE-049 entry describing the resolution.

### 0.3 Close ISSUE-018

Delete the `SEED_TEST_TX` / `TEST_UPI` receiver from [UpiNotificationListenerService.kt](app/src/main/java/com/voicetoinvoice/app/service/UpiNotificationListenerService.kt) outright. It has no place in a shipped ledger app. If manual UPI testing is still wanted, gate it behind a signature-level permission — but `app/build.gradle.kts` has `buildFeatures.buildConfig = false`, so enable `buildConfig = true` first and guard with `BuildConfig.DEBUG`. **Recommendation: just delete it.** Log as ISSUE-050.

### 0.4 Wiring Gate — Phase 0

- [ ] `ShopContext.requireShopId()` is called (directly or transitively) by every `insert`/`upsert` in `SttWorker`, `CloudSyncManager`, `SyncEngine`, and every UI write path. Verify with `grep -rn "default_shop" app/src/main` → **must return zero hits.**
- [ ] `AuthManager` is called from `MainActivity.onCreate()` and every `CloudSyncManager` request carries a Bearer token. `grep -rn "SUPABASE_ANON_KEY" app/src/main` → only the auth/OTP endpoints.
- [ ] Manual test: sign in as shop A, create a sale; sign in as shop B on a second device/emulator; shop B's ledger and catalog show **none** of shop A's data. Confirm the same via `execute_sql` as an authenticated (not service-role) user.
- [ ] `adb shell am broadcast -a com.voicetoinvoice.app.SEED_TEST_TX --ei amount 100` → no transaction appears.

---

## Phase 1 — Inventory Truth: Stock Ledger + Cost of Goods

**Goal**: one trustworthy `onHand` number per item, and a defensible profit figure. Every inventory and money feature downstream depends on this, so it lands before them.
**Side**: BOTH
**Room**: 18 → 19
**Supabase migration**: `20260802000000_stock_ledger_and_cost.sql`

### 1.1 Why the current stock number is wrong

[CatalogDao.kt:45-52](app/src/main/java/com/voicetoinvoice/app/data/local/dao/CatalogDao.kt#L45) derives on-hand live:

```sql
COALESCE((SELECT SUM(s.quantity) FROM stock_in s WHERE s.itemId = c.id), 0.0) -
COALESCE((SELECT SUM(t.quantity) FROM transactions t WHERE t.itemId = c.id), 0.0)
```

Three defects:

1. **It does not filter `voided = 0`.** Every other transaction query in [TransactionDao.kt](app/src/main/java/com/voicetoinvoice/app/data/local/dao/TransactionDao.kt) filters it; this one doesn't. So voiding a wrongly-booked sale **never returns the stock** — the item stays permanently short. Given `voidTransaction` is the correction signal for Learned Parse Memory (ISSUE-031), this fires often.
2. **Two correlated subqueries per catalog item, on every recomposition.** With 135 catalog items and a year of transactions, this is a full scan of `transactions` per item, on the UI thread's Flow. It will visibly hang after a few months.
3. **It cannot express *why* stock moved** — no opening stock, no return, no expiry write-off, no recount. Waste is currently smuggled in as a *negative* `stock_in.quantity` ([SttWorker.kt:312](app/src/main/java/com/voicetoinvoice/app/domain/processor/SttWorker.kt#L312)), which means "stock purchased" and "stock spoiled" are indistinguishable in the same column — so purchase totals and spoilage reporting are both unrecoverable.

### 1.2 The fix: append-only stock ledger + materialized quantity

**Decision: ledger + materialized column, not derived-on-read.** This is the standard inventory pattern. The ledger gives you an audit trail and the ability to rebuild; the materialized column gives you O(1) reads for low-stock alerts and UI.

New entity `data/local/entity/StockLedgerEntry.kt`:

```kotlin
@Entity(
    tableName = "stock_ledger",
    indices = [Index("itemId"), Index("occurredAtMs"), Index(value = ["refId"])]
)
data class StockLedgerEntry(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val shopId: String,
    val itemId: String,
    val itemName: String,           // denormalized for unlisted-item resilience
    val deltaQty: Double,           // SIGNED: +in, -out
    val reason: StockReason,
    val unitCost: Double? = null,   // per-unit cost for IN movements; null when unknown
    val refId: String? = null,      // transactions.id / stock_in.id / bill id
    val batchId: String? = null,    // Phase 1.5 expiry batches
    val note: String? = null,
    val occurredAtMs: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)

enum class StockReason {
    OPENING,        // first-time count at onboarding
    STOCK_IN,       // purchase received
    SALE,           // negative
    SALE_VOID,      // positive, reverses a SALE
    RETURN_IN,      // customer returned goods -> back on shelf
    RETURN_TO_SUPPLIER,
    WASTE,          // spoiled / damaged
    EXPIRY,         // written off past expiry
    RECOUNT,        // physical count adjustment (delta = counted - system)
    MANUAL_ADJUST
}
```

Add to `CatalogItem` (via `MIGRATION_18_19`, `ALTER TABLE catalog_items ADD COLUMN ...`):

```kotlin
val stockQty: Double = 0.0,          // materialized on-hand
val avgCostPrice: Double? = null,    // weighted-average cost, null = never had a costed IN
val trackStock: Boolean = true,
val trackExpiry: Boolean = false,    // Phase 1.5
val lastSoldAtMs: Long? = null,      // fast/slow-moving analysis (Phase 4)
val lastStockedAtMs: Long? = null
```

`lowStockThreshold` already exists and stays.

### 1.3 `StockLedgerRepository` — the single write path

New `data/repository/StockLedgerRepository.kt`. **This is the only code in the app permitted to change `catalog_items.stockQty`.** Every movement goes through it.

```kotlin
suspend fun record(
    itemId: String, itemName: String, deltaQty: Double, reason: StockReason,
    unitCost: Double? = null, refId: String? = null, batchId: String? = null, note: String? = null
): StockLedgerEntry
```

Implementation, inside a single `db.withTransaction { }`:
1. Insert the `StockLedgerEntry`.
2. `catalogDao.applyStockDelta(itemId, deltaQty, occurredAtMs)` — new `@Query("UPDATE catalog_items SET stockQty = stockQty + :delta, lastSoldAtMs = CASE WHEN :delta < 0 THEN :ts ELSE lastSoldAtMs END, lastStockedAtMs = CASE WHEN :delta > 0 THEN :ts ELSE lastStockedAtMs END, synced = 0 WHERE id = :itemId")`.
3. If `deltaQty > 0 && unitCost != null`, recompute weighted-average cost **before** the quantity update reaches the new value:
   ```
   newAvg = (oldQty * oldAvg + deltaQty * unitCost) / (oldQty + deltaQty)
   ```
   with `oldAvg` falling back to `unitCost` when null, and the whole thing skipped when `oldQty + deltaQty <= 0`.

**Decision: weighted-average cost (WAC), not FIFO.** FIFO requires lot-level consumption tracking and would force every sale to resolve which batch it drew from — unusable by voice, and vegetable shops do no lot accounting. WAC is what Vyapar/Tally use for this segment and it survives partial cost data. Expiry batches (Phase 1.5) track *dates* for alerting but do **not** drive costing.

`MIGRATION_18_19` must also **backfill the ledger from history** so existing shops don't start at zero:
- one `STOCK_IN` entry per existing `stock_in` row where `quantity > 0` (with `unitCost = costPrice` when `costMissing = 0`),
- one `WASTE` entry per existing `stock_in` row where `quantity < 0` (this un-smuggles the negative-quantity hack),
- one `SALE` entry per non-voided `transactions` row,
- then `UPDATE catalog_items SET stockQty = (SELECT COALESCE(SUM(deltaQty),0) FROM stock_ledger WHERE itemId = catalog_items.id)`,
- then seed `avgCostPrice` per item as the cost-weighted average of its costed stock-ins.

Wrap each step in try/catch per the existing migration style, but **log failures to a `migration_18_19_status` row** rather than swallowing them — a silent partial backfill produces wrong stock numbers that look plausible, which is worse than an obvious failure.

### 1.4 Rewire every existing stock/sale write

Replace all direct `stockInDao().insert(...)` and stock-affecting `transactionDao().insert(...)` sites:

| Call site | Change |
|---|---|
| [SttWorker.kt:305-319](app/src/main/java/com/voicetoinvoice/app/domain/processor/SttWorker.kt#L305) (stock-in / waste) | keep the `StockInRecord` insert for purchase history, **then** `stockLedger.record(... reason = STOCK_IN or WASTE, unitCost = costPrice.takeIf { !costMissing })`. Stop using negative `quantity` for waste — waste writes `+abs` to `stock_in`? No: **waste no longer writes a `stock_in` row at all**, only a `WASTE` ledger entry. |
| [SttWorker.kt:321-340](app/src/main/java/com/voicetoinvoice/app/domain/processor/SttWorker.kt#L321) (sale) | after `transactionDao().insert(txRecord)`, `stockLedger.record(deltaQty = -qty, reason = SALE, refId = txRecord.id)` |
| `TransactionDao.voidTransaction` | callers must additionally `stockLedger.record(deltaQty = +qty, reason = SALE_VOID, refId = txId)`. Add `StockLedgerRepository.voidSale(txId)` that does both atomically and make it the only void entry point. |
| [StockInScreen.kt](app/src/main/java/com/voicetoinvoice/app/ui/screens/stockin/StockInScreen.kt) manual add | route through the repository |
| Cost back-fill for `costMissing` rows | new `StockLedgerRepository.backfillCost(stockInId, unitCost)` — updates the ledger entry's `unitCost` **and** recomputes `avgCostPrice` |

Then **replace** `CatalogDao.getStockLevels()` with `@Query("SELECT id AS itemId, stockQty AS onHand FROM catalog_items WHERE active = 1")`, keeping the existing `StockLevel` shape so [MainActivity.kt:92](app/src/main/java/com/voicetoinvoice/app/MainActivity.kt#L92) needs no change.

Add a debug-only `StockLedgerRepository.rebuildFromLedger()` and a Settings button behind it, for the one inevitable case where a shop's number drifts.

### 1.5 COGS on the transaction, and honest profit

Add to `TransactionRecord` (`MIGRATION_18_19`):

```kotlin
val costAtSale: Double? = null,   // avgCostPrice snapshot at commit time; null = cost unknown
val txnType: TxnType = TxnType.SALE,
val billId: String? = null,
val returnOfTxnId: String? = null
```
```kotlin
enum class TxnType { SALE, RETURN }
```

At every sale commit, snapshot `costAtSale = catalogItem.avgCostPrice` (nullable). **Never** compute historical profit by applying today's cost to last month's sales — for vegetables where cost swings 40% week to week, that produces nonsense.

**Honest-profit rule (hard requirement).** Gross profit is reported only over lines where `costAtSale != null`, and **always** alongside coverage:

> आज का मुनाफ़ा: ₹1,240 — (आपकी 82% बिक्री का हिसाब; 18% का खरीद भाव नहीं पता)

A single confidently-wrong profit number will cost you the shopkeeper permanently. `costMissing` stock-ins are common by design ([StockInRecord.costMissing](app/src/main/java/com/voicetoinvoice/app/data/local/entity/StockInRecord.kt)), so coverage will genuinely be below 100% and must be surfaced, not hidden.

New `domain/query/ProfitCalculator.kt`:
```kotlin
data class ProfitResult(
    val revenue: Double, val cogs: Double, val grossProfit: Double,
    val marginPct: Double, val costCoveragePct: Double,
    val linesWithCost: Int, val linesWithoutCost: Int
)
suspend fun compute(startMs: Long, endMs: Long): ProfitResult
```
Backed by one SQL aggregate, not a Kotlin fold:
```sql
SELECT SUM(total) AS revenue,
       SUM(CASE WHEN costAtSale IS NOT NULL THEN quantity * costAtSale END) AS cogs,
       SUM(CASE WHEN costAtSale IS NOT NULL THEN total END) AS revenueWithCost,
       SUM(CASE WHEN costAtSale IS NULL THEN 1 ELSE 0 END) AS linesWithoutCost,
       COUNT(*) AS lines
FROM transactions
WHERE voided = 0 AND txnType = 'SALE' AND shopId = :shopId
  AND timestamp >= :startMs AND timestamp < :endMs
```
`marginPct` uses `revenueWithCost` as its denominator, not `revenue` — mixing costed and uncosted lines understates margin.

### 1.6 Expiry batches (opt-in)

**Decision: opt-in per item, defaulted by `ShopType`.** A vegetable seller does not want expiry prompts on tomatoes; a kirana/medical shop needs them on packaged goods and strips. `trackExpiry` defaults to `true` for `KIRANA`/`MEDICAL`/`DAIRY` shop types and `false` for `VEGETABLE`. Expiry UI is **completely hidden** for items with `trackExpiry = false` — no empty columns, no dead prompts.

New entity `data/local/entity/StockBatch.kt`:
```kotlin
@Entity(tableName = "stock_batches", indices = [Index("itemId"), Index("expiryDateMs")])
data class StockBatch(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val shopId: String, val itemId: String,
    val receivedQty: Double, val remainingQty: Double,
    val unitCost: Double?, val expiryDateMs: Long?,
    val receivedAtMs: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)
```
Batches are created only on `STOCK_IN` for `trackExpiry` items. Sales draw down `remainingQty` **nearest-expiry-first** (FEFO), which is what a shopkeeper actually does physically. `EXPIRY` write-offs come from a daily check (Phase 3.4 alerts) over `expiryDateMs < now + 7d AND remainingQty > 0`.

### 1.7 Wiring Gate — Phase 1

- [ ] `grep -rn "stockInDao().insert\|transactionDao().insert" app/src/main` → every hit is inside `StockLedgerRepository`, or immediately followed by a `stockLedger.record(...)` call.
- [ ] `grep -rn "stockQty = " app/src/main` → only inside `StockLedgerRepository` / `CatalogDao.applyStockDelta`.
- [ ] Unit test `app/src/test/.../StockLedgerRepositoryTest.kt`: stock-in 10kg → sale 3kg → void that sale → on-hand is **10.0**, not 7.0. This is the exact bug from 1.1(1); it must be a regression test, not a manual check.
- [ ] Unit test `ProfitCalculatorTest`: 3 lines, 1 without cost → `costCoveragePct` is computed off revenue-with-cost and `marginPct` never divides by total revenue.
- [ ] Unit test `WacTest`: 10 @ ₹20 then 10 @ ₹30 → `avgCostPrice == 25.0`; then a `costMissing` IN of 5 → avg stays 25.0 (unknown cost must not be treated as ₹0).
- [ ] On-device: run the 17→19 migration against a real pre-existing DB, confirm `stockQty` matches the old derived formula for a sample of 10 items **plus** the void correction.

---

## Phase 2 — The Complete Voice Command Layer

**Goal**: every spoken command the brief lists actually routes somewhere real, in Hindi *and* Hinglish *and* English, in spoken order, with visible status.
**Side**: BOTH — `IntentRouter.kt` (Kotlin) and a new `intent_router.ts` (Deno). This is the highest-risk mirror in the plan.
**Room**: 19 → 20

### 2.1 What's broken in the router today

[IntentRouter.kt](app/src/main/java/com/voicetoinvoice/app/domain/router/IntentRouter.kt) is 79 lines of Devanagari substring matching:

```kotlin
private val QUESTION_WORDS = setOf("कितना", "कितने", "कितनी", "कब", "कौन", ...)
```

Four structural problems:

1. **Devanagari-only, but your STT frequently returns Latin.** ISSUE-004/ISSUE-020 document exactly this: `"तीन किलो बैंगन"` comes back as `"Tinggal benggan"`. A shopkeeper saying *"aaj ki sale kitni hui"* hits **zero** keywords and falls through to `UNKNOWN`. The brief asks for "Natural Hindi + English + Hinglish support" — the router is the one place that is currently monolingual.
2. **`ACTION_COMMAND` is unreachable.** `ACTION_WORDS` is declared at line 25 and **never read**. No branch returns `ACTION_COMMAND`. And if it did, [SttWorker.kt:479](app/src/main/java/com/voicetoinvoice/app/domain/processor/SttWorker.kt#L479) answers `"यह काम अभी नहीं कर सकता"`. This dead-end plus the 0-call-site `ActionExecutor` **is** the "doesn't send anything anywhere" bug.
3. **Missing intents entirely**: no `PRICE_UPDATE`, no `RETURN`, no `PAYMENT_RECEIVED`, no `VOID`. The brief asks for voice price changes and voice returns; neither has an intent. (Price *does* partly work through the server's `price_intent: RATE_UPDATE` path at [index.ts:1436](supabase/functions/process-voice-job/index.ts#L1436) → `isValidRateUpdate` → `updatePrice`, but only as a side effect of a sale-shaped utterance — a bare *"aaloo ka rate 30 kar do"* is not reliably routed.)
4. **First-match-wins ordering with no scoring.** `"आलू खराब हो गया, 2 किलो फेंक दिया"` and `"खराब आलू का उधार लिख दो"` both hit `WASTE` first. There's no confidence, no tie-break, no arbitration.

### 2.2 Rebuild: phonetic lexicon + scored classification + AI arbitration

Full rewrite of `IntentRouter.kt`. Keep the `IntentClassification` return shape (SttWorker and AssistantFastPath already consume it) but replace everything inside.

**Intent set** (extend `AssistantIntent`):
```kotlin
enum class AssistantIntent {
    SALE, CREDIT_SALE, PAYMENT_RECEIVED, STOCK_IN, RETURN, WASTE, EXPIRY_WRITEOFF,
    PRICE_UPDATE, VOID_LAST, READ_QUERY, ACTION_COMMAND, UNKNOWN
}
```
Extend `CaptureIntent` to match: add `PAYMENT_RECEIVED`, `RETURN`, `PRICE_UPDATE`, `VOID_LAST`, `EXPIRY_WRITEOFF`.

**Decision: score every intent, don't first-match.** New `domain/router/IntentLexicon.kt` holding, per intent, a list of trigger phrases in **all three scripts**, each pre-reduced to a `PhoneticKey`. Reuse [PhoneticKey.kt](app/src/main/java/com/voicetoinvoice/app/domain/parser/PhoneticKey.kt) — it is already script-agnostic and already solved this problem for item names in ISSUE-020. Matching intents in the same phonetic key space is the single highest-leverage change in this phase.

```kotlin
data class IntentTrigger(
    val intent: AssistantIntent,
    val phrases: List<String>,   // "kitna", "कितना", "how much", "kitni hui"
    val weight: Double,          // 1.0 strong, 0.6 weak/ambiguous
    val requiresItemLines: Boolean? = null   // null = don't care
)
```

Minimum lexicon coverage — every row needs Devanagari + Latin-Hinglish + English:

| Intent | Examples (all three scripts required) |
|---|---|
| `READ_QUERY` | कितना/कितनी/कितने, kitna, kitni, how much, बताओ, batao, tell me, आज की सेल, aaj ki sale, today's sales, स्टॉक कितना, stock kitna |
| `PAYMENT_RECEIVED` | दे दिए, de diye, paise diye, चुका दिया, chuka diya, जमा, jama, paid, received, बाकी चुकाया |
| `RETURN` | वापस, wapas, return, लौटा, lauta, वापस कर दिया, return kar diya |
| `PRICE_UPDATE` | रेट, rate, भाव, bhav, price, कर दो, kar do, रेट बदलो, rate change |
| `WASTE` | खराब, kharab, सड़ गया, sad gaya, फेंका, phenka, टूटा, waste, spoiled |
| `EXPIRY_WRITEOFF` | एक्सपायर, expire, expired, तारीख निकल गई, date nikal gayi |
| `STOCK_IN` | आया, aaya, आ गया, aa gaya, खरीदा, kharida, लाया, laya, माल आया, maal aaya, stock aaya, purchase |
| `CREDIT_SALE` | उधार, udhaar, udhar, खाते में, khate mein, बही, बाकी, लिख दो, likh do, credit, khata |
| `VOID_LAST` | गलत, galat, कैंसिल, cancel, हटाओ, hatao, मिटा दो, mita do, wrong, delete |
| `ACTION_COMMAND` | भेजो, bhejo, send, व्हाट्सएप, whatsapp, बिल भेजो, bill bhejo, कॉल, call, मैसेज, message, याद दिलाओ, remind |

**Classification algorithm** (replaces the if-ladder):

1. Tokenize the transcript; compute `PhoneticKey.of()` per token and per adjacent bigram (multi-word triggers like "de diye" / "kar do" need bigrams).
2. For each `IntentTrigger`, score `= weight × max(0, 1 − PhoneticKey.normalizedDistance(token, phrase))` over all tokens/bigrams, taking the best match per trigger, summed per intent. Accept a phonetic match at `normalizedDistance ≤ 0.25`.
3. Apply structural gates: `hasItemLines` (existing logic, keep it) satisfies/blocks `requiresItemLines`; a bare number with a customer name and a payment trigger strongly favors `PAYMENT_RECEIVED` over `CREDIT_SALE` (opposite signs on the same ledger — getting this backwards doubles the error).
4. `PRICE_UPDATE` requires a rate trigger **and** exactly one item **and** a price, with no quantity>1 — otherwise it's a `SALE`. Preserve the existing server `price_intent` arbitration rather than duplicating it: if the server already returned `RATE_UPDATE` for a line, that wins.
5. Confidence = `topScore / (topScore + secondScore)`, clamped to `[0,1]`.
   - `≥ 0.75` → route directly.
   - `0.45–0.75` → **AI arbitration**: single Grok call, transcript + the top-2 candidate intents + the shop's catalog names, asked to pick one and return `{intent, confidence, reason}`. Cache by `PhoneticKey` of the whole transcript so a repeated phrasing is free the second time (reuse the `learned_parses` mechanism from ISSUE-031, now shop-scoped per Phase 0.2.4).
   - `< 0.45` → `UNKNOWN` → clarify (2.5).
6. Every classification writes into the diagnostic trace as a new `step_2b_intent_classification` block: scores per intent, chosen intent, confidence, whether AI arbitrated. **Without this you cannot debug misroutes in production** — and misroutes are the failure mode shopkeepers will report as "it did the wrong thing".

Mirror all of the above into `supabase/functions/process-voice-job/intent_router.ts`, plus `intent_router_test.ts` sharing the same fixture list as the Kotlin test. **Both sides must produce identical intents for the same 60-phrase fixture set** — that shared fixture list is the mirror-drift guard.

### 2.3 New command handlers (each one wired, each one confirmed aloud)

Replace `handleAssistantJob`'s `when` block ([SttWorker.kt:419-487](app/src/main/java/com/voicetoinvoice/app/domain/processor/SttWorker.kt#L419)). Every branch below must **commit something and speak a confirmation naming what changed**:

| Intent | Handler | Commits | Spoken confirmation |
|---|---|---|---|
| `PRICE_UPDATE` | `domain/action/PriceUpdateHandler.kt` | `catalogDao.updatePrice`; append `price_history` row (new table, for trend analysis) | "आलू का रेट ₹30 कर दिया" |
| `RETURN` | `domain/action/ReturnHandler.kt` | `TransactionRecord(txnType = RETURN, total = -x, returnOfTxnId = <resolved>)` + `stockLedger.record(+qty, RETURN_IN)`; if the original was CREDIT, reduce the customer's balance | "2 किलो आलू वापस लिया, ₹60 कम किए" |
| `PAYMENT_RECEIVED` | `domain/action/PaymentHandler.kt` | new `CustomerPayment` row (2.4) + customer balance recompute | "रमेश ने ₹500 दिए, अब ₹200 बाकी" |
| `WASTE` / `EXPIRY_WRITEOFF` | `StockLedgerRepository.record(WASTE/EXPIRY)` | ledger only — **no more negative `stock_in` rows** | "2 किलो टमाटर खराब दर्ज किया" |
| `VOID_LAST` | `StockLedgerRepository.voidSale` on the most recent non-voided txn from this shop within 10 min | void + `SALE_VOID` ledger entry | "पिछली एंट्री हटा दी — 3 किलो प्याज ₹90" |
| `ACTION_COMMAND` | `domain/action/ActionCommandHandler.kt` → **`ActionExecutor`** (finally called) | see Phase 5 | "रमेश को बिल भेजने के लिए तैयार है" |
| `READ_QUERY` | `QuestionTemplates` → Phase 4's `BusinessQnA` | nothing | the answer |

`VOID_LAST` has a 10-minute window and only ever targets the newest matching transaction — an unbounded voice "delete" over an append-only ledger is how a shopkeeper loses a day's books to a misheard word.

### 2.4 Customer payments & one unified balance

Today there are **two** credit ledgers: legacy `CreditRecord` ([CreditRecord.kt](app/src/main/java/com/voicetoinvoice/app/data/local/entity/CreditRecord.kt)) and `CustomerRecord` + `CustomerDao.getOutstandingFor()`, and [LedgerQueries.kt:25-32](app/src/main/java/com/voicetoinvoice/app/domain/query/LedgerQueries.kt#L25) tries the new one then *falls back* to the legacy one. Two sources of truth for money owed will produce two different numbers on two screens, and the shopkeeper will trust neither.

1. New entity `data/local/entity/CustomerPayment.kt` — `id`, `shopId`, `customerId`, `amount`, `mode: PaymentMode`, `note`, `receivedAtMs`, `jobId`, `synced`.
2. New `domain/query/CustomerBalance.kt` — **the one** balance definition:
   ```
   balance = Σ(CREDIT sales) − Σ(returns against credit sales) − Σ(CustomerPayment)
   ```
   as a single SQL query per customer, plus a bulk `getAllBalances()` for the list screen.
3. `MIGRATION_19_20` migrates every `CreditRecord` into `customers` + `CustomerPayment` (a `PAID` credit becomes a credit sale plus an offsetting payment), then marks the `credits` table **read-only legacy**. Delete the fallback branch in `LedgerQueries`. Do not leave both paths live.
4. Receivables aging: 0–7 / 8–30 / 31+ days, from the oldest unpaid credit sale per customer. Feeds the health score (3.5) and reminders (5.3).

### 2.5 Ordered queue, background processing, and status for every command

The brief asks for four things that are really one mechanism: *queue in spoken order*, *keep talking while it processes*, *clear status per command*, *confirm only when unsure*.

**Ordering — the subtle part.** Commands must **commit** in spoken order even though STT latency varies. If "aaloo ka rate 30 kar do" is spoken before "5 kilo aaloo", but its STT round-trip returns second, the sale books at the *old* price. Strict serial processing would fix it but makes every command wait for the slowest one.

**Decision: parallel recognition, serialized commit.**
- STT + parse stay parallel (unchanged — this is what keeps the mic responsive).
- Add a **commit barrier**: a process-wide `Mutex` in new `domain/processor/CommitSequencer.kt`. A job may commit only when every job with a smaller `recordedAtMs` has reached a terminal state (`AUTO_CONFIRMED`, `PARTIALLY_CONFIRMED`, `PARSED`, `FAILED`). Implement as: acquire mutex → `sttJobDao.countUnterminatedBefore(recordedAtMs) == 0` → commit; else `delay(150ms)` and re-check, with a **6-second ceiling** after which the job commits anyway and its trace records `commit_order_violated: true`. An unbounded barrier means one stuck network call freezes the whole ledger — the ceiling is the safety valve, and the flag makes the trade visible in diagnostics.
- Add `@Query("SELECT COUNT(*) FROM stt_jobs WHERE recordedAtMs < :ts AND status IN ('QUEUED','UPLOADING','PROCESSING')") suspend fun countUnterminatedBefore(ts: Long): Int` to `SttJobDao`.

**Status UI.** [PendingConfirmationsSheet.kt](app/src/main/java/com/voicetoinvoice/app/ui/components/PendingConfirmationsSheet.kt) only shows items needing confirmation. Replace with a **Command Feed** — new `ui/components/CommandFeedSheet.kt`, showing every utterance from the last 24h, newest first:

| State | Shown as |
|---|---|
| QUEUED / UPLOADING / PROCESSING | spinner + live transcript-so-far |
| AUTO_CONFIRMED | ✅ + what it did ("3 kg आलू ₹90 — बिक्री") |
| PARTIALLY_CONFIRMED | ⚠️ + "2 में से 1 लाइन दर्ज" + tap to fix |
| PARSED (needs review) | ❓ + tap to confirm |
| FAILED | ❌ + reason + **Retry** button (re-enqueues the same audio; this is why [RollingAudioBuffer](app/src/main/java/com/voicetoinvoice/app/audio/RollingAudioBuffer.kt) audio must be retained until terminal+24h) |

A persistent count badge lives on the home screen mic area. Every row is tappable to the diagnostic trace in debug builds.

**Confirm only when unsure** — keep the existing `0.80` auto-confirm threshold, and add: intent confidence `< 0.75` also forces review even when item confidence is high. A confidently-parsed item routed to the *wrong intent* is worse than an unparsed one, because it books silently.

### 2.6 Wiring Gate — Phase 2

- [ ] `grep -rn "ActionExecutor" app/src/main` → **at least one call site outside its own file.** (Today: zero. This is the gate that would have caught the original bug.)
- [ ] `grep -rn "यह काम अभी नहीं कर सकता" app/src/main` → zero hits.
- [ ] Every `AssistantIntent` value appears in the `when` in `handleAssistantJob` **and** commits via a named handler. No `else -> "दर्ज हो गया"` catch-all that silently does nothing.
- [ ] `IntentRouterTest.kt` (Kotlin) and `intent_router_test.ts` (Deno) run the **same 60-phrase fixture** (20 Devanagari, 20 Latin-Hinglish, 20 English) and agree on intent for all 60.
- [ ] Fixture must include the ambiguity cases: *"khate mein likh do"* → CREDIT_SALE; *"Ramesh ne 500 diye"* → PAYMENT_RECEIVED (**not** CREDIT_SALE); *"aaloo ka rate 30 kar do"* → PRICE_UPDATE (not SALE); *"aaj ki sale kitni hui"* → READ_QUERY (this one returns UNKNOWN today).
- [ ] `CommitSequencerTest`: three jobs whose STT returns in reverse order commit in `recordedAtMs` order; a 4th that never terminates does not block past 6s.
- [ ] On-device: speak 5 commands in a burst without pausing; all 5 appear in the Command Feed, in order, each ending in a terminal state.

---

## Phase 3 — Money: Reports, Profit, Udhaar, Alerts, Health

**Goal**: the shopkeeper can answer "did I make money today/this week/this month" without reading a table.
**Side**: CLIENT (all reporting is local-first; server only mirrors)
**Room**: 20 → 21

### 3.1 Stop scanning the whole table in Kotlin

[LedgerQueries.kt](app/src/main/java/com/voicetoinvoice/app/domain/query/LedgerQueries.kt) is 40 lines and every method is a full-table load plus a Kotlin filter:

```kotlin
val txns = db.transactionDao().getAllTransactionsList().filter { it.timestamp >= todayMidnightMs }
```

`getStockLevel()` is worse — it loads the entire catalog, the entire `stock_in` table, and the entire `transactions` table to answer one question about one item. On a shop with a year of history this is seconds of main-thread-adjacent work per spoken question, and the assistant is supposed to feel instant. Rewrite every method as a SQL aggregate. `getStockLevel` becomes `SELECT stockQty FROM catalog_items WHERE id = :id` after Phase 1.

### 3.2 Daily rollups

New entity `data/local/entity/DailyRollup.kt` — `@Entity(tableName = "daily_rollups", primaryKeys = ["shopId","dayEpoch","itemId"])`, columns: `qtySold`, `revenue`, `cogs`, `costKnownRevenue`, `txnCount`, `wasteQty`, `returnQty`, `stockInQty`.

Written incrementally: `StockLedgerRepository` and the sale commit path both upsert the affected `(day, item)` row in the same DB transaction. Plus a `RollupRepairWorker` (WorkManager, daily at 3am) that recomputes the last 3 days from source rows — incremental counters drift when a process dies mid-write, and a report that disagrees with the ledger is a support call.

Monthly/weekly reports read rollups (≤31 rows/item/month), never raw transactions.

### 3.3 Reports screen

Extend [DailySummaryScreen.kt](app/src/main/java/com/voicetoinvoice/app/ui/screens/summary/DailySummaryScreen.kt) (452 lines) into `ui/screens/reports/ReportsScreen.kt` with a Day / Week / Month toggle. Add `Screen.REPORTS` to the enum at [MainActivity.kt:82](app/src/main/java/com/voicetoinvoice/app/MainActivity.kt#L82).

Per period: revenue, COGS, gross profit + margin% + **cost-coverage%**, txn count, average bill, top 5 items by revenue *and* by quantity, cash vs UPI vs credit split, new udhaar vs collected, waste value, and a 7/30-day sparkline. Plus a prev-period delta on revenue and profit — the single most motivating number for a shopkeeper is "better or worse than last week".

Design constraints, non-negotiable for this user base: numbers in Devanagari-friendly rendering, **₹ with Indian digit grouping (1,24,500 not 124,500)**, no chart requiring a legend to read, one screen per period with no horizontal scrolling. Assume a 5-inch screen in bright sunlight and a user who may read slowly — large type, high contrast.

### 3.4 Low-stock, expiry & dead-stock alerts

New `domain/alert/AlertEngine.kt`, run by a WorkManager job at 6am and after each stock-affecting commit.

**Decision: derive thresholds automatically, allow manual override.** Shopkeepers will not set 135 thresholds by hand, so an alert system that requires configuration produces zero alerts.

```
effectiveThreshold = lowStockThreshold ?: max(1.0, ceil(avgDailySold(last 14d) * leadTimeDays(shop) * 1.5))
```

Alert types → `data/local/entity/AlertRecord.kt` (`id`, `shopId`, `type`, `itemId`, `severity`, `message`, `createdAtMs`, `dismissedAtMs`, `snoozedUntilMs`):
- `LOW_STOCK` — `stockQty <= effectiveThreshold`
- `OUT_OF_STOCK` — `stockQty <= 0` **and** sold at least once in 14 days (never alert on items the shop doesn't actually carry)
- `EXPIRING_SOON` — batch `expiryDateMs` within 7 days, `remainingQty > 0`, `trackExpiry` only
- `EXPIRED` — past expiry with stock remaining → prompts an `EXPIRY` write-off
- `DEAD_STOCK` — `stockQty > 0` and `lastSoldAtMs` older than 30 days
- `UDHAAR_OVERDUE` — customer balance > 0 with oldest unpaid credit sale > 30 days
- `MISSING_COST` — ≥5 `costMissing` stock-ins pending (this one directly improves profit coverage, so it pays for itself)

Every alert is **snoozable and dismissable**. Cap at 5 shown at once, severity-ordered. An alert list of 40 items is identical to no alert list.

### 3.5 Business health score

New `domain/query/HealthScore.kt`. **Decision: 5 transparent components, always shown broken down — never a bare number.** A "62/100" with no explanation is either ignored or insulting; the components are what make it actionable.

| Component | Weight | Measure |
|---|---|---|
| Sales trend | 25 | last 7d revenue vs prior 7d |
| Margin | 20 | gross margin% vs the shop's own 90-day median (not an industry benchmark you don't have) |
| Udhaar health | 20 | (receivables > 30d) / total receivables, inverted |
| Stock efficiency | 20 | 1 − (dead-stock value / total stock value) |
| Data completeness | 15 | cost-coverage% × (1 − unresolved-queue ratio) |

Score `0–100`, plus the 2 weakest components rendered as plain-language actions ("₹4,200 का उधार 1 महीने से बाकी है — याद दिलाएँ?" with a tap-through to Phase 5's reminder). Include `Data completeness` deliberately: it makes the app's own data hygiene visible to the shopkeeper and nudges them to back-fill costs, which makes every other number better.

### 3.6 Wiring Gate — Phase 3

- [ ] `grep -rn "getAllTransactionsList()" app/src/main` → zero hits outside migration/repair code.
- [ ] `ReportsScreen` is reachable from home; `Screen.REPORTS` handled in the `when` in `MainAppScreen`.
- [ ] `AlertEngine` is invoked from both the WorkManager job **and** the post-commit path; alerts render on home.
- [ ] `HealthScoreTest`: a shop with zero cost data scores lower on completeness but doesn't produce `NaN`/divide-by-zero on any component (empty shop, brand-new shop, single-transaction shop).
- [ ] `RollupTest`: rollup totals for a synthetic 200-transaction month equal the raw aggregate exactly, including after a void and a return.

---

## Phase 4 — AI Layer: Grounded Q&A, Reorder, Forecast, Per-Shop Learning

**Goal**: the assistant answers real business questions correctly, and the app gets better at *this* shop over time.
**Side**: BOTH (Q&A needs a server path so it works with the app closed)
**Room**: 21 → 22

### 4.1 Grounded business Q&A — the anti-hallucination architecture

[QuestionTemplates.kt](app/src/main/java/com/voicetoinvoice/app/domain/query/QuestionTemplates.kt) handles ~5 question shapes, Devanagari-only. Extend, don't replace — the deterministic path is instant, free, and offline, and it should stay the primary path.

**Two tiers:**

**Tier 1 — templates (target: 80% of questions).** Add to `QuestionTemplates`, all in the Phase 2.2 phonetic key space so Hinglish works: today/week/month sales; today's profit; stock of item X; total stock value; who owes me / how much does X owe; total receivables; top/slowest item; how many sales today; average bill; waste this week; what did I buy from supplier X; is item X below threshold; expiring items; sales vs yesterday/last week.

**Tier 2 — AI, given pre-computed facts only.** New `domain/query/BusinessQnA.kt`. When no template matches:
1. Build a compact JSON **fact sheet** — never raw rows: `{period, revenue, cogs, profit, marginPct, costCoveragePct, txnCount, avgBill, top5Items[], slowest5[], receivablesTotal, receivablesAging{}, stockValue, lowStockItems[], deadStockItems[], wasteValue, prevPeriodRevenue, prevPeriodProfit}`. Cap at ~2KB.
2. Send transcript + fact sheet to Grok with a system prompt whose central instruction is: **answer only from the supplied numbers; if the answer isn't derivable, say "यह जानकारी अभी नहीं है" and stop. Never estimate, never extrapolate, never invent a number.**
3. Post-validate: extract every numeric token from the model's answer and assert each appears in the fact sheet (allowing arithmetic on supplied values — sums, differences, percentages of supplied numbers). **Any unverifiable number → discard the answer and fall back to "यह जानकारी अभी नहीं है".** This validator is the hard guarantee; the prompt is only the soft one. Implement it in `domain/query/NumericGroundingValidator.kt` with its own unit tests.
4. Answers are spoken via [SpeechOutput.kt](app/src/main/java/com/voicetoinvoice/app/domain/voice/SpeechOutput.kt) (Android TTS with the `tts-proxy` Grok fallback already built) **and** shown as text in the Command Feed — the shopkeeper may be in a noisy market.

Mirror Tier 2 in `process-voice-job` so a question asked with the app closed still gets answered and stored in `assistantAnswer`.

### 4.2 Reorder suggestions

New `domain/intel/ReorderAdvisor.kt`. **Decision: explainable arithmetic, no model.**

```
avgDaily      = qtySold(last 21d) / 21, day-of-week weighted (4.3)
daysOfCover   = stockQty / max(avgDaily, 0.01)
reorderPoint  = avgDaily * (leadTimeDays + 1 safety day)
suggestQty    = max(avgDaily * 7 - stockQty, 0)   rounded to the item's purchase unit
```
Suggest when `stockQty <= reorderPoint`. Present as: *"आलू 2 दिन में खत्म — 40 किलो मंगवाएँ?"* with the reasoning one tap away ("रोज़ ~13 किलो बिकता है, 25 किलो बचा है"). A suggestion the shopkeeper can't sanity-check is a suggestion they'll ignore.

Group suggestions **by supplier** ([SupplierRecord](app/src/main/java/com/voicetoinvoice/app/data/local/entity/SupplierRecord.kt) exists) so the output is one order list per supplier — that's the actual unit of work, and it makes Phase 5.4's supplier WhatsApp order trivial.

### 4.3 Trend & demand forecast

New `domain/intel/DemandForecast.kt`. **Decision: EWMA + day-of-week seasonality. Explicitly no ARIMA/Prophet/ML.** Kirana demand is dominated by weekly rhythm and festivals; a 30-line explainable model captures most of the signal, runs instantly on-device, and can be explained to a shopkeeper. An opaque model that says "buy 60kg" without a reason will not be trusted, and cannot be debugged when it's wrong.

```
dowFactor[d]  = mean(revenue on weekday d, last 8w) / mean(revenue, last 8w)
baseline      = EWMA(daily qty, alpha = 0.3)
forecast(d)   = baseline * dowFactor[weekday(d)]
```
Surface: next-7-day expected quantity per top-20 item; expected revenue for tomorrow and next week; and a `confidence: LOW/MEDIUM/HIGH` from history depth (<14d = LOW and label it *"अंदाज़ा — अभी कम जानकारी"*, 14–56d = MEDIUM, >56d = HIGH). Hide forecasts entirely below 14 days of history rather than showing a confident-looking wrong number to a new shop.

Fast/slow-moving: rank items by 30-day quantity into Fast (top 20%) / Steady / Slow (bottom 30%) / Dead (0 sales, stock > 0), rendered as four tap-able buckets on the reports screen.

### 4.4 Per-shop learning (the moat)

This is where the grant story lives, so it gets first-class data structures rather than being a side effect.

New entity `data/local/entity/ShopLearning.kt` — `@Entity(tableName = "shop_learning", primaryKeys = ["shopId","kind","key"])` with `kind: LearningKind`, `key`, `value`, `hitCount`, `lastUsedAtMs`, `confidence`, `synced`.

```kotlin
enum class LearningKind {
    ITEM_ALIAS,      // this shop's word for an item ("tamatar" -> id, "TMTR" -> id)
    UNIT_MEANING,    // "peti" = 20 KG at THIS shop, 25 KG at another
    DEFAULT_PRICE,   // habitual selling price, for unpriced utterances
    PHRASE_INTENT,   // this shopkeeper's phrasing -> intent (feeds 2.2 step 5 cache)
    CUSTOMER_ALIAS,  // "doctor sahab" -> customer id
    SPEECH_PATTERN   // observed confusions, e.g. this speaker's "b"/"p" collapse
}
```

Learning writes on every **confirmation and every correction** — a shopkeeper fixing a wrong parse in the review queue is the highest-value training signal in the system and is currently thrown away. Specifically: review-queue confirmation → `ITEM_ALIAS` + `PHRASE_INTENT`; a void → *negative* signal that decays the offending entry's confidence (this is the ISSUE-031 correction channel, now generalized); manual unit correction → `UNIT_MEANING`.

Feed learning back in at three points: `EntityResolver` candidate boosting, `OrderingSegmenter` vocabulary, and the Grok prompt (inject the shop's top-30 aliases as context — a small, cheap, high-yield prompt addition).

Sync `shop_learning` to a shop-scoped Supabase table so it survives reinstall and device change. Per Phase 0.2.4, **shop-scoped, never global.**

### 4.5 Wiring Gate — Phase 4

- [ ] `NumericGroundingValidatorTest`: an answer containing a number absent from the fact sheet is **rejected**. Include an adversarial case where Grok returns a plausible-but-fabricated total.
- [ ] `BusinessQnA` is reachable from `READ_QUERY` in **both** `SttWorker` and `AssistantFastPath`, and mirrored in `process-voice-job`.
- [ ] `ReorderAdvisor` output is rendered on a screen and reachable in ≤2 taps from home.
- [ ] `ShopLearning` has a write call site on the review-queue confirm path, the void path, **and** a read call site in `EntityResolver`. A learning table that's written but never read is the Phase 0 dead-code pattern all over again.
- [ ] `DemandForecastTest`: a 10-day-history shop returns `LOW`/hidden, not a number.

---

## Phase 5 — Customer-Facing: WhatsApp Bills, Receipts, Reorder

**Goal**: the shopkeeper's customer gets a bill on WhatsApp, and last month's order can be repeated in one tap.
**Side**: CLIENT
**Room**: 22 (no new tables beyond 5.1)

### 5.1 Bills as first-class objects

Multi-item recordings already produce several `transactions` rows sharing a `jobId` ([TransactionRecord.lineNo](app/src/main/java/com/voicetoinvoice/app/data/local/entity/TransactionRecord.kt), ISSUE-029). Phase 1.5 added `billId`. Set `billId = jobId` for voice sales and generate one for manual multi-line entries, so a bill is exactly "all non-voided transactions sharing a `billId`".

New `domain/bill/BillBuilder.kt` → `Bill(billId, shopName, customerName?, lines[], subtotal, previousBalance?, newBalance?, paymentMode, timestamp)`. Render three ways from one model: plain text (WhatsApp body), a shareable PNG (Compose → `Bitmap`, for a printed-bill look), and a PDF (`PdfDocument`) for anything larger than ~10 lines.

### 5.2 WhatsApp send — the honest mechanism

**Decision: prefill + shopkeeper taps send. No silent sending.** WhatsApp has no API for a normal (non-Business-API) app to send a message without the user confirming, and building something that looks automatic would break the moment WhatsApp tightens its intent handling — plus silently messaging customers from a shopkeeper's number is not a thing to ship by default. So: we compose everything, open WhatsApp with the message and the right contact prefilled, and the shopkeeper taps send. That tap is also the consent checkpoint.

Extend the existing (currently uncalled) [ActionExecutor.kt](app/src/main/java/com/voicetoinvoice/app/domain/action/ActionExecutor.kt):

```kotlin
fun sendBill(context: Context, bill: Bill, phone: String?)          // text via api.whatsapp.com/send
fun sendBillImage(context: Context, imageUri: Uri, phone: String?)   // ACTION_SEND + FileProvider
fun sendPaymentReminder(context: Context, customer: CustomerRecord, balance: Double, aging: Int)
fun sendSupplierOrder(context: Context, supplier: SupplierRecord, lines: List<ReorderLine>)
fun dialCustomer(context: Context, phone: String)                    // exists
```

`sendBillImage` needs `FileProvider` — **already fully wired, no work needed**: it's declared at [AndroidManifest.xml:52](app/src/main/AndroidManifest.xml#L52) with authority `${applicationId}.fileprovider`, and [provider_paths.xml](app/src/main/res/xml/provider_paths.xml) grants `<cache-path path="."/>`, so any bills subdirectory under the cache dir is already shareable. Just write bill images to `context.cacheDir/bills/` and use that authority. Keep the existing `Uri.encode` message building (it's correct) and keep the try/catch, but **surface** the failure — the current silent `e.printStackTrace()` means "WhatsApp not installed" looks identical to success. Show a snackbar and offer SMS as fallback.

Bill message template (Hindi-first, no jargon):
```
नमस्ते {customer} जी 🙏
{shopName}

{qty} {unit} {item} — ₹{amount}
...
कुल: ₹{total}
{पिछला बकाया: ₹{prev} · अब कुल बकाया: ₹{newBalance}}

धन्यवाद!
```

### 5.3 Where sending is triggered

Three entry points, all of which must exist (this is the wiring that was missing before):
1. **Voice** — `ACTION_COMMAND` ("रमेश को बिल भेजो" / "Ramesh ko bill bhejo") → `ActionCommandHandler` resolves the customer via `EntityResolver`, resolves the bill (last bill for that customer, or the most recent bill if none named), calls `ActionExecutor.sendBill`. If the customer has no phone on file, speak *"रमेश का नंबर नहीं है — जोड़ दें?"* and open the customer edit screen. Ambiguous customer → speak the top 2 and ask.
2. **Post-sale prompt** — after a bill commits for a customer with a phone, the Command Feed row gets a "बिल भेजें" button. No auto-send.
3. **Screens** — a share icon on [CustomerDetailScreen.kt](app/src/main/java/com/voicetoinvoice/app/ui/screens/customer/CustomerDetailScreen.kt) (bill + reminder) and on the reorder list (supplier order).

Reminders also surface from the `UDHAAR_OVERDUE` alert (3.4) — tapping the alert opens the prefilled WhatsApp reminder. This closes the loop from "app noticed" to "shopkeeper acted", which is the whole point of the alert.

### 5.4 WhatsApp reorder from a previous bill

New `ui/screens/customer/RepeatOrderSheet.kt`: on a customer's detail screen, list their last 5 bills; tapping one shows its lines with checkboxes and editable quantities, priced at **today's** catalog price (not the historical price — that's the #1 support question if you get it wrong; label it *"आज के रेट पर"*). Confirming books it as a new sale (through the normal commit path, so stock and rollups stay correct) and offers the WhatsApp bill.

Same mechanism serves supplier reorder in 4.2, one order list per supplier.

### 5.5 Wiring Gate — Phase 5

- [ ] `grep -rn "ActionExecutor\." app/src/main` → **≥3 distinct call sites** (voice handler, command feed, customer screen).
- [ ] Speaking "रमेश को बिल भेजो" on a device opens WhatsApp with the bill text and Ramesh's number prefilled. This exact end-to-end path is the acceptance test for the original bug.
- [ ] Missing-phone, ambiguous-customer, and WhatsApp-not-installed all produce a spoken/visible message — never a silent no-op.
- [ ] `BillBuilderTest`: a 3-line bill with a previous balance renders correct subtotal, new balance, and Indian digit grouping.

---

## Cross-Cutting Requirements

### Testing gates (per phase, non-negotiable)

Existing test dirs: `app/src/test/java/com/voicetoinvoice/app/{audio,resolver,router}/`, plus Deno tests in `supabase/functions/process-voice-job/*_test.ts`. Add per phase:

```bash
./gradlew test
```
```bash
cd supabase/functions/process-voice-job && deno test --allow-all
```

Note: `app/src/androidTest/java/com/example/voicetoinvoice/ui/main/MainScreenTest.kt` is stale boilerplate under the wrong package referencing a non-existent `MainScreen` — **delete it** in Phase 0 so `connectedAndroidTest` can ever run.

### Per-phase definition of done

1. `./gradlew assembleDebug` succeeds; APK copied to `C:\Users\harsh\OneDrive\Desktop\VoiceToInvoice_APKs\VoiceToInvoice_v<N+1>.apk` (`ls` the folder for the real highest N — it drifts).
2. `./gradlew test` and the Deno suite green.
3. **Wiring Gate checklist passes**, greps included.
4. Edge function deployed (`npx supabase functions deploy process-voice-job`), live bundle re-fetched and grepped for expected markers — this repo has a history of placeholder deploys going live.
5. `Docs/audit.md` updated: `ISSUE-049`+ entries, §1 constants table updated for any changed threshold/version.
6. On-device smoke test of the phase's headline flow, with the diagnostic trace checked for the new step blocks.

### Constants introduced (record these in audit.md §1)

| Constant | Value | Where |
|---|---|---|
| Item auto-confirm confidence | `0.80` (unchanged) | `SttWorker`, `index.ts` |
| Intent direct-route confidence | `0.75` | `IntentRouter`, `intent_router.ts` |
| Intent AI-arbitration floor | `0.45` | same |
| Phonetic trigger match max distance | `0.25` | `IntentLexicon` |
| Commit barrier ceiling | `6000 ms` | `CommitSequencer` |
| `VOID_LAST` window | `10 min` | `VoidHandler` |
| Low-stock lead-time multiplier | `1.5` | `AlertEngine` |
| Reorder safety days | `1` | `ReorderAdvisor` |
| Forecast EWMA alpha | `0.3` | `DemandForecast` |
| Forecast min history / MEDIUM / HIGH | `14d / 14–56d / >56d` | `DemandForecast` |
| Dead-stock threshold | `30 days` | `AlertEngine` |
| Health weights | `25/20/20/20/15` | `HealthScore` |
| Room versions | `18,19,20,21,22` | `AppDatabase` |

### Open questions — decide before the phase that needs them

1. **Phase 0 auth**: phone OTP needs an SMS provider configured in Supabase (MSG91/Twilio for India). Not free, and it gates Phase 0. If you'd rather defer cost, the fallback is device-generated UUID `shopId` + RLS on a stored device secret — weaker (no reinstall recovery) but shippable. **My recommendation: pay for OTP.** Losing a shop's ledger on a reinstall is the one bug that ends the relationship.
2. **Phase 1 backfill**: existing production has 135 catalog items and live transactions with `shop_id = NULL`. Confirm they all belong to *your* test shop before the backfill assigns them to one shop id.
3. **Phase 3 GST/tax**: the brief doesn't mention it. I've assumed **no GST** (most Jaipur kirana/veg shops are under the composition/exempt threshold). If any target shop needs GST invoices, that's an additional slice on `BillBuilder` + catalog HSN codes — tell me and I'll plan it.
4. **Phase 4 Grok cost**: Tier-2 Q&A and intent arbitration add per-utterance LLM calls. At Jaipur scale this is the main variable cost. The template tier and the phonetic cache exist to keep it near-zero; worth setting a per-shop daily AI-call cap before rollout.

### Feature → phase traceability

Every item from the brief:

| # | Feature | Phase | Notes |
|---|---|---|---|
| 1 | Voice-first business management (not just billing) | 2 | full intent set |
| 2 | Automatic inventory update after every sale | 1 | stock ledger |
| 3 | Voice price change | 2.3 | `PRICE_UPDATE` + existing `RATE_UPDATE` |
| 4 | Voice add stock | 2.3 | exists; rewired to ledger |
| 5 | Voice returns | 2.3 | `RETURN` — new |
| 6 | Voice questions | 4.1 | template + grounded AI |
| 7 | Queue in spoken order | 2.5 | `CommitSequencer` |
| 8 | Background processing, keep speaking | 2.5 | exists; ordering added |
| 9 | Status per command | 2.5 | Command Feed |
| 10 | Confirm only when unsure | 2.5 | + intent-confidence gate |
| 11 | Daily/weekly/monthly reports | 3.3 | on rollups |
| 12 | Profit calculation | 1.5 + 3.3 | WAC + honest coverage |
| 13 | Udhaar management | 2.4 | unified single balance |
| 14 | Customer payment tracking | 2.4 | `CustomerPayment` — new |
| 15 | Low-stock alerts | 3.4 | auto thresholds |
| 16 | Business health score | 3.5 | 5 components |
| 17 | AI answers business questions | 4.1 | grounded + validated |
| 18 | Auto stock deduction | 1 | ledger |
| 19 | Auto stock addition | 1 | ledger |
| 20 | Expiry tracking | 1.6 | opt-in by shop type |
| 21 | Fast/slow-moving analysis | 4.3 | 4 buckets |
| 22 | AI shopkeeper assistant | 2 + 4 | intents + Q&A |
| 23 | Auto reorder suggestions | 4.2 | grouped by supplier |
| 24 | Sales trend prediction | 4.3 | EWMA + DOW |
| 25 | Demand forecasting | 4.3 | same engine |
| 26 | Hindi + English + Hinglish | 2.2 | phonetic lexicon — the current gap |
| 27 | Learn speaking style | 4.4 | `ShopLearning` |
| 28 | WhatsApp bills | 5.2 | wires the dead `ActionExecutor` |
| 29 | WhatsApp reorder from previous bill | 5.4 | `RepeatOrderSheet` |
| 30 | Digital receipts | 5.1 | text / PNG / PDF |
| — | **Multi-tenancy + RLS** | **0** | **blocker you didn't ask for** |
| — | **Exported-receiver fix** | **0** | **blocker (ISSUE-018)** |

---

## Suggested sequencing

Phases are ordered by dependency, not by visible value — Phase 0 and 1 are invisible to a shopkeeper but everything else is wrong without them. Ship 0 and 1 together if you want one release before showing anyone; ship 2 next because it's the phase that makes the app *feel* like the product in the brief.

Do not start a phase before the previous phase's Wiring Gate passes. That gate is the entire defense against shipping another `ActionExecutor` — 42 lines of correct code that nothing calls.
