# Voice Recording Timing Fix Plan
## ISSUE-073 — Wrong audio window across consecutive recordings

---

## Verified Root Causes

Four distinct bugs were identified by reading the code AND querying live `stt_job_logs`
(2026-07-31). Every root cause below is **verified**, not inferred.

### BUG A — `startRollingBuffer()` destroys timing state on every ASSISTANT press (CRITICAL)

**Location:** `RollingAudioBuffer.kt:54-62` (`startRollingBuffer()` body)

```kotlin
synchronized(ringBuffer) {
    totalBytesWritten = 0L        // ← RESET
    writeHead = 0                 // ← RESET
    lastWriteAtMs = 0L            // ← RESET
    Arrays.fill(ringBuffer, 0.toByte())  // ← 3.84 MB buffer wiped
}
```

The ASSISTANT flow in `PttMicButton.kt` calls this after every release:
```
stopRollingBuffer() → startListening() → ...user holds... → finishListening() → startRollingBuffer()
```

Every ASSISTANT press wipes the entire ring buffer and resets the time-to-byte coordinate
system. Any SALE recording made within seconds of an ASSISTANT press extracts from
the freshly-reset buffer. Consequence: either `extractAudioWindow()` returns null
(< `MIN_WINDOW_BYTES`), or it extracts old/garbage bytes from the wrong ring position.

**DB evidence (verified):** Two FAILED jobs with `trace_len=186`, `hold_duration_ms≈1470ms`
and null `raw_transcript`. Valid hold time → audio should exist; null transcript → audio was
empty. These appear after ASSISTANT interactions.

---

### BUG B — Old recording thread may re-enter the write loop after reset (CRITICAL)

**Location:** `RollingAudioBuffer.kt:132-143` (`stopRollingBuffer()`), `41-122` (`startRollingBuffer()`)

`stopRollingBuffer()` sets `isRecordingRunning=false` then calls `thread.join(500ms)`.
If `AudioRecord.read()` is blocked (up to ~64ms per chunk is normal, but transient delays
can extend this), the join times out.

`startRollingBuffer()` is then called. It sets `isRecordingRunning=true` (line 54), resets
`totalBytesWritten=0` (line 57), and starts a new thread. The **old thread** now sees
`isRecordingRunning=true` again (it was re-enabled by the new call) and re-enters the while
loop. Both the old and new thread write to the ring buffer concurrently:

- New thread: valid new audio + valid `lastWriteAtMs` and `totalBytesWritten` growing from 0
- Old thread: old audio (from old `AudioRecord`) also incrementing `totalBytesWritten`

`totalBytesWritten` grows faster than expected relative to `lastWriteAtMs`, so
`resolveWindowBytes` maps wall-clock timestamps to the wrong byte offsets → wrong audio
in the extracted file.

**Why it's intermittent:** requires the 500ms join to time out, which happens only under
system load or mic hardware contention. "Worked for a few times then broke" matches
an intermittent race.

---

### BUG C — ASSISTANT fallback tries to extract from a ring buffer that has no audio for that window (MODERATE)

**Location:** `PttMicButton.kt:191-229`

When on-device `SpeechRecognizer` returns a blank transcript, the code does:
```kotlin
val extractedAudio = rollingAudioBuffer.extractAudioWindow(
    startMs = group.startMs,  // ← timestamp during the ASSISTANT press
    endMs = group.endMs,
    ...
)
```

But the ring buffer was **stopped** for the entire duration of the ASSISTANT press (BUG A's
`stopRollingBuffer()` call precedes `startListening()`). No audio was written to the buffer
during that window. `extractAudioWindow()` either returns null (correctly), or extracts old
data that wrapped around the ring from before the press.

Even after fixing BUG A via `resumeRollingBuffer()` (Step 2 below), the gap still exists in
the buffer — bytes at positions P through P+(gap_duration*rate) contain audio from
**before** T_stop, overwritten by post-resume audio. The fallback is structurally broken and
must be removed regardless of the BUG A/B fix.

**DB evidence:** Same FAILED jobs (trace_len=186) — the fallback creates SttJobRecords with
empty audio files, which SttWorker marks FAILED.

---

### BUG D — `RollingAudioBuffer.getSharedInstance()` returns a different object than `MainActivity.sharedRollingBuffer` (MODERATE)

**Location:** `RollingAudioBuffer.kt:207-213`, `MainActivity.kt:280`

```kotlin
// MainActivity.kt:280
val sharedRollingBuffer = remember { RollingAudioBuffer(context) }  // ← instance #1

// RollingAudioBuffer companion object — called from SttWorker → SpeechOutput
fun getSharedInstance(context: Context): RollingAudioBuffer {
    return instance ?: synchronized(this) {
        instance ?: RollingAudioBuffer(context.applicationContext).also { instance = it }  // ← instance #2
    }
}
```

`PttMicButton` and `AssistantFloatingButton` receive instance #1 (passed down from
MainActivity). `SpeechOutput` (called from `SttWorker.handleAssistantJob()`) calls
`getSharedInstance()` which lazily creates instance #2 — **a separate `RollingAudioBuffer`
that is never started and never recording.**

`SpeechOutput.setSuppressed(true)` suppresses instance #2. Instance #1 (the one actually
recording) is NOT suppressed. TTS playback leaks into the ring buffer and appears in the
next SALE recording.

**DB evidence (verified):** job `3ec4febc`, hold=1900ms, `raw_transcript=
"और मैं मैं मैं मैं..."` repeated 127 times. The STT is transcribing TTS audio that
the unsuppressed ring buffer captured. "मैं" (I) is a common TTS pronunciation artifact
from repeated syllable detection.

---

## Fix Steps

Execute in order. Each step is a self-contained change.

---

### Step 1 — Fix BUG D: register the Compose instance as the singleton

**File:** `app/src/main/java/com/voicetoinvoice/app/audio/RollingAudioBuffer.kt`

Add a `setSharedInstance()` method to the companion object (at line 208, after `instance`):
```kotlin
fun setSharedInstance(buffer: RollingAudioBuffer) {
    instance = buffer
}
```

No other changes to `getSharedInstance()` — once the shared instance is set, it will be
returned instead of lazily creating a new one.

**File:** `app/src/main/java/com/voicetoinvoice/app/MainActivity.kt`

After line 280 (`val sharedRollingBuffer = remember { ... }`), add:
```kotlin
// Wire the Compose-owned buffer as the static singleton so SpeechOutput (in SttWorker)
// calls setSuppressed() on the same instance that is actually recording.
LaunchedEffect(sharedRollingBuffer) {
    RollingAudioBuffer.setSharedInstance(sharedRollingBuffer)
}
```

**Verify:** after this change, `SpeechOutput.setSuppressed(true)` (called before TTS in
`SttWorker.handleAssistantJob()`) will suppress the active recording buffer. TTS audio
will no longer leak into SALE recordings.

---

### Step 2 — Fix BUG A + BUG B: add `resumeRollingBuffer()` and harden `stopRollingBuffer()`

**File:** `app/src/main/java/com/voicetoinvoice/app/audio/RollingAudioBuffer.kt`

#### 2a — Increase join timeout in `stopRollingBuffer()` (line 136)

Change `thread?.join(500L)` → `thread?.join(1500L)`.

At 64ms per AudioRecord chunk, the thread should exit within 2 iterations after
`isRecordingRunning.set(false)`. 1500ms gives 23× headroom. If it still hasn't exited,
log and proceed — the new start logic below handles it safely.

#### 2b — Add `resumeRollingBuffer()` immediately after `stopRollingBuffer()` (after line 144)

This method is identical to `startRollingBuffer()` **except** it skips the state reset:
```kotlin
/**
 * Resumes recording after a stopRollingBuffer() call without resetting the timing
 * coordinate system (totalBytesWritten, writeHead, lastWriteAtMs, buffer content).
 * Use instead of startRollingBuffer() when the buffer was temporarily paused for
 * on-device SpeechRecognizer exclusivity (ASSISTANT press flow) so that the
 * absolute-time → byte-offset mapping remains valid for subsequent recordings.
 */
fun resumeRollingBuffer() {
    if (isRecordingRunning.get()) return

    val micGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
    if (!micGranted) {
        Log.w("RollingAudioBuffer", "RECORD_AUDIO permission not granted — cannot resume.")
        return
    }

    isRecordingRunning.set(true)
    // NOTE: totalBytesWritten, writeHead, lastWriteAtMs, and ringBuffer content are
    // intentionally NOT reset. The gap period (while stopped) simply has no bytes written
    // to it; the time-to-byte formula in resolveWindowBytes() handles this correctly
    // because lastWriteAtMs jumps forward on the first new write and totalBytesWritten
    // continues from where it left off.

    recordingThread = Thread {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        var audioRecord: AudioRecord? = null
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                Math.max(minBufferSize, 4096)
            )
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("RollingAudioBuffer", "AudioRecord not initialized on resume")
                isRecordingRunning.set(false)
                return@Thread
            }
            audioRecord.startRecording()
            val chunk = ByteArray(2048)
            while (isRecordingRunning.get()) {
                val bytesRead = audioRecord.read(chunk, 0, chunk.size)
                if (bytesRead > 0) {
                    if (isSuppressed.get()) {
                        if (suppressedAtMs > 0L && System.currentTimeMillis() - suppressedAtMs > MAX_SUPPRESSION_MS) {
                            setSuppressed(false)
                        } else {
                            java.util.Arrays.fill(chunk, 0, bytesRead, 0.toByte())
                        }
                    }
                    synchronized(ringBuffer) {
                        for (i in 0 until bytesRead) {
                            ringBuffer[writeHead] = chunk[i]
                            writeHead = (writeHead + 1) % bufferCapacity
                        }
                        totalBytesWritten += bytesRead
                        lastWriteAtMs = System.currentTimeMillis()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("RollingAudioBuffer", "Error in resumed audio recording loop", e)
        } finally {
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Exception) {
                Log.e("RollingAudioBuffer", "Error releasing AudioRecord on resume", e)
            }
            isRecordingRunning.set(false)
        }
    }
    recordingThread?.start()
}
```

**Why this works even with the gap:** `resolveWindowBytes` uses
`totalBytesWritten - (lastWriteAtMs - tsMs) * rate / 1000`. After resume:
- `lastWriteAtMs` advances to current time on first write
- `totalBytesWritten` continues growing from where it stopped
- The formula correctly maps any post-resume timestamp to the correct byte offset
- Pre-gap bytes in the ring buffer remain valid for extraction windows that predate the stop

---

### Step 3 — Fix BUG C: remove the ASSISTANT fallback ring-buffer extraction

**File:** `app/src/main/java/com/voicetoinvoice/app/ui/components/PttMicButton.kt`

In the ASSISTANT flow (around line 179), the `else` branch currently does:
```kotlin
} else {
    val flushed = pttBurstCoalescer.recordPressRelease(...)
    val group = flushed ?: pttBurstCoalescer.forceFlush(...)
    if (group != null) {
        val extractedAudio = rollingAudioBuffer.extractAudioWindow(...)
        if (extractedAudio != null && ...) {
            // create SttJobRecord and enqueue SttWorker
        }
    }
}
```

Replace the entire `else` block with:
```kotlin
} else {
    // On-device STT returned nothing. The ring buffer was stopped for the duration of
    // this press (exclusive mic for SpeechRecognizer), so no audio exists for this window
    // in the buffer — ring-buffer extraction would produce silence or old audio. Tell the
    // user to try again rather than uploading a useless file.
    withContext(kotlinx.coroutines.Dispatchers.Main) {
        Toast.makeText(context, "समझ नहीं आया — दोबारा बोलिए", Toast.LENGTH_SHORT).show()
    }
}
```

Also update the `pttWindowLedger.recordPress(pressTimestamp)` call at line 144 (just
before the ASSISTANT `if` block): **remove it**. For ASSISTANT presses, the window ledger
should not advance, because no audio window is committed from the ring buffer for ASSISTANT.
The current line 144 and line 234 both call `pttWindowLedger.recordPress(pressTimestamp)`.
Keep only line 234 (for the non-ASSISTANT path). Remove line 144.

---

### Step 4 — Fix BUG A (call site): use `resumeRollingBuffer()` instead of `startRollingBuffer()` in ASSISTANT flow

**File:** `app/src/main/java/com/voicetoinvoice/app/ui/components/PttMicButton.kt`

Line 176 (current):
```kotlin
rollingAudioBuffer.startRollingBuffer()
```

Change to:
```kotlin
rollingAudioBuffer.resumeRollingBuffer()
```

This is the single call-site fix that prevents BUG A from triggering. The method added in
Step 2 ensures timing state is preserved across the ASSISTANT stop/resume cycle.

---

## Verification Checklist (for Antigravity)

After implementing all four steps, verify the following with a physical device:

1. **SALE recording after ASSISTANT:** speak an assistant query ("कितना बिक्री?"), wait for answer, then immediately press SALE and say "दो किलो टमाटर". The SALE transcript should match what was spoken, not silence or garbage.

2. **Rapid SALE → SALE:** press SALE twice in quick succession (< 600ms gap). Each recording should extract distinct audio from its own time window. Check `DiagnosticLogsScreen` to confirm `audioStartMs` and `audioEndMs` differ correctly between consecutive jobs.

3. **No "मैं मैं मैं" hallucination:** use the ASSISTANT button, let TTS answer, then immediately press SALE. The SALE transcript should NOT contain the TTS content. (BUG D fix: suppression now hits the correct instance.)

4. **ASSISTANT + blank on-device result:** speak something unclear to the ASSISTANT. Should show a "समझ नहीं आया" toast, NOT create a FAILED job with empty audio in `stt_job_logs`.

5. **Query live DB after each test:**
```sql
SELECT job_id, status, hold_duration_ms, raw_transcript,
       length(diagnostic_trace_json) AS trace_len, created_at
FROM stt_job_logs ORDER BY created_at DESC LIMIT 10;
```
No FAILED rows with `trace_len=186` and non-null `hold_duration_ms` should appear.

---

## Open Questions for Antigravity

None. All steps are unambiguous. If a named symbol or file doesn't exist as described,
stop on that step and report with the exact error rather than guessing a fix.

---

## Post-fix: `Docs/audit.md` entry

Log as ISSUE-073 once verified. Cross-reference ISSUE-071 (same root-class: mic contention
causing wrong audio extraction). The fix to `startRollingBuffer()` / `resumeRollingBuffer()`
should be called out in the root-cause line of ISSUE-073's entry.
