# Latency Reduction Plan — process-voice-job

**Date:** 2026-08-08
**Author:** Claude Code (planning session, fresh read of the repo — no prior plan docs consulted)
**Implementer:** Antigravity — execute word by word, see §7 for order.
**Scope:** `supabase/functions/process-voice-job/index.ts` + one migration + one data fix.
**Not in scope:** any Kotlin file. Changes 2–4 are server-only and have **no client mirror** — the
client (`SttWorker.kt`) consumes whatever the endpoint returns and does not re-implement the STT
race, the persistence ordering, or the inspector. Change 1 is pure data and reaches the phone
through the existing `SyncEngine.pullCatalogFromCloud()` read path.

---

## §0 — Evidence base (what I actually checked, and what I did not)

Every number below is labelled. Read the labels; they are not decoration.

### 0.1 The measurement window

**Verified** (`stt_job_logs`, live, queried 2026-08-08): 357 rows in the last 30 days, but only
**25** carry a `step_4_fast_path` block — i.e. only 25 were processed by the deploy that contains
the fast path. Window: `2026-08-06 12:13:53Z` → `2026-08-08 04:54:22Z`.

**Every percentage in this document is out of that n=25.** That is a small sample and I am not
going to dress it up as more. It is, however, *the whole population of jobs that ran on current
code*, so it is the right sample — just a thin one. Where the numbers in the request table
(16%, 68%, 7.4 s) differ from mine, I say so.

**Verified** breakdown of the 25:

| outcome | n | share |
|---|---|---|
| fast path used (no AI call) | 17 | 68% |
| AI call made | 8 | 32% |
| learned-parse memory hit | 0 | 0% |

**Verified** skip reasons on the 8 AI jobs: `item_not_in_catalog` ×2, `catalog_item_unpriced` ×2,
`ambiguous_catalog_match` ×1, `segment_not_matched` ×1, `no_leading_quantity` ×1,
`inexact_phonetic_match` ×1.

**Not verified:** the "p50 7.4 s for the Grok chat call" figure. It comes from the code comment at
`index.ts:1438` (a 67-recording replay on 2026-08-06) and I could not reproduce it — the trace
records no timing for the chat call. Change 3 adds that timer (§4.4). Until then, treat 7.4 s as
**inherited, unverified**.

### 0.2 Change 1's premise — checked item by item

**Verified** against `catalog_items` for shop `2f992a33-fa26-4be2-9006-3e6eafd41e2c`:

| item | what the request said | what the database actually says |
|---|---|---|
| आम / Aam | "price आम" | ✅ correct. Shop row `9b0ad3c5` is **price 0**. A *global* row (`shop_id IS NULL`) has price 120, but the catalog fetch filters `.eq('shop_id', …)` (`index.ts:937`), so the priced row is **invisible** to the pipeline. |
| चावल | "add चावल" | ⚠️ **already added.** Row `b178b448` exists at **price 0**, created `2026-08-06 12:14:39.56Z` — by catalog-learning, during the very replay that produced this data. It needs a **price**, not an insert. `Basmati Rice` (₹90) is a different phonetic key and will never match it. |
| अदरक | "add अदरक" | ❌ **the diagnosis is wrong.** `Adrak` already exists, shop-scoped, at ₹120. The failure is a **phonetic-key mismatch**, not missing data. |

The अदरक finding, **verified by executing `phoneticKey` from `phonetic.ts` directly** (node, on the
real source, 2026-08-08):

```
अदरक   -> ATALAK   | Adrak   -> ATLAK    MISMATCH
आम     -> AN       | Aam     -> AN       MATCH
चावल   -> CAVAL    | Chawal  -> CAVAL    MATCH
केला   -> KILA     | kela    -> KILA     MATCH
केला   -> KILA     | Kheera  -> KILA     MATCH   <-- explains ambiguous_catalog_match
```

`devanagariToLatin` (`phonetic.ts:60`) applies schwa deletion **only word-finally**, so `अदरक`
becomes `adarak` while the romanised catalog name `Adrak` is `adrak`. One inherent vowel apart.
The segmenter still matched अदरक — it uses `normalizedDistance ≤ 0.25`, and the gap is 0.083 — but
`buildFastPath` re-does the lookup with **strict key equality** (`index.ts:1221`) and finds nothing.

That is the whole mechanism, and it matters for §8.

### 0.3 Change 2's premise — checked

**Verified**, per-job Grok vs Sarvam STT latency across the 25:

- Sarvam returns first in **22/25 (88%)**.
- Mean gap between first and second: **348 ms**. Median: **239 ms**. (The request's "~350 ms" is the
  mean — correct.)
- Winner's transcript scores **≥5 in 22/25 (88%)**.
- Winner ≥5 **and** fast path eligible today: **16/25 (64%)**. (The request says 68%; 68% is the
  fast-path-used rate. 64% is the shortcut rate, because job `9e211527` used the fast path but its
  first-returner scored 4.)
- After Change 1 lands, the four catalog-gap jobs also qualify → **20/25 (80%)**.

**Verified accuracy check — the one that matters:** across all 25, there is **not a single job**
where gating on "first-returner scores ≥5" would have adopted a transcript that scored *strictly
lower* than the one currently adopted. The two cases where the loser was better (`9e211527`
S4/G6, `64b1b8d0` G4/S1) both score **below 5** and the gate correctly declines to shortcut.
n=25 — real evidence, thin evidence. Change 4 exists to keep measuring this after rollout.

### 0.4 Change 3's premise — checked structurally, not timed

**Verified by reading** `index.ts`: before the first STT byte is sent, the request awaits, in
series: `ensure_shop` RPC (678) → idempotency `SELECT` (683) → `arrayBuffer()` (738) → **storage
upload, up to 8 s** (742) → QUEUED placeholder upsert (762) → catalog fetch (944) → alias fetch
(973). After the parse completes it awaits: full log upsert (2164) → ledger writes (2172–2327) →
**a second write to the same row** (2331) that only exists to attach `step_7_persistence`.

**Verified: the duplicate is real.** Line 2160 serialises `persistence` while it still holds only
three keys; the ledger writes add more; line 2331 rewrites the entire trace to pick them up. Two
full round-trips writing one row.

**Not measured:** how many milliseconds any of this costs. `stt_job_logs` has no server-side
completion timestamp (**verified** — no `updated_at` column), so end-to-end server time is not
derivable from existing data. This is why §4.4 (timing instrumentation) is not optional.

---

## §1 — What this plan does, in one table

| # | Change | Mechanism | Expected saving | Accuracy risk |
|---|---|---|---|---|
| 1 | Price आम + चावल; make अदरक resolvable | data (+ 3 lines of code, §2.3) | one AI round-trip on 16% of jobs | none |
| 2 | STT race, gated on score ≥5 **and** fast-path eligibility | code | ~348 ms mean on 64% → 80% of jobs | ~0 after the gate (§0.3) |
| 3 | Defer the log write, delete the duplicate, move the upload off the critical path | code | unmeasured — §4.4 makes it measurable | none (debug/persistence only) |
| 4 | Parse Inspector: shadow-verify skipped-AI jobs, act on nothing | code + migration | none (this one costs) | none — it is the safety net for 1–3 |

---

## §2 — Change 1: close the catalog gaps

### Step 1a — price the two real items (SQL, run against prod)

Run as one statement. `updated_at` must move forward or the phone's last-write-wins merge
(`SyncEngine.kt:72`) will ignore it.

```sql
UPDATE catalog_items SET price = 120, updated_at = now()
WHERE id = '9b0ad3c5-33dc-46bc-8447-2865409f5bc7';  -- Aam, shop-scoped, 0 -> 120

UPDATE catalog_items SET price = 60, updated_at = now()
WHERE id = 'b178b448-e157-4063-b4d4-88ded86125a8';  -- चावल, shop-scoped, 0 -> 60
```

**Open question — ask the user before running, do not guess:** ₹120 for Aam is copied from the
global seed row; ₹60 for चावल is a placeholder I invented. These are the shopkeeper's prices and a
wrong one books wrong money. **Ask for both figures, then substitute them.** Everything else in
this plan can proceed while you wait.

### Step 1b — delete the learned garbage rows

**Verified**: seven of the nine price-0 rows on this shop are STT debris promoted by
catalog-learning (`index.ts:1852`), not items: `March`, `अठारह के लोग`, `पंद्रह`, `बचा रहा`,
`सत्ताईस`, `सत्रह की`, `सिंगर`. They are live fast-path hazards — every one of them is another
chance at `ambiguous_catalog_match`.

```sql
UPDATE catalog_items SET active = false, updated_at = now()
WHERE shop_id = '2f992a33-fa26-4be2-9006-3e6eafd41e2c'
  AND price = 0
  AND name IN ('March','अठारह के लोग','पंद्रह','बचा रहा','सत्ताईस','सत्रह की','सिंगर');
```

Deactivate, do not `DELETE` — `transactions.item_id` may reference them, and the catalog fetch
filters on `active = true` anyway.

### Step 1c — make अदरक resolvable (3 lines, and this is the real fix)

Adding a Devanagari `अदरक` row would work and would be pure data. **Do not do that.** It fixes one
word out of a class (§8) and leaves a duplicate item in the shopkeeper's picker.

In `index.ts`, inside `buildFastPathFrom` (the function you extract in §3.2), replace the strict
equality lookup:

```ts
// BEFORE (index.ts:1221 today)
const hits = dbCatalogItems.filter(ci => phoneticKey(ci.name) === key)
if (hits.length !== 1) return no(hits.length === 0 ? 'item_not_in_catalog' : 'ambiguous_catalog_match')
```

```ts
// AFTER
const FAST_PATH_KEY_MAX_NORM = 0.10
let hits = dbCatalogItems.filter(ci => phoneticKey(ci.name) === key)
if (hits.length === 0) {
  // Same-key equality misses a romanised catalog name against a Devanagari spoken form
  // whenever schwa handling differs ("अदरक" -> ATALAK vs "Adrak" -> ATLAK). Widen to a
  // very tight neighbourhood -- far tighter than the segmenter's own 0.25 MATCH line --
  // and still demand a UNIQUE winner, so this can never turn an ambiguous read into a
  // silent booking.
  hits = dbCatalogItems.filter(ci =>
    normalizedDistance(phoneticKey(ci.name), key) <= FAST_PATH_KEY_MAX_NORM)
}
if (hits.length !== 1) return no(hits.length === 0 ? 'item_not_in_catalog' : 'ambiguous_catalog_match')
```

`normalizedDistance` is already imported (`index.ts:7`). Add nothing else.

**Why 0.10 and not looser:** `अदरक`↔`Adrak` is 0.083. `केला`↔`Kheera` is 0 (identical keys) and is
*not* rescued by this — it still fails `hits.length !== 1`, which is correct, because those two
genuinely are indistinguishable in phone space. Loosening beyond 0.10 starts pulling in real
different items; 0.10 buys exactly the schwa class.

### Verifying Change 1

After Steps 1a–1c are live, record one utterance each for आम, चावल, अदरक, then:

```sql
SELECT job_id, raw_transcript, status,
       diagnostic_trace_json::jsonb->'step_4_fast_path' AS fast_path,
       diagnostic_trace_json::jsonb->>'step_4_interpretation_source' AS source
FROM stt_job_logs WHERE created_at > now() - interval '30 minutes' ORDER BY created_at DESC;
```

Pass = `fast_path.used: true`, `skipReason: null`, `source: "segmenter_fast_path"` on all three.
A `BUILD SUCCESSFUL` or a clean deploy is **not** verification of this — the row is.

---

## §3 — Change 2: race the two STT providers

### 3.1 The idea

Today `index.ts:1021` does `Promise.all([grok, sarvam])` and pays the slower of the two, every
time. Sarvam wins 88% of races by a mean of 348 ms.

Take the first transcript that lands. If it scores ≥5 **and** the deterministic fast path is
eligible on it, the second opinion has nothing left to contribute — every remaining decision is a
catalog lookup — so answer immediately and abandon the loser. Any other outcome falls back to
today's `Promise.all` and today's code path, byte for byte.

Two gates, both required. The score gate is the cheap filter; **fast-path eligibility is the real
gate**, and it is already the strictest thing in this pipeline (exact phonetic match, priced
catalog row, no spoken price, leading quantity present, nothing sanity-flagged).

### 3.2 Extract `buildFastPathFrom` to module scope

`buildFastPath` today (`index.ts:1188–1243`) is a closure over `step3Segments`, `chosenRaw`,
`dbCatalogItems`, `isAssistant`. The race needs to call it **before** those exist in their final
form, so lift it out of `processVoiceJob` to a top-level function:

```ts
function buildFastPathFrom(args: {
  segments: RawItemSegment[]
  chosenRaw: string
  dbCatalogItems: Array<{ id: string, name: string, price: number, unit_id: string }>
  isAssistant: boolean
}): { eligible: boolean, items: any[], skipReason: string | null } {
  const { segments, chosenRaw, dbCatalogItems, isAssistant } = args
  // ... body of today's buildFastPath, verbatim, with step3Segments -> segments ...
}
```

Body copied **verbatim** apart from the rename and the §2.3 lookup change. No other edits — do not
"tidy" it. Then at the original call site:

```ts
const fastPath = buildFastPathFrom({ segments: step3Segments, chosenRaw, dbCatalogItems, isAssistant })
```

### 3.3 New constants

Next to `AI_CHAT_TIMEOUT_MS` (`index.ts:166`):

```ts
/** Minimum transcript score for the STT race to consider answering on one provider alone.
 *  5 = one recognised catalog item (3) + an explicit non-default unit (2). */
const FAST_STT_MIN_SCORE = Number(Deno.env.get('FAST_STT_MIN_SCORE') || '5')
/** Kill switch. Set to '1' to restore the unconditional Promise.all. */
const DISABLE_STT_RACE = Deno.env.get('DISABLE_STT_RACE') === '1'
```

### 3.4 The race

Replace `index.ts:1021–1028` with:

```ts
const grokPromise: Promise<SttOutcome> = xaiApiKey
  ? callGrokStt(audioBuffer, jobId, xaiApiKey, { keyterms, language: 'hi' })
  : Promise.resolve(EMPTY_STT)
const sarvamPromise: Promise<SttOutcome> = sarvamApiKey
  ? callSarvamStt(audioBuffer, jobId, sarvamApiKey)
  : Promise.resolve(EMPTY_STT)

// Neither call ever rejects -- both catch internally and return an SttOutcome (see
// callGrokStt / callSarvamSttOnce) -- so abandoning the loser cannot produce an
// unhandled rejection.
type RaceShortcut = {
  provider: 'grok' | 'sarvam'
  outcome: SttOutcome
  scored: { score: number; segments: RawItemSegment[] }
  fastPath: { eligible: boolean; items: any[]; skipReason: string | null }
}
let raceShortcut: RaceShortcut | null = null
const sttRaceTrace: Record<string, unknown> = { attempted: false, winner: null, winnerScore: null, shortcut: false, declineReason: null }

if (!DISABLE_STT_RACE && !isAssistant && xaiApiKey && sarvamApiKey) {
  const tag = (p: Promise<SttOutcome>, provider: 'grok' | 'sarvam') => p.then(o => ({ provider, outcome: o }))
  const first = await Promise.race([tag(grokPromise, 'grok'), tag(sarvamPromise, 'sarvam')])
  const scored = scoreTranscript(first.outcome.transcript, fullCatalogList, aliases)
  sttRaceTrace.attempted = true
  sttRaceTrace.winner = first.provider
  sttRaceTrace.winnerScore = scored.score
  sttRaceTrace.winnerLatencyMs = first.outcome.latencyMs

  if (first.outcome.error) {
    sttRaceTrace.declineReason = 'winner_stt_error'
  } else if (scored.score < FAST_STT_MIN_SCORE) {
    sttRaceTrace.declineReason = `winner_score_${scored.score}_below_${FAST_STT_MIN_SCORE}`
  } else {
    const fp = buildFastPathFrom({
      segments: scored.segments, chosenRaw: first.outcome.transcript, dbCatalogItems, isAssistant,
    })
    if (fp.eligible) {
      raceShortcut = { provider: first.provider, outcome: first.outcome, scored, fastPath: fp }
      sttRaceTrace.shortcut = true
    } else {
      sttRaceTrace.declineReason = `fast_path_${fp.skipReason}`
    }
  }
}

let grokOutcome: SttOutcome
let sarvamOutcome: SttOutcome
if (raceShortcut) {
  const notAwaited: SttOutcome = { transcript: '', error: 'not awaited (STT race shortcut)', httpStatus: null, latencyMs: 0 }
  grokOutcome  = raceShortcut.provider === 'grok'   ? raceShortcut.outcome : notAwaited
  sarvamOutcome = raceShortcut.provider === 'sarvam' ? raceShortcut.outcome : notAwaited
} else {
  ;[grokOutcome, sarvamOutcome] = await Promise.all([grokPromise, sarvamPromise])
}
```

Everything after this — the `note()` calls, `providerOf`, scoring, adoption — stays exactly as it
is. Do not touch it.

### 3.5 Short-circuit the branches the shortcut has already decided

Three places must respect `raceShortcut`. All three are small.

**(a) Adoption** — after `let transcript = chosenRaw || ''` (`index.ts:1083`):

```ts
if (raceShortcut) {
  chosenRaw = raceShortcut.outcome.transcript
  step3Segments = raceShortcut.scored.segments
  bestScore = raceShortcut.scored.score
  transcript = chosenRaw
  sttProvider = `${raceShortcut.provider}+raced`
}
```

Re-decode needs no guard: it triggers on `bestScore < 3`, and the shortcut requires ≥5.

**(b) Learned-parse memory lookup** (`index.ts:1360–1396`) — skip it entirely under a shortcut. It
is a DB round-trip whose only possible outcome is agreeing with a stricter engine that has already
answered. Change the guard:

```ts
const memoryEnabled = !!memoKey && !raceShortcut
```

and add to the `step_4_learned_parse_memory` trace block: `skipped: raceShortcut ? 'stt_race_shortcut' : null`.

**(c) Parse-source selection** (`index.ts:1435`) — the shortcut has already built the items:

```ts
} else if (raceShortcut) {
  parsedRawItems = raceShortcut.fastPath.items
  aiModelUsed = 'fast_path'
  aiError = null
  parseSource = 'segmenter_fast_path'
} else if (fastPath.eligible) {
  // ... unchanged ...
```

Insert this branch **immediately after** the `if (memoryHit && memoCorroborated)` block and
**before** `else if (fastPath.eligible)`.

### 3.6 Trace

In `step_2_stt_proxy_response`, add one field:

```ts
sttRace: sttRaceTrace,
```

`declineReason` is the field to read when someone reports "still slow" — it names the exact gate
that refused the shortcut.

### 3.7 Recovering the loser transcript for the trace

Under a shortcut the trace loses one STT opinion, which is a real debugging cost. Recover it for
free inside the deferred write from Change 3 (§4.3): before the single `stt_job_logs` upsert, and
only when `raceShortcut` is set, do

```ts
const loserPromise = raceShortcut.provider === 'grok' ? sarvamPromise : grokPromise
const loser = await Promise.race([
  loserPromise,
  new Promise<SttOutcome>(r => setTimeout(() => r({ transcript: '', error: 'loser not settled within 1500ms', httpStatus: null, latencyMs: 0 }), 1500)),
])
```

and write it into `sttRaceTrace.loserOutcome`. This runs after the response has been sent, so it
costs the shopkeeper nothing. **Change 3 must land in the same deploy as Change 2** for this to be
free — see §7.

---

## §4 — Change 3: get persistence off the critical path

### 4.1 Move the audio upload

`audioCloudUrl` is a **deterministic string** (`index.ts:757`) — it never reads the upload result,
so nothing downstream depends on the upload having finished. Replace the awaited block at
`index.ts:741–755` with:

```ts
const uploadStartedMs = Date.now()
const uploadPromise = withTimeout(
  supabase.storage.from('voice-recordings').upload(storagePath, audioBuffer, {
    contentType: 'audio/wav', upsert: true,
  }),
  8000,
  'Storage upload'
).then(({ error }: any) => {
  if (error) console.warn(`Audio storage upload warning for job ${jobId}:`, error.message)
  return Date.now() - uploadStartedMs
}).catch((e: any) => {
  console.warn(`Audio storage upload failed/timed out for job ${jobId}:`, e?.message ?? e)
  return -1
})
EdgeRuntime.waitUntil(uploadPromise)
```

`.catch` is mandatory — an unhandled rejection here would be a new failure mode, not a saving.

### 4.2 Leave the QUEUED placeholder alone

`index.ts:762` stays awaited. It is the row the 202/poll path depends on, and skipping it
reintroduces ISSUE-044's "job exists nowhere" hole. One round-trip, deliberately paid.

### 4.3 One deferred log write instead of two

Delete the duplicate. Concretely:

1. **Delete** the upsert at `index.ts:2164–2166`.
2. **Delete** the update at `index.ts:2329–2333`.
3. Keep all ledger writes (`transactions`, `stock_in`, `credits`, `unmatched_queue`, the safety
   fallback) exactly where and as they are, still awaited. They are money; they stay on the
   critical path. Their `persistence.*` assignments are unchanged.
4. After the last ledger write and **before** the `return`, insert:

```ts
// Single, deferred write. Previously this row was written twice -- once before the
// ledger writes with a partially-filled `persistence`, then again afterwards purely to
// attach the rest of it. Both were on the response path. One write, after the response.
traceObj.step_7_persistence = persistence
const logPayloadFinal = { ...logPayload, diagnostic_trace_json: '' }  // trace filled in below
EdgeRuntime.waitUntil((async () => {
  // §3.7: recover the abandoned STT opinion, capped, at zero cost to the caller.
  if (raceShortcut) { /* ... loser recovery from §3.7, writes into sttRaceTrace ... */ }
  traceObj.step_8_timings = timings          // §4.4
  logPayloadFinal.diagnostic_trace_json = JSON.stringify(traceObj)
  const { error: logErr } = await supabase.from('stt_job_logs')
    .upsert([logPayloadFinal], { onConflict: 'job_id' })
  if (logErr) console.error(`Failed to write stt_job_logs for job ${jobId}:`, logErr.message)
})())
```

`logPayload` itself (`index.ts:2142–2162`) is unchanged except that its
`diagnostic_trace_json` is now assigned inside the deferred block.

**The one hazard, and why it is acceptable:** a client that gets 202 and polls could in principle
see `QUEUED` for a few extra milliseconds. `SttWorker.pollForCompletion` polls at ≥1 s intervals
(`SttWorker.kt:795` region) and `SttProxyClient` at 2 s (`SttProxyClient.kt:244`) — orders of
magnitude more slack than the deferral introduces. **Inferred, not measured.** If a
`poll_timed_out: true` ever appears in a client trace after this ships, this is the first thing to
suspect.

### 4.4 Timing instrumentation — not optional

Change 3's saving is currently unmeasurable, and `stt_job_logs` has no completion timestamp.
Without this the change cannot be verified by its effect, which the repo's own rules forbid.

At the top of `processVoiceJob`:

```ts
const t0 = Date.now()
const mark = (): number => Date.now() - t0
const timings: Record<string, number> = {}
```

Then one line at each boundary:

```ts
timings.catalogFetchedAtMs = mark()   // after the catalog fetch
timings.aliasesFetchedAtMs = mark()   // after the alias fetch
timings.sttResolvedAtMs    = mark()   // after the race / Promise.all
timings.parseResolvedAtMs  = mark()   // after the parse-source branch (§3.5c)
timings.ledgerWrittenAtMs  = mark()   // after the last ledger write
timings.totalMs            = mark()   // immediately before `return`
```

Add `uploadMs` from §4.1's resolved promise inside the deferred block, and
`step_8_timings: timings` to the trace. Six numbers, no behaviour.

---

## §5 — Change 4: the Parse Inspector

Log disagreements. **Act on nothing.** This is the instrument that tells you whether Changes 1–3
cost accuracy — without it, "~0 risk" stays an assertion.

### 5.1 Migration

New file `supabase/migrations/20260808120000_parse_inspections.sql`:

```sql
-- Shadow verification of jobs that shipped WITHOUT an AI second opinion (fast path,
-- STT-race shortcut, learned-parse memory). Observe-only: nothing in the pipeline reads
-- this table, and no code path may branch on its contents. It exists to answer one
-- question from data instead of from argument -- "how often is the deterministic path
-- wrong?" -- and to be deletable the day that question is settled.
create table if not exists public.parse_inspections (
  id             uuid primary key default gen_random_uuid(),
  job_id         text not null,
  shop_id        uuid,
  created_at     timestamptz not null default now(),
  parse_source   text not null,          -- segmenter_fast_path | memory
  transcript     text,
  shipped_items  jsonb not null,
  grok_items     jsonb,
  agrees         boolean,
  mismatch_kind  text,                   -- item_count|item_name|quantity|unit|price_intent|grok_error
  grok_model     text,
  grok_latency_ms integer,
  grok_error     text
);
create index if not exists parse_inspections_created_idx on public.parse_inspections (created_at desc);
create index if not exists parse_inspections_mismatch_idx on public.parse_inspections (mismatch_kind) where mismatch_kind is not null;
alter table public.parse_inspections enable row level security;
-- service-role only; the client never reads this.
```

### 5.2 Constant

```ts
/** Share of AI-skipped jobs that get a shadow Grok verification. 1.0 during rollout;
 *  drop to ~0.15 once the disagreement rate is established. Costs an AI call, never
 *  latency -- it runs after the response is sent and changes nothing. */
const INSPECTOR_RATE = Number(Deno.env.get('PARSE_INSPECTOR_RATE') || '1.0')
```

### 5.3 The hook

After `finalParsedItems` is built and after the deferred log write is scheduled (§4.3), add:

```ts
const inspectorEligible = (parseSource === 'segmenter_fast_path' || parseSource === 'memory')
  && !!xaiApiKey && Math.random() < INSPECTOR_RATE
if (inspectorEligible) {
  const shipped = toMemoShape(parsedRawItems)
  EdgeRuntime.waitUntil((async () => {
    const started = Date.now()
    const res = await callGrokChatInterpretation(
      xaiApiKey, systemPrompt, userPrompt,
      () => knownGoodChatModel, (m) => { knownGoodChatModel = m },
    )
    const latency = Date.now() - started
    let agrees: boolean | null = null
    let kind: string | null = null
    if (res.error || res.items.length === 0) {
      kind = 'grok_error'
    } else {
      const fresh = toMemoShape(res.items)
      agrees = canonicalSignature(fresh) === canonicalSignature(shipped)
      if (!agrees) {
        kind = fresh.length !== shipped.length ? 'item_count'
          : fresh.some((f, i) => phoneticKey(f.item_name) !== phoneticKey(shipped[i].item_name)) ? 'item_name'
          : fresh.some((f, i) => f.quantity !== shipped[i].quantity) ? 'quantity'
          : fresh.some((f, i) => f.unit !== shipped[i].unit) ? 'unit'
          : 'price_intent'
      }
    }
    await supabase.from('parse_inspections').insert([{
      job_id: jobId, shop_id: resolvedShopId, parse_source: parseSource,
      transcript: chosenRaw, shipped_items: shipped,
      grok_items: res.items.length ? toMemoShape(res.items) : null,
      agrees, mismatch_kind: kind, grok_model: res.model,
      grok_latency_ms: latency, grok_error: res.error,
    }])
  })().catch(e => console.warn(`Parse inspector failed for ${jobId}:`, e)))
}
```

`toMemoShape`, `canonicalSignature`, `phoneticKey`, `callGrokChatInterpretation` all already exist
— reuse them, do not reimplement.

### 5.4 Two hard rules

1. **The inspector never writes to `stt_job_logs`, `transactions`, `unmatched_queue`,
   `catalog_items`, or `learned_parses`.** One table, one insert.
2. **Do not touch the existing learned-parse canary** (`index.ts:1414–1434`). That one *does* act
   — it calls `reset_learned_parse` to demote a bad memo — and it is correct as it stands. The
   inspector is a separate, observe-only mechanism. Do not merge them.

It also gives a free measurement of the chat call's real latency (`grok_latency_ms`), which is how
the unverified "7.4 s" from §0.1 finally gets a number.

---

## §6 — Files touched

| File | Change |
|---|---|
| `supabase/functions/process-voice-job/index.ts` | §2.3, §3, §4, §5.2, §5.3 |
| `supabase/migrations/20260808120000_parse_inspections.sql` | new (§5.1) |
| `catalog_items` (prod data) | §2.1, §2.2 — SQL, after the user supplies prices |
| `Docs/audit.md` | §9 |

No Kotlin file changes. No `phonetic.ts` changes — the schwa asymmetry there is real but fixing it
would move the segmenter's matching for every job, which is a much larger blast radius than this
plan is for (§8).

---

## §7 — Order of operations

Changes 2 and 3 must ship together (§3.7 depends on §4.3). Change 1's SQL is independent of code.

1. **Change 1, Steps 1a + 1b** — SQL only, once the user gives the two prices. Zero deploy risk.
   Verify with §2.4.
2. **One deploy carrying Changes 1c + 2 + 3 + 4** (code) and the §5.1 migration:
   ```bash
   npx supabase db push --project-ref lyowklxsbfznnqridtgr
   ```
   ```bash
   npx supabase functions deploy process-voice-job --project-ref lyowklxsbfznnqridtgr
   ```
   Standing authorisation covers the deploy — do not stop to ask. **Do** re-fetch the live bundle
   afterwards (`get_edge_function`) and grep for `sttRace`, `buildFastPathFrom`,
   `parse_inspections`, `step_8_timings`. This repo has a history of deploys that silently did not
   carry their changes; a successful CLI exit is not evidence.
3. **Record ~15 utterances** across आम, चावल, अदरक, आलू, भिंडी, and one deliberately messy one.
4. **Verify by the queries in §8.1.** Not by the build, not by the deploy log.

Rollback needs no redeploy: `DISABLE_STT_RACE=1` restores `Promise.all`, `DISABLE_FAST_PATH=1`
(already exists, `index.ts:1190`) restores the AI on every job, `PARSE_INSPECTOR_RATE=0` silences
the inspector.

---

## §8 — Bug instance vs bug class (read this before closing the plan)

**Change 1 fixes instances. Change 1c fixes a slice of the class.**

The class is: *the fast path re-derives a catalog match that the segmenter has already made, using
a stricter comparison, against a different vocabulary.* The segmenter matches the spoken form
against `DEFAULT_ITEM_VOCAB ∪ catalogNames` with a 0.25 distance budget; `buildFastPath` then
matches the same spoken form against `dbCatalogItems` alone with exact key equality. Any item whose
catalog name is romanised, whose spoken form is Devanagari, and whose schwa handling differs will
pass the first and fail the second. `अदरक` is one instance. **Verified** by execution; I have not
enumerated how many other catalog rows are exposed, and I am not going to claim a count I did not
measure.

The architectural fix — have the fast path *consume the segment's already-resolved catalog
identity* instead of re-matching from scratch — is out of scope here because it changes what the
segmenter must carry on `RawItemSegment` on both the Deno and Kotlin sides. **§2.3's 0.10
neighbourhood is a narrowed instance fix, not the class fix, and should be labelled as such in the
audit entry.**

Two scenarios §2.3 does **not** cover, stated explicitly because "I cannot name two" would mean I
had not looked:

1. **Genuine phone-space collisions.** `केला` and `Kheera` both key to `KILA` (**verified**).
   §2.3 leaves these failing `hits.length !== 1` — correct, but it means every such pair
   permanently costs an AI round-trip and no amount of catalog data fixes it.
2. **Multi-word names.** `Amul Gold Milk` keyed whole against a spoken `गोल्ड` is nowhere near
   0.10; the fast path will keep rejecting branded multi-word items regardless of §2.3. The 68%
   fast-path rate measured here is on single-word vegetable names — **do not assume it holds for a
   grocery-heavy shop.**

---

## §8.1 — Verification queries (run these; quote the output)

```sql
-- Shortcut rate, decline reasons, and whether accuracy moved.
SELECT
  diagnostic_trace_json::jsonb->'step_2_stt_proxy_response'->'sttRace'->>'shortcut'      AS shortcut,
  diagnostic_trace_json::jsonb->'step_2_stt_proxy_response'->'sttRace'->>'declineReason' AS decline_reason,
  diagnostic_trace_json::jsonb->>'step_4_interpretation_source'                          AS source,
  count(*),
  round(avg((diagnostic_trace_json::jsonb->'step_8_timings'->>'totalMs')::numeric)) AS avg_total_ms
FROM stt_job_logs
WHERE created_at > now() - interval '1 day'
  AND diagnostic_trace_json::jsonb ? 'step_8_timings'
GROUP BY 1,2,3 ORDER BY 4 DESC;
```

```sql
-- The only question Change 4 exists to answer.
SELECT parse_source, mismatch_kind, count(*),
       round(avg(grok_latency_ms)) AS avg_grok_ms
FROM parse_inspections
WHERE created_at > now() - interval '7 days'
GROUP BY 1,2 ORDER BY 3 DESC;
```

**Decision rule, fixed in advance so it cannot be rationalised afterwards:** if
`mismatch_kind IN ('item_name','quantity','unit')` exceeds **2%** of inspected fast-path jobs, the
fast path is booking wrong money and `DISABLE_FAST_PATH=1` goes on **before** any further tuning.
`price_intent` mismatches are a softer signal — the fast path only fires when no price was spoken —
but log them anyway.

---

## §9 — Docs/audit.md

Highest existing issue is **ISSUE-098** (**verified** by grep). On completion add, per the repo
convention (Symptom / Root Cause / Resolution / Verification Date):

- **ISSUE-099** — fast path blocked by unpriced and phonetically-unreachable catalog rows
  (आम price 0, चावल price 0, अदरक ATALAK≠ATLAK), costing an AI round-trip on 16% of jobs.
  Resolution: §2.1–2.3. Must state that §2.3 narrows the instance and the class survives (§8).
- **ISSUE-100** — both STT providers awaited unconditionally; the slower one added a mean 348 ms
  to 88% of jobs with nothing to contribute once the fast path was eligible. Resolution: §3.
- **ISSUE-101** — audio upload, and two writes of the same `stt_job_logs` row, sat on the response
  path. Resolution: §4.

Also update §1 "Ground-Truth Source-Code Verified Constants" with `FAST_STT_MIN_SCORE = 5`,
`FAST_PATH_KEY_MAX_NORM = 0.10`, `PARSE_INSPECTOR_RATE = 1.0`.

---

## §10 — Open questions (stop and ask; do not guess)

1. **Prices for आम and चावल** (§2.1). Blocking for Step 1a only — everything else proceeds.
2. **Deactivating the seven garbage catalog rows** (§2.2): confirm none of `March`, `सिंगर`,
   `बचा रहा` is a real product this shop sells under an odd name.
3. **Inspector cost.** At `PARSE_INSPECTOR_RATE=1.0` every fast-path job still pays for one Grok
   chat call — the latency is gone, the API spend is not. Confirm that is acceptable for the first
   week, or set 0.25 from the start.
