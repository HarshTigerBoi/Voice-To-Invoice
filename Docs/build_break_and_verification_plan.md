# Build Break + End-to-End Verification Plan

**Written**: 2026-07-31 (Claude Code) · **For**: Antigravity · **Severity**: P0

## The actual situation

I compiled the project just now. **It does not build.** `./gradlew.bat compileDebugKotlin` fails with 17 unresolved-reference errors. That is the entire explanation for "nothing works at all, sales blocked, assistant blocked, no processing" — whatever APK is on the phone right now cannot be the code you think it is, because that code has never successfully compiled. Either the phone is running a stale build from before this round of changes, or a broken debug build got sideloaded some other way. Either way, **step zero is: get to a green build**, because nothing else can be meaningfully tested until then.

I also found and fixed one live infrastructure gap while investigating (see §3) — that one's already done, not part of Antigravity's work.

Everything below is mechanical. I've pulled the exact current (broken) code and the exact correct signatures it needs to call. There should be no ambiguity in any of these fixes; if you hit one, stop and ask rather than guessing, because the last three rounds of "should be a small fix" are what got the app into this state.

---

## 1. Fix the 17 compile errors

Run this first to see the current list and confirm you're looking at the same errors:
```bash
./gradlew.bat --stop
```
```bash
./gradlew.bat compileDebugKotlin --console=plain
```
If it fails with `Failed to delete ...merged_res_blame_folder...`, that's the known OneDrive file-lock quirk (CLAUDE.md), not a code problem — run `./gradlew.bat --stop` again and delete `%LOCALAPPDATA%\VoiceToInvoiceBuild\app\intermediates\merged_res_blame_folder`, then retry.

### 1.1 — `PttMicButton.kt` calls a `PttBurstCoalescer` API that doesn't exist (13 of the 17 errors)

The assistant-mic branch (added to make the assistant fall back to audio capture when on-device STT returns blank) calls:
```kotlin
pttBurstCoalescer.onPressReleased(
    pressStartMs = assistantPressTs,
    releaseMs = assistantReleaseTs,
    onGroupReady = { burstGroup -> ... }
)
```
**`onPressReleased` does not exist on `PttBurstCoalescer`.** The class's real API (`app/src/main/java/com/voicetoinvoice/app/audio/PttBurstCoalescer.kt`) is:
```kotlin
fun recordPressRelease(pressMs: Long, releaseMs: Long, lastConsumedEndMs: Long = 0L): CoalescedBurstGroup?
fun checkAndFlushIfIdle(lastReleaseMs: Long, nowMs: Long, lastConsumedEndMs: Long): CoalescedBurstGroup?
fun forceFlush(lastConsumedEndMs: Long = 0L): CoalescedBurstGroup?
```
It returns a group directly (nullable), not via a callback — this is the exact pattern already used 60 lines below in the same file for the sales-mic branch (`processGroup` + `recordPressRelease` + a delayed `checkAndFlushIfIdle`). **Copy that pattern, don't invent a new one.**

Also wrong in the same block: `CoalescedBurstGroup` has fields `startMs`, `endMs`, `firstPressMs`, `lastReleaseMs`, `pressCount`, `boundaries`, and a method `utteranceBoundariesJson()` — **not** `isCoalesced` or `boundariesJson` (properties that don't exist). And `SttJobRecord` has no `isCoalescedBurst` or `coalescedPressCount` fields — the real fields are `pressCount: Int` and `utteranceBoundariesJson: String`.

Replace the entire `else` branch (the on-device-blank fallback, currently calling `onPressReleased`) with:
```kotlin
} else {
    val flushed = pttBurstCoalescer.recordPressRelease(
        assistantPressTs, assistantReleaseTs, pttWindowLedger.lastConsumedEndMs()
    )
    val group = flushed ?: pttBurstCoalescer.forceFlush(pttWindowLedger.lastConsumedEndMs())
    if (group != null) {
        val targetFile = File.createTempFile("voice_record_", ".wav", context.cacheDir)
        val extractedAudio = rollingAudioBuffer.extractAudioWindow(
            startMs = group.startMs,
            endMs = group.endMs,
            outputFile = targetFile,
            floorStartMs = group.startMs
        )
        if (extractedAudio != null && extractedAudio.length() > 0) {
            pttWindowLedger.commitWindow(group.startMs, group.endMs)
            val job = SttJobRecord(
                audioFilePath = extractedAudio.absolutePath,
                status = SttJobStatus.QUEUED,
                pressStartMs = group.firstPressMs,
                releaseMs = group.lastReleaseMs,
                audioStartMs = group.startMs,
                audioEndMs = group.endMs,
                utteranceBoundariesJson = group.utteranceBoundariesJson(),
                pressCount = group.pressCount,
                captureIntent = CaptureIntent.ASSISTANT
            )
            db.sttJobDao().insertJob(job)   // see 1.2 -- insertJob, not insert
            val workRequest = OneTimeWorkRequestBuilder<SttWorker>()
                .setInputData(
                    workDataOf(
                        SttWorker.KEY_JOB_ID to job.id,
                        SttWorker.KEY_AUDIO_PATH to extractedAudio.absolutePath
                    )
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }
}
```
Use `forceFlush` unconditionally as the fallback when `recordPressRelease` returns null (a single isolated assistant press, no coalescing needed) — unlike the sales mic, there is no separate idle-flush coroutine here because the assistant press already waited for `tryAwaitRelease()` and the on-device result before reaching this branch, so the group is complete by construction. Don't add a delayed `checkAndFlushIfIdle` call for this branch; it would just be dead code.

**Why `forceFlush` and not just discard on null**: `recordPressRelease` returns null when it *starts* a new group rather than closing one (see its source — it only returns non-null when a gap/span threshold flushes the *previous* group). For a single assistant press this is normally the first press in a fresh group, so it correctly returns null and `forceFlush` immediately closes that same group. Confirm this reads correctly against the source before shipping; if the semantics don't match, ask rather than patch around it.

### 1.2 — `AssistantFastPath.kt:130` calls a DAO method that doesn't exist

```kotlin
val insertedId = db.sttJobDao().insert(job)
```
`SttJobDao` has `suspend fun insertJob(job: SttJobRecord)` — it returns `Unit`, not an id, because `SttJobRecord.id` is already a client-generated UUID (`val id: String = UUID.randomUUID().toString()`, see the entity). Fix:
```kotlin
db.sttJobDao().insertJob(job)
```
and change every use of `insertedId` immediately below it to `job.id`.

### 1.3 — `HomeScreen.kt:233` references an icon that isn't in this project's icon set

```kotlin
Icon(androidx.compose.material.icons.Icons.Default.RateReview, contentDescription = "समीक्षा (Pending Review)")
```
`RateReview` is only in `material-icons-extended`, which is **not** a dependency of this project (checked `app/build.gradle.kts` — only `material3` core icons are available; every other icon in this file — `Receipt`, `Info`, `Keyboard`, `Mic`, `Person`, `CheckCircle`, `Edit` — is from the core set). Do not add the extended-icons dependency for one icon. Use `Icons.Default.List` instead — it's in the core set and reads clearly as "list of things to review":
```kotlin
Icon(Icons.Default.List, contentDescription = "समीक्षा (Pending Review)")
```

### 1.4 — Recompile and confirm zero errors

```bash
./gradlew.bat compileDebugKotlin --console=plain
```
Must show `BUILD SUCCESSFUL` with no `e:` lines. **Do not proceed to §2 until this is true.** If a new unresolved reference appears that isn't listed above, stop and report it rather than guessing a fix — the pattern in this file is "confidently wrong signatures," and another one is plausible.

---

## 2. Verify this is a permanent top-level entry point, not a dead-end sheet

The icon in §1.3 was meant to be the "समीक्षा" fallback the previous plan asked for (a route to the review sheet that doesn't depend on `pendingLineCount > 0`). Confirm `IconButton(onClick = { showPendingSheet = true })` actually opens `PendingConfirmationsSheet` with the full job list (not just jobs with parsed lines) — read `HomeScreen.kt` around where `showPendingSheet` is consumed and confirm the sheet's data source isn't still filtered down to `pendingJobs` (the `parsedItemsJson`-derived flow). If it is, this button opens an empty sheet, which is the same dead end as before with an extra click. Widen it to also include jobs with `status == FAILED` and jobs with `status == PARSED && parsedItemsJson blank`, or add a "सभी हाल की रिकॉर्डिंग" tab, whichever is the smaller change.

---

## 3. Already fixed (server-side, done by Claude Code during diagnosis — nothing for you to do here)

**`ensure_shop` RPC was written as a migration file but never applied to the live database.** `index.ts` (already deployed) calls `supabase.rpc('ensure_shop', ...)` — that call was failing silently (function not found) on every single job, so no shop row was ever provisioned, so every `transactions`/`unmatched_queue`/`stock_in` insert was failing its foreign key, exactly matching the "server runs for 10 seconds, returns 200, writes nothing" symptom from the last diagnosis. I applied the migration directly and verified with a probe insert that it now works end-to-end. This explains why the previous plan's Steps 2/3 (shop-id plumbing, which I also verified are correctly implemented in `SttWorker.kt` and `CloudSyncManager.kt`) didn't fix anything from the outside — the client-side half was done correctly, but the one-line server migration to make it *possible* was never deployed.

**Lesson for this repo, worth internalizing**: this is the second time in two sessions a migration file existed correctly in the repo and simply was never run against the live project (the `customers` table was the first, `ensure_shop` is the second). Writing a migration file is not the same as it existing in production. From now on, **after writing any migration file, apply it immediately** — do not treat "the file is in `supabase/migrations/`" as done.

---

## 4. End-to-end verification (do this after §1 and §2, on the real device)

1. `./gradlew.bat testDebugUnitTest` — must be green.
2. `./gradlew.bat assembleDebug` — must succeed. `ls "C:/Users/harsh/OneDrive/Desktop/VoiceToInvoice_APKs"` first (number drifts), then copy as the next `VoiceToInvoice_v<N>.apk`.
3. Install fresh on the device (uninstall the old one first if there's any doubt about which build is currently on it — better to be certain than to debug a stale install again).
4. Record a plain sale: **"दो किलो आलू"**. Within ~15 seconds, confirm via SQL:
   ```sql
   SELECT job_id, status, raw_transcript, line_count,
          diagnostic_trace_json::jsonb -> 'step_7_persistence' AS persistence
   FROM stt_job_logs ORDER BY created_at DESC LIMIT 1;
   ```
   Expect: non-empty `raw_transcript` containing आलू, `line_count >= 1`, and `persistence.transactions.ok = true` or `persistence.unmatchedQueue.ok = true` (never neither, never both null).
5. Confirm a row actually landed:
   ```sql
   SELECT * FROM transactions ORDER BY timestamp DESC LIMIT 1;
   ```
6. Press the assistant mic and ask a question ("आज कितना बिका"). Confirm you get a spoken answer.
7. Press the assistant mic and say something write-shaped ("पांच किलो प्याज़") — with on-device STT likely blank (per the historical 100%-failure note in `AssistantFastPath.kt`'s doc comment), confirm this now falls through to the §1.1 fallback and produces a real `SttJobRecord` with real audio, rather than the dead "यह बिक्री जैसा लगा" redirect. Confirm the job appears in `stt_job_logs` with a non-186-byte trace.
8. Record something deliberately unintelligible. Confirm it becomes reachable via the new समीक्षा icon (§2) even though it produced zero parsed lines.
9. Report the actual `step_7_persistence` JSON for at least one real sale in your response — not "it worked," the literal JSON. That field exists precisely so this stops being something we have to infer from silence.

---

## 5. Audit log

Add to `Docs/audit.md`, next sequential `ISSUE-NNN` (check the current highest first — it has drifted every single time recently, always `grep` for it, don't trust a remembered number):
- The 17 compile errors as one entry (root cause: three call sites written against APIs that don't exist — `PttBurstCoalescer.onPressReleased`, `SttJobDao.insert`, `Icons.Default.RateReview` — none verified against a successful build before being reported done).
- The `ensure_shop` migration-never-applied gap as its own entry, cross-referencing the `customers`-table precedent (ISSUE-059) as the second occurrence of the same mistake class, and note the process fix ("apply migrations immediately after writing them") in whichever section of this repo's process notes fits best.

**Before marking any future task "done," run `./gradlew.bat compileDebugKotlin` and, for any Supabase migration written, apply it and re-query the live schema to confirm it exists.** Both of today's root causes are exactly the class of thing that check would have caught in seconds, before they ever reached the user.
