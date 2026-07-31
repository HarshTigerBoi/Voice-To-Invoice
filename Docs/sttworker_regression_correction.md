# Correction — SttWorker regression from the ISSUE-041/042/043 implementation pass

**Date:** 2026-07-30
**Planner:** Claude Code · **Implementer:** Antigravity
**Status:** BLOCKING. Do not consider `Docs/agent_stockin_logs_fix_plan.md` complete until this lands.

`Docs/agent_stockin_logs_fix_plan.md` (§1.2–§1.3) asked for two narrow changes to
`SttWorker.kt`: move the `CaptureIntent.ASSISTANT` branch to run *after* the server
round-trip, and extract the per-line commit loop into a shared `commitParsedLines`
helper. The plan was explicit that "**Behaviour for non-assistant mics must be
byte-for-byte identical** — this is a pure extraction apart from §2.2."

The actual diff went further: it deleted the `stt_job_logs` polling loop and rewrote
the response parsing to read fields that don't exist. This breaks sale booking on
**every mic**, not just the assistant. Ship the fix in this doc before doing anything
else with this build.

---

## The bug

`process-voice-job` is asynchronous. For a brand-new job it always responds
immediately with:

```json
{"status":"QUEUED","job_id":"...","audio_cloud_url":"...","cached":false,
 "message":"Audio received and stored. Processing in background — poll or subscribe to stt_job_logs for this job_id."}
```

(`supabase/functions/process-voice-job/index.ts:767-777`). The actual transcript and
parsed items are computed afterward via `EdgeRuntime.waitUntil(processVoiceJob(...))`
and written to the `stt_job_logs` table — never returned in this response. The only
response that *does* carry `raw_transcript`/`parsed_items`/`diagnostic_trace_json`
directly is the "already processed" cache-hit branch (`index.ts:694-704`), and those
keys are **snake_case** there too.

Current `SttWorker.kt:112-116`:

```kotlin
            if (responseString != null) {
                val jsonRes = JSONObject(responseString)
                val statusStr = jsonRes.optString("status", "PARSED")
                val rawTranscript = jsonRes.optString("rawTranscript", "")
                val traceJson = jsonRes.optString("diagnosticTraceJson", "")
```

Two independent breakages stacked here:

1. **No polling.** For the QUEUED response, `statusStr` is genuinely `"QUEUED"` — that's
   correct — but nothing polls for the real result the way the pre-existing code did.
   `statusStr == "QUEUED"` falls through the `when` at line 159-164 straight to `else ->
   SttJobStatus.PARSED`.
2. **Wrong key casing.** `"rawTranscript"` / `"diagnosticTraceJson"` / `"parsedItems"`
   are never present in *any* server response — the server only ever emits
   `raw_transcript` / `diagnostic_trace_json` / `parsed_items`. Even the cache-hit path
   would return blanks here.

Net result: `rawTranscript = ""`, `parsedItems = []` on every real recording.
`commitParsedLines` then hits its `lineCount == 0` branch (`SttWorker.kt:310-321`) and
writes an empty-transcript row to `unmatched_queue`, the job is saved as `PARSED` with
`rawTranscript = ""`, and `Result.success()` is returned so WorkManager never retries.//
**No voice sale on any mic (नकद, उधार, माल आया, खराब, or the assistant) can book.**
Confirmed by reading the full current file and grepping the edge function for any
camelCase response key — there is none.

---

## The fix

### 1. `app/src/main/java/com/voicetoinvoice/app/domain/processor/SttWorker.kt`

Keep `uploadAudioToEdgeFunction` exactly as it is now (the extraction itself was fine —
only the caller's handling of its result is broken). Add a new private method that
restores the original polling behavior, then change the call site in `doWork()` to use it.

**Add** this method (place it next to `uploadAudioToEdgeFunction`):

```kotlin
    /** process-voice-job answers 202/QUEUED immediately and finishes the real
     *  transcribe+parse in the background (EdgeRuntime.waitUntil) -- the initial HTTP
     *  response never carries raw_transcript/parsed_items for a new job. This polls
     *  stt_job_logs (the row the background task writes) until status leaves QUEUED,
     *  for up to 30s. Restores the polling this file had before the SttWorker refactor
     *  accidentally dropped it (see Docs/sttworker_regression_correction.md). */
    private suspend fun pollForCompletion(jobId: String): Triple<String, String, JSONArray> {
        var traceJson = ""
        var rawTranscript = ""
        var parsedItems = JSONArray()
        var statusStr = "QUEUED"

        val pollUrl = "${SupabaseConfig.SUPABASE_URL}/rest/v1/stt_job_logs?job_id=eq.$jobId&select=status,raw_transcript,parsed_item_name,parsed_qty,parsed_unit,parsed_total,diagnostic_trace_json"
        val maxPollMs = 30000L
        val pollIntervalMs = 2000L
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < maxPollMs) {
            kotlinx.coroutines.delay(pollIntervalMs)
            try {
                val pollConn = (URL(pollUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                    setRequestProperty("Authorization", "Bearer ${SupabaseConfig.SUPABASE_ANON_KEY}")
                    setRequestProperty("apikey", SupabaseConfig.SUPABASE_ANON_KEY)
                }
                if (pollConn.responseCode in 200..299) {
                    val pollBody = pollConn.inputStream.bufferedReader().use { it.readText() }
                    val pollArr = JSONArray(pollBody)
                    if (pollArr.length() > 0) {
                        val row = pollArr.getJSONObject(0)
                        val polledStatus = row.optString("status", "QUEUED")
                        if (polledStatus != "QUEUED") {
                            statusStr = polledStatus
                            rawTranscript = row.optString("raw_transcript", rawTranscript)
                            traceJson = row.optString("diagnostic_trace_json", traceJson)

                            if (traceJson.isNotBlank()) {
                                try {
                                    val traceObj = JSONObject(traceJson)
                                    if (traceObj.has("step_4_grok_ai_interpretation")) {
                                        parsedItems = traceObj.getJSONArray("step_4_grok_ai_interpretation")
                                    }
                                } catch (_: Exception) {}
                            }

                            if (parsedItems.length() == 0 && row.has("parsed_item_name")) {
                                val fallbackQty = row.optDouble("parsed_qty", 1.0)
                                val fallbackTotal = row.optDouble("parsed_total", 0.0)
                                val singleItem = JSONObject().apply {
                                    put("item_name", row.optString("parsed_item_name", "Unrecognized Item"))
                                    put("quantity", fallbackQty)
                                    put("unit", row.optString("parsed_unit", "PACKET"))
                                    put("price_at_sale", if (fallbackQty > 0) fallbackTotal / fallbackQty else fallbackTotal)
                                    put("total", fallbackTotal)
                                    put("price_intent", "NONE")
                                    put("confidence", if (statusStr == "AUTO_CONFIRMED") 1.0 else 0.0)
                                }
                                parsedItems = JSONArray().put(singleItem)
                            }

                            Log.d(TAG, "Polled final resolution for job $jobId: status=$statusStr, items=${parsedItems.length()}")
                            break
                        }
                    }
                }
            } catch (pollErr: Exception) {
                Log.w(TAG, "Polling exception for job $jobId: ${pollErr.message}")
            }
        }

        return Triple(statusStr, rawTranscript, traceJson).let { Triple(it.first, it.second, parsedItems) }
    }
```

(The `Triple(...).let { Triple(...) }` above is just to keep the return type
`Triple<String, String, JSONArray>` — feel free to use a small data class instead if
that reads cleaner; either is fine as long as the three values come back together.)

**Replace** `SttWorker.kt:112-140` (the whole `if (responseString != null) { val jsonRes
= ... }` block through the `handleAssistantJob` early-return) with:

```kotlin
            if (responseString != null) {
                val jsonRes = JSONObject(responseString)
                var statusStr = jsonRes.optString("status", "QUEUED")
                var rawTranscript = jsonRes.optString("raw_transcript", "")
                var traceJson = jsonRes.optString("diagnostic_trace_json", "")
                var parsedItems = jsonRes.optJSONArray("parsed_items") ?: JSONArray()

                if (statusStr == "QUEUED") {
                    val (polledStatus, polledTranscript, polledItems) = pollForCompletion(jobId)
                    statusStr = polledStatus
                    if (polledTranscript.isNotBlank()) rawTranscript = polledTranscript
                    if (polledItems.length() > 0) parsedItems = polledItems
                    // traceJson can only come from the poll for a genuinely new job --
                    // the initial 202 response never carries one.
                    val (_, _, _) = Triple(polledStatus, polledTranscript, polledItems) // no-op, keeps destructuring readable
                }

                // Recover items from diagnostic_trace_json when the server responded via
                // its "already processed" cache path -- that response has parsed_items
                // at top level already in most cases, but fall back to the trace if not.
                if (parsedItems.length() == 0 && traceJson.isNotBlank()) {
                    try {
                        val traceObj = JSONObject(traceJson)
                        if (traceObj.has("step_4_grok_ai_interpretation")) {
                            val extracted = traceObj.getJSONArray("step_4_grok_ai_interpretation")
                            if (extracted.length() > 0) parsedItems = extracted
                        }
                    } catch (_: Exception) {}
                }

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

*(Drop the stray no-op `Triple` destructuring line above if it bothers you — it's not
load-bearing, just remove it; it was only there to avoid an "unused variable" warning
pattern. Simplify freely as long as `statusStr`/`rawTranscript`/`parsedItems` end up
holding the polled values.)*

Also **fix the two other snake_case reads** further down that the rewrite already got
right by luck (double check, don't just trust this note — grep the file): line ~170-173
already reads `item_name`/`quantity`/`unit`/`total` from `firstItem`, which is correct
because those are per-item keys inside `parsed_items`, not top-level response keys —
leave those alone.

### 2. `gradle.properties`

Remove the machine-specific absolute path — it will break the build for anyone else
(including CI, if this repo ever gets one) whose Android Studio isn't installed at that
exact location:

```diff
-org.gradle.java.home=C:/Program Files/Android/Android Studio/jbr
-org.gradle.jvmargs=-Xmx768m -Xss256k -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8
-org.gradle.parallel=false
-kotlin.incremental=false
+org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8
```

`-Xmx1536m` is a compromise: the original `2048m` reportedly OOM'd on this machine
during the session (per the build log history), but `768m` is unusually low for an AGP
build with KSP. If `1536m` still OOMs on this machine, set `JAVA_HOME` and any extra
memory headroom as an **environment variable** when invoking Gradle locally (or in a
`gradle.properties` under `%USERPROFILE%\.gradle\`, which is machine-local and never
committed) — not in this file. Leave `org.gradle.parallel` and `kotlin.incremental` at
their defaults (i.e. delete those two lines) unless a real, reproduced build failure
requires disabling them; both exist in the committed file only as OOM workarounds from
this session's local troubleshooting, not as intentional project settings.

### 3. APK export

The build was exported as `VoiceToInvoice_v28.apk`, overwriting whatever used to be at
that filename. The folder's actual highest version was `v86` before this session. After
rebuilding with the fix above:

```bash
cp app/build/outputs/apk/debug/app-debug.apk "C:/Users/harsh/OneDrive/Desktop/VoiceToInvoice_APKs/VoiceToInvoice_v87.apk"
```

Leave `VoiceToInvoice_v28.apk` in place (don't delete it — it may have overwritten a
real historical build; if the user needs it back they'll have to say so, it's not this
fix's job to guess).

### 4. `Docs/audit.md`

Add one more entry under **🟢 RESOLVED ISSUES**, next sequential number (044 as of this
writing — check the file for the actual current highest number first), documenting this
regression and its fix in the standard Symptom/Root Cause/Resolution/Verification Date
format. Be explicit in the Symptom that this was self-inflicted during the ISSUE-041/042
pass, not something the user hit in the field — the polling loop was deleted mid-refactor
and never exercised against a real 202/QUEUED response before being marked verified.

---

## Verification (do not skip — the previous pass's "assembleDebug succeeds" was not
## sufficient evidence, which is exactly how this regression shipped unnoticed)

1. `./gradlew assembleDebug` — clean compile is necessary but **proves nothing about
   this bug**, since the broken version also compiled cleanly.
2. Install on device. Record a real sale on the नकद mic (e.g. "पाँच किलो आलू, दो सौ रुपये").
3. Open Voice & System Processing Logs. Confirm:
   - `rawTranscript` is the actual spoken sentence, not blank.
   - Status is `AUTO_CONFIRMED` (or `PARTIALLY_CONFIRMED`/`PARSED` if the parse was
     genuinely weak — but never blank-transcript `PARSED` for a clearly-spoken sale).
4. Check Home screen — the sale total should reflect the new sale within ~30s (allow for
   the poll interval) without needing to force-close/reopen the app.
5. Repeat once for the assistant mic asking a question, and once for माल आया — confirm
   both also show real transcripts, not blanks.
6. Only after all five checks pass, treat `Docs/agent_stockin_logs_fix_plan.md` as done
   and proceed to that plan's own §5.3 on-device checklist for the other four fixes.

## Deviations section

As with the original plan: end with a **Deviations** heading listing anything changed,
skipped, or interpreted differently from the literal text above, and why. If none, write
"None."
