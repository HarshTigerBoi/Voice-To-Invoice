# STT Blackout + Unreachable Review Queue — Diagnosis & Fix Plan

**Written**: 2026-07-31 (Claude Code) · **For**: Antigravity · **Severity**: P0 — nothing has been booked to the ledger all day

Audio capture is fixed (confirmed by the user, and the ring-buffer fixes are verified present in `RollingAudioBuffer.kt`). The failure has moved downstream.

---

## 1. What the evidence actually shows

Hard facts from the live database and edge-function logs, not inference:

| Evidence | Value |
|---|---|
| Audio files uploaded to storage today | **5** ✅ |
| `process-voice-job` POSTs today | **7**, all HTTP **200**, 7–12 s each ✅ |
| `stt_job_logs` rows written **by the server** today | **0** ❌ |
| `stt_job_logs` rows today (all client-written, 186-byte traces) | 4 |
| `transactions` / `unmatched_queue` / `stock_in` rows today | **0 / 0 / 0** ❌ |
| Newest row of any kind | 07:49:01 UTC — nothing after, despite POSTs until 08:00:55 |

Yesterday the identical pipeline worked: `आलू बीस किलो`, `पाँच किलो आलू`, `एक किलो केला चार किलो आमरुद…` — all `AUTO_CONFIRMED`, Grok 200 + Sarvam 200, traces 3–9 KB.

**So: the audio arrives, the server runs the full pipeline for 7–12 seconds, returns 200 — and then writes nothing to any table.** The blank transcripts you see are not an STT accuracy problem. STT never got a chance to report anything, because the job's result is being thrown away before it is persisted.

That is why "do kilo aalu" fails despite potato being registered 100 times. It is not a recognition problem. **The item never reaches the recognizer's output stage.**

### FINDING 1 (P0) — the server's writes are silently failing

`processVoiceJob` catches its own exceptions and logs DB errors with `console.error` **without ever failing the request** — so a total write failure still returns HTTP 200. That is why the symptom is invisible from the outside.

I could not identify *which* write fails without the function's console output. I verified from SQL that the database itself is healthy: with the service role, an upsert into `stt_job_logs` with `ON CONFLICT (job_id)` and a NULL `shop_id` **succeeds**, and all three unique indexes (`idx_stt_job_logs_unique_job_id`, `idx_transactions_job_line`, `idx_unmatched_queue_job_line`) exist and are correct. So the schema is not the problem.

Ranked candidates, to be confirmed in Step 1:
1. **`created_at` construction throws.** `logPayload` does `new Date(metadata.recordedAtMs || Date.now()).toISOString()`. If `recordedAtMs` arrives malformed, `toISOString()` raises `RangeError`, which aborts before the upsert. The client now sends `recordedAtMs` from a changed capture path, so this is newly plausible.
2. **A payload key with no matching column** → PostgREST 400 on every write.
3. **Service-role env var lost on redeploy** — though storage writes succeeded, which argues against it.

### FINDING 2 (P0) — `SttWorker` never sends the real shop id

`app/src/main/java/com/voicetoinvoice/app/domain/processor/SttWorker.kt`:
```kotlin
shopId = SupabaseConfig.getNullSafeShopId(null),   // always null
```
It passes a hardcoded `null` instead of `ShopContext.requireShopId()`. Every server-side row is written unscoped. `ShopContext` was built specifically to stop this.

### FINDING 3 (P0 — landmine) — fixing FINDING 2 naively will break *everything*

I probed the live database with a device-style UUID (a real UUID with no row in `public.shops`):

| Table | Insert with device UUID |
|---|---|
| `stt_job_logs` | **OK** (no FK on `shop_id`) |
| `unmatched_queue` | **FAILED** — FK `shop_id → shops.id` |
| `transactions` | **FAILED** — FK `shop_id → shops.id` |

`public.shops` contains exactly **one** row: the sentinel `00000000-0000-0000-0000-000000000001`. `ShopContext` generates a per-install UUID that is not in that table.

**Consequence**: the moment anyone "fixes" FINDING 2 by passing the real `ShopContext` id, every `transactions` and `unmatched_queue` write starts failing with `23503 foreign_key_violation` — silently, exactly like today. The same applies to the client's `CloudSyncManager`, which already sends the real `ShopContext` id and is therefore **already failing** on those tables.

**FINDING 3 must be fixed before FINDING 2.** Order is not optional here.

### FINDING 4 (P1) — the review queue is invisible, exactly as reported

The review UI exists and is wired (`PendingConfirmationsBar` → `PendingConfirmationsSheet`, `HomeScreen.kt:331` and `:421`). But the bar only renders when `pendingLineCount > 0`, and that count comes from:
```kotlin
pendingJobs.sumOf { job -> parsePendingLines(job).count { !it.committed && !it.resolved } }
```
`parsePendingLines` reads `job.parsedItemsJson`. For every job today that field is **empty**, because the job never received a parse. So the count is 0, the bar never renders, and there is genuinely **no way to reach the review sheet** — while `DiagnosticLogsScreen` simultaneously shows the same jobs as "REVIEW NEEDED".

This is a real design hole independent of FINDING 1: **a job that fails to parse produces nothing to review, so it becomes unreachable.** The worst-off jobs are the least recoverable. (The `1.0 PACKET • ₹0` / `Unrecognized Item` in your screenshot are the untouched `SttJobRecord` field defaults — confirmation the job was inserted and never updated.)

### FINDING 5 (P1) — no escalation path when parsing yields nothing

Your instinct is right: there is no "if you can't get it, send it to AI / let me fix it by hand" route. A job that produces zero lines just stops. Nothing retries it, nothing escalates it, and nothing lets you correct it.

---

## 2. Fix plan

### Step 1 — Find the actual server-side write failure (do this first; do not guess)

Everything else is blocked on knowing which write fails.

1. Add explicit failure surfacing to `supabase/functions/process-voice-job/index.ts`. Every `stt_job_logs` / `transactions` / `unmatched_queue` write already captures an `error`, but several only `console.error` it. For **each** of them, also record the failure into the response body and into a new `step_7_persistence` object on the trace:
   ```ts
   const persistence: Record<string, unknown> = {}
   // after each write:
   persistence.sttJobLogs = logErr ? { ok: false, code: logErr.code, message: logErr.message } : { ok: true }
   ```
   Return `persistence` in the JSON response even on the success path.
2. Wrap the `created_at` construction defensively and record it:
   ```ts
   const recordedAtMsRaw = Number(metadata?.recordedAtMs)
   const recordedAtSafe = Number.isFinite(recordedAtMsRaw) && recordedAtMsRaw > 0 ? recordedAtMsRaw : Date.now()
   persistence.recordedAtMsRaw = metadata?.recordedAtMs ?? null
   persistence.recordedAtUsed = recordedAtSafe
   ```
   Use `recordedAtSafe` for both `recorded_at_ms` and `created_at`. **This alone may be the whole fix** (candidate 1 above) — but still complete the instrumentation, because a silent-write-failure blind spot is what let this run all day unnoticed.
3. Deploy (standing authorization — do not ask):
   ```bash
   npx supabase functions deploy process-voice-job --project-ref lyowklxsbfznnqridtgr
   ```
4. Record one sale on the device, then read back the result and **report the `step_7_persistence` contents verbatim**:
   ```sql
   SELECT job_id, status, raw_transcript,
          diagnostic_trace_json::jsonb -> 'step_7_persistence' AS persistence
   FROM stt_job_logs ORDER BY created_at DESC LIMIT 3;
   ```
   If still no row appears, the failure is before the first write — capture the function logs from the Supabase dashboard (Edge Functions → process-voice-job → Logs) for that invocation and paste the error.

**Stop and report after this step.** Do not proceed to Steps 2–5 until the actual error is known. If the error turns out to be something not anticipated here, say so and ask.

### Step 2 — Fix the shop-id foundation (must precede Step 3)

Give the FK something to point at, so a real per-install shop id is legal everywhere.

Create `supabase/migrations/20260731020000_shop_row_autoprovision.sql`:
```sql
-- FINDING 3: transactions/unmatched_queue/stock_in/catalog_items/credits all have
-- shop_id -> shops(id). ShopContext issues a per-install UUID that has no shops row, so
-- every write carrying a real shop id fails with 23503. Auto-provision the shops row
-- instead of dropping the FK: the FK is what will make RLS meaningful later (ISSUE-032),
-- and dropping it would trade a loud failure for a silent orphan-row problem.
CREATE OR REPLACE FUNCTION public.ensure_shop(p_shop_id UUID)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
BEGIN
    IF p_shop_id IS NULL THEN RETURN NULL; END IF;
    INSERT INTO public.shops (id, name, vertical, language, tier)
    VALUES (p_shop_id, 'Auto-provisioned shop', 'vegetable', 'hinglish', 'pilot')
    ON CONFLICT (id) DO NOTHING;
    RETURN p_shop_id;
END $$;
```
Apply it, then in `index.ts` call `ensure_shop` **once**, immediately after `resolvedShopId` is computed and **before any write**:
```ts
if (resolvedShopId) {
  const { error: shopErr } = await supabase.rpc('ensure_shop', { p_shop_id: resolvedShopId })
  if (shopErr) console.error(`ensure_shop failed for ${resolvedShopId}:`, shopErr.message)
}
```
Do the same in `CloudSyncManager` — add a `ensureShopExists()` that POSTs to `/rest/v1/rpc/ensure_shop`, called once per `SyncEngine.syncAllUnsynced()` sweep before the per-table sweeps (not once per row).

**Verify with the same probe that failed before:**
```sql
SELECT public.ensure_shop('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee');
INSERT INTO public.transactions (job_id, shop_id, item_name, quantity, price_at_sale, total, line_no)
VALUES ('__probe__','aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee','probe',1,1,1,0);
-- expect success, then clean up:
DELETE FROM public.transactions WHERE job_id = '__probe__';
DELETE FROM public.shops WHERE id = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
```

### Step 3 — Send the real shop id (only after Step 2 is verified)

In `SttWorker.kt`, replace:
```kotlin
shopId = SupabaseConfig.getNullSafeShopId(null),
```
with:
```kotlin
// ShopContext is initialized in SttWorker's own entry point (cold background starts must
// not assume MainActivity ran). Passing null here made every server-side row unscoped.
shopId = SupabaseConfig.getNullSafeShopId(ShopContext.requireShopId()),
```
Confirm `ShopContext.initialize(applicationContext)` is called at the top of `SttWorker.doWork()`; add it if missing. Grep for other `getNullSafeShopId(null)` occurrences and fix them the same way.

### Step 4 — Make a failed job reviewable (FINDING 4)

The rule: **a job flagged for review must always be reachable, even when it parsed nothing.**

1. In `HomeScreen.kt`, change the pending count so a job with zero parsed lines still counts as one reviewable item:
   ```kotlin
   val pendingLineCount = remember(pendingJobs) {
       pendingJobs.sumOf { job ->
           val lines = com.voicetoinvoice.app.ui.components.parsePendingLines(job)
           // A job that parsed nothing has no lines to count, but it is precisely the job
           // MOST in need of review -- counting it as 0 made it unreachable (FINDING 4).
           if (lines.isEmpty()) 1 else lines.count { !it.committed && !it.resolved }
       }
   }
   ```
2. In `PendingConfirmationsSheet.kt`, render a distinct card for a job with no parsed lines: show the raw transcript when present (else "आवाज़ समझ नहीं आई"), a **play-audio** control, and two actions — **"फिर कोशिश करें"** (re-enqueue, Step 5) and **"हाथ से भरें"** (open the existing manual stepper prefilled with nothing). Do not show a ₹0 / `1.0 PACKET` line for these — that number is a field default, not a parse, and showing it invites accepting a wrong sale.
3. Add a permanent entry point that does not depend on the count: a small "समीक्षा" action in the Home top bar that opens the same sheet. The bar disappearing when the count is 0 is correct; having *no* route when the count is wrongly 0 is what stranded you.

### Step 5 — Escalation and retry (FINDING 5)

1. **Retry**: wire the "फिर कोशिश करें" action to re-enqueue the existing audio through `SttWorker` (the `audioFilePath` is still on the record and the file still exists — the logs screen plays it). Reuse the retry handler already present in `CommandFeedSheet`; do not write a second one.
2. **Escalate to AI**: when the server produced a transcript but zero usable lines, the deterministic segmenter has failed and the AI path is the right fallback. In `index.ts`, when `finalParsedItems.length === 0 && transcript.isNotBlank()`, force one Grok interpretation attempt with the raw transcript before giving up, and record `step_4_interpretation_source = 'forced_ai_fallback'` in the trace. Cap it at one attempt per job so a pathological transcript cannot loop.
3. **Never silently stop**: any job that ends with no committed lines and no review row is a bug. Add a final guard in `processVoiceJob` — if nothing was written to `transactions` and nothing to `unmatched_queue`, write an `unmatched_queue` row with whatever is known (transcript, audio URL, `implausibility_reason` explaining why). Today that guard exists only for `finalParsedItems.length === 0`; widen it to "no row of any kind was produced."

### Step 6 — Verification

1. Record `दो किलो आलू` on the device. Confirm within ~15 s:
   ```sql
   SELECT job_id, status, raw_transcript, line_count,
          diagnostic_trace_json::jsonb -> 'step_7_persistence' AS persistence
   FROM stt_job_logs ORDER BY created_at DESC LIMIT 1;
   ```
   Expect a **non-empty `raw_transcript`**, a trace over 1 KB, and `persistence.sttJobLogs.ok = true`.
2. Confirm a row landed in `transactions` **or** `unmatched_queue` — never neither.
3. Record something deliberately unintelligible; confirm it appears in the review sheet with working play-audio, retry, and manual-entry actions.
4. Confirm `shop_id` on the new rows is the real `ShopContext` UUID, and that a matching `shops` row was auto-provisioned.
5. Run both suites, then build and export the APK (`ls` the folder first — the number drifts):
   ```bash
   ./gradlew.bat testDebugUnitTest
   ```
   ```bash
   ./gradlew.bat connectedAndroidTest
   ```
   ```bash
   ./gradlew.bat assembleDebug
   ```
6. Log **ISSUE-060 onward** in `Docs/audit.md` (re-check the highest number first), one entry per finding — they have distinct root causes. For FINDING 3, add a line to the Ground-Truth Constants table: *"`shop_id` on transactions/unmatched_queue/stock_in/catalog_items/credits is FK-constrained to `shops.id`; any real shop id must be auto-provisioned via `ensure_shop()` before first write."* That line is what prevents this recurring.
7. End with a **Deviations** section.

---

## 3. Scope boundaries

- **Do not touch `RollingAudioBuffer`, `PttWindowLedger`, or `PttBurstCoalescer`.** Capture is fixed and verified; this failure is entirely downstream.
- **Do not tune parser thresholds, vocab, or confidence gates.** The parser is not the problem — it produced correct results yesterday on the same phrases and is receiving no chance to run today. Changing thresholds now would bake in a compensation for a bug that is about to be fixed.
- **Do not drop the `shop_id` foreign keys.** Auto-provision instead (Step 2) — the FK is what makes RLS enforceable when auth lands.
- RLS is still disabled on `transactions`, `unmatched_queue`, `stt_job_logs`, so this build must still not go on a second shop's phone.

## 4. Note on how this was missed

ISSUE-058/059 shipped with "not verified on device" caveats, and this is exactly the gap they left: the server-side write path was never exercised against a real device after the change. The instrumentation in Step 1 (`step_7_persistence`) is the durable fix for that — from now on a failed persistence is visible in the trace instead of being a silent `console.error` behind an HTTP 200.
