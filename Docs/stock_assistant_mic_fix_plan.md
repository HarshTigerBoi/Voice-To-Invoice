# Stock Mic Wrong-Time Recording & Assistant Mic Not Working — Root Cause & Fix Plan

**Written**: 2026-07-31 (Antigravity) · **Severity**: P0 — two separate defects, both in `PttMicButton.kt`
**Next ISSUE#**: ISSUE-071 (Stock mic audio window) · ISSUE-072 (Assistant mic silent)

---

## 0. TL;DR

There are **two distinct bugs**, both confined to `PttMicButton.kt`:

| # | Intent | Symptom | Root Cause |
|---|--------|---------|------------|
| 1 | STOCK_IN | Mic records but submits audio from a *different* time window than when button was pressed | `onDeviceRecognizer.startListening()` is called concurrently with `RollingAudioBuffer`'s `AudioRecord` on every SALE/STOCK press — the two AudioRecord sessions compete for the mic, causing STT errors and (on loss) a wrong-window extraction |
| 2 | ASSISTANT | Mic button appears to work (isRecording = true) but produces no output — no answer, no log entry for the utterance | `RollingAudioBuffer` is **never stopped or suppressed** before `onDeviceRecognizer.startListening()` in the ASSISTANT branch — `AudioRecord` already holds the mic so `SpeechRecognizer` gets nothing |

---

## 1. Detailed Root Cause Analysis

### 1.1 Evidence: the `RollingAudioBuffer` / `SpeechRecognizer` mic contention

**Documented in the codebase itself** (`RollingAudioBuffer.kt` line 128-131):

```
// Blocks until the capture thread has really exited and AudioRecord.release() has run.
// Without the join, this returned while AudioRecord still held the microphone, so a
// SpeechRecognizer started immediately afterwards failed to open it -- BUG-B, the cause of
// "the assistant records nothing".
```

Android does not multiplex `AudioRecord` — only one caller can hold the microphone source `VOICE_RECOGNITION` at a time. When `RollingAudioBuffer.startRollingBuffer()` has run its thread, calling `SpeechRecognizer.startListening()` on the same device will silently fail.

### 1.2 Bug 1 — STOCK_IN and SALE presses call `startListening` while ring buffer is running

In `PttMicButton.kt`, the non-ASSISTANT press path (lines 232–233):

```kotlin
pttWindowLedger.recordPress(pressTimestamp)
try { onDeviceRecognizer.startListening("hi-IN") } catch (e: Exception) {}
// ... tryAwaitRelease() ...
// ... processGroup() submits audio job ...
```

`sharedRollingBuffer` is still recording (`AudioRecord` thread is live). `startListening()` tries to open a second `AudioRecord` session on the same source. On some devices the ring buffer's `AudioRecord.read()` returns zeros or errors, causing `lastWriteAtMs` anchor to stall.

`resolveWindowBytes` uses `lastWriteAtMs` as the anchor:
```kotlin
val anchor = if (lastWriteAtMs > 0L) lastWriteAtMs else System.currentTimeMillis()
fun byteOffsetFor(tsMs: Long): Long =
    totalWritten - (anchorMs - tsMs) * bytesPerSecond.toLong() / 1000L
```

If the ring buffer thread was starved by the concurrent `SpeechRecognizer` for even 100-300ms, `lastWriteAtMs` falls behind wall-clock by that amount → `byteOffsetFor(pressMs)` computes a byte offset that is 100-300ms behind where the press actually was in the ring → **audio starts at the wrong time**.

### 1.3 Bug 2 — ASSISTANT press never stops the ring buffer before calling `startListening`

In `PttMicButton.kt`, the ASSISTANT branch (lines 143–145):

```kotlin
if (intent == CaptureIntent.ASSISTANT) {
    pttWindowLedger.recordPress(pressTimestamp)
    try { onDeviceRecognizer.startListening("hi-IN") } catch (e: Exception) {}
```

**`rollingAudioBuffer.stopRollingBuffer()` is never called.** The code comment on lines 134-142 says:
> "The always-on RollingAudioBuffer is released for the duration of this press..."

...but this release **never actually happens in code**. `AssistantFastPath.kt` (line 39-42) also explicitly documents this requirement:
> "To make on-device STT work at all, the caller must release the always-on RollingAudioBuffer for the duration of the press"

Result: every single ASSISTANT press captures an empty or garbage transcript → `result.transcript.isNotBlank()` on line 179 is **always false** → `AssistantFastPath.handle()` is **never called** → no answer, no spoken response → button does absolutely nothing.

---

## 2. Fix Plan

All changes are in one file: `app/src/main/java/com/voicetoinvoice/app/ui/components/PttMicButton.kt`

No changes to `AssistantFastPath.kt`, `RollingAudioBuffer.kt`, `OnDeviceSpeechRecognizer.kt`, `MainActivity.kt`, or any screen file.

---

### Fix A — ASSISTANT branch: stop buffer before `startListening`, restart it after

**Target**: `PttMicButton.kt` lines 143–229 (the ASSISTANT block)

**Change 1**: Before `onDeviceRecognizer.startListening("hi-IN")` (line 145), add:
```kotlin
rollingAudioBuffer.stopRollingBuffer()
```

**Change 2**: After `onDeviceRecognizer.finishListening()` (line 174), and BEFORE the `scope.launch` block, add:
```kotlin
rollingAudioBuffer.startRollingBuffer()
```

The ASSISTANT block after the fix looks like:

```kotlin
if (intent == CaptureIntent.ASSISTANT) {
    pttWindowLedger.recordPress(pressTimestamp)
    // FIX A-1: stop ring buffer so SpeechRecognizer can open the mic
    rollingAudioBuffer.stopRollingBuffer()
    try { onDeviceRecognizer.startListening("hi-IN") } catch (e: Exception) {}

    tryAwaitRelease()

    val assistantReleaseTs = System.currentTimeMillis()
    val holdDurationMs = Math.max(assistantReleaseTs - pressTimestamp, 100L)

    if (holdDurationMs >= LONG_HOLD_WARNING_MS) {
        Toast.makeText(context, "बहुत लंबी रिकॉर्डिंग — कृपया थोड़े आइटम एक बार में बोलें", Toast.LENGTH_LONG).show()
    } else if (holdDurationMs < SHORT_HOLD_ADVISORY_MS) {
        Toast.makeText(context, "बहुत छोटी रिकॉर्डिंग हो सकती है — ज़रूरत हो तो दोबारा बोलिए", Toast.LENGTH_SHORT).show()
    }

    try {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    } catch (e: Exception) {
        e.printStackTrace()
    }

    isRecording = false
    onRecordingStateChange?.invoke(false)
    try { onDeviceRecognizer.finishListening() } catch (e: Exception) {}

    // FIX A-2: restart ring buffer so subsequent presses have a valid buffer
    rollingAudioBuffer.startRollingBuffer()

    val assistantPressTs = pressTimestamp
    scope.launch(Dispatchers.IO) {
        val result = onDeviceRecognizer.awaitResult(2500L)
        if (result.transcript.isNotBlank()) {
            com.voicetoinvoice.app.domain.voice.AssistantFastPath.handle(
                context = context,
                db = db,
                rollingAudioBuffer = rollingAudioBuffer,
                transcript = result.transcript,
                pressStartMs = assistantPressTs,
                releaseMs = assistantReleaseTs
            )
        } else {
            // fallback: treat as upload job
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
                    db.sttJobDao().insertJob(job)
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
    }
    return@detectTapGestures
}
```

---

### Fix B — SALE/STOCK branch: remove `startListening()` / `finishListening()` / backfill coroutine

**Change 1** — delete `startListening` call (line 233):
```kotlin
// BEFORE:
pttWindowLedger.recordPress(pressTimestamp)
try { onDeviceRecognizer.startListening("hi-IN") } catch (e: Exception) {}
tryAwaitRelease()

// AFTER:
pttWindowLedger.recordPress(pressTimestamp)
tryAwaitRelease()
```

**Change 2** — delete `finishListening` call at the end of the release block (line 262):
```kotlin
// DELETE:
try { onDeviceRecognizer.finishListening() } catch (e: Exception) {}
```

**Change 3** — delete the on-device backfill coroutine (lines 300-311):
```kotlin
// DELETE the entire block:
scope.launch(Dispatchers.IO) {
    val res = onDeviceRecognizer.awaitResult(4000L)
    db.sttJobDao().getJobById(job.id)?.let { currentJob ->
        db.sttJobDao().updateJob(
            currentJob.copy(
                onDeviceTranscript = res.transcript,
                onDeviceStatus = res.status
            )
        )
    }
}
```

**Rationale**: The on-device backfill for SALE/STOCK is purely diagnostic. It is not part of the commit gate, confidence scoring, or item parsing — those all run server-side via Grok+Sarvam in `SttWorker`. The concurrent `startListening()` is what corrupts `lastWriteAtMs` → wrong audio window.

---

## 3. Scope Boundaries

- **Do not modify `RollingAudioBuffer.kt`** — its `stopRollingBuffer()`/`startRollingBuffer()` API is correct. We are simply calling it where it was always supposed to be called.
- **Do not modify `AssistantFastPath.kt`** — its documented assumption (buffer stopped before `startListening`) is correct; we're making the caller honor it.
- **Do not modify `OnDeviceSpeechRecognizer.kt`** — API is correct.
- **Do not touch `MainActivity.kt`** — the three separate coalescers from ISSUE-070 are correctly wired.
- **Do not modify `HomeScreen.kt`, `StockInScreen.kt`, or any screen file** — all changes inside `PttMicButton.kt` only.
- **Do not change pre/post-roll constants** (300L/300L) — not implicated.
- **Do not change `PttBurstCoalescer.kt`** — not implicated.

---

## 4. Verification Plan

### Step 1 — Build
```bat
cd "c:\Users\harsh\Documents\Voice To Invoice"
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```
Both must pass with 0 failures / 0 compile errors.

### Step 2 — ASSISTANT works
1. Press ASSISTANT button (bottom-right purple). Say: **"आज कितनी बिक्री हुई"**
2. **Expected**: TTS speaks the answer within ~2s. `stt_job_logs` row appears with `captureIntent = ASSISTANT` and non-blank `assistantAnswer`.
3. **Failure sign**: silence, or no log row.

### Step 3 — STOCK_IN correct time window
1. Go to **माल+** screen. Press STOCK_IN mic. Say: **"दस किलो आलू"**. Release.
2. **Expected**: `stt_job_logs` STOCK_IN job with `raw_transcript` containing "आलू" and `audioStartMs` within 1s of actual press time.
3. **Failure sign**: wrong transcript or `audioStartMs` off by >2s.

### Step 4 — SALE still works
1. Press SALE mic. Say: **"पांच किलो प्याज़"**
2. **Expected**: review card shown with correct item.

### Step 5 — Interleaved test (ISSUE-070 regression)
1. Press SALE mic ("दो किलो टमाटर"), release.
2. Within 1 second press ASSISTANT button ("आज कितना बेचा").
3. **Expected**: two separate `stt_job_logs` rows — one SALE, one ASSISTANT — NOT merged.

### Step 6 — Audit log
After verification, log `ISSUE-071` and `ISSUE-072` as RESOLVED in `Docs/audit.md`.

---

## 5. Risk Assessment

| Risk | Probability | Mitigation |
|------|-------------|-----------|
| `startRollingBuffer()` takes >0ms to init; assistant fallback audio extraction gets null | Low | Fallback path (`result.transcript.isBlank()`) extracts audio by `group.startMs` from the new buffer. If buffer just restarted, the press window predates `recordingStartedAtMs` → `resolveWindowBytes` returns null → graceful no-op. Fast path (`transcript.isNotBlank()`) doesn't need the buffer at all. |
| Removing `startListening` for SALE/STOCK breaks on-device transcript column | Negligible | `onDeviceTranscript`/`onDeviceStatus` are diagnostic-only columns. No UI or commit gate reads them. |
| ASSISTANT buffer stop takes >500ms join timeout | Very Low | `stopRollingBuffer()` logs a warning but does not throw. `startListening()` is called after the join attempt regardless — worst case: same `ERROR_AUDIO` failure as before, handled by the fallback. |
| Two rapid ASSISTANT presses interleave stop/start/stop | Low | `startRollingBuffer()` has an `isRecordingRunning` guard (line 42: `if (isRecordingRunning.get()) return`). `stopRollingBuffer()` sets `isRecordingRunning = false` atomically before joining. |

---

## 6. Files Changed

| File | Change | Lines |
|------|--------|-------|
| `PttMicButton.kt` | Add `stopRollingBuffer()` before assistant `startListening` | ~145 |
| `PttMicButton.kt` | Add `startRollingBuffer()` after assistant `finishListening` | ~174 |
| `PttMicButton.kt` | Remove `startListening()` from SALE/STOCK path | ~233 |
| `PttMicButton.kt` | Remove `finishListening()` from SALE/STOCK release block | ~262 |
| `PttMicButton.kt` | Remove on-device backfill coroutine from SALE/STOCK path | ~300-311 |

**Total: 1 file, ~5 surgical changes.**
