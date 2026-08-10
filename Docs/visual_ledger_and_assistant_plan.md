# Visual Ledger, Colour System, Assistant Rebuild & Ledger Explorer — Implementation Plan

**Author:** Claude Code · **Date:** 2026-08-10 · **Implementer:** Antigravity
**Issue numbers allocated:** ~~ISSUE-115 … ISSUE-119~~ **CORRECTED 2026-08-10**: the original scan only checked `#### ` headings and missed the `##### ` sub-entries under the ISSUE-110..116 batch entry (2026-08-09), which already claims 115 and 116. **WS-A is ISSUE-122, WS-B is ISSUE-123** (WS-B already implemented and logged under 123). C/D/J/K (117–121) were clear and are unchanged.
**Room:** current `version = 27` → this plan takes it to **28**

---

## 0. Scope

**Part I** (§2–§5) — the four product workstreams the user selected.
**Part II** (§10–§16) — every remaining OPEN issue in `Docs/audit.md`, each re-verified against the live system on 2026-08-10 before being planned.

| WS | Goal | Issue |
|---|---|---|
| **A** | Item (and customer) identity is a **photo**, captured on-device | ISSUE-122 |
| **B** | One semantic **colour vocabulary**, applied app-wide | ISSUE-123 (done) |
| **C** | Assistant: fix **comprehension** (wrong answers) and **speed** | ISSUE-117, ISSUE-118 |
| **D** | **Ledger Explorer** — filter by item/date/time/mode, always-visible running total | ISSUE-119 |
| **E** | **RLS + `shop_id`** — live security exposure and mixed-NULL tenancy | ISSUE-032 |
| **F** | AI/FAST/RULES badges — **already fixed in tree**, needs logging + verification | ISSUE-102 |
| **G** | `audit.md` hygiene — duplicated section, stale-open entries | ISSUE-018, doc defect |
| **H** | ISSUE-045 — set a **closure criterion**, do not invent a fix | ISSUE-045-OPEN |
| **I** | ISSUE-004 — phonetic collapse set from a **confusion matrix**, not hand-tuning | ISSUE-004 |
| **J** | Intent router misfires `ACTION_COMMAND` on plain sales (found this session) | ISSUE-120 |
| **K** | **Expenses + cash book** — the reason "मुनाफ़ा" is currently wrong | ISSUE-121 |

### Explicitly OUT of scope

- **Speak-on-tap for report numbers.** Dropped at the user's direction (2026-08-10): "numbers person should know at least."
- **Parallelising catalog+alias fetch.** Already live — do not re-do it. See §1.4.

---

## 1. Evidence — what was verified before this plan was written

All claims below are **verified** (queried/read just now) unless marked otherwise.

### 1.1 The assistant never calls an LLM

`SttWorker.kt:610-613` routes `READ_QUERY` to `QuestionTemplates(ledgerQueries).answerQuestion(...)`.
`QuestionTemplates.kt` is **eight hardcoded `if` branches** over phonetic n-grams. Its own doc comment says *"deterministic templates only, no AI."* There is **no assistant edge function** — `list_edge_functions` returns only `stt-proxy`, `term-interpret`, `sync-term-aliases`, `diagnostic-reader`, `db-setup`, `process-voice-job`, `tts-proxy`.

**The user's hypothesis was correct: nothing is sent to Grok on the answer path.** But there is no model to "switch" there — the path has to be built.

### 1.2 A miss produces a *wrong answer*, not "I didn't understand"

Live proof. Exactly one true `READ_QUERY` exists in 399 jobs:

```
job_id 472d4af1-e438-4f1f-a398-67e240d47362   2026-08-04 19:18:59Z
raw_transcript: "आज कितने आलू बिके"
step_2b: {"intent":"READ_QUERY","confidence":0.526,"needsArbitration":true}
```

`QuestionTemplates` has **no item-scoped branch**. Tracing it: `TOP_ITEM_WORDS` misses, `PROFIT_WORDS` misses, then `REVENUE_WORDS` contains `"बिका"` — phonetically ≈ `"बिके"` — so it **matches branch 3 and answers today's total sales**. The shopkeeper asked "how many potatoes sold" and got "₹X total business today." Confidently wrong. This is the single most damaging failure mode in the file.

### 1.3 Intent routing is unreliable on plain sales

Of 13 assistant-flagged jobs, several plain sales were classified `ACTION_COMMAND` at confidence 0.526 and **routed to review instead of booked**:

```
54e7fe50  "चार किलो चाच"   ACTION_COMMAND 0.526  routedToReview: true
8430fe59  "चार किलो आलू"   ACTION_COMMAND 0.526  routedToReview: true
467ea9d5  "चार किलो गोल्ड"  ACTION_COMMAND 0.526  routedToReview: true
```

All three begin "चार किलो" (four kilo). Noted here as an observation for a future issue — **not fixed by this plan**, and not to be touched by the implementer.

### 1.4 Latency: where the time actually goes

Two traces, both real:

```
2fd43483 (fast path, no AI call)   totalMs 2397   uploadMs 283
  catalogFetchedAtMs 449 · aliasesFetchedAtMs 890 · sttResolvedAtMs 1799
  · parseResolvedAtMs 2017 · ledgerWrittenAtMs 2397
  grokStt 446ms · sarvamStt 907ms · step_4_fast_path.aiCallMade: false

54e7fe50 (AI interpretation fired)  totalMs 5523
  sttResolvedAtMs 1098 → parseResolvedAtMs 4947  = 3849ms inside step 4
```

**The AI call is ~3.8 s of a 5.5 s job.** That is the latency target.

⚠️ **A contradiction I chased down, so nobody repeats it:** the 2fd43483 trace shows catalog at 449 ms and aliases at 890 ms — 441 ms apart — yet `mark()` is `Date.now() - t0` (cumulative), and the source fetches both in one `Promise.allSettled` (index.ts:1160). Both readings cannot be true. Resolution: that trace was written 2026-08-09 **17:01 UTC**; edge function v91 deployed **17:53 UTC**. The trace predates the fix. I confirmed the *deployed* bundle contains `A1 (ISSUE-110)`, `sarvamPromise` and `Promise.allSettled`. **The parallelisation is live. Do not re-implement it.**

### 1.5 The photo infrastructure does not exist

- `CatalogItem.imageUrl` exists and syncs both ways; server column `image_url` exists (ISSUE-092). **Nothing ever writes it.**
- `CustomerRecord.photoPath` exists in the entity, in `MIGRATION` DDL (AppDatabase.kt:284) and in `CloudSyncManager.kt:247`. Grepping all of `app/src/main/java` for `photoPath` returns **exactly those three hits — zero writes, zero UI**. Customer faces were schema'd and never built. (This corrects the assumption that faces already shipped.)
- `ItemIcon.kt` falls back to **4 Material vector icons** keyed off ~40 hardcoded names. Any item outside that list renders as an identical grey `Icons.Default.Category` circle.
- **No CAMERA permission is declared** in `AndroidManifest.xml`. This is good news: `ActivityResultContracts.TakePicture()` delegates to the system camera app and needs **no runtime permission** as long as we never declare `android.permission.CAMERA`. **Do not add that permission.**
- `provider_paths.xml` already exposes `<files-path name="internal_files" path="." />`, so `filesDir/photos/` is shareable with **no manifest change**.

### 1.6 The model the user asked for is real

`grok-4.20-0309-non-reasoning` is listed on xAI's current pricing table: 1M context, $1.25/$2.50 in, $2.50/$5.00 out — **cheaper than `grok-4.5`** ($2.00/$6.00) and non-reasoning by construction. Verified against <https://docs.x.ai/docs/models>.

---

## 2. WS-A — Identity is a photo (ISSUE-122)

### A1. Room migration 27 → 28

`app/src/main/java/com/voicetoinvoice/app/data/local/AppDatabase.kt`

1. Change line 36: `version = 27,` → `version = 28, // ISSUE-122: imagePath on catalog_items`
2. Add after `MIGRATION_26_27` (line ~780), following the existing try/catch'd style:

```kotlin
/** `catalog_items`: local on-device photo path. ISSUE-122. */
private val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        try {
            db.execSQL("ALTER TABLE catalog_items ADD COLUMN imagePath TEXT DEFAULT NULL")
        } catch (e: Exception) {
            android.util.Log.w("AppDatabase", "MIGRATION_27_28 imagePath: ${e.message}")
        }
    }
}
```

3. Append `MIGRATION_27_28` to the `.addMigrations(...)` call at line 894.
4. If the defensive column-list map at line ~804 (`"imageUrl" to "TEXT"`) is used to self-heal schemas, add `"imagePath" to "TEXT"` alongside it.

### A2. Entity

`data/local/entity/CatalogItem.kt` — add after `imageUrl` (line 51):

```kotlin
/** On-device photo captured by the shopkeeper. Takes precedence over [imageUrl] when
 *  both exist: the shopkeeper's own photo of their own stock is always the better
 *  identity than a generic pack shot. Local-only — never synced (the file isn't). */
val imagePath: String? = null,
```

**Do not** add `imagePath` to `CloudSyncManager` — it is a device-local file path and is meaningless on another device.

### A3. Photo capture utility (new file)

`app/src/main/java/com/voicetoinvoice/app/utils/PhotoCapture.kt`

```kotlin
package com.voicetoinvoice.app.utils

object PhotoCapture {
    private const val MAX_DIM = 512
    private const val JPEG_QUALITY = 80

    /** Creates (and mkdirs) an empty target file under filesDir/photos/. */
    fun newPhotoFile(context: Context, prefix: String): File

    /** FileProvider URI for [file]; authority "${context.packageName}.fileprovider". */
    fun uriFor(context: Context, file: File): Uri

    /** Decodes [file], scales longest edge down to MAX_DIM, re-encodes JPEG at
     *  JPEG_QUALITY, overwrites in place. Camera output is 4-12 MB; a 512px
     *  thumbnail is ~40 KB and is all ItemIcon ever renders. */
    fun compressInPlace(file: File)

    /** Deletes the file if it exists. Used when the user clears a photo. */
    fun delete(path: String?)
}
```

Use `BitmapFactory.Options.inSampleSize` for the decode (never decode full-res into memory first).

### A4. Capture button (new composable)

`app/src/main/java/com/voicetoinvoice/app/ui/components/PhotoCaptureButton.kt`

```kotlin
@Composable
fun PhotoCaptureButton(
    currentPath: String?,
    filePrefix: String,          // "item" or "customer"
    onCaptured: (String?) -> Unit,   // absolute path, or null when cleared
    size: Dp = 72.dp
)
```

- `rememberLauncherForActivityResult(ActivityResultContracts.TakePicture())`.
- Hold the pending `File` in `remember { mutableStateOf<File?>(null) }` — the contract only returns `Boolean`.
- On `success == true`: call `PhotoCapture.compressInPlace(file)` on `Dispatchers.IO`, then `onCaptured(file.absolutePath)`.
- On `success == false`: delete the empty file, do not call back.
- Renders the current photo if set, else a camera-icon placeholder circle.
- Long-press → clear (delete file, `onCaptured(null)`).

**Do not add `<uses-permission android:name="android.permission.CAMERA" />`.** Declaring it makes the runtime grant mandatory; without it `TakePicture` works permission-free.

### A5. `ItemIcon` prefers the local photo

`ui/components/ItemIcon.kt` — add a parameter, keep the existing ones so no call site breaks:

```kotlin
@Composable
fun ItemIcon(
    itemName: String,
    imageUrl: String?,
    imagePath: String? = null,   // NEW — takes precedence
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
)
```

Resolution order inside: `imagePath` (as `java.io.File`, if `exists()`) → `imageUrl` → existing category vector fallback. Coil's `AsyncImage` accepts a `File` as `model` directly.

Then pass `imagePath = item.imagePath` at the call sites that have a `CatalogItem` in hand:

- `ui/screens/catalog/CatalogManagementScreen.kt:74`
- `ui/components/ConfirmSaleDialog.kt:40` (`parsedSale.matchedItem?.imagePath`)
- `ui/components/PendingConfirmationsSheet.kt:369` (`matchedCatalogItem?.imagePath`)
- `ui/components/ManualStepperComponent.kt:41`

Leave `CommandFeedSheet.kt:109` and `DailySummaryScreen.kt:326` as-is for now — both only have a name string, not a `CatalogItem`. (WS-D changes the summary one; see D4.)

### A6. Wire capture into catalog management

`ui/screens/catalog/CatalogManagementScreen.kt` — in the add/edit item row, place a `PhotoCaptureButton(currentPath = item.imagePath, filePrefix = "item", …)` that on capture persists via `CatalogDao` (`item.copy(imagePath = path, updatedAt = System.currentTimeMillis())`).

### A7. Photo-first manual entry

`ui/components/ManualStepperComponent.kt` currently lists items as rows with a 32.dp icon. Change step 1 (item selection) to a **grid**: `LazyVerticalGrid(GridCells.Adaptive(minSize = 96.dp))`, each cell = `ItemIcon(size = 80.dp)` above the name, cell height ≥ 112.dp. This is the payoff — selecting an item becomes "tap the picture."

Sort the grid by `lastSoldAtMs DESC NULLS LAST` so the shop's actual movers surface first. `CatalogItem.lastSoldAtMs` already exists and is indexed.

### A8. Customer faces — finish what was schema'd

`ui/screens/customer/CustomerEditScreen.kt` — add `PhotoCaptureButton(currentPath = customer.photoPath, filePrefix = "customer", …)`, persisting to `CustomerRecord.photoPath`.
`ui/screens/customer/CustomerCard.kt` — render `photoPath` when present, else keep the existing initial-circle.

Consent note already agreed in `Docs/voice_assistant_framework.md` §R4: photo capture is **opt-in and shopkeeper-initiated**. Do not auto-prompt for it.

### A9. Bundled pack shots — ⚠️ BLOCKED, do not attempt

`seedMasterCatalog` seeds **53 items** (AppDatabase.kt:986+). Pre-loading photos for them would make day-one non-empty. **Antigravity cannot generate or source photographs.** This needs 53 licensed images supplied by the user before any code is written.

If/when the images arrive, the shape is: `res/drawable-nodpi/seed_<lowercased_name>.webp` + a `SeedItemImages.kt` `Map<String, Int>` consulted by `ItemIcon` as the tier between `imageUrl` and the vector fallback.

**Open question for the user (see §7):** the "stock photo website" referenced was not named. My recommendation is **Open Food Facts** — free API, keyed by barcode, genuinely good Indian FMCG coverage — but its images are **CC-BY-SA**, which obliges attribution and share-alike. That licence question must be settled before the images ship in an APK.

---

## 3. WS-B — Semantic colour vocabulary (ISSUE-116)

### B1. Single source of truth (new file)

`app/src/main/java/com/voicetoinvoice/app/ui/theme/LedgerColors.kt`

```kotlin
package com.voicetoinvoice.app.ui.theme

/**
 * The app's colour *vocabulary*. One meaning per colour, app-wide, never reused for a
 * second meaning — this is the only channel a shopkeeper who cannot read the label has.
 */
object LedgerColors {
    val MoneyIn      = Color(0xFF2E7D32)  // cash/UPI received, profit, growth, fast movers
    val MoneyOut     = Color(0xFFC62828)  // waste, loss, overdue receivables, shrink
    val Udhaar       = Color(0xFFF9A825)  // credit given — owed TO the shop, not yet money
    val Upi          = Color(0xFF0288D1)  // UPI specifically, where split from cash matters
    val Neutral      = Color(0xFF616161)  // no judgement attached

    /** Positive-is-good delta colouring. */
    fun forDelta(delta: Double): Color = if (delta >= 0) MoneyIn else MoneyOut

    /** Health-score banding — thresholds unchanged from ReportsScreen.healthColor. */
    fun forScore(score: Int): Color = when {
        score >= 70 -> MoneyIn
        score >= 40 -> Udhaar
        else        -> MoneyOut
    }
}
```

### B2. Replace every hardcoded colour literal

Delete `private val Color0xFF2E7D32` at `ui/screens/reports/ReportsScreen.kt:510` and the `healthColor` function at :467-471; route both through `LedgerColors`.

Then sweep for remaining literals and replace by meaning:

```bash
grep -rn "Color(0xFF" app/src/main/java/com/voicetoinvoice/app/ui/
```

Mapping rules — apply these, do not invent new ones:
- `0xFF2E7D32` (green) → `LedgerColors.MoneyIn`
- `0xFFC62828` / `MaterialTheme.colorScheme.error` **where it means money lost** → `LedgerColors.MoneyOut`. Leave `colorScheme.error` alone where it means "UI validation error."
- `0xFFF9A825` (amber) → `LedgerColors.Udhaar`
- `0xFF0288D1` (blue) → `LedgerColors.Upi`
- `0xFF616161` (grey) → `LedgerColors.Neutral`

`ui/components/ItemIcon.kt:78-88` (`getCategoryBackgroundColor`) is a **category** palette, not a money palette. Leave it alone — it is a different vocabulary and conflating them would break B's premise.

### B3. Fix the one existing collision

`ReportsScreen.MoversCard` (:286) paints "तेज़ 🔥" (fast-moving) green and "धीमा 🐢" (slow) with `colorScheme.error` red. Under the vocabulary, red = *money out*, and a slow-moving item is not a loss. Change slow/dead to `LedgerColors.Udhaar` (amber = attention, no loss claimed). Fast stays `MoneyIn`.

---

## 4. WS-C — Assistant comprehension and speed (ISSUE-117, ISSUE-118)

### C-PART-1 — Speed (ISSUE-117), edge function only

`supabase/functions/process-voice-job/index.ts`

**C1.1** Put the fast model at the head of the chain. Lines 57-62 become:

```typescript
const XAI_CHAT_MODELS: string[] = [
  Deno.env.get('XAI_CHAT_MODEL') || '',
  'grok-4.20-0309-non-reasoning',  // ISSUE-117: step 4 is structured extraction, not
                                   // reasoning. Measured 3849ms of a 5523ms job on
                                   // grok-4.5 (trace 54e7fe50). Also cheaper:
                                   // $1.25/$2.50 vs $2.00/$6.00 per 1M.
  'grok-4.5',
  'grok-4.3',
  'grok-4',
].filter(Boolean)
```

**C1.2 — the trap that will otherwise take the whole AI stage down.** Line 87 is:

```typescript
const supportsReasoningEffort = (model: string) => model.startsWith('grok-4')
```

`'grok-4.20-0309-non-reasoning'.startsWith('grok-4')` is **`true`**, so the current code would send `reasoning_effort: 'low'` to a model that has no reasoning to configure. If xAI answers 400 for the unsupported parameter, `isModelUnavailableError` (line 97-103) does **not** match it — it only matches deprecation wording — so `callGrokChatInterpretation` hits line 409 and **`break`s out of the chain entirely**. No fallback to grok-4.5. Step 4 dies silently on every job, which is precisely the ISSUE-021 failure mode the chain was built to prevent.

Change line 87 to:

```typescript
const supportsReasoningEffort = (model: string) =>
  model.startsWith('grok-4') && !model.includes('non-reasoning')
```

**C1.3 — defence in depth.** Widen `isModelUnavailableError` (line 97-103) so a *parameter* rejection advances the chain instead of killing it. Add to the returned disjunction:

```typescript
    b.includes('unsupported parameter') || b.includes('unknown field') ||
    b.includes('unrecognized') || b.includes('invalid_request_error')
```

**C1.4** Deploy immediately (standing authorisation, per CLAUDE.md):

```bash
npx supabase functions deploy process-voice-job --project-ref lyowklxsbfznnqridtgr
```

Then re-fetch the live bundle and grep for `grok-4.20-0309-non-reasoning` and `non-reasoning'` in the `supportsReasoningEffort` body. This project has a history of silent partial deploys — the grep is not optional.

### C-PART-2 — Comprehension (ISSUE-118)

**Hard rule preserved throughout: the AI never produces a number.** It chooses *which query to run*; Kotlin runs it against Room and phrases the result. This is the `Docs/master_build_plan.md` §4.1 rule and it is not negotiable.

**C2.1 — Free tier: add the missing item-scoped branch.**

`domain/query/LedgerQueries.kt` — add:

```kotlin
/** Qty and revenue for one item in a window. Backed by TransactionDao.getItemSalesBetween,
 *  which already GROUPs BY itemId — filter its result rather than adding a DAO query. */
suspend fun getItemSalesInPeriod(itemName: String, startMs: Long, endMs: Long): Triple<String, Double, Double>?
```

Resolve `itemName` with the existing `PhoneticKey` matching (mirror `QuestionTemplates.bestPhoneticMatch`, threshold `0.34`). Return `(resolvedName, qty, revenue)` or null.

`domain/query/QuestionTemplates.kt` — insert a new **branch 3** *before* the existing revenue branch (currently line 174), so `"आज कितने आलू बिके"` stops being swallowed:

```kotlin
// 3. Item-scoped sales: "आज कितने आलू बिके". MUST precede the REVENUE branch --
//    REVENUE_WORDS contains "बिका", which phone-matches "बिके", so this question
//    previously answered with total shop revenue (job 472d4af1, 2026-08-04).
if (matches(ngrams, REVENUE_WORDS) || matches(ngrams, GENERIC_QUESTION_WORDS)) {
    val candidate = extractCandidateName(clean)
    if (candidate.isNotBlank()) {
        val hit = ledgerQueries.getItemSalesInPeriod(candidate, todayMidnight, nowMs)
        if (hit != null) return ResponseComposer.formatItemSales(hit.first, hit.second, hit.third)
    }
}
```

Add `ResponseComposer.formatItemSales(name, qty, revenue)` → `"आज $name ${fmtQty(qty)} बिका, ₹${revenue.toInt()} का"`.

Renumber the existing comment numbering in the file so branches stay 1..9 in order.

**C2.2 — AI tier: new edge function `assistant-query`.**

New file `supabase/functions/assistant-query/index.ts`. Request:

```json
{ "transcript": "...", "item_names": ["आलू", …], "customer_names": ["रमेश", …] }
```

Response — a **query spec only**:

```json
{
  "query_type": "TOTAL_SALES|PROFIT|TOP_ITEM|ITEM_SALES|ITEM_STOCK|CUSTOMER_BALANCE|TOTAL_RECEIVABLES|WASTE|UNKNOWN",
  "item_name": "string|null",
  "customer_name": "string|null",
  "period": "TODAY|YESTERDAY|THIS_WEEK|THIS_MONTH|ALL_TIME"
}
```

Implementation notes:
- Reuse the `XAI_CHAT_MODELS` chain, `modelChain`, `isModelUnavailableError` and `supportsReasoningEffort` pattern from `process-voice-job` — **copy them into the new function**, do not refactor a shared module in this pass (the two functions deploy independently and a shared import is a separate change).
- `response_format: { type: 'json_object' }`, `temperature: 0`, `max_tokens: 256`.
- Timeout **4000 ms**. This is a person standing at a counter; a slow answer is worse than a template answer.
- System prompt must state: return ONLY the spec; `item_name`/`customer_name` must be copied verbatim from the supplied lists or be null; never invent quantities or amounts.
- `verify_jwt: false` to match the other client-called functions.

**C2.3 — Kotlin client and wiring.**

New `network/AssistantQueryClient.kt` — POST to `${SupabaseConfig.SUPABASE_URL}/functions/v1/assistant-query`, anon-key bearer, 4 s timeout, returns `AssistantQuerySpec?` (null on any failure).

New `domain/query/AssistantQuerySpec.kt` — the data class + `QueryType`/`Period` enums.

`QuestionTemplates.answerQuestion` — replace the final `return ResponseComposer.formatUnrecognized()` (line 236) with:

```kotlin
// 9. Nothing matched deterministically. Ask the AI which query this is -- it returns a
//    query SPEC, never a number, and Kotlin executes it. See ISSUE-118.
val spec = assistantQueryClient?.classify(clean, catalogNames(), customerNames())
if (spec != null && spec.queryType != QueryType.UNKNOWN) {
    return executeSpec(spec)
}
return ResponseComposer.formatUnrecognized()
```

`assistantQueryClient` becomes a nullable constructor param (`= null`) so existing tests and call sites keep compiling. `SttWorker.kt:611-612` passes a real one.

`executeSpec` is a private `when` over `QueryType` that calls the **existing** `LedgerQueries` methods and existing `ResponseComposer` formatters. It must not contain any new arithmetic.

**C2.4 — trace it.** Add to the client trace in `SttWorker.handleAssistantJob`: `assistant_tier` = `"template"` or `"ai_spec"`, plus `assistant_query_type` when the AI tier ran. Without this there is no way to tell later which tier answered.

---

## 5. WS-D — Ledger Explorer (ISSUE-119)

**Decision: upgrade `DailySummaryScreen` in place. Do not add a 15th `Screen` enum value.** It already owns the transaction list, range chips, the void flow and a TSV export (`performCopy`, line 89) that already appends totals. A parallel screen would duplicate all of it.

### D1. Filter state

`ui/screens/summary/DailySummaryScreen.kt` — add above the composable:

```kotlin
data class LedgerFilter(
    val rangeMode: RangeMode = RangeMode.DAY,
    val customStartMs: Long? = null,       // set → overrides rangeMode
    val customEndMs: Long? = null,
    val itemId: String? = null,
    val paymentMode: PaymentMode? = null,
    val customerId: String? = null,
    val txnType: TxnType? = null,
    val fromHour: Int? = null,             // 0..23 local, inclusive
    val toHour: Int? = null                // 0..23 local, inclusive
) {
    val isActive: Boolean get() = itemId != null || paymentMode != null ||
        customerId != null || txnType != null || fromHour != null || toHour != null
}
```

### D2. DAO — one filtered query, one filtered aggregate

`data/local/dao/TransactionDao.kt` — append. The `(:param IS NULL OR col = :param)` idiom is what keeps this a single static Room query instead of `@RawQuery`.

```kotlin
@Query("""
    SELECT * FROM transactions
    WHERE voided = 0
      AND timestamp >= :startMs AND timestamp < :endMs
      AND (:itemId       IS NULL OR itemId      = :itemId)
      AND (:paymentMode  IS NULL OR paymentMode = :paymentMode)
      AND (:customerId   IS NULL OR customerId  = :customerId)
      AND (:txnType      IS NULL OR txnType     = :txnType)
      AND (:fromHour IS NULL OR CAST(strftime('%H', timestamp/1000, 'unixepoch', 'localtime') AS INTEGER) >= :fromHour)
      AND (:toHour   IS NULL OR CAST(strftime('%H', timestamp/1000, 'unixepoch', 'localtime') AS INTEGER) <= :toHour)
    ORDER BY timestamp DESC
""")
fun getFiltered(
    startMs: Long, endMs: Long,
    itemId: String?, paymentMode: String?, customerId: String?, txnType: String?,
    fromHour: Int?, toHour: Int?
): Flow<List<TransactionRecord>>
```

`paymentMode`/`txnType` are passed as `String?` (`.name`) — the existing `getTotalByPaymentMode` already proves enums are stored as their name strings.

Add `getFilteredTotals(...)` with the identical `WHERE` clause returning a new
`data class FilteredTotals(val revenue: Double, val cash: Double, val upi: Double, val credit: Double, val qty: Double, val lineCount: Int)`.

Computing totals **in SQL, not in Kotlin over a windowed list**, is what makes the footer correct when the filtered set is larger than what is rendered.

### D3. The always-visible total — the core of this workstream

The user's requirement: *"total in the end written always."*

Implement as a **sticky footer** — `Scaffold(bottomBar = { LedgerTotalBar(totals, filter) })`, so it is on screen at all times regardless of scroll position, and recomputes live whenever `LedgerFilter` changes.

`LedgerTotalBar` shows, in this order and at `headlineSmall` weight for the first:
1. `₹<revenue>` — the filtered total, largest element on the bar
2. `💵 <cash>` · `📲 <upi>` · `📜 <credit>` — coloured per `LedgerColors` (`MoneyIn` / `Upi` / `Udhaar`)
3. line count and summed quantity
4. when `filter.isActive`, a "फ़िल्टर हटाएँ" chip that resets to defaults — a filtered total that looks like a shop total is a way to mislead yourself about your own day.

### D4. Filter bar UI

A horizontally scrollable `Row` of chips above the list:

- **Date** — existing `RangeMode` chips (आज / 7 दिन / 30 दिन), plus a "चुनें" chip opening a `DatePickerDialog` pair that sets `customStartMs`/`customEndMs`.
- **Item** — opens a bottom sheet reusing the **A7 photo grid**. Tap a picture to filter. This is the filter a non-reading shopkeeper can actually operate, so it must be photo-first, not a name list.
- **Time** — a chip opening presets: सुबह (6-12), दोपहर (12-17), शाम (17-22), रात (22-6), plus a custom hour range.
- **Payment** — नकद / UPI / उधार, coloured per `LedgerColors`.
- **Type** — बिक्री / वापसी (`TxnType.SALE` / `RETURN`).

Selected chips render filled with `LedgerColors`; unselected outlined.

While here, pass `imagePath` into the row's `ItemIcon` at line 326 — the screen has only `tx.itemName`, so look the item up once into a `Map<String, CatalogItem>` keyed by `itemId` and index it per row.

### D5. Export the filtered set

Extend `performCopy` (line 89) to (a) export exactly the currently-filtered rows and (b) write a header line naming the active filter, so a pasted table is self-describing. Keep the existing pending-price safeguard dialog — it is the reason a ₹0 line can't silently enter someone's spreadsheet.

Keep the trailing total block (lines 100-104) that already exists; it is the same "total at the end" requirement in the export surface.

---

## 6. Execution order

⚠️ **Superseded by §17**, which orders all eleven workstreams together. Part I's internal order is C-PART-1 → WS-B → WS-A → WS-D → C-PART-2; §17 is the authoritative sequence.

---

## 7. Verification — by effect, never by build

"BUILD SUCCESSFUL" verifies nothing. For each workstream:

**C-PART-1.** After deploying, record a fresh multi-item sale on the phone, then:

```sql
SELECT job_id, created_at,
       substring(diagnostic_trace_json from '"step_4_ai_model":"[^"]*"') AS model,
       substring(diagnostic_trace_json from '"step_8_timings":\{[^}]*\}') AS timings
FROM stt_job_logs ORDER BY created_at DESC LIMIT 5;
```

Pass = `step_4_ai_model` reads `grok-4.20-0309-non-reasoning` **and** `parseResolvedAtMs - sttResolvedAtMs` is materially under the 3849 ms baseline from trace 54e7fe50. A row where the model field still says `grok-4.5` means the deploy didn't take. **No new row at all = the verification did not happen; say so rather than reporting success.**

**WS-A.** Capture a photo for one item; confirm it renders in catalog, the manual-entry grid, and the confirm dialog. Force-stop and reopen the app to prove `imagePath` survives. Check the file is a ~40 KB JPEG under `filesDir/photos/`, not a 6 MB camera original.

**WS-B.** `grep -rn "Color(0xFF" app/src/main/java/com/voicetoinvoice/app/ui/` returns hits only in `ItemIcon.kt` (category palette) and `LedgerColors.kt`.

**WS-D.** With ≥2 days and ≥2 items of data: filter to a single item and confirm the footer total equals the sum of the visible rows; add a time filter and confirm it drops. Then clear filters and confirm the footer matches `ReportsScreen`'s "कुल बिक्री" for the same range — two independent code paths agreeing is the actual check.

**C-PART-2.** Ask, out loud, **"आज कितने आलू बिके"** — the exact utterance that failed in job 472d4af1. Pass = an item-scoped answer, and `assistant_tier: "template"` in the trace (the free branch should catch it, no AI call). Then ask something deliberately outside all nine branches and confirm `assistant_tier: "ai_spec"` with a sensible `query_type`.

---

## 8. Open questions — do not guess, ask

1. **A9 / stock photos.** Which website was it? My recommendation is Open Food Facts, but its CC-BY-SA licence needs a decision before images ship in an APK. Until answered, A9 stays blocked and camera capture is the only photo path.
2. **`AssistantFastPath.kt` is deleted** in the working tree (`git status` shows `D`), while `ConversationController`, `ResponseComposer` and `SpeechOutput` remain. `SttWorker.kt:732-733` still refers to "when the on-device fast path didn't resolve it." Was the deletion intentional? If a fast path is meant to exist, C2.3's AI tier should sit behind it, not in front.
3. **Time-filter day boundary.** `strftime(... 'localtime')` uses the device timezone. For a shop open past midnight, does "शाम" on the 9th include 00:30 on the 10th? I have assumed **no** (calendar days, hour range within each day). Confirm before D2 ships.

---

---
---

# PART II — Every remaining OPEN issue

`Docs/audit.md` lists five 🔴 OPEN issues. **All five were re-verified against the live system on 2026-08-10 before being planned here** — three of the five turned out to be misfiled. Do not trust the log's status field; the verified state is below.

| Log says | Actually |
|---|---|
| ISSUE-004 OPEN | **OPEN, confirmed.** Still hand-tuned, still unverified. → WS-I |
| ISSUE-018 OPEN | **Already fixed.** Receiver is gone from source. Log is stale. → WS-G |
| ISSUE-032 OPEN | **OPEN and worse than described.** Both halves changed since it was written. → WS-E |
| ISSUE-102 OPEN | **Already fixed in the working tree**, never logged. → WS-F |
| ISSUE-045 OPEN | **OPEN, no recurrence in 11 days.** Needs a closure rule, not a fix. → WS-H |

---

## 10. WS-E — RLS and `shop_id` (ISSUE-032) 🔴 the real one

### 10.1 Verified state, 2026-08-10

RLS, queried directly off `pg_class` / `pg_policies`:

| Table | `relrowsecurity` | policies |
|---|---|---|
| `transactions` | **false** | 2 |
| `unmatched_queue` | **false** | 2 |
| `stt_job_logs` | **false** | 1 |
| *(all 15 other public tables)* | true | ≥1 (except `parse_inspections`, 0) |

Supabase's own linter agrees — three `ERROR`-level lints: `rls_disabled_in_public` and `policy_exists_rls_disabled` on exactly those three tables.

**The finding the audit log misses.** The existing policies are:

```
transactions      "Public transactions access"        ALL  USING (true)  WITH CHECK (true)
transactions      "Shop isolation for transactions"   ALL  USING (shop_id IN (SELECT id FROM shops WHERE user_id = auth.uid()))
unmatched_queue   "Public unmatched_queue access"     ALL  USING (true)  WITH CHECK (true)
unmatched_queue   "Shop isolation for unmatched_queue" ALL USING (shop_id IN (SELECT id FROM shops WHERE user_id = auth.uid()))
stt_job_logs      "Public stt_job_logs access"        ALL  USING (true)  WITH CHECK (true)
```

Postgres **OR**s permissive policies. `true OR shop_isolation` is `true`. So:

> **Enabling RLS on these three tables changes nothing.** It will not break the client — and it will not secure anything either. The `USING (true)` policy grants the anon role full read/write regardless.

Real isolation requires **dropping** the `Public … access` policies. That will hard-break every client write, because the app **has no authentication at all** — the anon key is compiled into `network/SupabaseConfig.kt`, there is no sign-in flow, so `auth.uid()` is `NULL` and the shop-isolation policy matches zero rows.

**This is not an RLS bug. It is an unbuilt-auth bug wearing an RLS costume.** Any plan that just says "turn RLS on" is fencing off the actual problem — precisely the failure mode CLAUDE.md §4 warns about.

### 10.2 `shop_id` — the log's claim is stale

Log says: `shops` has 0 rows, every `shop_id` is NULL. **Live, today:**

```
shops                            2
catalog_items    131 total,  53 shop_id IS NULL
transactions     236 total, 163 shop_id IS NULL
stt_job_logs                232 shop_id IS NULL
```

So `ensure_shop` now works and new rows are tenanted — but **69% of transactions and all 53 seeded catalog items are still NULL**. That is worse than uniformly-NULL, because it is *silently partial*: the edge function's catalog fetch does `.eq('shop_id', resolvedShopId)` (index.ts:1153), so once a shop id is resolved, **the 53 seeded items are invisible to it**. Any future shop-scoped report would show 73 of 236 transactions and call it the shop's history.

### 10.3 Steps

**E1 — Backfill, before anything else.** One-off migration. There are exactly 2 shops; the plan assumes single-tenant reality (`ensureShopId 2f992a33-fa26-4be2-9006-3e6eafd41e2c` appears in production traces).

⚠️ **Confirm with the user which of the 2 shop rows is the real one before running this.** Do not guess — a wrong backfill mislabels the entire ledger.

```sql
-- supabase/migrations/<timestamp>_backfill_shop_id.sql
UPDATE public.catalog_items SET shop_id = '<CONFIRMED_SHOP_UUID>' WHERE shop_id IS NULL;
UPDATE public.transactions  SET shop_id = '<CONFIRMED_SHOP_UUID>' WHERE shop_id IS NULL;
UPDATE public.stt_job_logs  SET shop_id = '<CONFIRMED_SHOP_UUID>' WHERE shop_id IS NULL;
UPDATE public.unmatched_queue SET shop_id = '<CONFIRMED_SHOP_UUID>' WHERE shop_id IS NULL;
```

Verify: all four `count(*) WHERE shop_id IS NULL` return **0**.

**E2 — Enable RLS.** Now safe and honest about what it buys:

```sql
ALTER TABLE public.transactions    ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.unmatched_queue ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.stt_job_logs    ENABLE ROW LEVEL SECURITY;
```

This clears the three ERROR lints and closes the "policies exist but are not enforced" gap. **State plainly in the audit entry that this does not yet restrict anon access** — the `USING (true)` policies remain. Claiming otherwise would be a false security record.

**E3 — `parse_inspections` has RLS on and zero policies** = deny-all for anon. Confirm nothing client-side reads it (grep `parse_inspections` across `app/` and `supabase/functions/`). If unused, drop the table. If used by a service-role path only, add a comment recording that and leave it.

**E4 — Auth is a prerequisite, not a step here.** Dropping `Public … access` is **out of scope for this plan** and must not be attempted. It requires a Supabase auth session in the client first. Record it as a follow-up issue with this exact wording so nobody "helpfully" drops the policy and bricks sync.

**E5 — `SECURITY DEFINER` functions callable by anon.** The linter flags six: `ensure_shop`, `reset_learned_parse`, `demote_learned_parses_for_job`, `record_learned_parse_observation`, `record_unmatched_item_observation`, `promote_verified_term_aliases`. `ensure_shop` is called by the client and must stay. For the other five, check whether any client path calls them; if only `process-voice-job` does (service-role), then:

```sql
REVOKE EXECUTE ON FUNCTION public.<fn>(<args>) FROM anon;
```

Do these **one at a time**, re-running a real voice job after each. A blanket revoke will silently break the learned-parse pipeline.

**E6 — `search_path` on 9 functions** (WARN). Low risk, mechanical:
`ALTER FUNCTION public.<fn>(<args>) SET search_path = public, pg_temp;`

---

## 11. WS-F — AI/FAST/RULES badges (ISSUE-102) ✅ already fixed

**Verified:** all three call sites in `ui/screens/logs/DiagnosticLogsScreen.kt` already read `root.optJSONObject("server") ?: root` — `pathBadge` (:223), `serverIssues` (:243), `fastPathSkipReason` (:255). That is exactly what `Docs/ai_badge_trace_path_fix_plan.md` specified. The change is in the working tree and uncommitted.

**Do not re-implement.** Steps:

1. Install the current build and confirm on-device that ⚡ FAST / 🤖 AI / 📐 RULES badges now render in Diagnostic Logs. Job `2fd43483` has `step_4_fast_path.used: true` and should show ⚡ FAST; a job with `step_4_interpretation_source: "grok_ai"` should show 🤖.
2. Only after seeing it on screen, move ISSUE-102 to 🟢 RESOLVED with a Verification Date saying what was observed.

---

## 12. WS-G — `audit.md` hygiene (ISSUE-018 + doc defect)

**G1 — The whole "## 2. Living Issues Log" section is duplicated.** Lines 48–309 and 310–~1464 are two copies with the same heading; the 🔴 OPEN blocks at 50–88 and 312–351 are byte-identical. Delete the second copy (from the second `## 2. Living Issues Log` at line 310 down to just before the second `### 🟢 RESOLVED ISSUES` at 353), then reconcile the two RESOLVED lists into one, keeping every unique entry. **Diff the two lists before deleting anything** — if the second copy has entries the first lacks, they must be preserved.

**G2 — ISSUE-018 is stale-open.** Its heading already says `✅ CLOSED 2026-07-30 by ISSUE-050` while its body says `Status: OPEN`. **Verified fixed:** the only surviving mention in source is a past-tense comment at `service/UpiNotificationListenerService.kt:18` (*"`SEED_TEST_TX` / `TEST_UPI` used to be registered here…"*); grep for `SEED_TEST_TX|TEST_UPI|RECEIVER_EXPORTED|registerReceiver` across `app/src/main/java` returns nothing else. Move it to 🟢 RESOLVED and delete the contradictory `Status: OPEN` line.

**G3** — Once WS-E lands, update §1 "Ground-Truth Source-Code Verified Constants" with the new Room version (28) and the RLS state.

---

## 13. WS-H — ISSUE-045 (recording reached server twice, zero log rows)

**Do not invent a fix. The root cause was never diagnosed and still hasn't reproduced.**

**Verified:** no recurrence. Status distribution across all 399 rows shows **zero `QUEUED`** rows and only 2 `ERROR` (newest 2026-08-08); the 111 `FAILED` rows stop at 2026-08-05. The instrumentation from ISSUE-046 has had 11 days and caught nothing.

**Steps:** write a closure criterion into the issue and otherwise leave it alone.

> Close ISSUE-045-OPEN when either (a) 30 consecutive days pass with no `job_id` that received a 202 and has no `stt_job_logs` row, or (b) a recurrence is caught by the `Failed to write QUEUED placeholder` console log or a client trace with `outcome: "exception"`. Until one of those, it stays open. **Absence of recurrence is not a fix** — the failure was never explained.

Add one cheap detector so (a) is checkable rather than assumed: a `TaskCreate`-style reminder is not enough; instead record in the issue the exact query to run monthly:

```sql
SELECT count(*) FROM stt_job_logs WHERE status = 'QUEUED' AND created_at < now() - interval '1 hour';
```

---

## 14. WS-I — ISSUE-004 phonetic collapse set (acoustic consonant blending)

**Verified still open.** ISSUE-020 moved matching into phonetic-key space, which removed the structural blindness, but the collapse set is hand-tuned against observed traces and **no post-deploy verification batch has ever been run**.

Live evidence that the class is still alive — from the last 25 jobs:

```
"तेज किलोसरगी"        (fused, unrecoverable)
"दो किलो आ" / "पाँच किलो आ" / "दो किलो से" / "पाँच किलो से"   (item truncated to a single phone)
"दो किलो आहा"          → PARSED, wrong item
```

**I1 — Derive the collapse set from data, not intuition.** Build the confusion matrix the issue asks for:

```sql
SELECT
  substring(diagnostic_trace_json from '"grokTranscript":"([^"]*)"')   AS grok,
  substring(diagnostic_trace_json from '"sarvamTranscript":"([^"]*)"') AS sarvam,
  raw_transcript, parsed_item_name, status, created_at
FROM stt_job_logs
WHERE diagnostic_trace_json LIKE '%grokTranscript%'
ORDER BY created_at DESC;
```

Where the two engines disagree, the disagreement **is** the confusion pair — two independent acoustic models rendering the same phones differently. Tabulate character-level substitutions across all 399 jobs and keep pairs occurring ≥3 times. That is a derived collapse set with evidence behind it.

**I2 — Mirror it on both sides.** The collapse table lives in `domain/parser/PhoneticKey.kt` (client) and is re-implemented in `supabase/functions/process-voice-job/index.ts` (server). **Both must change together** — this is the mirrored-logic case CLAUDE.md calls out.

**I3 — Add a regression test.** `app/src/test/…/PhoneticKeyTest.kt` — assert that each derived pair collapses to the same key. Include the live failures above as fixtures.

**I4 — Verify by effect.** Re-record the same utterances that failed (`"छः किलो अदरक"`, `"तेज किलोसरगी"`) and re-query. Pass = `status` improves to `AUTO_CONFIRMED` with the right `parsed_item_name`. **A passing unit test is not verification** — quote the new `stt_job_logs` row.

---

## 15. WS-J — Intent router misfires on plain sales (new, ISSUE-120)

**Found in this session, not previously logged.** Of 13 assistant-flagged jobs, three plain sales were classified `ACTION_COMMAND` at confidence 0.526 and **routed to review instead of booked**:

```
54e7fe50  "चार किलो चाच"   ACTION_COMMAND 0.526  runnerUp SALE 0.9  routedToReview: true
8430fe59  "चार किलो आलू"   ACTION_COMMAND 0.526  runnerUp SALE 0.9  routedToReview: true
467ea9d5  "चार किलो गोल्ड"  ACTION_COMMAND 0.526  runnerUp SALE 0.9  routedToReview: true
```

All three open with **"चार किलो"**. The scores are the tell: `ACTION_COMMAND` scores a flat `1` while `SALE` scores `0.9` — so the winner is decided by a hair, `needsArbitration` is set, and arbitration resolves the wrong way. A sale being silently diverted to review is a direct hit on the core loop.

**J1** — Read `domain/router/IntentRouter.kt` and find which `ACTION_COMMAND` trigger phrase `"चार"` / `"किलो"` phone-matches. The flat `1` suggests an exact trigger hit, most likely a short trigger whose phonetic key collides with a numeral.

**J2** — Fix the collision at its source: short triggers (≤3 phones) must require a higher match quality, or numerals must be excluded from trigger matching entirely. **Do not** just raise the arbitration threshold — that treats the symptom and this plan is explicit about which is which.

**J3** — Bug class statement, required before closing: this fixes *the specific numeral/trigger collision*. The class — "short phonetic triggers collide with common words" — is only eliminated if J2 takes the exclusion route rather than tuning one phrase.

**J4** — Verify: record `"चार किलो आलू"` and confirm `step_2b_intent_classification.intent` is `SALE`, `bookedServerSide: true`, `routedToReview: false`.

---

## 16. WS-K — Expenses and cash book (new, ISSUE-121)

**Verified gap:** zero matches for `Expense|expense|kharcha|CashDrawer|cashInHand|openingBalance` across all of `app/src/main/java`. Every "मुनाफ़ा" the app shows — `ProfitCalculator`, `ReportsScreen`, the voice assistant's profit answer — is **gross** profit. Rent, bijli, staff, tempo, chai are invisible.

This is why the diary stays open next to the phone, and it is the largest remaining hole between this app and Excel.

**K1 — Entity.** New `data/local/entity/ExpenseRecord.kt`, Room **28 → 29**:

```kotlin
@Entity(tableName = "expenses", indices = [Index("timestamp"), Index("category"), Index("synced"), Index("shopId")])
data class ExpenseRecord(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val shopId: String = ShopContext.currentOrLegacy(),
    val category: ExpenseCategory,
    val amount: Double,
    val note: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val source: TransactionSource = TransactionSource.VOICE,
    val rawTranscript: String = "",
    val jobId: String? = null,
    val voided: Boolean = false,
    val synced: Boolean = false
)

enum class ExpenseCategory { RENT, ELECTRICITY, SALARY, TRANSPORT, SUPPLIES, TEA, OTHER }
```

Categories are **icon-first** in the UI (WS-A/WS-B rules apply: a category is chosen by tapping a picture, not reading a word).

**K2 — DAO + migration** mirroring `TransactionDao`: `getBetween`, `getTotalByCategory`, `getPeriodTotal`, `getUnsynced`, `markSynced`, soft-delete via `voided`.

**K3 — Voice intent.** Add `EXPENSE` to `AssistantIntent` and trigger phrases (`खर्चा`, `kharcha`, `बिजली का बिल`, `किराया`, `expense`) in `IntentRouter`. ⚠️ Read WS-J first — adding short triggers is exactly the collision risk J is fixing. Do WS-J before this.

**K4 — Net profit, stated honestly.** `ProfitCalculator` gains `netProfit = grossProfit - expensesInPeriod` and an `expenseCoverage` flag. **Rule: never relabel the existing gross figure as net.** Show both, labelled, exactly as `costCoveragePct` already qualifies the gross number. A shopkeeper who sees one "मुनाफ़ा" that quietly changed meaning between versions loses trust permanently.

**K5 — Cash book.** Once expenses exist, `cashInHand = opening + cashSales + paymentsReceived − cashExpenses`. Surface it in the WS-D sticky footer as a fourth figure. This is the single most-used page of the physical diary.

**K6 — Sync.** Add `expenses` to `CloudSyncManager` and `SyncEngine` following the existing `synced`-flag sweep. New Supabase table + RLS matching whatever WS-E settles on.

---

## 17. Revised execution order (all workstreams)

Dependency-ordered. Ship in this sequence:

1. **WS-F** — confirm badges on device, log ISSUE-102 resolved. Zero code.
2. **WS-G** — `audit.md` dedupe + ISSUE-018 reclassification. Zero code. Do it before anything writes new entries into a duplicated file.
3. **C-PART-1** — model swap + the `supportsReasoningEffort` trap + deploy.
4. **WS-E** — E1 backfill (after confirming the shop UUID), E2 enable RLS, E3, E5, E6. E4 stays a follow-up.
5. **WS-B** — colours. Consumed by A, D, K.
6. **WS-A** — photos. A9 blocked on assets.
7. **WS-J** — intent router collision. **Must precede K3.**
8. **WS-D** — Ledger Explorer. Needs A7's photo grid.
9. **C-PART-2** — assistant comprehension.
10. **WS-K** — expenses + cash book. Room 28 → 29.
11. **WS-I** — phonetic confusion matrix. Independent; slot anywhere after 3.
12. **WS-H** — record the closure criterion. Zero code.

**Room versions are sequential and must not be reordered:** WS-A takes 27 → 28, WS-K takes 28 → 29. If WS-K ships first, renumber both.

---

## 18. Additional open questions (Part II)

4. **Which of the 2 `shops` rows is real?** E1 cannot run without this. A wrong backfill mislabels 163 transactions and 232 log rows.
5. **Is `parse_inspections` used at all?** RLS is on with zero policies, so anon is denied. If something reads it client-side it is already broken and nobody noticed.
6. **Do you want auth at all (E4)?** Without a Supabase auth session, RLS can never do more than it does today. That is a product decision — a single-shop app may legitimately not need it — but it should be decided, not defaulted into.

---

## 19. Deviations

Antigravity: replace this section with what you actually changed and why. If none, write "None."
