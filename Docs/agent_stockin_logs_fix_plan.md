# Fix Plan — Assistant Mic, Stock-In Misbooking, Copy JSON, Summary Nav

**Date:** 2026-07-30
**Planner:** Claude Code · **Implementer:** Antigravity
**Scope:** 5 reported issues, one DB migration (16 → 17), one edge-function deploy.

Implement in the order given (§0 → §5). §0 must land before §2 and §3 compile.

---

## Evidence gathered before planning

Supabase edge-function logs for the three assistant presses visible in the user's
screenshot (12:44:21 / 12:44:39 / 12:44:51, epoch ≈ 1785395661 / 1785395679 / 1785395691):

| time | function | status |
|---|---|---|
| 12:44:25 | `tts-proxy` | 200 |
| 12:44:42 | `tts-proxy` | 200 |
| 12:44:54 | `tts-proxy` | 200 |
| — | `process-voice-job` | **no calls at all** |

So the assistant *is* speaking (TTS works, three answers played) and *never* transcribes:
the audio is never uploaded. That is the whole "says some stuff and does nothing" symptom.

---

## §0 — DB migration 16 → 17 (do this first)

### 0.1 `app/src/main/java/com/voicetoinvoice/app/data/local/entity/SttJobRecord.kt`

Add one field at the end of `SttJobRecord` (after `captureIntent`):

```kotlin
    /** What the assistant spoke back for this job. Kept out of parsedItemName so an
     *  answered question never renders as a booked ₹0 sale in the logs (see §1.6). */
    val assistantAnswer: String = ""
```

### 0.2 `app/src/main/java/com/voicetoinvoice/app/data/local/entity/StockInRecord.kt`

Add one field at the end of `StockInRecord` (after `supplierId`):

```kotlin
    /** True when the stock-in was booked from voice with no spoken cost price. The
     *  quantity is trusted and committed; cost is back-filled later from StockInScreen. */
    val costMissing: Boolean = false
```

### 0.3 `app/src/main/java/com/voicetoinvoice/app/data/local/AppDatabase.kt`

- Change `version = 16` → `version = 17` (line 29), and update the trailing comment to
  `// Bumped: assistantAnswer on stt_jobs, costMissing on stock_in`.
- Add, immediately after `MIGRATION_15_16` (line 260), following that object's exact
  try/catch-per-statement pattern:

```kotlin
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("ALTER TABLE stt_jobs ADD COLUMN assistantAnswer TEXT NOT NULL DEFAULT ''")
                } catch (e: Exception) { }
                try {
                    db.execSQL("ALTER TABLE stock_in ADD COLUMN costMissing INTEGER NOT NULL DEFAULT 0")
                } catch (e: Exception) { }
            }
        }
```

- Append `, MIGRATION_16_17` to the `.addMigrations(...)` call (line 331).

### 0.4 `supabase/migrations/20260731000000_stock_cost_missing.sql` (new file)

```sql
ALTER TABLE public.stock_in ADD COLUMN IF NOT EXISTS cost_missing BOOLEAN NOT NULL DEFAULT FALSE;
```

### 0.5 `supabase/schema.sql`

In `CREATE TABLE IF NOT EXISTS public.stock_in` (line 135), add after `cost_price`:

```sql
    cost_missing BOOLEAN NOT NULL DEFAULT FALSE,
```

### 0.6 `app/src/main/java/com/voicetoinvoice/app/network/CloudSyncManager.kt`

In `syncStockInToCloud` (line 253), add to the payload after `cost_price`:

```kotlin
                put("cost_missing", stockIn.costMissing)
```

---

## §1 — The assistant mic actually works (ISSUE-040)

### Root causes

1. **It never transcribes.** `SttWorker.doWork()` early-returns at
   `SttWorker.kt:56-58` for `CaptureIntent.ASSISTANT`, straight into
   `handleAssistantJob` (`SttWorker.kt:495-544`), which reads *only*
   `jobRecord.onDeviceTranscript`. The server upload block (lines 96-306) is skipped
   entirely. Confirmed by the edge logs above: zero `process-voice-job` calls.
2. **The on-device recognizer — its only ear — is starved of the microphone.**
   `RollingAudioBuffer` holds an `AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION)`
   open continuously (`RollingAudioBuffer.kt:55-75`), while `PttMicButton.kt:134` asks
   Google's `SpeechRecognizer` for the same source. It returns `error_audio` / `no_match`
   / blank, so `cleanTranscript.isBlank()` → `"समझ नहीं आया, फिर से बोलिए।"` every time.
   That is the exact string in the screenshot, on all three cards.
3. **It listens to itself.** `SpeechOutput.playAudioFile` plays the answer through
   `MediaPlayer` while the rolling buffer keeps recording; nothing mutes or ducks it,
   so the TTS answer lands in the ring buffer and the next PTT window (which includes
   `PRE_ROLL_MS` before the press) can capture it.
4. **Routing is keyword-only and mis-fires.** `IntentRouter.QUESTION_WORDS`
   (`IntentRouter.kt:24`) contains `"स्टॉक"`, `"बिक्री"`, `"बकाया"`, so most *write*
   utterances classify as `READ_QUERY`; `QuestionTemplates.answerQuestion`
   (`QuestionTemplates.kt:8-48`) then does naive `replace()` string surgery and falls
   through to `formatUnrecognized()`.
5. **Writes are refused by construction.** `SttWorker.kt:517-521` returns
   `"अभी सिर्फ सवाल पूछें..."` for anything write-shaped. The user has decided the
   assistant **should** book sales / udhaar / stock — this restriction is removed.
6. **The log card reads like a booked sale.** `handleAssistantJob` sets
   `parsedItemName = answer` and `status = AUTO_CONFIRMED` (`SttWorker.kt:533-541`),
   which `DiagnosticLogsScreen.kt:248-258` renders as
   `Item: समझ नहीं आया… | 1.0 PACKET • ₹0` under a green **AUTO-CONFIRMED** chip.
   No transaction row is actually written — the card is lying, not the ledger.

### 1.1 Server: `supabase/functions/process-voice-job/index.ts`

The assistant must get the full dual-STT transcript and the full segmenter/AI parse, but
must never write ledger rows itself — the client decides where the parse lands.

- After line 809 (`const isStockCapture = ...`), add:

```ts
  // ASSISTANT jobs get the full transcribe + parse pipeline but write no ledger rows
  // here. The client routes the parse to the right pipeline after intent classification
  // (SttWorker.handleAssistantJob), so a server-side write would double-book it.
  const isAssistant = captureIntent === 'ASSISTANT'
```

- Guard every ledger write with `!isAssistant`, leaving `stt_job_logs` (line ~1795)
  unguarded so the trace is always persisted:
  - line 1801: `if (!isAssistant && isStockCapture && committedSaleEntries.length > 0)`
  - line 1816: `else if (!isAssistant && !isStockCapture && committedSaleEntries.length > 0)`
  - the `unmatched_queue` insert for `pendingSaleEntries`: add `!isAssistant &&`
  - the `catalog_items` price update for `validRateUpdateEntries`: add `!isAssistant &&`
  - the credits insert (the `isCreditSale` branch): add `!isAssistant &&`

- Add `isAssistant` to the trace under `step_1_ptt_recording_metadata` (line ~1662,
  next to the existing `captureIntent`) so the decision is auditable.

**Deploy:** `npx supabase functions deploy process-voice-job --project-ref lyowklxsbfznnqridtgr`,
then re-fetch the live bundle and grep for `isAssistant` to confirm it carried.

### 1.2 Client: move the assistant branch *after* the server round-trip

`app/src/main/java/com/voicetoinvoice/app/domain/processor/SttWorker.kt`

- **Delete** the early return at lines 52-58 (the `CaptureIntent.ASSISTANT` block and
  its Fix-7 comment). The assistant now goes through the same upload as every other mic.
- **Insert** the branch after line 306 (after the `parsedItemsJson` recovery block,
  immediately before the `val audioCloudUrl = ...` line at 308):

```kotlin
                // The assistant mic gets the same transcript + parse as every other mic,
                // then routes the result to the pipeline the shopkeeper actually meant.
                // It must run before the per-line commit loop below, because that loop
                // keys off jobRecord.captureIntent, which for an assistant job is not yet
                // the effective intent.
                if (jobRecord.captureIntent == CaptureIntent.ASSISTANT) {
                    return@withContext handleAssistantJob(
                        jobRecord = jobRecord,
                        rawTranscript = rawTranscript,
                        parsedItems = parsedItems,
                        traceJson = traceJson,
                        catalog = catalog,
                        audioPath = audioPath
                    )
                }
```

### 1.3 Client: extract the commit engine so the assistant can reuse it

Still in `SttWorker.kt`. The per-line `for (i in 0 until lineCount)` loop (lines 318-433)
currently reads `jobRecord.captureIntent` in two places (lines 345 and 368). Extract it
verbatim into a private method so both the normal path and the assistant path share it:

```kotlin
    /** Books one job's parsed lines. `effectiveIntent` is jobRecord.captureIntent for a
     *  normal mic press, and the assistant's routed intent for an ASSISTANT job.
     *  Returns the number of lines that were committed (not sent to review). */
    private suspend fun commitParsedLines(
        jobRecord: SttJobRecord,
        effectiveIntent: CaptureIntent,
        parsedItems: JSONArray,
        catalog: List<com.voicetoinvoice.app.data.local.entity.CatalogItem>,
        rawTranscript: String,
        audioPath: String,
        audioCloudUrl: String,
        jobId: String
    ): Int
```

Move lines 318-448 (the loop **and** the `if (lineCount == 0)` fallback) into it, with
these edits and no others:

- every `jobRecord.captureIntent` → `effectiveIntent` (lines 345, 368)
- add the stock-commit gate from §2.2 below
- count and return committed lines

Replace the original block in `doWork()` with a single call passing
`effectiveIntent = jobRecord.captureIntent`. **Behaviour for non-assistant mics must be
byte-for-byte identical** — this is a pure extraction apart from §2.2.

### 1.4 Client: rewrite `handleAssistantJob`

Replace the whole of `SttWorker.kt:490-544` with:

```kotlin
    private suspend fun handleAssistantJob(
        jobRecord: SttJobRecord,
        rawTranscript: String,
        parsedItems: JSONArray,
        traceJson: String,
        catalog: List<com.voicetoinvoice.app.data.local.entity.CatalogItem>,
        audioPath: String
    ): Result
```

Logic, in this order:

1. If `rawTranscript.isBlank()` → `answer = ResponseComposer.formatUnrecognized()`,
   status `FAILED`, `errorMessage = "Assistant: empty transcript from STT"`. Speak, save, return.
2. `val classification = IntentRouter.classify(rawTranscript, parsedItems)` (new
   signature — see §1.5).
3. **READ_QUERY** → `answer = QuestionTemplates(LedgerQueries(db)).answerQuestion(rawTranscript)`,
   status `AUTO_CONFIRMED`, no writes.
4. **SALE / CREDIT_SALE / STOCK_IN / WASTE** →
   `val committed = commitParsedLines(jobRecord, classification.captureIntent, parsedItems, catalog, rawTranscript, audioPath, audioCloudUrl, jobRecord.id)`.
   Compose the answer from line 0 of `parsedItems`:
   - `SALE` → `ResponseComposer.formatSaleConfirmed(name, qty, unit, total)`
   - `CREDIT_SALE` → `ResponseComposer.formatUdhaarConfirmed(...)` when a customer
     resolved, otherwise `"₹<total> उधार दर्ज हो गया, किसका?"`
   - `STOCK_IN` → `"<qty> <unit> <item> स्टॉक में जोड़ा गया"`
   - `WASTE` → `"<qty> <unit> <item> खराब दर्ज हुआ"`
   - `committed == 0` → `"समझ नहीं आया — कृपया दोबारा बोलिए"`, status `PARSED` so it
     surfaces in the review queue.
   Status: `AUTO_CONFIRMED` when `committed == parsedItems.length()`,
   `PARTIALLY_CONFIRMED` when `0 < committed < length`, else `PARSED`.
5. **ACTION_COMMAND** → out of scope for this pass; answer
   `"यह काम अभी नहीं कर सकता"`, status `AUTO_CONFIRMED`, no writes. (Keep
   `ActionExecutor.kt` untouched — it needs an Activity context and cannot be driven
   from a Worker.)
6. **UNKNOWN** → `formatUnrecognized()`, status `PARSED`.
7. `speechOutput.speak(answer)` — wrapped in try/catch as today.
8. Persist:

```kotlin
        db.sttJobDao().updateJob(
            jobRecord.copy(
                status = finalStatus,
                rawTranscript = rawTranscript,
                assistantAnswer = answer,
                parsedItemName = firstItem?.optString("item_name") ?: "",
                parsedQty = firstItem?.optDouble("quantity") ?: 0.0,
                parsedUnit = firstItem?.optString("unit") ?: "",
                parsedTotal = firstItem?.optDouble("total") ?: 0.0,
                parsedItemsJson = parsedItems.toString(),
                lineCount = parsedItems.length(),
                diagnosticTraceJson = traceJson,
                isSanityFlagged = finalStatus == SttJobStatus.PARSED,
                synced = false
            )
        )
```

`assistantAnswer` carries the spoken text; `parsedItemName` no longer does. This is what
kills the misleading `Item: समझ नहीं आया | 1.0 PACKET • ₹0` card.

### 1.5 `app/src/main/java/com/voicetoinvoice/app/domain/router/IntentRouter.kt`

Keywords alone cannot separate a question from a sale. The server's parse can. New signature:

```kotlin
    fun classify(transcript: String, parsedItems: JSONArray): IntentClassification
```

Rules, evaluated in this exact order:

1. `hasItemLines` = any element of `parsedItems` with `quantity > 0` and a non-blank
   `item_name` that is not `"Unrecognized Item"`.
2. `isInterrogative` = transcript contains any of
   `कितना, कितने, कितनी, कब, कौन, क्या, बताओ, बता दो, कमाई, कमाया, हिसाब बता`.
   **Remove** `स्टॉक`, `बिक्री`, `बकाया` from the interrogative set — they appear in
   write utterances just as often. Keep them only as *topic* hints inside `QuestionTemplates`.
3. `isInterrogative && !hasItemLines` → `READ_QUERY` (confidence 0.95, `isReadOnly = true`).
4. `WASTE_WORDS` hit → `WASTE`.
5. `STOCK_WORDS` hit → `STOCK_IN`. Tighten `STOCK_WORDS` to
   `आया, आयी, आ गया, खरीदा, खरीद, लाया, माल आया, स्टॉक में, भरा` — drop the bare
   `स्टॉक` and `मात्रा`, which are far more often questions.
6. `UDHAAR_WORDS` hit → `CREDIT_SALE`. **Remove the bare honorific test**
   (`clean.contains("जी") || "भाई" || "चाचा"` at `IntentRouter.kt:54`) — `"जी"` is a
   substring of ordinary Hindi words and turns arbitrary sales into credit sales.
   Only `उधार, खाते, बही, बाकी, खाता, लिख दो` classify as credit.
7. `hasItemLines` → `SALE`.
8. `isInterrogative` → `READ_QUERY` (interrogative but nothing parsed).
9. Otherwise `UNKNOWN` (confidence 0.0). **`UNKNOWN` must never fall through to `SALE`** —
   the current default at line 59 does, which is how an unparsed mumble becomes a sale.

Add a JVM unit test `app/src/test/java/com/voicetoinvoice/app/router/IntentRouterTest.kt`
covering, at minimum: `"आज कितना कमाया"` → READ_QUERY; `"पाँच किलो आलू"` (with item
lines) → SALE; `"पचास किलो आलू आया"` → STOCK_IN; `"रमेश जी को दो किलो प्याज उधार"` →
CREDIT_SALE; `"दो किलो टमाटर"` spoken by a customer named "…जी" with no udhaar word → SALE
(regression guard for rule 6); `""` → UNKNOWN.

### 1.6 `app/src/main/java/com/voicetoinvoice/app/domain/query/QuestionTemplates.kt`

Replace the `replace()`-chain name extraction (lines 26 and 37) with catalog/customer
matching, so real phrasings resolve:

- Build the candidate name by **stripping** a stopword set
  (`का, की, के, कितना, कितने, कितनी, है, बचा, बाकी, उधार, स्टॉक, मेरा, अभी`) token-wise,
  then match the remaining tokens against the catalog via
  `PhoneticKey.of(...)` (already used by `LedgerQueries.getCustomerBalance`) instead of
  `String.contains`.
- Add a today's-**profit** branch and a top-selling-item branch — `LedgerQueries` already
  exposes `getTopSellingItem(startMs, endMs)` and it is currently dead code.
- When nothing matches, return a *specific* miss
  (`"<name> नाम का कोई आइटम नहीं मिला"`) rather than the generic `formatUnrecognized()`,
  so the shopkeeper can tell "I misheard you" from "I don't stock that".

### 1.7 Stop the assistant hearing itself

`app/src/main/java/com/voicetoinvoice/app/audio/RollingAudioBuffer.kt`

Add a suppression flag and honour it in the capture loop (lines 78-95):

```kotlin
    private val isSuppressed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** While true the ring buffer keeps advancing but stores silence, so TTS playback
     *  never lands in a window the next PTT press extracts. */
    fun setSuppressed(suppressed: Boolean) { isSuppressed.set(suppressed) }
```

In the read loop, when `isSuppressed.get()` is true, zero the sample bytes **before**
writing them into the ring buffer. Do not stop or restart `AudioRecord` — restarting it
costs ~200 ms and would drop the pre-roll for a press that lands mid-answer.

`app/src/main/java/com/voicetoinvoice/app/domain/voice/SpeechOutput.kt`

- Constructor takes an optional `rollingAudioBuffer: RollingAudioBuffer? = null`.
- `speak(...)`: call `rollingAudioBuffer?.setSuppressed(true)` before playback starts,
  and clear it in **both** completion paths — `setOnCompletionListener`
  (line 109) and the Android-TTS fallback (line 51) — plus in `stop()` (line 55) and the
  `playAudioFile` catch block (line 117). A leaked `true` deafens the app.
- Set `MediaPlayer.setAudioAttributes(AudioAttributes.Builder()
  .setUsage(USAGE_ASSISTANT).setContentType(CONTENT_TYPE_SPEECH).build())` before
  `prepare()` so the answer plays on the assistant stream rather than music.

`SttWorker.handleAssistantJob` constructs `SpeechOutput(context)` today
(`SttWorker.kt:505`) and has no buffer reference. Pass the singleton:
`SpeechOutput(context, RollingAudioBuffer.getSharedInstance())`. **If `RollingAudioBuffer`
has no singleton accessor** — `MainActivity` owns the instance and hands it down as
`sharedRollingAudioBuffer` (`HomeScreen.kt:106`) — add one mirroring
`PttWindowLedger.getInstance()`, and make `MainActivity` obtain its instance from it so
there is exactly one buffer app-wide. *Ambiguity: if adding that singleton conflicts with
how `MainActivity` currently constructs the buffer, stop and ask rather than creating a
second `AudioRecord`.*

### 1.8 Assistant jobs must not pollute the sales review queue

`SttJobDao.getParsedJobsFlow()` feeds `PendingConfirmationsSheet`. An assistant job left
at `PARSED` (steps 4/6 above) is a legitimate review item now that the assistant can book
sales, so leave it in the flow — but the sheet must respect `captureIntent`, which §2.4
handles.

---

## §2 — Stock-in must never book a sale (ISSUE-041)

### Root causes

1. **The commit gate is the sale gate.** `isCommittable` (`index.ts:1636-1643`) and its
   Kotlin mirror `isCommittableSale` (`SttWorker.kt:337-339`) both require
   `price_at_sale > 0.0 && total > 0.0`. A stock-in with no spoken price has both at 0,
   so it can never commit — it lands in `pendingSaleEntries` → `unmatched_queue`.
2. **Large quantities are flagged as implausible.** `implausibilityReason`
   (`price_intent.ts:189-220`) caps KG/LITRE at 200 and PIECE/PACKET/DOZEN at 500, and
   rejects any total below `MIN_PLAUSIBLE_SALE_VALUE`. Those ceilings are correct for one
   retail sale and wrong for a delivery — exactly the "the number was large and it went
   for review" the user hit.
3. **Confirming a review item always writes a sale.** `HomeScreen.onConfirmLine`
   (`HomeScreen.kt:337-419`) unconditionally inserts a `TransactionRecord` — it never
   reads `job.captureIntent`. Stock on hand is `Σ stock_in − Σ sold`
   (`LedgerQueries.getStockLevel`), so confirming a stock-in review **subtracts** the
   quantity. That is precisely "it went to sales and decreased stock further."
4. **Same bug for credit.** That handler also hardcodes the default `paymentMode`
   (CASH) and writes no `CreditRecord`, so confirming an उधार sale from the review sheet
   silently books it as cash — the same class of bug as ISSUE-039, on the review path.

### 2.1 Server: `supabase/functions/process-voice-job/price_intent.ts`

Add a mode parameter (keep the existing default so no call site changes behaviour):

```ts
export function implausibilityReason(
  unit: string,
  qty: number,
  total: number,
  rawTranscript: string = '',
  priceAtSale: number = 0,
  mode: 'SALE' | 'STOCK' = 'SALE'
): string | null
```

In `STOCK` mode:
- KG / LITRE ceiling `200` → `5000`
- PIECE / PACKET / DOZEN ceiling `500` → `10000`
- GRAM / ML: keep the `< 10` lower bound (still a mis-heard KG), raise `5000` → `100000`
- **Skip** the `MIN_PLAUSIBLE_SALE_VALUE` check entirely — a stock-in has no sale value.
- **Keep** the `extractSpokenNumbers` consistency guard unchanged: it catches genuinely
  dropped digits, which matters more for stock than for sales.

### 2.2 Server + client: a separate stock commit gate

`index.ts`, beside `isCommittable` (line ~1636):

```ts
    // A stock-in/waste line commits on quantity alone. Price is optional — the
    // shopkeeper is recording what arrived, not what it cost, and holding the stock
    // hostage to a price sends every unpriced delivery into the sales review queue.
    const isStockCommittable = (item: any) =>
      item.confidence >= 0.80 &&
      item.quantity > 0 &&
      item.item_name &&
      item.item_name.trim().length > 0 &&
      item.item_name !== "Unrecognized Item" &&
      item.implausibility_reason === null
```

Then `const committedSaleEntries = saleEntries.filter(e => isStockCapture ? isStockCommittable(e.item) : isCommittable(e.item))`.

Pass `mode` at the `implausibilityReason` call site (`index.ts:1427`):
`implausibilityReason(unit, qty, total, chosenRaw, priceAtSale, isStockCapture ? 'STOCK' : 'SALE')`.

In the stock insert (`index.ts:1801-1815`), set `cost_missing: !(item.price_at_sale > 0)`.

**Mirror all of this in the Kotlin client** inside `commitParsedLines` (§1.3):

```kotlin
                    val isCommittableStock = confidence >= 0.80 && qty > 0.0 &&
                        itemName.isNotBlank() && itemName != "Unrecognized Item" &&
                        implausibilityReason == null
                    val isStockIntent = effectiveIntent == CaptureIntent.STOCK_IN ||
                        effectiveIntent == CaptureIntent.WASTE
```

Change the branch at `SttWorker.kt:345` to
`isStockIntent && isCommittableStock -> { ... }` and set
`costMissing = !(priceAtSale > 0.0)` on the `StockInRecord`. Change the sale branch at
line 362 to `!isStockIntent && isCommittableSale ->`.

Ordering matters: the stock branch must be tested **before** `isCommittableSale`, exactly
as it is today, so an unpriced stock line never reaches the sale gate.

**Deploy** `process-voice-job` after 2.1 + 2.2 and re-grep the live bundle for
`isStockCommittable`.

### 2.3 Add the tests

`supabase/functions/process-voice-job/item_resolution_test.ts` — add cases:
- `implausibilityReason('KG', 500, 0, '', 0, 'STOCK')` → `null`
- `implausibilityReason('KG', 500, 0, '', 0, 'SALE')` → non-null
- `implausibilityReason('KG', 50, 0, 'पचास किलो आलू आया', 0, 'STOCK')` → `null`
- `implausibilityReason('GRAM', 5, 0, '', 0, 'STOCK')` → non-null (lower bound survives)

Run with `deno test supabase/functions/process-voice-job/` before deploying.

### 2.4 The review sheet must respect `captureIntent`

`app/src/main/java/com/voicetoinvoice/app/ui/screens/home/HomeScreen.kt` — the
`onConfirmLine` lambda at line 337. Wrap the write in a branch on `job.captureIntent`:

- **`STOCK_IN` / `WASTE`** — insert a `StockInRecord` instead of a `TransactionRecord`:

```kotlin
                    val stockRecord = StockInRecord(
                        itemId = item.id,
                        itemName = item.name,
                        quantity = if (job.captureIntent == CaptureIntent.WASTE) -Math.abs(line.quantity) else Math.abs(line.quantity),
                        costPrice = rate,
                        costMissing = rate <= 0.0
                    )
                    db.stockInDao().insert(stockRecord)
```

  Keep the existing catalog insert/price-update, trace merge, `markLineResolved`, job
  status update and `syncEngine.syncAllUnsynced()` calls exactly as they are — only the
  ledger row differs. Do **not** call `db.catalogDao().updatePrice(...)` for a stock-in:
  a purchase cost is not a selling price and overwriting the sale rate with it corrupts
  every margin figure. Gate that call (`HomeScreen.kt:352-356`) on `!isStockIntent`.

- **`CREDIT_SALE`** — `TransactionRecord(..., paymentMode = PaymentMode.CREDIT)` plus a
  `CreditRecord(customerName = "अज्ञात", customerId = null, amount = total,
  status = CreditStatus.PENDING, linkedTransactionId = txRecord.id)`, mirroring
  `SttWorker.kt:393-418`. The existing `UdhaarPickerOverlay` on this screen then picks up
  the unassigned credit with no further work.

- **`SALE` / `ASSISTANT`** — unchanged.

`app/src/main/java/com/voicetoinvoice/app/ui/components/PendingConfirmationsSheet.kt`:
- `PendingLine` gains `val captureIntent: CaptureIntent` (populate from `job.captureIntent`
  in `parsePendingLines`, which already receives the job).
- `isConfirmable` (line 350) → for STOCK_IN/WASTE, drop the `line.total > 0.0` requirement:
  `val isConfirmable = if (isStockIntent) line.quantity > 0.0 && line.itemName.isNotBlank() && line.itemName != "Unrecognized Item" else line.total > 0.0 && ...`
- Card heading (line 178): `"Pending Voice Sales (n)"` → `"पेंडिंग (n)"`, and give each
  card a small intent chip — `बिक्री` / `उधार` / `माल आया` / `खराब` — so the shopkeeper
  can see what confirming will do *before* tapping. This is the last line of defence
  against the misbooking the user reported.
- `PendingLineEditDialog`: for a stock intent, label the money field
  `"लागत भाव (₹ प्रति <unit>) — वैकल्पिक"` and relax `canSave` to allow `rateVal == 0.0`.

### 2.5 Surface the missing cost

`app/src/main/java/com/voicetoinvoice/app/ui/screens/stockin/StockInScreen.kt` — in the
recent-stock list, render an `AssistChip("भाव डालें")` on any row with
`costMissing == true`; tapping it opens the existing quantity/cost entry path pre-filled
with that item, and saving clears `costMissing`.

---

## §3 — Copy JSON (ISSUE-042)

### Root causes

1. For the assistant jobs in the screenshot, `diagnosticTraceJson` was never populated
   (`handleAssistantJob` never set it), so the button copied an **empty string** while
   still toasting `"Copied JSON trace to clipboard!"` — `DiagnosticLogsScreen.kt:381-386`.
   §1.4 fixes the underlying blank by persisting `traceJson`.
2. For real jobs the trace is large (full dual-STT dump + segmenter lattice + per-line
   outcomes). `ClipData` crosses a Binder transaction with a ~1 MB ceiling, and several
   OEM clipboards (Samsung's included) truncate well below that. A silent truncation
   looks exactly like "copy doesn't work".

### 3.1 `app/src/main/java/com/voicetoinvoice/app/ui/screens/logs/DiagnosticLogsScreen.kt`

Replace the `onClick` at line 381 with:

```kotlin
                            onClick = {
                                if (log.diagnosticTraceJson.isBlank()) {
                                    Toast.makeText(context, "इस job का trace उपलब्ध नहीं है", Toast.LENGTH_SHORT).show()
                                    return@OutlinedButton
                                }
                                try {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Voice Trace JSON", log.diagnosticTraceJson))
                                    Toast.makeText(
                                        context,
                                        "Copied ${log.diagnosticTraceJson.length} chars to clipboard",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Clipboard failed (${e.javaClass.simpleName}) — use Share JSON", Toast.LENGTH_LONG).show()
                                }
                            }
```

Reporting the character count makes a truncated paste self-diagnosing.

Add a second button beside it, modelled on `shareAudioFile` (line 399):

```kotlin
private fun shareTraceJson(context: Context, jobId: String, json: String) {
    // The clipboard round-trips through a Binder transaction with a ~1 MB ceiling and
    // OEM clipboards truncate below it; a file share has no such limit.
    val dir = File(context.cacheDir, "traces").apply { mkdirs() }
    val file = File(dir, "trace_$jobId.json")
    file.writeText(json)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share trace JSON"))
}
```

`app/src/main/res/xml/file_paths.xml` must expose `cache-path` for `traces/`. Check
whether the existing `<cache-path>` entry (used by the audio share) already covers the
whole cache dir — if it names a specific subdirectory, add `traces/`. *If `file_paths.xml`
does not exist under that name, find the path referenced by the `fileprovider` entry in
`AndroidManifest.xml` and edit that file instead.*

### 3.2 Assistant cards in the log list

Same file, lines 233-258. When `log.captureIntent == CaptureIntent.ASSISTANT`:
- render `log.assistantAnswer` under a `"🤖 जवाब:"` label instead of the
  `Item: … | qty unit • ₹total` row (the row that produced `1.0 PACKET • ₹0`);
- suppress the quantity/price row entirely — an answer has no quantity;
- show the status chip as `ASSISTANT` (neutral colour), not the green `AUTO-CONFIRMED`.

Add a small `captureIntent` chip to *every* card (`बिक्री` / `उधार` / `माल` / `खराब` /
`सहायक`) — with four mics writing into one log, the intent is the first thing worth seeing.

---

## §4 — Sales-summary button on the sales tab (ISSUE-043)

### 4.1 `app/src/main/java/com/voicetoinvoice/app/ui/screens/home/HomeScreen.kt`

- Add a parameter after `onNavigateToLogs` (line 88):
  `onNavigateToSummary: () -> Unit = {},`
- In the `TopAppBar` `actions` block (lines 194-203), add **before** the existing logs
  `IconButton`, so the order reads Summary → Logs → today's total:

```kotlin
                    IconButton(onClick = onNavigateToSummary) {
                        Icon(Icons.Default.Assessment, contentDescription = "Sales Summary")
                    }
```

  Import `androidx.compose.material.icons.filled.Assessment`.

### 4.2 `app/src/main/java/com/voicetoinvoice/app/MainActivity.kt`

`DailySummaryScreen.onNavigateBack` is hardcoded to `Screen.CUSTOMER_LIST`
(line 449), so reaching Summary from Home and pressing back would dump the user on the
हिसाब tab. Track the origin:

- Beside `currentScreen` (line 89): `var summaryOrigin by remember { mutableStateOf(Screen.CUSTOMER_LIST) }`
- In the `HomeScreen(...)` call (line 263), after `onNavigateToLogs`:
  `onNavigateToSummary = { summaryOrigin = Screen.HOME; currentScreen = Screen.SUMMARY },`
- In `CustomerListScreen`'s existing `onNavigateToSummary` (line 479), set
  `summaryOrigin = Screen.CUSTOMER_LIST` before navigating.
- Change `DailySummaryScreen`'s `onNavigateBack` (line 449) to
  `{ currentScreen = summaryOrigin }`.
- The bottom-bar `selected` expression at line 246 already includes `Screen.SUMMARY` in
  the हिसाब tab's set. Leave it — Summary is a हिसाब-flavoured screen regardless of entry
  point, and changing it would make the tab indicator flicker on back-navigation.

---

## §5 — Audit log & build

### 5.1 `Docs/audit.md`

Add four entries under **🟢 RESOLVED ISSUES**, next sequential numbers (check the highest
in the file first — the numbering is at ISSUE-039 as of this plan), dated the
implementation day, in the existing Symptom / Root Cause / Resolution / Verification Date
format:

- **ISSUE-040** — Assistant mic never transcribed (no `process-voice-job` call; on-device
  recognizer starved by `RollingAudioBuffer`), answered `"समझ नहीं आया"` to everything,
  and refused all writes. Cite the edge-log evidence table at the top of this plan.
- **ISSUE-041** — Stock-in routed to the sales review queue and booked as a sale on
  confirm, decreasing on-hand stock. Cite `HomeScreen.kt:337` and the sale-shaped
  commit gate. Note that the CREDIT_SALE-books-as-CASH review bug found alongside it is
  the same root cause class as ISSUE-039 — cross-reference it there rather than filing
  a fifth entry.
- **ISSUE-042** — Copy JSON silently copied an empty/truncated string.
- **ISSUE-043** — Summary unreachable from the sales tab.

Update **§1 Ground-Truth Source-Code Verified Constants**: `AppDatabase.version` 16 → 17,
and add the STOCK-mode plausibility ceilings from §2.1.

### 5.2 Build & ship

```bash
./gradlew test
```

```bash
./gradlew assembleDebug
```

Then `ls "C:/Users/harsh/OneDrive/Desktop/VoiceToInvoice_APKs"` for the highest `v<N>`
and copy `app/build/outputs/apk/debug/app-debug.apk` there as `VoiceToInvoice_v<N+1>.apk`.

### 5.3 On-device verification checklist

1. Fresh install over the old build — confirm migration 16→17 runs without wiping data.
2. Assistant mic, ask `"आज कितना कमाया"` → spoken rupee total matching the Home header.
3. Assistant mic, say `"पाँच किलो आलू"` → sale booked, spoken confirmation, appears in Summary.
4. Assistant mic, say `"पचास किलो आलू आया"` → **stock_in** row, stock goes **up**.
5. Press the assistant mic *while it is still speaking* → the new recording must not
   contain the TTS answer (check `rawTranscript` in the logs screen).
6. Stock-in mic, `"पाँच सौ किलो आलू आया"` (deliberately large, no price) → commits
   directly, no review card, `costMissing` chip visible in Stock-In.
7. Any pending review card → confirm it and check the intent chip matched what got written.
8. Logs → expand a job → Copy JSON pastes the full trace; Share JSON opens the chooser.
9. Sales tab → new summary icon opens Summary; back returns to **Home**, not हिसाब.

---

## Open questions for the implementer

1. **`RollingAudioBuffer` singleton (§1.7).** `SttWorker` runs in a Worker with no access
   to `MainActivity`'s instance. If adding a `getSharedInstance()` accessor conflicts with
   how `MainActivity` constructs and hands down the buffer, **stop and ask** — creating a
   second `AudioRecord` would break every recording, not just the assistant.
2. **`commitParsedLines` extraction (§1.3).** If moving lines 318-448 out of `doWork()`
   requires touching anything beyond the two `jobRecord.captureIntent` reads and the §2.2
   gate, stop and ask rather than adjusting the commit semantics for sales.
3. **`file_paths.xml` (§3.1).** If the FileProvider config does not already expose the
   cache dir broadly enough for `traces/`, add the path — but quote the existing entry in
   your report so the audio-share path is verifiably unaffected.

## Deviations section

End your run with a **Deviations** heading listing anything changed, skipped, or
interpreted differently from the literal text above, and why. If none, write "None."
