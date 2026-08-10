# Speed / Cost / Smoothness Plan

**Author:** Claude Code · **Date:** 2026-08-09 · **Implementer:** Antigravity
**Issues opened by this plan:** ISSUE-110 … ISSUE-116

## Why (evidence, not theory)

All numbers below are **verified** by querying `stt_job_logs` on project `lyowklxsbfznnqridtgr`
on 2026-08-09, restricted to traces that carry the `step_4_fast_path` key (i.e. the current
deploy, 64 jobs).

| Road a recording takes | Jobs | Avg end-to-end | Auto-confirm |
|---|---|---|---|
| Fast path — segmenter only, **no AI call** | 38 | **1,332 ms** | **89%** |
| Grok-4.5 chat interpretation | 26 | **7,400–8,800 ms** | **36%** |

Supporting facts, each verified:

- `step_8_timings` averages (39 jobs): `catalogFetchedAtMs` 256, `aliasesFetchedAtMs` 292
  (cumulative, so alias adds ~292 on top), `sttResolvedAtMs` 1,068, parse phase 3,165,
  ledger phase 413. **~550 ms elapses before either STT call starts.**
- Of the 26 AI-triggering jobs, **13 had `resolutionKind: MATCH` with `itemMatchNorm = 0`** —
  the segmenter had already resolved them exactly.
- `item_name_source = "segmenter_override"` (5 jobs): the AI was called, its item name was then
  discarded in favour of the segmenter's, confidence fell 0.9 → 0.55, and **0 of 5
  auto-confirmed.** Paid ~7.4 s and a Grok call to manufacture a review-queue chore.
- Job `76892c70-6d5f-4d87-b105-6bf7bdb08a07`: `sttResolvedAtMs` 1,288 → `parseResolvedAtMs`
  28,497. **A single Grok chat call took 27.2 s and succeeded** (`step_4_ai_error: null`,
  model `grok-4.5`), with `step_5…triggered: false, passesExecuted: 0` — so re-decode was *not*
  involved. `AI_CHAT_TIMEOUT_MS` is 45,000.
- `learned_parses`: 108 rows, **0 hits ever**. Rows are shattered across **8 catalog
  fingerprints × 2 shop IDs**; only 6 are `promoted`; avg `observations` 0.9. Memo key
  `PANCAKILOALO` exists under 2 fingerprints with 4 combined observations — enough to promote
  if it weren't fragmented. `computeCatalogFingerprint` hashes the **whole** catalog, so any
  catalog edit invalidates every memo.
- `MainActivity.kt:270` collects `getAllJobsTraceLogsFlow()` — `SELECT *` over 200 rows
  including `diagnosticTraceJson` (avg 4.4 KB/row) — and **the result is never read**. Verified
  by grep: `sttJobsState` has exactly one occurrence in the file, its own declaration.
- Performance advisor: 11 unindexed foreign keys, 1 duplicate index on `stt_job_logs`.

## Scope rules for the implementer

- **Do not** change `supabase/functions/process-voice-job/index.ts` parsing semantics beyond
  what is written here. In particular: do not touch `OrderingSegmenter`, `phonetic.ts`,
  `item_resolution.ts`, or the confidence/sanity model.
- The Kotlin client mirrors server parsing logic. **Phases A and C are server-only** — the
  mirrored Kotlin parser (`domain/parser/`) must NOT be changed, because none of these changes
  alter parse *semantics*, only which engine produces the parse and when.
- Do not run gradle. Do not build. Do not deploy.
- After each phase, stop and re-read the file you changed before starting the next.

---

# Phase A — Server latency (`supabase/functions/process-voice-job/index.ts`)

## A1 · Start Sarvam STT immediately; fetch catalog and aliases concurrently

**Problem:** catalog fetch and alias fetch are two serial `await`s that both complete before
either STT promise is constructed. Sarvam STT needs neither of them.

**A1.1** — The API-key reads currently sit at lines 1195–1196, *after* both fetches. Move them
up. Find this block (immediately before `let catalogFetchError: string | null = null`):

```ts
    let catalogFetchError: string | null = null
```

Replace with:

```ts
    // A1 (ISSUE-110): Sarvam STT needs nothing but the audio, so it starts here rather than
    // after ~550ms of catalog+alias DB round-trips. Verified 2026-08-09: catalogFetchedAtMs
    // averaged 256ms and aliasesFetchedAtMs 292ms cumulative across 39 jobs, all of it ahead
    // of the first STT byte. callSarvamStt never throws (callSarvamSttOnce catches and returns
    // an SttOutcome), so holding this promise unawaited cannot produce an unhandled rejection.
    const xaiApiKey = Deno.env.get('XAI_API_KEY') || ''
    const sarvamApiKey = Deno.env.get('SARVAM_API_KEY') || ''
    const sarvamPromise: Promise<SttOutcome> = sarvamApiKey
      ? callSarvamStt(audioBuffer, jobId, sarvamApiKey)
      : Promise.resolve(EMPTY_STT)

    let catalogFetchError: string | null = null
```

**A1.2** — Make the two DB fetches concurrent. The catalog fetch is a `try/catch/finally` block
ending in `timings.catalogFetchedAtMs = mark()`, followed by the `catalogNames` build, followed
by the alias `try/catch/finally` ending in `timings.aliasesFetchedAtMs = mark()`.

Restructure so both queries are in flight together, preserving every existing behaviour
(error capture, the `note()` calls, the alias sorting/filtering, both timing marks):

- Build the two PostgREST query objects **without awaiting**:
  - `catalogQuery` — identical to today's `supabase.from('catalog_items').select(...).eq('active', true)`
    plus the conditional `.eq('shop_id', resolvedShopId)`.
  - `aliasQuery` — identical to today's `supabase.from('term_aliases').select(...).or(...)`.
- Await both with `Promise.allSettled([withTimeout(catalogQuery, 5000, 'Catalog DB fetch'),
  withTimeout(aliasQuery, 5000, 'Aliases DB fetch')])`.
- Immediately after the `allSettled` resolves, set **both** marks:
  `timings.catalogFetchedAtMs = mark()` and `timings.aliasesFetchedAtMs = mark()`.
  They will now be near-identical; that is the intended signal.
- Then run today's result-handling logic unchanged against each settled result: for the catalog,
  the `catErr` / `catData.length > 0` / else-`note()` branches; for the aliases, the
  `sorted` / `isGlobal` / `distinct_shop_count < 3` filtering into the `aliases` map.
- A `rejected` settled result must take the same path today's `catch` block takes for that
  fetch (set `catalogFetchError` and `note('catalog_fetch', 'error', …)` / `console.warn` for
  aliases).

**A1.3** — At the STT construction site, delete the now-duplicated key reads and the
now-duplicated `sarvamPromise`. Find:

```ts
    // Step 2: Dual concurrent STT (Grok + Sarvam), 8s timeout each, with fast-path race shortcut
    const xaiApiKey = Deno.env.get('XAI_API_KEY') || ''
    const sarvamApiKey = Deno.env.get('SARVAM_API_KEY') || ''

    const grokPromise: Promise<SttOutcome> = xaiApiKey
      ? callGrokStt(audioBuffer, jobId, xaiApiKey, { keyterms, language: 'hi' })
      : Promise.resolve(EMPTY_STT)
    const sarvamPromise: Promise<SttOutcome> = sarvamApiKey
      ? callSarvamStt(audioBuffer, jobId, sarvamApiKey)
      : Promise.resolve(EMPTY_STT)
```

Replace with:

```ts
    // Step 2: Dual concurrent STT (Grok + Sarvam), 8s timeout each, with fast-path race shortcut.
    // sarvamPromise was started in A1.1, before the catalog/alias fetches. Grok waits because it
    // is the only one that needs `keyterms`.
    const grokPromise: Promise<SttOutcome> = xaiApiKey
      ? callGrokStt(audioBuffer, jobId, xaiApiKey, { keyterms, language: 'hi' })
      : Promise.resolve(EMPTY_STT)
```

Everything downstream (`raceShortcut`, `Promise.all([grokPromise, sarvamPromise])`) is unchanged.

**Expected effect:** `sttResolvedAtMs` drops by roughly the current `aliasesFetchedAtMs` value
(~300–550 ms) on every job, fast path included.

## A2 · Let the fast path handle unambiguous bulk-total sales

**Problem:** [index.ts:691] disqualifies the fast path whenever *any* price is spoken, even when
quantity, unit, item and price intent are all unambiguous. Trace `8d38c7b7` spent 6,083 ms
asking Grok to compute 40 ÷ 2 on a segment the segmenter had already matched exactly.

**Deliberately still excluded** (do not widen): `RATE_UPDATE` (mutates catalog prices),
`AMBIGUOUS_UNTRUSTED` (no rupee word), and any multi-segment utterance (price attribution across
lines is exactly what system-prompt rule 8 exists to prevent).

**A2.1** — In `buildFastPathFrom`, replace this single line:

```ts
    if (seg.spokenPrice != null || seg.rupeeWordPresent) return no('spoken_price_present')
```

with:

```ts
    // A2 (ISSUE-111): a spoken price no longer forces the AI round-trip when the price is
    // deterministically unambiguous. Narrow on purpose — single segment, leading quantity,
    // an explicit rupee word, a positive parsed price, and the whole-utterance detector
    // reporting no ambiguous number. That is exactly BULK_SALE_TOTAL and nothing else:
    // RATE_UPDATE is excluded by the hasLeadingQty check below, and AMBIGUOUS_UNTRUSTED is
    // excluded by requiring rupeeWordPresent.
    if (seg.spokenPrice != null || seg.rupeeWordPresent) {
      const whole = detectPriceIntent(chosenRaw)
      const deterministicBulkTotal =
        segments.length === 1 &&
        seg.hasLeadingQty === true &&
        seg.rupeeWordPresent === true &&
        typeof seg.spokenPrice === 'number' && seg.spokenPrice > 0 &&
        typeof seg.quantity === 'number' && seg.quantity > 0 &&
        whole.priceIntent === 'BULK_SALE_TOTAL' &&
        whole.hasAmbiguousPriceNumber === false
      if (!deterministicBulkTotal) return no('spoken_price_present')
    }
```

`detectPriceIntent` is already imported at the top of the file — do not add an import.

**A2.2** — The item pushed into `items[]` must carry the bulk-total pricing instead of the
catalog rate. In the `items.push({ … })` call inside `buildFastPathFrom`, the current fields are:

```ts
      price: priceForSpokenUnit,
      price_intent: 'NONE',
```

Replace those two lines with:

```ts
      price: (seg.spokenPrice != null && seg.quantity > 0)
        ? seg.spokenPrice / seg.quantity
        : priceForSpokenUnit,
      total: (seg.spokenPrice != null && seg.quantity > 0) ? seg.spokenPrice : undefined,
      price_intent: (seg.spokenPrice != null && seg.quantity > 0) ? 'BULK_SALE_TOTAL' : 'NONE',
```

Leave every other field in that object exactly as it is.

**Verification note for whoever reviews this:** control reaches `items.push` with a non-null
`seg.spokenPrice` *only* through the `deterministicBulkTotal` gate in A2.1, so the guards above
cannot fire on a `RATE_UPDATE` or ambiguous read.

## A3 · Bound the AI chat tail at 12 s

**Problem:** `AI_CHAT_TIMEOUT_MS` is 45,000. Verified tail: one job spent 27.2 s in a *successful*
Grok call. A fallback path already exists — `parseSource = 'segmenter_fallback'` at
[index.ts:1692] — and produces a usable parse routed to review.

Find:

```ts
const AI_CHAT_TIMEOUT_MS = Number(Deno.env.get('AI_CHAT_TIMEOUT_MS') || '45000')
```

Replace with:

```ts
// A3 (ISSUE-112): 45s was a tail nobody can wait through. Verified 2026-08-09, job
// 76892c70-6d5f-4d87-b105-6bf7bdb08a07: a SUCCESSFUL grok-4.5 chat call took 27.2s
// (sttResolvedAtMs 1288 -> parseResolvedAtMs 28497) with re-decode off, so this is the chat
// call's own tail and not an STT artifact. Past 12s the segmenter fallback below
// (parseSource='segmenter_fallback') is strictly better than making the shopkeeper wait:
// it still books to the review queue instead of vanishing. Env-tunable if this proves tight.
const AI_CHAT_TIMEOUT_MS = Number(Deno.env.get('AI_CHAT_TIMEOUT_MS') || '12000')
```

---

# Phase B — Client smoothness

## B1 · Delete the dead 200-row trace collection at the root composable

**Verified:** `sttJobsState` occurs exactly once in `MainActivity.kt` — its own declaration. It
loads 200 rows × ~4.4 KB of `diagnosticTraceJson` and re-emits into the **root** composable on
every write to `stt_jobs` (a single recording writes QUEUED → TRANSCRIBING → PARSED → synced).

In `app/src/main/java/com/voicetoinvoice/app/MainActivity.kt`, delete this line entirely:

```kotlin
    val sttJobsState by database.sttJobDao().getAllJobsTraceLogsFlow().collectAsState(initial = emptyList())
```

Delete nothing else. If the Kotlin compiler later reports an unresolved `sttJobsState`
reference anywhere, **stop and report it** — that would contradict the grep this step rests on.

## B2 · Give the Home command feed a bounded, trace-free query

**Problem:** `HomeScreen.kt:179` uses the same `SELECT *` LIMIT 200 flow, then immediately
discards everything older than 24 h in `commandFeedJobs`. Verified: `CommandFeedSheet.kt`
contains no reference to `diagnosticTraceJson`, so the blob is loaded and never read.

**B2.1** — In `app/src/main/java/com/voicetoinvoice/app/data/local/dao/SttJobDao.kt`, add this
query directly beneath the existing `getAllJobsTraceLogsFlow()` declaration:

```kotlin
    /**
     * Command-feed variant of [getAllJobsTraceLogsFlow]: bounded by TIME rather than by a
     * LIMIT 200. HomeScreen discards everything older than 24h anyway, so the old query was
     * materialising ~200 rows x ~4.4KB of diagnosticTraceJson on every stt_jobs write and
     * re-emitting the lot into a live composable. At observed volumes (12-94 jobs/day) this
     * cuts the working set by roughly an order of magnitude.
     *
     * NOTE: this still returns the full SttJobRecord including diagnosticTraceJson, because
     * CommandFeedSheet takes List<SttJobRecord>. Stripping the blob as well would mean an
     * explicit column list plus a projection type and a change to that composable's
     * signature — deliberately out of scope here. Row-count bounding is the cheap 90%.
     */
    @Query(
        """
        SELECT * FROM stt_jobs
        WHERE recordedAtMs >= :sinceMs
        ORDER BY recordedAtMs DESC
        """
    )
    fun getJobsSinceFlow(sinceMs: Long): Flow<List<SttJobRecord>>
```

**B2.2** — In `app/src/main/java/com/voicetoinvoice/app/ui/screens/home/HomeScreen.kt`, replace:

```kotlin
    val allRecentJobs by db.sttJobDao().getAllJobsTraceLogsFlow().collectAsState(initial = emptyList())
    val commandFeedJobs = remember(allRecentJobs) {
        val since = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        allRecentJobs.filter { it.recordedAtMs >= since }
    }
```

with:

```kotlin
    // Bounded at the query, not in memory (ISSUE-113). `since` is remembered so the flow key
    // is stable across recompositions — recomputing System.currentTimeMillis() inline would
    // build a new Flow on every recomposition.
    val commandFeedSince = remember { System.currentTimeMillis() - 24 * 60 * 60 * 1000L }
    val commandFeedJobs by remember(commandFeedSince) {
        db.sttJobDao().getJobsSinceFlow(commandFeedSince)
    }.collectAsState(initial = emptyList())
```

`inFlightCount` below it already keys off `commandFeedJobs` and needs no change. If
`allRecentJobs` is referenced anywhere else in the file, **stop and report** — grep showed
lines 179/180/182 only.

**No Room migration is required**: no schema change, only a new `@Query`.

---

# Phase C — Make Learned Parse Memory actually fire

**Problem (verified):** `computeCatalogFingerprint(fullCatalogList)` hashes every catalog name,
so adding one unrelated item invalidates the shop's entire parse memory. 108 memos are split
across 8 fingerprints; 0 have ever hit. The independent safety belt —
`itemsCorroboratedBySegmenter`, which requires the deterministic segmenter to reach the same
item identities on *this* recording — is unaffected by this change and stays in force.

## C1 · Scope the fingerprint to the items the memo actually names

**C1.1** — Directly beneath the existing `computeCatalogFingerprint` function, add:

```ts
/**
 * ISSUE-114: fingerprint only the catalog entries a memo actually references, not the whole
 * catalog. The whole-catalog hash meant adding one unrelated item reset every memo in the shop
 * — verified 2026-08-09: 108 rows across 8 fingerprints, 0 lifetime hits, avg observations 0.9.
 * Item names are matched case/whitespace-insensitively and sorted so the hash is order-stable.
 * A memo item absent from the catalog contributes the literal token `absent:<name>`, so a memo
 * whose item was deleted still invalidates — which is the one case the old hash got right.
 */
function computeScopedCatalogFingerprint(memoItemNames: string[], catalogNames: string[]): string {
  const norm = (n: string) => n.toLowerCase().trim()
  const catalog = new Set(catalogNames.map(norm))
  const parts = Array.from(new Set(memoItemNames.map(norm)))
    .sort()
    .map(n => (catalog.has(n) ? n : `absent:${n}`))
  return fnv1aHash(parts.join('|'))
}
```

**C1.2** — At the memo lookup, replace the fingerprint equality test. Find:

```ts
        if (memoRow && (memoRow as any).promoted && !(memoRow as any).permanently_blocked &&
          (memoRow as any).catalog_fingerprint === catalogFingerprint) {
          memoryHit = { canonical_items: (memoRow as any).canonical_items as MemoItemShape[] }
        }
```

Replace with:

```ts
        if (memoRow && (memoRow as any).promoted && !(memoRow as any).permanently_blocked) {
          const memoItems = (memoRow as any).canonical_items as MemoItemShape[]
          const stored = (memoRow as any).catalog_fingerprint as string | null
          const scoped = computeScopedCatalogFingerprint(
            (memoItems || []).map(i => i.item_name), fullCatalogList
          )
          // `stored === null` is the post-migration state for rows carrying a legacy
          // whole-catalog hash (migration 20260809010000). Accepting null is safe because a
          // memo is still only ever USED when itemsCorroboratedBySegmenter agrees on this
          // recording; the next observation write below backfills the scoped value.
          if (stored === null || stored === scoped) {
            memoryHit = { canonical_items: memoItems }
          }
        }
```

**C1.3** — At the observation write, store the scoped value. Find:

```ts
          p_catalog_fingerprint: catalogFingerprint,
```

Replace with:

```ts
          p_catalog_fingerprint: computeScopedCatalogFingerprint(
            toMemoShape(parsedRawItems).map(i => i.item_name), fullCatalogList
          ),
```

**C1.4** — `catalogFingerprint` is still referenced in the trace payload
(`step_4_learned_parse_memory.catalogFingerprint` around line 2406). Leave that as-is so the
whole-catalog hash remains visible for debugging, and add a sibling field in the same object:

```ts
        scopedFingerprintEnabled: true,
```

## C2 · Migration — merge the sentinel shop and clear legacy fingerprints

Create `supabase/migrations/20260809010000_learned_parse_scoped_fingerprint.sql`:

```sql
-- ISSUE-114: Learned Parse Memory had 0 lifetime hits. Two causes, both fixed here.
--
-- 1. Fingerprint fragmentation. catalog_fingerprint hashed the WHOLE catalog, so any catalog
--    edit invalidated every memo. Verified 2026-08-09: 108 rows across 8 fingerprints, only 6
--    promoted, avg observations 0.9. The edge function now stores a SCOPED hash covering only
--    the items a memo names, so legacy values can never match and must be cleared. NULL is
--    read as "revalidate on next use" by the lookup; the corroboration belt still guards it.
--
-- 2. Shop-ID split. Rows exist under both the real shop and the legacy sentinel
--    00000000-0000-0000-0000-000000000001 (used before ensure_shop provisioning landed).
--    Verified counts at time of writing: 51+12+7+6 real vs 18+5+5+2+1+1 sentinel.

-- Merge sentinel rows into the real shop, but only where the real shop has no row for that
-- memo_key. Observation counts are summed so a memo split across both IDs keeps its history.
UPDATE learned_parses r
SET observations             = r.observations + s.observations,
    segmenter_corroborations = r.segmenter_corroborations + s.segmenter_corroborations,
    corrections              = r.corrections + s.corrections,
    distinct_days            = GREATEST(r.distinct_days, s.distinct_days),
    last_seen_at             = GREATEST(r.last_seen_at, s.last_seen_at),
    last_seen_date           = GREATEST(r.last_seen_date, s.last_seen_date)
FROM learned_parses s
WHERE s.shop_id = '00000000-0000-0000-0000-000000000001'
  AND r.shop_id <> '00000000-0000-0000-0000-000000000001'
  AND r.memo_key = s.memo_key;

-- Re-point sentinel rows that have no counterpart in a real shop. If more than one real shop
-- ever exists this is ambiguous, so it is restricted to the single-tenant case that is true
-- today (verified: exactly 2 distinct shop_ids, one of them the sentinel).
UPDATE learned_parses
SET shop_id = (
      SELECT shop_id FROM learned_parses
      WHERE shop_id <> '00000000-0000-0000-0000-000000000001'
      GROUP BY shop_id ORDER BY count(*) DESC LIMIT 1
    )
WHERE shop_id = '00000000-0000-0000-0000-000000000001'
  AND (SELECT count(DISTINCT shop_id) FROM learned_parses
       WHERE shop_id <> '00000000-0000-0000-0000-000000000001') = 1
  AND memo_key NOT IN (
      SELECT memo_key FROM learned_parses
      WHERE shop_id <> '00000000-0000-0000-0000-000000000001'
  );

-- Drop any sentinel rows left over (they were duplicates merged above).
DELETE FROM learned_parses WHERE shop_id = '00000000-0000-0000-0000-000000000001';

-- Every surviving fingerprint is a legacy whole-catalog hash. Clear them.
UPDATE learned_parses SET catalog_fingerprint = NULL;
```

Also append the same statements to `supabase/schema.sql` **only if** that file carries a
migration-history section; if it does not, leave `schema.sql` untouched and say so in Deviations.

---

# Phase D — Database indexes

Create `supabase/migrations/20260809010100_fk_indexes_and_dupe.sql`:

```sql
-- ISSUE-115: Supabase performance advisor, 2026-08-09 — 11 foreign keys with no covering
-- index and one duplicate index. CONCURRENTLY is deliberately NOT used: these tables are
-- small and the migration runner wraps statements in a transaction, which CONCURRENTLY
-- cannot run inside.
CREATE INDEX IF NOT EXISTS idx_catalog_items_unit_id            ON public.catalog_items(unit_id);
CREATE INDEX IF NOT EXISTS idx_credits_customer_id              ON public.credits(customer_id);
CREATE INDEX IF NOT EXISTS idx_credits_linked_transaction_id    ON public.credits(linked_transaction_id);
CREATE INDEX IF NOT EXISTS idx_customers_merged_into_id         ON public.customers(merged_into_id);
CREATE INDEX IF NOT EXISTS idx_shops_user_id                    ON public.shops(user_id);
CREATE INDEX IF NOT EXISTS idx_stock_in_item_id                 ON public.stock_in(item_id);
CREATE INDEX IF NOT EXISTS idx_stock_in_shop_id                 ON public.stock_in(shop_id);
CREATE INDEX IF NOT EXISTS idx_transactions_customer_id         ON public.transactions(customer_id);
CREATE INDEX IF NOT EXISTS idx_transactions_item_id             ON public.transactions(item_id);
CREATE INDEX IF NOT EXISTS idx_unmatched_queue_resolved_item_id ON public.unmatched_queue(resolved_item_id);
CREATE INDEX IF NOT EXISTS idx_unmatched_queue_shop_id          ON public.unmatched_queue(shop_id);

-- Identical to idx_stt_job_logs_job_id_unique.
DROP INDEX IF EXISTS public.idx_stt_job_logs_unique_job_id;
```

**Explicitly out of scope:** the 126 "Multiple Permissive Policies" advisories. Consolidating RLS
policies is a security-semantics change and must not be done as a blind sweep — it needs its own
reviewed pass. Do not touch any `CREATE POLICY` / `DROP POLICY` statement in this plan.

---

# Phase E — Build optimisation (do this last, and separately)

**Read this before starting.** Phases A–D are verified by watching `stt_job_logs` and the UI.
R8 changes code generation app-wide and can break Room/Compose/kotlinx in ways that only show up
at runtime. Do not fold Phase E into the same build as A–D — it would confound every
verification above. Implement it, but expect it to be built and soaked on its own.

**A baseline profile is NOT part of this plan.** Generating one requires a `:baselineprofile`
macrobenchmark module and on-device runs, which cannot be done headlessly. `profileinstaller` is
added below so a profile can be dropped in later.

## E1 · Add a `perf` build type

**Problem:** `tools/vti-ship.ps1` runs `assembleDebug`, and `release` has
`isMinifyEnabled = false`. So every APK ever tested has been a debuggable, un-minified build.
A `release`-like variant signed with the **debug** keystore gives near-release performance with
no new keystore and no credential handling.

In `app/build.gradle.kts`, replace:

```kotlin
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
```

with:

```kotlin
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // ISSUE-116: every APK shipped so far has been `assembleDebug` — debuggable, no R8.
        // This variant is release-shaped (not debuggable, R8 on) but signed with the debug
        // keystore, so it installs on the test phone without introducing a signing secret.
        // Obfuscation stays OFF: it buys nothing here and would make stack traces unreadable
        // while the parse pipeline is still being tuned.
        create("perf") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
```

Add to `dependencies`, next to the other AndroidX entries:

```kotlin
  // Lets a baseline profile be installed once one is generated (see Phase E preamble).
  implementation("androidx.profileinstaller:profileinstaller:1.3.1")
```

## E2 · Keep rules

Append to `app/proguard-rules.pro`:

```
# ISSUE-116 — R8 keep rules for the `perf` build type.
# Obfuscation is disabled so traces stay readable while the parse pipeline is being tuned.
-dontobfuscate

# Room generates *_Impl classes reflectively resolved by name at runtime.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# WorkManager instantiates workers by class name from the enqueued request.
-keep class * extends androidx.work.ListenableWorker { public <init>(...); }

# Entities crossing the Supabase JSON boundary are matched by field name.
-keep class com.voicetoinvoice.app.data.local.entity.** { *; }
-keepclassmembers class com.voicetoinvoice.app.data.local.entity.** { <fields>; }

# kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
```

**Do not modify `tools/vti-ship.ps1`.** The `perf` APK is built and installed manually the first
time so any R8 breakage is caught deliberately rather than silently replacing the known-good
debug pipeline.

---

# Verification — by effect, not by build

`BUILD SUCCESSFUL` proves nothing here. After deploy + install, record **new** utterances and
query for jobs created *after* the change:

```sql
WITH t AS (
  SELECT job_id, created_at,
    (diagnostic_trace_json::json->'step_8_timings'->>'totalMs')::int          AS total_ms,
    (diagnostic_trace_json::json->'step_8_timings'->>'sttResolvedAtMs')::int  AS stt_ms,
    (diagnostic_trace_json::json->'step_8_timings'->>'catalogFetchedAtMs')::int AS cat_ms,
    (diagnostic_trace_json::json->'step_8_timings'->>'aliasesFetchedAtMs')::int AS alias_ms,
    (diagnostic_trace_json::json->'step_4_fast_path'->>'used')::bool          AS fastpath,
    diagnostic_trace_json::json->'step_4_fast_path'->>'skipReason'            AS skip_reason,
    (diagnostic_trace_json::json->'step_4_learned_parse_memory'->>'hit')::bool AS memo_hit
  FROM stt_job_logs WHERE created_at > '<deploy timestamp>'
)
SELECT * FROM t ORDER BY created_at DESC;
```

Pass criteria, per phase:

- **A1** — `alias_ms - cat_ms` collapses to roughly 0 (the two fetches now settle together), and
  `stt_ms` falls by ~300–550 ms versus the 1,068 ms baseline.
- **A2** — an utterance with a spoken bulk total (e.g. "दो पैकेट छाछ चालीस रुपये") comes back
  `fastpath = true`, `skip_reason = null`, `total_ms` near 1,300 rather than ~7,400, and the
  booked line shows `price_at_sale = spokenPrice / qty` with `total = spokenPrice`.
- **A3** — no job exceeds ~13 s total; any that would have run longer shows
  `step_4_ai_error` containing `AI Timeout (12000ms limit)` and
  `parse_source = 'segmenter_fallback'`.
- **B1/B2** — behavioural, not query-visible: the Home command feed still lists the last 24 h,
  and the visible stutter while a recording is processing is gone.
- **C** — `SELECT count(*) FROM learned_parses WHERE catalog_fingerprint IS NOT NULL;` grows from
  0 after the migration as observations are re-recorded, and `memo_hit = true` appears on a
  repeated utterance. **This will not hit immediately** — promotion needs repeat observations.
- **D** — re-run the performance advisor; unindexed-FK count goes 11 → 0, duplicate index 1 → 0.
- **E** — the `perf` APK installs, records a sale end-to-end, and the review queue renders.

# Bug class vs. bug instance

State this plainly when reporting:

- **A1, A3, B1, B2, D** are **instance** fixes. They remove specific measured waste; they do not
  prevent the same shape of waste reappearing.
- **A2** narrows a **class** — "the deterministic engine already knows the answer but we pay for
  AI anyway." It does not eliminate the class: `segment_not_matched` (11 of 26 AI calls) is still
  a genuine AI need, and `item_not_in_catalog` / `catalog_item_unpriced` / `ambiguous_catalog_match`
  (7 more) remain unaddressed and are the obvious next target.
- **C** eliminates a **class** — memory invalidation on unrelated catalog edits — because the
  fingerprint is now causally scoped to what the memo depends on.
- **E** is neither; it is a measurement-apparatus fix that should have preceded all of the above.

# Deviations

End your run with a `Deviations` section. If none, write `None.`
