# Audio Pipeline Regression — Diagnosis & Fix Plan

**Written**: 2026-07-31 (Claude Code) · **For**: Antigravity · **Severity**: P0 — the core capture path is producing wrong audio, so every downstream number can be wrong

Two reported symptoms:
1. **The assistant mic records nothing at all.**
2. **Sales recordings are misaligned** — the window starts far too early, misses the actual utterance, and contains unrelated audio. "It was accurate earlier."

These are **not two bugs**. Symptom 2 is caused by symptom 1's implementation. Four defects are involved; they are listed below in the order they must be fixed.

---

## 1. Diagnosis

### BUG-A (root cause of symptom 2) — `startRollingBuffer()` resets the byte counter but not the write head

`app/src/main/java/com/voicetoinvoice/app/audio/RollingAudioBuffer.kt`

`extractAudioWindow` maps an absolute byte offset onto a ring index with:
```kotlin
val startRingIndex = (startByteOffset % bufferCapacity).toInt()   // line 162
```
That is only correct while this invariant holds:
```
writeHead == totalBytesWritten % bufferCapacity
```

`startRollingBuffer()` (lines 50–51) does:
```kotlin
recordingStartedAtMs = System.currentTimeMillis()
totalBytesWritten = 0L
```
…and **never resets `writeHead`** (confirmed: `writeHead` appears only at its declaration on line 22 and in the write loop on lines 91–92).

So on **every restart**, `totalBytesWritten` goes back to 0 while `writeHead` stays wherever it was. The invariant breaks by exactly the stale `writeHead` value — an arbitrary offset of **up to 120 seconds of old audio**. Every subsequent extraction reads from the wrong place in the ring: audio recorded earlier, unrelated to the button press. Exactly the reported symptom.

**When does a restart happen?** Far more often than it looks:
- `MainActivity.kt:301-302` — every `ON_STOP` → `ON_START`, i.e. **every time the app is backgrounded and reopened**.
- `PttMicButton.kt:143` and `:159` — **every assistant mic press**.
- `HomeScreen.kt:122` / `StockInScreen.kt:99` — only when no shared buffer is supplied (both correctly guarded), so not a factor in the real app.

**This is why "it was accurate earlier."** On a fresh install both counters are 0, so the invariant holds and capture is correct. The first background/foreground cycle — or the first assistant press — breaks it permanently for the rest of the process's life.

### BUG-B (root cause of symptom 1) — `stopRollingBuffer()` does not wait for the mic to be released

```kotlin
fun stopRollingBuffer() {
    isRecordingRunning.set(false)
    recordingThread?.interrupt()
    recordingThread = null          // returns immediately
}
```
`audioRecord.stop()` / `.release()` run **on the capture thread**, after its blocking `read()` returns. `stopRollingBuffer()` returns before any of that happens.

`PttMicButton.kt:143-144` then does:
```kotlin
rollingAudioBuffer.stopRollingBuffer()
try { onDeviceRecognizer.startListening("hi-IN") } catch (e: Exception) {}
```
`SpeechRecognizer` grabs the mic while `AudioRecord` is very likely still holding it. On most devices that fails silently — which is precisely "the assistant can't record anything."

Worse: the assistant path captures **no audio at all** by design (`AssistantFastPath` doc comment, `PttMicButton.kt:139`). So when the on-device recognizer fails, there is no fallback — the press produces nothing. There is no recovery path.

### BUG-C (severe, intermittent) — TTS suppression can stick on permanently

`app/src/main/java/com/voicetoinvoice/app/domain/voice/SpeechOutput.kt:52`

`speak()` sets `rollingAudioBuffer?.setSuppressed(true)` and relies on a **callback** to clear it (lines 59, 69, 183, 191). There is no `try/finally`. If the calling coroutine is cancelled while suspended in `speakOffline`'s `withTimeoutOrNull` (line 94) — which happens whenever WorkManager cancels `SttWorker`, or a composable scope is torn down on navigation — `onComplete` never runs and the flag stays `true`.

`isSuppressed` lives on the **shared singleton** buffer, and while set the capture loop overwrites every chunk with zeros (lines 86–88). Once stuck, **every recording in the app becomes digital silence** until the process restarts. This is a second, independent cause of symptom 1.

### BUG-D (latent, gets worse the longer the app runs) — window offsets are anchored to the wall clock

```kotlin
val startByteOffset = (effectiveStartMs - recStarted) * bytesPerSecond / 1000L   // line 142
```
This assumes the audio stream advances at exactly 16000 samples/sec in wall-clock time. Real `AudioRecord` streams drift (device clocks, resampling, read-loop stalls). Because the offset is anchored to `recordingStartedAtMs`, **all drift accumulated since the buffer started** lands in the window position. Over a long session this misaligns windows on its own, even after BUG-A is fixed.

The fix is to anchor to the **most recent write** instead of the start, so only drift *within the short window* matters, and any accumulated error self-corrects.

---

## 2. Fix plan

Do these in order. Steps 1–3 are the correctness fixes and can ship together. Step 4 is a design change that needs the decision in §3 first — **do not start Step 4 until the user answers**.

### Step 1 — Fix the ring buffer's addressing invariant (BUG-A + BUG-D)

All changes in `app/src/main/java/com/voicetoinvoice/app/audio/RollingAudioBuffer.kt`.

**1.1** Add a field next to the existing ones:
```kotlin
/** Wall-clock time of the most recent chunk written. The extraction anchor: mapping a
 *  timestamp to a byte offset relative to the LAST write (rather than to
 *  recordingStartedAtMs) means only drift inside the requested window matters, not drift
 *  accumulated over the whole session. See BUG-D in Docs/audio_pipeline_regression_fix_plan.md. */
@Volatile private var lastWriteAtMs: Long = 0L
```

**1.2** In `startRollingBuffer()`, reset the write head **together with** the byte counter, under the ring lock, before the capture thread starts:
```kotlin
isRecordingRunning.set(true)
recordingStartedAtMs = System.currentTimeMillis()
synchronized(ringBuffer) {
    // MUST be reset together. extractAudioWindow maps an absolute byte offset onto a ring
    // index as (offset % bufferCapacity), which is only valid while
    // writeHead == totalBytesWritten % bufferCapacity. Resetting the counter but not the
    // head (the pre-fix behaviour) shifted every later extraction by an arbitrary amount of
    // up to bufferDurationSeconds -- that was BUG-A, the cause of "recording starts far too
    // early / contains unrelated audio".
    totalBytesWritten = 0L
    writeHead = 0
    lastWriteAtMs = 0L
    java.util.Arrays.fill(ringBuffer, 0.toByte())
}
// Suppression must never survive a restart -- a stuck flag would otherwise make the buffer
// silently record digital silence forever (BUG-C).
isSuppressed.set(false)
```
Remove the now-duplicated `totalBytesWritten = 0L` from its old position.

**1.3** In the capture loop, record the write time inside the existing `synchronized(ringBuffer)` block, right after `totalBytesWritten += bytesRead`:
```kotlin
lastWriteAtMs = System.currentTimeMillis()
```

**1.4** Replace the offset computation in `extractAudioWindow`. Inside `synchronized(ringBuffer)`, after the `totalWritten` guard, replace lines 141–144 with:
```kotlin
// Anchor to the most recent actual write, not to recordingStartedAtMs -- see BUG-D.
// `anchorMs` is the wall-clock time that corresponds to byte offset `totalWritten`.
val anchorMs = if (lastWriteAtMs > 0L) lastWriteAtMs else System.currentTimeMillis()
fun byteOffsetFor(tsMs: Long): Long =
    totalWritten - (anchorMs - tsMs) * bytesPerSecond.toLong() / 1000L

val startByteOffset = Math.max(0L, byteOffsetFor(effectiveStartMs))
val endByteOffset = Math.max(startByteOffset, byteOffsetFor(endMs))
val requestedBytes = (endByteOffset - startByteOffset).toInt()
```
Leave everything below (`minStartAllowed`, the 9600-byte floor, the copy loop) unchanged — those are correct once the offsets are right.

**1.5** Extract the mapping into a pure, testable function so this bug class gets a regression test that does **not** need a device. Add to the companion object:
```kotlin
/**
 * Pure form of the window→byte-range mapping, factored out so it is unit-testable without a
 * microphone. `anchorMs` is the wall-clock time corresponding to byte offset `totalWritten`.
 * Returns null when the requested window is unrecoverable (already overwritten, or shorter
 * than [MIN_WINDOW_BYTES] after clamping).
 */
fun resolveWindowBytes(
    startMs: Long, endMs: Long, anchorMs: Long,
    totalWritten: Long, bufferCapacity: Int, bytesPerSecond: Int
): IntRange?
```
Move the arithmetic there and have `extractAudioWindow` call it. Also add `const val MIN_WINDOW_BYTES = 9600` and use it instead of the bare literal.

### Step 2 — Make `stopRollingBuffer()` actually release the mic (BUG-B)

```kotlin
/**
 * Blocks until the capture thread has really exited and AudioRecord.release() has run.
 * Without the join, this returned while AudioRecord still held the microphone, so a
 * SpeechRecognizer started immediately afterwards failed to open it -- BUG-B, the cause of
 * "the assistant records nothing". The timeout is a safety valve: a wedged capture thread
 * must not freeze the UI thread.
 */
fun stopRollingBuffer() {
    isRecordingRunning.set(false)
    val thread = recordingThread
    recordingThread = null
    try {
        thread?.join(500L)
        if (thread?.isAlive == true) {
            Log.w("RollingAudioBuffer", "Capture thread did not exit within 500ms; mic may still be held")
        }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}
```
**Remove the `recordingThread?.interrupt()` call.** `AudioRecord.read()` does not respond to interrupt, so it did nothing useful, and interrupting the thread mid-`read` risks skipping the `stop()`/`release()` in the `finally`. Clearing `isRecordingRunning` already ends the loop after at most one chunk (~64 ms at 2048 bytes).

Also move `audioRecord.stop()` / `audioRecord.release()` out of the `try` block and into the existing `finally`, so they run even if the loop throws.

> `join(500L)` on the main thread is a deliberate, bounded stall. It is only reached on app background and on an assistant press, and 500 ms is the worst case, not the typical one (~64 ms). If Step 4 Option A is chosen, the assistant no longer stops the buffer at all and this only runs on backgrounding.

### Step 3 — Make TTS suppression impossible to leak (BUG-C)

`app/src/main/java/com/voicetoinvoice/app/domain/voice/SpeechOutput.kt`

**3.1** Wrap the whole body of `speak()` in `try/finally` so the flag is cleared on **every** exit path, including coroutine cancellation:
```kotlin
suspend fun speak(text: String, preferOffline: Boolean = false, onComplete: (() -> Unit)? = null) =
    withContext(Dispatchers.IO + NonCancellable) {
        stop()
        rollingAudioBuffer?.setSuppressed(true)
        try {
            // ... existing Grok-then-offline logic, but WITHOUT the setSuppressed(false)
            //     calls inside the callbacks -- the finally below is now the single owner ...
        } finally {
            rollingAudioBuffer?.setSuppressed(false)
        }
    }
```
Import `kotlinx.coroutines.NonCancellable`.

**Do not leave the old `setSuppressed(false)` calls in the callbacks.** Two owners of one flag is how this class of bug returns — a late callback from a previous utterance could clear suppression during the *next* one. The `finally` is the single owner. (Keep the `onComplete?.invoke()` calls where they are; only the suppression calls move.)

**3.2** Note the behaviour change and confirm it is what we want: with a single `finally`, suppression now ends when `speak()` returns rather than when audio finishes playing. For the Grok/MediaPlayer path `playAudioFile` is fire-and-forget, so `speak()` returns while audio is still playing — suppression would end early and the mic could hear the tail of the TTS. **Fix this by making `playAudioFile` suspend until completion** (wrap it in `suspendCancellableCoroutine`, resuming from `setOnCompletionListener` and from the error path), so `speak()` genuinely spans the whole utterance. The offline path already waits via `UtteranceProgressListener`.

**3.3** Add a watchdog as defence in depth, in `RollingAudioBuffer`:
```kotlin
fun setSuppressed(suppressed: Boolean) {
    isSuppressed.set(suppressed)
    suppressedAtMs = if (suppressed) System.currentTimeMillis() else 0L
}
```
and in the capture loop, before zeroing a chunk:
```kotlin
// Self-healing: suppression is only ever meant to span one spoken answer. If it somehow
// outlives that (a lost callback, a killed coroutine), clear it rather than silently
// recording digital silence forever.
if (isSuppressed.get() && suppressedAtMs > 0L &&
    System.currentTimeMillis() - suppressedAtMs > MAX_SUPPRESSION_MS) {
    Log.w("RollingAudioBuffer", "Suppression exceeded ${MAX_SUPPRESSION_MS}ms; force-clearing")
    setSuppressed(false)
}
```
with `private const val MAX_SUPPRESSION_MS = 20_000L` (comfortably longer than the 15 s TTS timeout in `speakOffline`, so it never fires during a legitimate long answer).

### Step 4 — The assistant mic (needs the decision in §3 below)

**Option A (recommended) — stop tearing down the ring buffer; capture audio for the assistant too.**

In `PttMicButton.kt`, delete the `rollingAudioBuffer.stopRollingBuffer()` at line 143 and the `startRollingBuffer()` at line 159. Let the `ASSISTANT` branch fall through to the same capture path the sales mics use, so an assistant press produces a real audio window and a real `SttJobRecord`. Then:

- Still call `onDeviceRecognizer.startListening(...)` opportunistically. If it returns a usable transcript within the existing 2500 ms budget, run `AssistantFastPath` as today — the fast path is preserved when it works.
- If it returns blank, **fall back** to the extracted audio window and enqueue it through `SttWorker` with `captureIntent = ASSISTANT`, exactly like a sales job. The server already classifies `ASSISTANT` jobs (ISSUE-058) and routes the write-shaped intents, so this path exists and is deployed.
- This also removes the `"यह बिक्री जैसा लगा। कृपया नकद, उधार या माल बटन दबाकर बोलिए।"` dead end in `AssistantFastPath.kt:118`: with audio captured, a sale spoken to the assistant mic can actually be booked instead of redirecting the user to press a different button.

Why this is the right shape: it removes the *only* remaining caller that restarts the buffer mid-session (the other is app backgrounding, which is unavoidable and now safe after Step 1), and it means the assistant degrades to "slower but correct" instead of "silently does nothing" when on-device STT fails.

**Risk to check**: the code asserts (`AssistantFastPath.kt:38-42`) that `AudioRecord` and `SpeechRecognizer` cannot share the mic — "confirmed by every historical trace showing on-device STT failing 100% of the time it ran concurrently with the ring buffer." Option A runs them concurrently on purpose. **Verify this empirically before relying on it** (Step 5.4). Even if on-device STT still fails 100% concurrently, Option A is still a strict improvement, because the fallback now yields a correct answer instead of nothing — the assistant just loses its sub-1.5 s response for that turn.

**Option B (fallback) — keep the teardown, rely on Steps 1+2 to make it safe.**

Steps 1 and 2 already make stop/restart correct (head reset with the counter; mic genuinely released before `startListening`). If §3's answer is that the fast path's latency must be preserved at all costs, do nothing further here. The assistant still captures no audio and still has no fallback when on-device STT fails — accept that explicitly, and add a spoken "समझ नहीं आया, दोबारा बोलिए" when the transcript comes back blank so the press is never silently ignored.

### Step 5 — Verification

**5.1 Unit test (JVM, no device)** — `app/src/test/java/com/voicetoinvoice/app/audio/RollingBufferWindowTest.kt`, against the pure `resolveWindowBytes` from Step 1.5:
1. `windowMapsToCorrectRangeMidBuffer` — a straightforward window well inside the buffer resolves to the expected byte range.
2. `windowOlderThanBufferReturnsNull` — a start time older than `totalWritten - bufferCapacity` returns null rather than wrapping onto newer audio. **This is the guard against the exact corruption we just shipped.**
3. `windowIsAnchoredToLastWriteNotStart` — with `totalWritten` deliberately inconsistent with `anchorMs - recordingStart` (simulating drift), assert the resolved range still lands on the correct recent audio. This is the BUG-D regression test.
4. `shortWindowReturnsNull` — under `MIN_WINDOW_BYTES` returns null.
5. `endBeforeStartReturnsNull` — no crash, no negative-size array.

**5.2 Instrumented invariant test** — `app/src/androidTest/java/com/voicetoinvoice/app/audio/RollingBufferRestartTest.kt`:
1. `restartResetsWriteHeadWithCounter` — start the buffer, let it capture ~2 s, stop, restart, capture ~2 s, extract a window over the second capture, and assert the returned WAV is non-null and its duration matches the request within ±150 ms. **This is the direct BUG-A regression test.** It fails on today's code.
2. `suppressionDoesNotSurviveRestart` — `setSuppressed(true)`, restart, assert captured audio is not all-zero.
3. `stopReleasesMicBeforeReturning` — after `stopRollingBuffer()` returns, assert a fresh `AudioRecord` can be constructed and started. This is the BUG-B regression test.

**5.3 Device test — the misalignment itself.** This is the one that matters most; do it by hand and report what you see:
- Fresh launch → record "चार किलो आलू" → confirm the trace's transcript matches.
- **Background the app, reopen it**, record the same phrase → confirm it still matches. *This is the exact sequence that is broken today.*
- Press the assistant mic once, then record a sale → confirm still correct.
- Repeat the background/foreground cycle 3–4 times and confirm there is no progressive drift.
- Read back `step_1_ptt_recording_metadata` and `step_2_stt_proxy_response.rawTranscript` from the diagnostic trace for each, and paste them into your report.

**5.4 Device test — mic sharing (only if Option A is chosen).** With the ring buffer running, press the assistant mic and check logcat for whether `SpeechRecognizer` returns a transcript or errors:
```bash
adb logcat -d -s OnDeviceSpeechRecognizer:* RollingAudioBuffer:* SpeechOutput:*
```
Report the result plainly — it decides whether the fast path survives on this hardware. Either outcome is acceptable; the fallback covers both.

**5.5 Device test — suppression leak (BUG-C).** Trigger an assistant answer, then immediately background the app (cancelling the coroutine mid-TTS), reopen, and record a sale. Confirm the sale audio is not silent.

**5.6 Full suites**, then build and export:
```bash
./gradlew.bat testDebugUnitTest
```
```bash
./gradlew.bat connectedAndroidTest
```
```bash
./gradlew.bat assembleDebug
```
Copy to `C:/Users/harsh/OneDrive/Desktop/VoiceToInvoice_APKs/` as the next free `VoiceToInvoice_v<N>.apk` — **`ls` the folder first**, the number drifts.

### Step 6 — Audit log

Add entries to `Docs/audit.md` starting at **ISSUE-060** (re-check the file; 059 was the highest as of this plan). Log BUG-A, BUG-B, BUG-C and BUG-D — **as separate entries**, since they have distinct root causes and distinct regression tests, and a future agent searching for any one of them should find its own entry.

For BUG-A specifically, record the invariant in the **Ground-Truth Source-Code Verified Constants** table (§1): *"`RollingAudioBuffer` requires `writeHead == totalBytesWritten % bufferCapacity`; both must be reset together in `startRollingBuffer()`."* That line is what stops this being reintroduced.

Then update `Docs/remaining_work_plan.md`, and end your final message with a **Deviations** section.

---

## 3. Decision needed before Step 4

**Should the assistant mic capture audio (Option A) or stay transcript-only (Option B)?**

- **Option A** — the assistant always gets a usable result; a sale spoken to it can actually be booked; the ring buffer is never torn down mid-session. Cost: when on-device STT fails, that turn takes the full ~4–8 s server round trip instead of ~1.5 s.
- **Option B** — preserves the sub-1.5 s fast path exactly as designed. Cost: the assistant still captures no audio, so it stays fully dependent on a recognizer that is currently failing, with no fallback.

**My recommendation is Option A.** The fast path's speed is worth much less than the assistant working at all, and Option A removes the last in-session caller of `startRollingBuffer()` — which is the code path that caused this entire regression.

Steps 1–3 are unconditional and should proceed regardless of this answer.

---

## 4. Scope boundaries

- **Do not** change `domain/parser/` or `supabase/functions/process-voice-job/*`. This is a capture-layer bug; the parser and the edge function are innocent and the client/server mirror rule does not come into play. If you find yourself editing `index.ts`, stop.
- **Do not** "fix" the misalignment by adjusting `PRE_ROLL_MS` / `POST_ROLL_MS` or the coalescer's gap threshold. Those values are correct; the bug is in the byte-offset mapping. Tuning them would mask the symptom on one device and leave the corruption in place.
- **Do not** add `fallbackToDestructiveMigration()` or touch the Room schema — no DB change is needed for any of this.
- `PttWindowLedger` and `PttBurstCoalescer` were reviewed during diagnosis and are **not** implicated. Leave them alone.
