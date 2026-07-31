# Fix Plan — Assistant Latency, Empty Traces, Background Mic, Failing Jobs

**Date:** 2026-07-30
**Planner:** Claude Code · **Implementer:** Antigravity
**Supersedes nothing** — this stacks on `Docs/agent_stockin_logs_fix_plan.md` and
`Docs/sttworker_regression_correction.md`, both of which are already implemented (v87).

Implement §1 → §6 in order. §1 and §2 are the ones that make everything else diagnosable.

---

## What the evidence actually says

I pulled the live `stt_job_logs` rows and edge-function logs for the two failing
recordings in the user's screenshots. This is not inference — it is measured.

### The assistant recording at 14:05:36 IST (job `249db598-…`)

| clock (IST) | event | source |
|---|---|---|
| 14:05:36.181 | mic released, job created | `recorded_at_ms` |
| 14:05:41.852 | POST reaches `process-voice-job` | edge log |
| ~14:05:45.9 | server answers **202 QUEUED** (4102 ms exec) | edge log |
| 14:05:46 → 14:06:12 | client polls `stt_job_logs` every 2 s, **30 s budget** | `SttWorker.pollForCompletion` |
| 14:06:14.951 | `tts-proxy` called with "समझ नहीं आया" | edge log |
| ~14:06:17 | shopkeeper finally hears the answer | — |

**≈ 41 seconds from press to spoken answer**, and the answer was "I didn't understand."
The row in `stt_job_logs` ended as `status=FAILED`, `raw_transcript=''`,
`diagnostic_trace_json='{}'` (literally two characters).

### The stock-in recording at 14:08:56 IST

Edge logs show **two** `202` responses for it — 14:08:59 and a WorkManager retry at
14:11:03. `stt_job_logs` contains **no row for it whatsoever**. The recording reached
the server twice and left no trace on either attempt. Locally it shows
`REVIEW NEEDED / Unrecognized Item / 1.0 PACKET / ₹0`.

### For contrast, the same mic 79 minutes earlier (job `cd683ace-…`, 12:50:59)

`"आलू बीस किलो"` → Grok 893 ms, Sarvam 217 ms, `AUTO_CONFIRMED`, 3183-char trace,
`Aaloo / 20 KG`. **The STT and the parser are not the problem.** Sarvam heard the
sentence perfectly. Every one of those traces also shows
`onDeviceStt: {"status": "no_match" | "error_11" | "unavailable"}` — the on-device
recognizer has never once returned a word (see §4).

---

## §1 — Traces are empty because the trace is thrown away, then overwritten

Two independent defects, both introduced this session. The first one is mine — the
`pollForCompletion` signature I specified in `Docs/sttworker_regression_correction.md`
returned a `Triple<String, String, JSONArray>` carrying status, transcript and items,
and **silently dropped the trace**. It was implemented exactly as I wrote it.

### 1.1 `SttWorker.pollForCompletion` reads the trace and discards it

`app/src/main/java/com/voicetoinvoice/app/domain/processor/SttWorker.kt:458-524`
declares `var traceJson = ""`, fills it from the polled row, and then returns
`Triple(statusStr, rawTranscript, parsedItems)` — no trace. The caller at line 119-124
never touches `traceJson` again, so it stays `""` for every job that goes through the
poll path, which is every job.

**Fix.** Replace the `Triple` with a small data class so the value cannot be lost again:

```kotlin
    /** Everything the server's finished stt_job_logs row carries back to the client. */
    private data class PolledResult(
        val status: String,
        val rawTranscript: String,
        val traceJson: String,
        val parsedItems: JSONArray
    )
```

Change the signature to `private suspend fun pollForCompletion(jobId: String): PolledResult`,
return `PolledResult(statusStr, rawTranscript, traceJson, parsedItems)` at line 524, and
update the call site (line 119-124) to:

```kotlin
                if (statusStr == "QUEUED") {
                    val polled = pollForCompletion(jobId)
                    statusStr = polled.status
                    if (polled.rawTranscript.isNotBlank()) rawTranscript = polled.rawTranscript
                    if (polled.traceJson.isNotBlank()) traceJson = polled.traceJson
                    if (polled.parsedItems.length() > 0) parsedItems = polled.parsedItems
                }
```

### 1.2 The client overwrites the server's good trace with `{}`

`CloudSyncManager.postTraceLogToSupabaseDatabase` (`CloudSyncManager.kt:354-387`)
upserts on `job_id` and writes `diagnostic_trace_json = traceObj.toString()`
unconditionally. When the client's local trace is blank, `traceObj` serialises to `{}`
and **destroys the 3000-char trace the server already wrote**. That is exactly the
`trace_len = 2` on job `249db598`.

**Fix.** Never let a blank client trace overwrite a populated server one. In
`postTraceLogToSupabaseDatabase`, omit the column entirely when there is nothing to say:

```kotlin
                val traceStr = traceObj.toString()
                // A blank client-side trace must never clobber the server's own trace --
                // the server writes the full step_1..step_6 breakdown and the client is a
                // mirror, not a source of truth, for this column (see ISSUE-044).
                if (traceStr.isNotBlank() && traceStr != "{}") {
                    put("diagnostic_trace_json", traceStr)
                }
```

Apply the same guard to `raw_transcript` and `parsed_item_name` — a client row with a
blank transcript must not blank out a server row that has one.

### 1.3 The client must always write its own trace, even on total failure

The user's complaint — *"json log doesnt have any data about what happened"* — stays true
for the stock-in job even after 1.1 and 1.2, because that job produced **no server row at
all**. Today the trace is 100 % server-authored, so when the server writes nothing, the
phone can say nothing about what it tried.

**Fix.** Build a client trace unconditionally in `SttWorker.doWork()`. Accumulate a
`JSONObject` from the first line of the method and write it into
`SttJobRecord.diagnosticTraceJson` on **every** exit path, merging (not replacing) the
server's trace when one arrives:

```kotlin
        val clientTrace = JSONObject().apply {
            put("client_started_at", System.currentTimeMillis())
            put("capture_intent", jobRecord.captureIntent.name)
            put("audio_path", audioPath)
            put("audio_bytes", audioFile.length())
            put("attempt", runAttemptCount)   // CoroutineWorker property -- exposes retries
        }
```

Record into it, at minimum: the upload HTTP status code and duration; the number of poll
attempts made and the status seen on each; whether the poll timed out; the exception
class and message from any `catch`; and the final decision (`committed n lines` /
`sent to review` / `retry`). Then, wherever the job record is updated:

```kotlin
        val mergedTrace = JSONObject().apply {
            put("client", clientTrace)
            if (traceJson.isNotBlank()) put("server", JSONObject(traceJson))
        }.toString()
```

This is the single change that makes every future failure self-explaining, and it is why
it is worth doing even though it is not, by itself, a user-visible feature. Store it under
a `client` key so the existing `step_1…step_6` server shape stays untouched under `server`.

`DiagnosticLogsScreen` needs no change — it renders the JSON blob verbatim.

---

## §2 — Kill the 30-second wait: let the server answer synchronously

This is the single biggest latency win and it helps **every** mic, not just the assistant.

`process-voice-job` currently always returns `202 QUEUED` and does the real work in
`EdgeRuntime.waitUntil(...)` (`index.ts:750-777`). The client then polls at 2-second
intervals for up to 30 seconds (`SttWorker.kt:465-466`). But the measured server work is
**2.2 – 4.1 seconds** (edge-log `execution_time_ms`, and Grok 893 ms + Sarvam 217 ms
inside it). We are waiting 30 seconds for something that finishes in 4.

The 202 exists so a recording still completes when the app is closed. That property is
preserved by `waitUntil` regardless of what we return — so we can await the work and
return the result, and *still* have the row written if the client disappears mid-request.

### 2.1 `supabase/functions/process-voice-job/index.ts`

Replace the fire-and-forget at line 750-777 with a bounded inline await:

```ts
    // Await the pipeline for a short budget and return the real result inline -- the
    // client's 30s poll was costing ~30s of dead time on every recording for work that
    // measurably takes 2-4s. waitUntil is retained on the timeout path so a slow job
    // still finishes server-side even if the phone gives up or the app is closed.
    const work = processVoiceJob({ jobId, shopId: resolvedShopId, metadata, audioBuffer,
      audioCloudUrl, catalogNamesRaw, onDeviceTranscript, onDeviceStatus, previousJobId,
      precedingGapMs, captureIntent, supabase })

    const INLINE_BUDGET_MS = 20000
    const finished = await Promise.race([
      work.then(() => 'done' as const),
      new Promise<'timeout'>(r => setTimeout(() => r('timeout'), INLINE_BUDGET_MS))
    ])

    if (finished === 'timeout') {
      EdgeRuntime.waitUntil(work)
      return new Response(JSON.stringify({
        status: 'QUEUED', job_id: jobId, audio_cloud_url: audioCloudUrl, cached: false,
        message: 'Still processing — poll stt_job_logs for this job_id.'
      }), { status: 202, headers: { 'Content-Type': 'application/json', ...CORS_HEADERS } })
    }

    // Read back the row the pipeline just wrote so the response carries the same shape
    // the poll path returns (snake_case -- the client reads raw_transcript/parsed_items).
    const { data: finishedRow } = await supabase.from('stt_job_logs')
      .select('status,raw_transcript,diagnostic_trace_json').eq('job_id', jobId).maybeSingle()

    let inlineParsedItems: any[] = []
    try {
      const t = JSON.parse(finishedRow?.diagnostic_trace_json || '{}')
      if (Array.isArray(t.step_4_grok_ai_interpretation)) inlineParsedItems = t.step_4_grok_ai_interpretation
    } catch (_) {}

    return new Response(JSON.stringify({
      status: finishedRow?.status || 'PARSED',
      job_id: jobId,
      raw_transcript: finishedRow?.raw_transcript || '',
      diagnostic_trace_json: finishedRow?.diagnostic_trace_json || '',
      parsed_items: inlineParsedItems,
      audio_cloud_url: audioCloudUrl,
      cached: false
    }), { status: 200, headers: { 'Content-Type': 'application/json', ...CORS_HEADERS } })
```

`processVoiceJob` must be checked for whether it currently swallows its own errors —
if it can reject, wrap the race so a rejection still returns a 200 with `status: 'FAILED'`
and the error in the trace, rather than a 500 that the client turns into a blind retry.

**Requires no client change** — `SttWorker` already reads `raw_transcript` /
`parsed_items` / `diagnostic_trace_json` from the immediate response and only polls when
`status == "QUEUED"` (line 119). After this, the poll almost never runs.

### 2.2 Tighten the client poll anyway

`SttWorker.kt:465-466`: drop `maxPollMs` from `30000L` to `20000L` and `pollIntervalMs`
from `2000L` to `750L`. With §2.1 in place this path is now the rare exception, and when
it does run, 750 ms granularity costs nothing and removes up to 2 s of dead time.

### 2.3 Investigate the vanished stock-in job

The 14:08:56 recording got two `202`s and produced no `stt_job_logs` row at all. The
QUEUED placeholder upsert at `index.ts:736-747` is `await`ed but its error is **never
checked** — unlike every other write in the file. Add the check:

```ts
    const { error: queuedErr } = await supabase.from('stt_job_logs').upsert([{ ... }], { onConflict: 'job_id' })
    if (queuedErr) console.error(`Failed to write QUEUED placeholder for job ${jobId}:`, queuedErr.message)
```

Do the same for any other unchecked `.upsert`/`.insert` in this file. §1.3's client trace
plus this log line together will identify the cause on the next occurrence; do not guess
at a fix for the disappearance itself until one of them has actually caught it.

---

## §3 — Make the assistant instant

Target: **under 1.5 s** from mic release to spoken answer for a question. Today it is 41 s.

Even after §2 the assistant would still be ~6-8 s (upload + server STT + parse + network
TTS). A question does not need any of that. The data is already on the phone.

### 3.1 The assistant must not touch the network for a read query

Restructure the assistant press so it never enqueues an `SttWorker` job for questions:

1. **On-device STT only.** `OnDeviceSpeechRecognizer` returns in ~300-800 ms. §4 is what
   makes it actually work.
2. **Classify locally.** `IntentRouter.classify(transcript, JSONArray())` — the item-lines
   signal is unavailable in the fast path, so the interrogative rules (rules 3 and 8) carry
   it. This is fine: questions are what the fast path is for.
3. **Answer from RAM** (§3.2).
4. **Speak with the offline engine** (§3.3).
5. Only if the utterance is **write-shaped** does it fall back to the existing
   audio-upload path, and it speaks `"दर्ज कर रहा हूँ…"` immediately so the shopkeeper
   is never left waiting in silence.

Wire this in `PttMicButton` (or a new `AssistantMicButton`) so `CaptureIntent.ASSISTANT`
takes the fast path at release time instead of writing an `SttJobRecord`. Keep writing a
job record for the log, but mark it resolved locally — do not enqueue `SttWorker` for a
read query.

### 3.2 Precompute the ledger — "already loaded, just waiting to be asked"

`LedgerQueries` (`domain/query/LedgerQueries.kt`) is the opposite of instant. Every single
question runs `db.transactionDao().getAllTransactionsList()` — **the entire transactions
table into memory** — and filters in Kotlin. `getStockLevel` does it three times over
(all catalog + all stock_in + all transactions) for one number. This gets linearly slower
every day the shop uses the app.

**Fix, two parts.**

**(a) Real SQL aggregates.** Add to `TransactionDao` / `StockInDao` / `CreditDao`:

```kotlin
    @Query("SELECT COALESCE(SUM(total),0) FROM transactions WHERE timestamp >= :since AND isVoided = 0")
    suspend fun totalSince(since: Long): Double

    @Query("SELECT COUNT(*) FROM transactions WHERE timestamp >= :since AND isVoided = 0")
    suspend fun countSince(since: Long): Int

    @Query("SELECT itemName, SUM(quantity) AS qty FROM transactions WHERE timestamp BETWEEN :from AND :to AND isVoided = 0 GROUP BY itemName ORDER BY qty DESC LIMIT 1")
    suspend fun topItemBetween(from: Long, to: Long): TopItemRow?
```

*(Check the real column names in `TransactionRecord.kt` before writing these — the voided
flag in particular. Do not guess; if `isVoided` is named differently or absent, use what
the entity actually declares and say so in Deviations.)*

Stock on hand should come from the existing `catalogDao().getStockLevels()` — `MainActivity`
already collects it as `stockLevelsMap` (`MainActivity.kt:92-93`), so a second hand-rolled
computation in `LedgerQueries.getStockLevel` is both slower and a second source of truth.

**(b) An in-memory snapshot.** New `app/src/main/java/com/voicetoinvoice/app/domain/query/LedgerSnapshot.kt`:

```kotlin
/** The answers to every question the assistant can be asked, kept hot in memory so a
 *  spoken query costs zero database work. Refreshed from Room Flows -- Room emits on
 *  every write to the underlying tables, so this cannot go stale without the data
 *  changing first. */
object LedgerSnapshot {
    @Volatile var todayTotal: Double = 0.0; private set
    @Volatile var todayCount: Int = 0; private set
    @Volatile var stockByItemId: Map<String, Double> = emptyMap(); private set
    @Volatile var outstandingByCustomer: Map<String, Double> = emptyMap(); private set
    @Volatile var topItemToday: String? = null; private set
    @Volatile var lastRefreshedMs: Long = 0L; private set

    fun start(scope: CoroutineScope, db: AppDatabase) { /* collect the Flows, update fields */ }
}
```

Start it once from `MainActivity` (alongside the existing DAO collection) and have
`QuestionTemplates` read these fields directly instead of calling `LedgerQueries`. Keep
`LedgerQueries` as the cold path for anything not in the snapshot.

`lastRefreshedMs` exists so a stale snapshot is detectable rather than silently wrong — if
it is older than ~10 s when a question arrives, fall through to `LedgerQueries` for that
one answer.

### 3.3 Speak instantly

`SpeechOutput.speak` (`domain/voice/SpeechOutput.kt:36-53`) **always** tries Grok TTS
first — a network round trip measured at **1302-2432 ms** in the edge logs — and only
falls back to Android's offline engine when it fails. For an assistant whose whole job is
to feel instant, that is backwards.

**Fix.** Add a `preferOffline: Boolean = false` parameter to `speak(...)`. The assistant
fast path passes `true`, which uses `androidTts` directly and skips the network entirely.
Keep Grok TTS as the default for anything not latency-critical.

Also fix a real bug in the same method while you are in it: on the Android-TTS fallback
path (line 47-52) `onComplete` is invoked **immediately** after `androidTts?.speak(...)`
returns, not when speech actually finishes — `speak()` is asynchronous. Any caller relying
on `onComplete` to know the answer has been heard (e.g. `ConversationController.askQuestion`,
which reopens the mic in that callback) fires while the phone is still talking, which is a
second mechanism for the assistant hearing itself. Use `setOnUtteranceProgressListener` /
`UtteranceProgressListener.onDone` keyed on the utterance id that is already being passed
(`"voice_output_id"`).

### 3.4 Make the assistant understand more than three phrasings

`QuestionTemplates.answerQuestion` (`domain/query/QuestionTemplates.kt`) matches four
hardcoded substrings and strips words with a `replace()` chain. `"आज कितना कमाया"` works;
`"आज की कमाई कितनी हुई"`, `"कितना बिका"`, `"आज का हिसाब"` all fall to
`formatUnrecognized()`. This is the "it's dumb" complaint.

Restructure as **topic + timeframe**, both extracted independently:

- **Topic** — `REVENUE` (कमाई, कमाया, बिक्री, बिका, हिसाब, कुल), `STOCK`
  (स्टॉक, बचा, बाकी है, कितना है, माल), `UDHAAR` (उधार, बकाया, देना, खाता),
  `TOP_ITEM` (सबसे ज्यादा, सबसे अच्छा, टॉप), `PROFIT` (मुनाफा, फायदा, बचत).
- **Timeframe** — `TODAY` (आज, default), `YESTERDAY` (कल), `WEEK` (हफ्ता, सप्ताह),
  `MONTH` (महीना).
- **Entity** — remaining tokens after both are stripped, resolved through
  `PhoneticKey.of(...)` against the catalog (for STOCK) or `EntityResolver<CustomerRecord>`
  (for UDHAAR) rather than `String.contains`.

Fall back to `REVENUE` + `TODAY` when a topic cannot be identified but the utterance is
clearly interrogative — "something about today's business" is the overwhelmingly common
case and a useful answer beats "समझ नहीं आया".

When the entity does not resolve, say which part failed
(`"<name> नाम का कोई आइटम नहीं मिला"`) instead of the generic miss.

Add `app/src/test/java/com/voicetoinvoice/app/query/QuestionTemplatesTest.kt` covering at
least two phrasings per topic and one unresolvable-entity case.

---

## §4 — Why on-device STT has never once worked

Every trace in the database shows `onDeviceStt.status` as `no_match`, `error_11` or
`unavailable`, and `onDeviceTranscript: ""`. Not one word, ever. §3.1 depends on this
being fixed — without it the assistant has no fast ear.

**Cause.** `RollingAudioBuffer` holds an `AudioRecord` on
`MediaRecorder.AudioSource.VOICE_RECOGNITION` continuously
(`RollingAudioBuffer.kt:55-75`), while `PttMicButton.kt:134` simultaneously asks Google's
`SpeechRecognizer` — a *different process* — for the same source. Android's concurrent-capture
policy silences one of them, and it is consistently the recogniser.

**Fix.** For `CaptureIntent.ASSISTANT` only, release the microphone for the duration of
the press:

- On press: `rollingAudioBuffer.stopRollingBuffer()`, then
  `onDeviceRecognizer.startListening("hi-IN")`.
- On release: `finishListening()`, await the result, then `startRollingBuffer()` again.

The assistant does not need the ring buffer — there is no pre-roll to protect, because the
shopkeeper presses *then* speaks. Accept that assistant fast-path jobs have **no saved
audio file**; a question is ephemeral and the transcript is the artefact worth keeping.
Note this explicitly on the log card so an absent "Play Recorded Audio" button does not
read as a bug.

The ~200 ms `AudioRecord` restart cost lands *after* the answer is already being spoken,
so it is invisible to the user. Do **not** apply this to the sale/stock mics — they need
the ring buffer and its pre-roll, and their audio file is evidence.

If, once the mic is genuinely exclusive, `SpeechRecognizer` still returns `error_11`
(`ERROR_LANGUAGE_NOT_SUPPORTED`) for `hi-IN` on this device, stop and report it rather
than working around it — that would mean the Hindi offline model is not installed, which
is a device-setup answer (Settings → Google → Voice → Offline speech recognition), not a
code change. Add `RecognizerIntent.EXTRA_PREFER_OFFLINE = true` so the offline model is
used when present.

---

## §5 — Stop recording when the app leaves the foreground

**Confirmed.** `MainActivity.kt:182-185` starts the buffer in a
`DisposableEffect(Unit)` inside `MainAppScreen`. `onDispose` runs only when the composable
leaves composition — i.e. when the Activity is *destroyed*. Pressing Home stops the
Activity but does not destroy it, so `stopRollingBuffer()` never runs and the microphone
stays open indefinitely. `AppForegroundService` is `START_STICKY`, which keeps the process
(and therefore the capture thread) alive for as long as Android allows.

**Fix.** Bind the buffer to the *lifecycle*, not to composition. Replace the
`DisposableEffect(Unit)` at `MainActivity.kt:182-185` with:

```kotlin
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // The mic is only open while the shopkeeper can actually see the app.
                // Backgrounding must release it -- a permanently-hot mic is both a battery
                // cost and, correctly, something users do not expect.
                Lifecycle.Event.ON_START -> sharedRollingBuffer.startRollingBuffer()
                Lifecycle.Event.ON_STOP -> sharedRollingBuffer.stopRollingBuffer()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sharedRollingBuffer.stopRollingBuffer()
        }
    }
```

Imports: `androidx.compose.ui.platform.LocalLifecycleOwner`, `androidx.lifecycle.Lifecycle`,
`androidx.lifecycle.LifecycleEventObserver`.

**Keep `AppForegroundService` running.** It still does periodic sync and keeps
`UpiNotificationListenerService` viable — neither needs the microphone. But its
notification currently reads *"Listening for UPI payments & sync active"*
(`AppForegroundService.kt:31`), which now overstates things. Change `setContentText` to
`"UPI payments & sync active"`, and confirm the service is **not** declared with
`android:foregroundServiceType="microphone"` in `AndroidManifest.xml` — if it is, remove
that type, since the mic no longer runs in the background and Play Store review treats
that declaration as a claim about behaviour.

Verify `startRollingBuffer()` is idempotent under this new call pattern — it early-returns
on `isRecordingRunning.get()` (`RollingAudioBuffer.kt:32`), which looks correct, but it is
now called on every foreground transition rather than once per process, so confirm
`stopRollingBuffer()` fully tears the capture thread down before the next `ON_START`.

---

## §6 — Audit log

Add to `Docs/audit.md` under **🟢 RESOLVED ISSUES**, next sequential numbers (highest is
currently ISSUE-043 — check before writing):

- **ISSUE-044** — Diagnostic traces empty on every job. Two causes: `pollForCompletion`
  discarded the polled trace (a defect in the correction plan's own specified signature,
  implemented as written), and `CloudSyncManager.postTraceLogToSupabaseDatabase`
  overwrote the server's populated trace with `{}`. Cite job `249db598-…`
  (`trace_len = 2`) as the concrete example.
- **ISSUE-045** — Assistant took ~41 s to answer. Cite the timing table at the top of this
  plan. Resolution: inline server response (§2), on-device fast path (§3.1), in-memory
  snapshot (§3.2), offline TTS (§3.3).
- **ISSUE-046** — Microphone stayed open after the app was backgrounded.
- **ISSUE-047** — On-device STT never returned a result on any job in the history of the
  app; mic contention with the always-on `RollingAudioBuffer`.

Update **§1 Ground-Truth Source-Code Verified Constants** with the new poll budget
(20 s / 750 ms) and the server inline budget (20 s).

Add a 🔴 **OPEN** entry for the vanished stock-in job (14:08:56, two `202`s, zero rows) —
it is instrumented by §1.3 and §2.3 but **not diagnosed**. Do not write it up as resolved.

---

## §7 — Verification

Unit/build:

```bash
./gradlew test
```

```bash
deno test supabase/functions/process-voice-job/
```

Deploy `process-voice-job`, then re-fetch the live bundle and grep for `INLINE_BUDGET_MS`
to confirm §2.1 actually shipped.

On device — **measure, do not eyeball**:

1. Assistant: ask `"आज कितना कमाया"`. Time press-release → first spoken word.
   **Target < 1.5 s.** Report the actual number.
2. Assistant: ask the same thing four different ways (§3.4) — all four must answer.
3. Sale: `"पाँच किलो आलू"` on नकद. Time release → card appears. **Target < 6 s.**
4. Open the log for every one of the above: the JSON box must show a `client` block
   **and** a `server` block. No "No detailed JSON trace available".
5. Stock: `"पचास किलो आलू आया"` → `stock_in` row, stock goes **up**, no review card.
6. Background mic: press Home, then check the notification shade / mic-access indicator —
   the green mic dot must disappear within a second. Reopen the app and confirm the next
   recording still captures its leading word (pre-roll survived the restart).
7. Airplane mode: press the assistant and ask a question. It must still answer from the
   local snapshot — that is the real proof §3 is not secretly hitting the network.

Then re-run the outstanding on-device checklist from `Docs/agent_stockin_logs_fix_plan.md`
§5.3, which has still never been completed.

---

## Open questions

1. **§3.1 scope.** The fast path skips saving audio for assistant read queries. If audio
   for questions turns out to matter (for later debugging of mishearings), say so and it
   becomes a `SpeechRecognizer` + parallel `AudioRecord` problem, which is materially
   harder — stop and ask rather than half-implementing it.
2. **§2.1 error semantics.** Confirm whether `processVoiceJob` can reject, and what
   `stt_job_logs` holds when it does, before wiring the `Promise.race`. A 500 here would
   turn into a silent WorkManager retry loop.
3. **§3.2 column names.** `isVoided` and the exact transaction timestamp column are
   assumed from `TransactionRecord.kt` — verify against the entity, and if they differ,
   use the real names and note it.

## Deviations

End with a **Deviations** heading listing anything changed, skipped, or interpreted
differently from the literal text above, and why. If none, write "None."
