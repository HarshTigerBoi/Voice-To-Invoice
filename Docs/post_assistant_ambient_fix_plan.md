# Post-ASSISTANT Audio Pollution Fix Plan
## ISSUE-075 — Three remaining bugs after voice_timing_fix_plan.md (ISSUE-073)

---

## Root Cause Analysis — Verified Against Live DB (2026-08-04)

**Primary finding:** The 6:28–6:30 PM failure session was on the OLD APK (before
`voice_timing_fix_plan.md` Steps 1–4 were installed). The `af527425` trace has
`{"client": {"fast_path":true, "on_device_transcript":"", "outcome":"blank_transcript"}}`.
This format is only produced when `AssistantFastPath.handle()` is called with a blank
transcript. The current `PttMicButton.kt` gates on `result.transcript.isNotBlank()` before
calling `handle()` — so this trace is impossible on the new APK. The user was testing with
the old APK.

**What happened at 6:30 PM (old APK):**
- 6:29:44 PM: ASSISTANT press → `stopRollingBuffer()` → SpeechRecognizer → blank result →
  old code called `AssistantFastPath.handle("")` → blank path → TTS "समझ नहीं आया" played →
  `startRollingBuffer()` (BUG A cold reset) wiped the ring buffer
- 34 seconds of ambient shop conversation captured ("हाँ जी हाँ जी", conversation noise)
- 6:30:20 PM: SALE press → ring buffer correctly extracted audio from the ambient window →
  "हाँ जी हाँ जी" transcribed (this WAS what was in the microphone at that moment)

**Timing was not broken.** `resolveWindowBytes()` computed the correct byte offset for
6:30:20 PM. The failure was content — ambient conversation was captured because
34 seconds of shop noise had accumulated since the buffer reset.

**User action required first:** Install the new APK (built from `voice_timing_fix_plan.md`
Steps 1–4). That fixes BUG A (cold reset) and BUG C (ring-buffer extraction from gap).

---

## Remaining Bugs in the New APK

Three bugs survive the previous plan and must be fixed now.

---

### BUG E — `AssistantFastPath.handle()` SALE-intent path still extracts audio from the gap window

**Location:** `app/src/main/java/com/voicetoinvoice/app/domain/voice/AssistantFastPath.kt`
lines 110–148

```kotlin
AssistantIntent.SALE, AssistantIntent.CREDIT_SALE,
AssistantIntent.STOCK_IN, AssistantIntent.WASTE,
AssistantIntent.RETURN, AssistantIntent.PRICE_UPDATE,
AssistantIntent.EXPIRY_WRITEOFF -> {
    val extractedAudio = rollingAudioBuffer.extractAudioWindow(
        startMs = pressStartMs - 300L,
        endMs = releaseMs + 300L,
        outputFile = targetFile
    )
```

With the new APK's `resumeRollingBuffer()`, the ring buffer was **stopped** during the
entire ASSISTANT press `[pressStartMs, releaseMs]`. After `resumeRollingBuffer()`, those
ring positions contain audio from ~120 seconds **before** the ASSISTANT press (old audio
that wrapped around before the stop). `extractAudioWindow()` returns that old audio —
the extracted file is uploaded and processed as a real SALE, producing wrong transcriptions.

**Scenario:** User says "दो किलो टमाटर" to the ASSISTANT button → on-device STT returns
"दो किलो टमाटर" → classified as SALE → `extractAudioWindow(pressStartMs-300, releaseMs+300)`
extracts audio from the gap → old audio (e.g., "पाँच किलो आलू" from 2 minutes ago) →
wrong item confirmed.

**The fix the old plan (Step 3) missed:** Step 3 removed the ring-buffer extraction from
`PttMicButton.kt`'s else-branch (blank-transcript path). But the SALE-intent path inside
`AssistantFastPath.handle()` itself was not changed. It still extracts.

---

### BUG F — `ON_START` lifecycle event still cold-resets the ring buffer

**Location:** `app/src/main/java/com/voicetoinvoice/app/MainActivity.kt` line 312

```kotlin
androidx.lifecycle.Lifecycle.Event.ON_START -> sharedRollingBuffer.startRollingBuffer()
```

Every time the app is foregrounded (home button → back to app), `startRollingBuffer()`
wipes `totalBytesWritten=0`, `writeHead=0`, `lastWriteAtMs=0`, and zeroes the ring buffer.
The absolute-time → byte-offset coordinate system is destroyed. If a SALE is pressed after
foreground, the first recording works (new fresh session). But `PttWindowLedger.lastEndMs`
still has the old end-time from before the background, so `clampedStartMs = max(T_press-300,
lastEndMs_old)` may clamp correctly for large gaps but creates a confusing mismatch between
the ring buffer's epoch and the ledger's epoch.

More critically: after `ON_START → startRollingBuffer()`, if the ASSISTANT button was used
right before backgrounding, the ring buffer is reset with a new `recordingStartedAtMs`.
Any SALE job windows calculated against the old epoch (before background) will extract
from wrong ring positions.

---

### BUG G — `resumeRollingBuffer()` silent failure leaves buffer dead

**Location:** `app/src/main/java/com/voicetoinvoice/app/audio/RollingAudioBuffer.kt`
lines 187–190

```kotlin
if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
    Log.e("RollingAudioBuffer", "AudioRecord not initialized on resume")
    isRecordingRunning.set(false)
    return@Thread
}
```

If `SpeechRecognizer` has not yet fully released the microphone when `resumeRollingBuffer()`
tries to open `AudioRecord`, initialization fails. `isRecordingRunning = false`, but
`lastWriteAtMs` and `totalBytesWritten` retain their old values (from before the stop).

Subsequent SALE presses will call `extractAudioWindow()`:
- `anchorMs = lastWriteAtMs` (stale — from before the stop)
- `byteOffsetFor(T_press - 300ms) = totalBytesWritten + (T_press - T_stop) * rate` > `totalWritten`
- `actualBytesToExtract` is negative → `resolveWindowBytes()` returns null → no audio extracted

The user sees nothing — no job, no error, no toast. They press SALE again thinking it didn't
register. The buffer remains dead until the app is backgrounded/foregrounded (which triggers
`ON_START → startRollingBuffer()` — itself BUG F, but at least it restarts).

---

## Fix Steps

Execute in order. Each step is self-contained.

---

### Step 1 — Fix BUG E: remove audio extraction from SALE-intent path in AssistantFastPath

**File:** `app/src/main/java/com/voicetoinvoice/app/domain/voice/AssistantFastPath.kt`

Replace lines 110–148 (the entire `AssistantIntent.SALE, AssistantIntent.CREDIT_SALE, ...`
`when` branch and its body) with a redirect-only response:

```kotlin
AssistantIntent.SALE, AssistantIntent.CREDIT_SALE,
AssistantIntent.STOCK_IN, AssistantIntent.WASTE,
AssistantIntent.RETURN, AssistantIntent.PRICE_UPDATE,
AssistantIntent.EXPIRY_WRITEOFF -> {
    // The ring buffer was stopped during this ASSISTANT press, so there is no audio for
    // the [pressStartMs-300, releaseMs+300] window — extracting gives old audio from
    // ~120 seconds before the press (ring positions contain pre-stop data). Redirect
    // the user to use the dedicated button instead.
    answer = "यह बिक्री या स्टॉक जैसा लगा। कृपया नकद, उधार या माल बटन दबाकर बोलिए।"
    finalStatus = SttJobStatus.PARSED
    clientTrace.put("outcome", "redirected_write_intent_to_button")
}
```

Also remove the `targetFile` variable declaration that precedes this branch (it is only used
in the removed block). That declaration is:
```kotlin
val targetFile = java.io.File.createTempFile("voice_record_", ".wav", context.cacheDir)
```

After this change, the `AssistantFastPath` no longer touches the ring buffer for any intent
path — all ring-buffer interaction belongs to the `SpeechOutput` TTS path, which is
correct and already suppressed.

**Verify:** After this step, if a user says "दो किलो आलू" to the ASSISTANT button, the app
should say "यह बिक्री जैसा लगा..." via TTS and create a PARSED job. No audio should be
extracted from the ring buffer for this press. Check `stt_job_logs` to confirm
`outcome=redirected_write_intent_to_button` and `trace_len ≈ 200 bytes` (client-only trace,
no server processing).

---

### Step 2 — Fix BUG F: use smart-start in the lifecycle observer

**File:** `app/src/main/java/com/voicetoinvoice/app/audio/RollingAudioBuffer.kt`

Add a `smartStart()` method immediately after `stopRollingBuffer()` (after line 144):

```kotlin
/**
 * Called on every ON_START lifecycle event. Cold-starts the buffer on first launch;
 * on subsequent foregrounds (buffer was stopped by ON_STOP), resumes without destroying
 * the timing coordinate system.
 */
fun smartStart() {
    if (isRecordingRunning.get()) return
    if (totalBytesWritten == 0L) startRollingBuffer() else resumeRollingBuffer()
}
```

**File:** `app/src/main/java/com/voicetoinvoice/app/MainActivity.kt`

Change line 312:
```kotlin
// BEFORE:
androidx.lifecycle.Lifecycle.Event.ON_START -> sharedRollingBuffer.startRollingBuffer()

// AFTER:
androidx.lifecycle.Lifecycle.Event.ON_START -> sharedRollingBuffer.smartStart()
```

**Why this works:** On cold app start, `totalBytesWritten = 0` → `startRollingBuffer()` (full
reset, correct). On foreground after background, `totalBytesWritten > 0` → `resumeRollingBuffer()`
(preserves coordinate system, correct). The timing epoch is never destroyed by a routine
foreground event.

---

### Step 3 — Fix BUG G: fall back to cold start when `resumeRollingBuffer()` fails

**File:** `app/src/main/java/com/voicetoinvoice/app/audio/RollingAudioBuffer.kt`

In `resumeRollingBuffer()`, change the AudioRecord failure path (lines 187–191):

```kotlin
// BEFORE:
if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
    Log.e("RollingAudioBuffer", "AudioRecord not initialized on resume")
    isRecordingRunning.set(false)
    return@Thread
}

// AFTER:
if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
    Log.e("RollingAudioBuffer", "AudioRecord not initialized on resume — falling back to cold start")
    isRecordingRunning.set(false)
    // Post to main thread so we don't call startRollingBuffer() from inside its own thread
    android.os.Handler(android.os.Looper.getMainLooper()).post { startRollingBuffer() }
    return@Thread
}
```

**Why main-thread post:** `resumeRollingBuffer()` runs its body on the recording thread.
Calling `startRollingBuffer()` from within that thread would start a second thread while still
on the first — safe for the code, but calling it via the main-thread handler ensures the
thread lifecycle is clean (old thread exits via `return@Thread`, then handler posts the new
start). This is a one-time fallback path, not a hot path.

**Consequence of this fix:** If the mic is still held by `SpeechRecognizer` when
`resumeRollingBuffer()` fires, the buffer falls back to a cold start after the mic is
released (typically within 100–300ms). The coordinate system is reset, but the buffer IS
running — subsequent SALE presses will produce valid jobs rather than silent null extractions.

---

### Step 4 — Add 1-second post-TTS ambient mute in `AssistantFastPath.handle()`

**File:** `app/src/main/java/com/voicetoinvoice/app/domain/voice/AssistantFastPath.kt`

After the `speechOutput.speak(answer, preferOffline = true)` call (line 163), add a 1-second
suppression window:

```kotlin
try {
    speechOutput.speak(answer, preferOffline = true)
    // 1-second mute after TTS to prevent the shopkeeper's verbal response to the app
    // ("हाँ जी", "ठीक है") from landing in the 300ms pre-roll of the next SALE recording.
    rollingAudioBuffer.setSuppressed(true)
    kotlinx.coroutines.delay(1000L)
    rollingAudioBuffer.setSuppressed(false)
} catch (e: Exception) {
    rollingAudioBuffer.setSuppressed(false)
    android.util.Log.w("AssistantFastPath", "TTS or post-mute failed: ${e.message}")
}
```

**Context for Antigravity:** `AssistantFastPath.handle()` is a `suspend fun`. `delay()` is
available. The `rollingAudioBuffer` parameter is the correct active instance (passed from
`PttMicButton`, not from `getSharedInstance()`).

**Why 1 second:** In a kirana shop, the shopkeeper's verbal acknowledgement ("हाँ जी हाँ जी")
typically lasts 0.5–1 second after hearing the app's response. A 1-second post-TTS mute
window writes silence to the ring buffer during this window. When the next SALE press
happens, the 300ms pre-roll pulls from after the mute period — clean audio, not verbal
response noise.

The `MAX_SUPPRESSION_MS` safety valve (20 seconds) still applies and will auto-clear
suppression if the mute somehow runs long.

---

## What Step 1–4 do NOT fix

- **Environmental ambient noise in the ring buffer between recordings:** The ring buffer
  always captures whatever is in the microphone. In a noisy shop, a sale item spoken at low
  volume may be drowned by ambient speech. This is a product-level challenge, not a code bug.

- **`PttWindowLedger.lastConsumedEndMs` surviving buffer resets:** After `smartStart()` does
  a cold reset (e.g., first launch after a long background), the ledger's `lastEndMs` is still
  0 (it resets on process start). This is correct — no issue.

---

## Verification Checklist (for Antigravity)

After implementing all four steps, verify on device:

1. **ASSISTANT press + sale-like phrase:** Say "दो किलो आलू" to the ASSISTANT button. Should
   hear "यह बिक्री जैसा लगा..." spoken, and NO ring-buffer job created (no new row with
   `step_1_ptt_recording_metadata` in `stt_job_logs`).

2. **ASSISTANT press → immediate SALE:** Press ASSISTANT (any phrase), hear the answer,
   then within 2 seconds press SALE and say "पाँच किलो टमाटर". The SALE transcript should
   match "पाँच किलो टमाटर", not the assistant's spoken response or the verbal acknowledgement.

3. **App background → foreground → SALE:** Background the app (home button), wait 5 seconds,
   foreground it, then press SALE and speak. Should create a valid job with correct audio.
   Confirm `stt_job_logs` shows non-null `raw_transcript`.

4. **Query DB after each test:**
```sql
SELECT job_id, status, hold_duration_ms, raw_transcript,
       length(diagnostic_trace_json) AS trace_len,
       LEFT(diagnostic_trace_json, 200) AS trace_head,
       created_at
FROM stt_job_logs ORDER BY created_at DESC LIMIT 10;
```

---

## Open Questions for Antigravity

None. All file paths, line numbers, and exact code changes are specified. If a symbol
doesn't exist as described, stop on that step and report with the exact error.

---

## Deviations section (for Antigravity to fill in after implementation)

_None yet._
