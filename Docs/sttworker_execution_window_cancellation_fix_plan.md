# SttWorker Execution-Window Cancellation Fix Plan
## Candidate ISSUE-080 — "Job was cancelled" entries where the server actually succeeded

**Written**: 2026-08-05 (Claude Code) · **For**: Antigravity · **Severity**: P1 — shopkeeper sees "Job was cancelled" / FAILED / "Unrecognized Item" in the diagnostic log for recordings the server transcribed and parsed correctly; WorkManager then silently re-uploads the same audio a second time.

---

## 0. Why this is a new bug, not a re-run of ISSUE-079

`Docs/audit.md` ISSUE-079 (closed today, shipped in `VoiceToInvoice_v106.apk`, built **00:41:15**) wrapped `SttWorker.doWork()`'s cancellation-handling DB write in `withContext(NonCancellable + Dispatchers.IO)` so a cancelled job's status gets safely persisted as `FAILED` instead of stuck forever at `QUEUED`/`TRANSCRIBING`. That part is verified working (see §1 below — the FAILED write now succeeds where the pre-fix logcat shows it throwing a second `JobCancellationException`).

**But the screenshot the user reported was captured after v106 was already built and (per `Docs/audit.md` L93) installed via ADB** — the failing jobs' `created_at` timestamps (00:42:43–00:42:49 IST) are ~90 seconds *after* the v106 build finished. ISSUE-079 fixed how a cancellation is *recorded*; it did not fix *why the cancellation happens*, and — this is the new finding — it did not make the client check whether the server had already finished the job correctly before discarding the result. That gap is what this plan fixes. Per the global reporting rule: this is a **new instance in the same bug class** (WorkManager/coroutine cancellation of `SttWorker`), not a regression of ISSUE-079's own fix.

---

## 1. Root cause — verified against live data

**Verified** (queried `stt_job_logs` directly, project `lyowklxsbfznnqridtgr`, and cross-checked against `scratch/logcat_repro.txt`):

Three jobs were created within 13 seconds, all originating from the phone at IST 00:42:43–00:42:49 on 2026-08-05:

| job_id | intent | client `upload_ms` | outcome | server had a real result? |
|---|---|---|---|---|
| `bde47c16-…` | SALE | (normal, no cancellation) | `PARSED`, real trace (3211 bytes) | yes — clean |
| `bda78c1e-…` attempt 0 | ASSISTANT | **9813 ms** | `exception` / `JobCancellationException: Job was cancelled` | **yes** — `upload_response_null:false`, `initial_status:"PARSED"` was already read out of the response before the coroutine died |
| `bda78c1e-…` attempt 1 (same job_id, 30.0s later) | ASSISTANT | 765 ms | `assistant_answered` / "समझ नहीं आया" (blank/unrecognized) | re-uploaded the *same* audio file (`voice_record_4581510671390953221.wav`) a second time |
| `4916df13-…` | SALE | **10216 ms** | `exception` / `JobCancellationException: Job was cancelled` | **yes** — same pattern, `initial_status:"PARSED"` already parsed out |

The `upload_ms` field is stopped the instant `uploadAudioToEdgeFunction()` returns (`SttWorker.kt:137`, before any JSON parsing) — so in both cancelled cases the HTTP round-trip to `process-voice-job` had **already completed** (9.8s and 10.2s respectively) and the response body had already been read into `responseString`. The cancellation struck a moment later, inside the client's own post-response processing (JSON parse → `CommitSequencer.runInOrder` → local DB write), not inside the network call itself.

**Disconfirming check run**: is this a client-side timeout constant? Grepped the whole `app/` module for `10000L`/`10_000`/`withTimeout` around this path — none exists in `SttWorker.kt` or anything it calls. `connectTimeout=30000`/`readTimeout=60000` on the `HttpURLConnection` don't match either. `SttWorker` never calls `setForeground()`/overrides `getForegroundInfo()` (grepped, zero matches) — it relies entirely on `.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)` (`PttMicButton.kt:274`, `HomeScreen.kt:661`, `BackgroundSttProcessor.kt:42`). That means: when Android's per-app expedited-job quota is exhausted — plausible here, since three presses fired within 13 seconds — the request **silently downgrades to an ordinary background job with no foreground-service backing**. `scratch/logcat_repro.txt` (an earlier repro of the *same* symptom, captured **before** this session's v106 build) shows the exact mechanism directly:
```
00:24:32.241 D/GreezeManager: cancelJobTimeout pkg=com.voicetoinvoice.app uid=10614
00:24:32.250 I/WM-WorkerWrapper: Work [...SttWorker...] was cancelled
00:24:32.567 E/SttWorker: kotlinx.coroutines.JobCancellationException: Job was cancelled
```
`GreezeManager` is MIUI's background-process freezer; it stopped the WorkManager job directly, ~10 seconds after start, matching the 9.8s/10.2s durations in the fresh Supabase evidence almost exactly. **This is inferred, not proven to the exact millisecond** — I have three data points (one from logcat, two from the DB) all landing in a ~9.8–10.2s band, and a ruled-out alternative (no client timeout code exists), but I have not independently confirmed MIUI's specific threshold value. Flagged as an open question in §4.

**The actual bug**: because the client cancels itself (via the OS) *after* the server has already answered, and the exception handler (ISSUE-079's own fix) unconditionally writes `status = FAILED` / `errorMessage` without checking whether a usable result already exists, a job the server processed correctly gets reported to the shopkeeper as "Job was cancelled" / "Unrecognized Item". WorkManager then separately auto-retries the *same* job ~30 seconds later (this is WorkManager's own policy for work stopped by the system, not `Result.retry()` from our code — `SttWorker.kt:270-272` explicitly re-throws `CancellationException` rather than returning `Result.retry()`), re-uploading the identical audio and burning a second STT/LLM round-trip — which on `bda78c1e`'s retry landed on a worse (blank) transcription than the first attempt already had.

---

## 2. What this plan does NOT establish (flagged, not assumed)

- The exact OS/MIUI execution-window threshold (looks like ~10s, not independently confirmed against MIUI documentation).
- Whether the *first* (cancelled) attempt of `commitParsedLines()`/`CommitSequencer.runInOrder` had already written to `transactions`/`stock_in`/`unmatched_queue` before being cut off, which combined with the retry's second pass could produce a **double-committed sale**. I did not find evidence of a duplicate `transactions` row in this session's queries, but I also did not specifically query for one — Step 2 below closes this risk regardless of the answer, so I did not chase it further. **Antigravity should run the duplicate-commit check in §5 before considering this closed.**

---

## 3. Fix steps

### Step 1 — Reconcile with server truth before declaring cancellation-failure

**File**: `app/src/main/java/com/voicetoinvoice/app/domain/processor/SttWorker.kt`, catch block at lines 251–274.

Today, any exception (including a post-response `CancellationException`) unconditionally writes `status = FAILED`. Change it to first check whether `responseString` was already non-null (i.e., the server had already answered) before giving up:

```kotlin
} catch (e: Exception) {
    Log.e(TAG, "SttWorker network execution failed", e)
    clientTrace.put("outcome", "exception")
    clientTrace.put("exception_class", e.javaClass.simpleName)
    clientTrace.put("exception_message", e.message ?: "")
    try {
        withContext(NonCancellable + Dispatchers.IO) {
            // The server may have already finished this job even though our own
            // coroutine was cancelled afterward (verified 2026-08-05: two jobs showed
            // upload_ms ~10s with a fully-read response, then died in local post-processing —
            // see Docs/sttworker_execution_window_cancellation_fix_plan.md §1). Check
            // stt_job_logs for a completed row for this job_id before writing FAILED.
            val serverResult = reconcileWithServerTrace(jobId)
            if (serverResult != null) {
                clientTrace.put("outcome", "recovered_from_server_after_cancellation")
                // reuse the SAME commit path the happy case uses, so recovered jobs
                // go through commitParsedLines/CommitSequencer exactly like a normal run
                val audioCloudUrl = "${SupabaseConfig.SUPABASE_URL}/storage/v1/object/public/voice-recordings/$jobId.wav"
                val committedCount = com.voicetoinvoice.app.domain.processor.CommitSequencer.runInOrder(
                    db, jobRecord.recordedAtMs, clientTrace
                ) {
                    commitParsedLines(
                        jobRecord = jobRecord,
                        effectiveIntent = jobRecord.captureIntent,
                        parsedItems = serverResult.parsedItems,
                        catalog = catalog,
                        rawTranscript = serverResult.rawTranscript,
                        audioPath = audioPath,
                        audioCloudUrl = audioCloudUrl,
                        jobId = jobId
                    )
                }
                val firstItem = if (serverResult.parsedItems.length() > 0) serverResult.parsedItems.getJSONObject(0) else null
                val updatedStatus = when (serverResult.status) {
                    "AUTO_CONFIRMED" -> SttJobStatus.AUTO_CONFIRMED
                    "PARTIALLY_CONFIRMED" -> SttJobStatus.PARTIALLY_CONFIRMED
                    "RATE_UPDATED" -> SttJobStatus.RATE_UPDATED
                    else -> SttJobStatus.PARSED
                }
                db.sttJobDao().updateJob(
                    jobRecord.copy(
                        status = updatedStatus,
                        rawTranscript = serverResult.rawTranscript,
                        parsedItemName = firstItem?.optString("item_name") ?: "Unrecognized Item",
                        parsedQty = firstItem?.optDouble("quantity") ?: 1.0,
                        parsedUnit = firstItem?.optString("unit") ?: "PACKET",
                        parsedTotal = firstItem?.optDouble("total") ?: 0.0,
                        isSanityFlagged = updatedStatus != SttJobStatus.AUTO_CONFIRMED && updatedStatus != SttJobStatus.RATE_UPDATED,
                        diagnosticTraceJson = mergeClientTrace(clientTrace, serverResult.traceJson),
                        parsedItemsJson = serverResult.parsedItems.toString(),
                        lineCount = serverResult.parsedItems.length(),
                        committedCount = committedCount,
                        synced = true
                    )
                )
            } else {
                db.sttJobDao().updateJob(
                    jobRecord.copy(
                        status = SttJobStatus.FAILED,
                        errorMessage = e.message ?: "Worker execution failed",
                        isSanityFlagged = true,
                        diagnosticTraceJson = mergeClientTrace(clientTrace, null)
                    )
                )
            }
        }
    } catch (persistErr: Exception) {
        Log.e(TAG, "Failed to persist client trace after exception", persistErr)
    }
    if (e is kotlinx.coroutines.CancellationException) {
        throw e
    }
    return@withContext Result.retry()
}
```

Add a small helper (reuses the exact same REST query `pollForCompletion` already issues at `SttWorker.kt:698`, single lookup, no polling loop needed since we just want the current state):

```kotlin
/** Called from the cancellation-exception path only: checks whether the server had
 *  already finished this job by the time our own coroutine was cut off (verified
 *  2026-08-05 that this happens — see the fix plan). Single GET, no retry loop. */
private suspend fun reconcileWithServerTrace(jobId: String): PolledResult? {
    val url = "${SupabaseConfig.SUPABASE_URL}/rest/v1/stt_job_logs?job_id=eq.$jobId&select=status,raw_transcript,parsed_item_name,parsed_qty,parsed_unit,parsed_total,diagnostic_trace_json"
    return try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Authorization", "Bearer ${SupabaseConfig.SUPABASE_ANON_KEY}")
            setRequestProperty("apikey", SupabaseConfig.SUPABASE_ANON_KEY)
        }
        if (conn.responseCode !in 200..299) return null
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val arr = JSONArray(body)
        if (arr.length() == 0) return null
        val row = arr.getJSONObject(0)
        val status = row.optString("status", "QUEUED")
        if (status == "QUEUED") return null
        val traceJson = row.optString("diagnostic_trace_json", "")
        var parsedItems = JSONArray()
        if (traceJson.isNotBlank()) {
            try {
                val traceObj = JSONObject(traceJson)
                if (traceObj.has("step_4_grok_ai_interpretation")) {
                    parsedItems = traceObj.getJSONArray("step_4_grok_ai_interpretation")
                }
            } catch (_: Exception) {}
        }
        if (parsedItems.length() == 0 && row.has("parsed_item_name") && row.optString("parsed_item_name").isNotBlank()) {
            val fallbackQty = row.optDouble("parsed_qty", 1.0)
            val fallbackTotal = row.optDouble("parsed_total", 0.0)
            parsedItems = JSONArray().put(JSONObject().apply {
                put("item_name", row.optString("parsed_item_name"))
                put("quantity", fallbackQty)
                put("unit", row.optString("parsed_unit", "PACKET"))
                put("price_at_sale", if (fallbackQty > 0) fallbackTotal / fallbackQty else fallbackTotal)
                put("total", fallbackTotal)
                put("price_intent", "NONE")
                put("confidence", if (status == "AUTO_CONFIRMED") 1.0 else 0.0)
            })
        }
        PolledResult(status, row.optString("raw_transcript", ""), traceJson, parsedItems)
    } catch (_: Exception) {
        null
    }
}
```

**Why this closes the bug**: it directly targets the contradiction found in §1 — server says `PARSED`/completed, client says `FAILED`/cancelled. After this change, the client asks the server for the truth before giving up, exactly the discipline this repo's own `CLAUDE.md` mandates for diagnosis, now applied at runtime.

### Step 2 — Stop the downgrade-to-ordinary-background-job path that lets MIUI kill mid-flight work

**File**: `app/src/main/java/com/voicetoinvoice/app/domain/processor/SttWorker.kt`

Add a `getForegroundInfo()` override and call `setForeground()` at the top of `doWork()` (before the network call), so the worker runs as a real foreground-service-backed expedited job rather than silently downgrading when quota is exhausted:

```kotlin
override suspend fun getForegroundInfo(): ForegroundInfo {
    val channelId = com.voicetoinvoice.app.service.AppForegroundService.CHANNEL_ID
    val notification = NotificationCompat.Builder(applicationContext, channelId)
        .setContentTitle("आवाज़ प्रोसेस हो रही है...")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ForegroundInfo(2001, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
        ForegroundInfo(2001, notification)
    }
}
```

Call `setForeground(getForegroundInfo())` as the first line inside the `withContext(Dispatchers.IO) { ... }` block in `doWork()` (right after the `Log.i(TAG, "Starting Expedited SttWorker...")` line, `SttWorker.kt:58`), wrapped in try/catch since `setForeground()` can itself throw if the OS refuses (e.g. background restriction) — on failure, log and continue rather than crash the worker.

Needed imports: `androidx.work.ForegroundInfo`, `androidx.core.app.NotificationCompat`, `android.os.Build`.

**Manifest note (verify, don't assume)**: `AndroidManifest.xml` already declares `android:permission.FOREGROUND_SERVICE_DATA_SYNC` and `AppForegroundService` with `android:foregroundServiceType="dataSync"` (lines 10, 40). WorkManager's own bundled `SystemForegroundService` (used internally by `setForeground()`) picks up the type from the `ForegroundInfo` passed at runtime on API 29+; no manifest entry for it currently exists. **If the build fails with a missing foreground-service-type manifest error, stop and report — do not silently add a workaround**, since targetSdk 36 (Android 15) enforcement of this is stricter than the plan author has verified against this exact library version.

### Step 3 — Open question, do not implement without asking

WorkManager auto-retries a system-stopped job ~30s later regardless of app code (confirmed in §1: `bda78c1e`'s attempt 1 fired at exactly +29.9s with the same job_id). Steps 1–2 make the retry far less likely to be needed (Step 2) and harmless if it does happen because Step 1 will find the server's already-completed result on the *first* cancellation and never reach a second attempt in the common case. **Do not add code to suppress WorkManager's retry** (e.g., returning `Result.failure()` instead of re-throwing) — that would turn a recoverable system-stopped job into a permanently abandoned one for the cases Step 1 *doesn't* catch (e.g., the server itself hadn't finished yet). Leave WorkManager's default retry behavior as-is.

---

## 4. Open questions for Antigravity

1. The exact MIUI/OS execution-window threshold is inferred (~10s) from 3 data points, not confirmed against documentation. If Step 2 doesn't fully eliminate repeat cancellations after implementation, capture 2-3 fresh `adb logcat | grep -E "GreezeManager|WM-WorkerWrapper|SttWorker"` traces during a deliberate rapid-press repro and report the exact gap between worker start and cancellation — don't assume Step 2 alone closes the class.
2. If `setForeground()`'s `ForegroundInfo(...)` constructor signature or `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` doesn't exist/compile against this project's `androidx.work` version, stop and report the actual compile error rather than guessing a replacement API.

---

## 5. Verification (mandatory before closing as an ISSUE-0NN entry)

1. Build, install, and reproduce: fire 3 rapid PTT presses (SALE → ASSISTANT → SALE) within ~15 seconds, same as the reported case, saying a distinct recognizable phrase each time (not the same phrase 3x, so results are easy to distinguish).
2. Query `stt_job_logs` for the 3 new job_ids and confirm:
   - No `JobCancellationException` client outcome appears in the final persisted local Room state (check via `DiagnosticLogsScreen` in-app, not just Supabase) for any job whose server-side row shows a completed status — i.e., §1's contradiction (server `PARSED`, client `FAILED`) no longer occurs.
   - If a cancellation still occurs, confirm `outcome:"recovered_from_server_after_cancellation"` appears in the client trace and the job's final status/parsed items match the server's row exactly.
3. **Duplicate-commit check (flagged in §2 as unresolved)**: for any job that hit the cancellation path pre-fix, query `transactions`/`stock_in`/`unmatched_queue` for that `jobId` and confirm exactly one row exists, not two, after Step 1 lands.
4. Paste the actual before/after `stt_job_logs` rows (not just "BUILD SUCCESSFUL") in the Deviations section and in the `Docs/audit.md` entry — per this repo's global rule, a build/install log is not verification of the fix.
5. Log as `Docs/audit.md` ISSUE-080 (confirm 079 is still the highest number before assigning), cross-referencing this plan and noting explicitly that ISSUE-079 fixed the *persistence* half of this bug class and this entry fixes the *reconciliation* half.

---

## Deviations section (for Antigravity to fill in after implementation)

_None yet._
