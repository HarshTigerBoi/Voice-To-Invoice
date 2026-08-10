# Assistant TTS Suppression Silences a Concurrent SALE Recording — Fix Plan
## Candidate ISSUE-081 — "record via assistant mic, immediately record a sale → the sale never reaches the log"

**Written**: 2026-08-05 (Claude Code) · **For**: Antigravity · **Severity**: P1 — a real, correctly-held mic press captures silence instead of the shopkeeper's voice, because of an unrelated feature (anti-echo muting for the assistant's own TTS reply).

**Confidence**: this is a **code-level, mechanistic diagnosis**, not yet confirmed against a live `stt_job_logs` repro row (I did not find one in this session's queries, which covered 2026-08-04 18:53–19:18 UTC and didn't happen to catch this exact sequence). The mechanism below is unambiguous from reading the code — labeled **inferred**, not **verified**. Antigravity should capture one live repro (§4) before/after the fix and paste the actual rows, per this repo's verification rule.

---

## 1. The mechanism

`SpeechOutput.speak()` (`app/src/main/java/com/voicetoinvoice/app/domain/voice/SpeechOutput.kt:49-70`):
```kotlin
suspend fun speak(text: String, preferOffline: Boolean = false, onComplete: (() -> Unit)? = null) =
    withContext(Dispatchers.IO + NonCancellable) {
        stop()
        rollingAudioBuffer?.setSuppressed(true)
        try {
            ...playback...
        } finally {
            rollingAudioBuffer?.setSuppressed(false)
        }
    }
```
This is called from `SttWorker.handleAssistantJob()` (`SttWorker.kt:645`) for **every** ASSISTANT-intent job, and suppression stays on for the full playback duration — which can be several seconds (offline TTS has a 15s bound, `SpeechOutput.kt:92`; a normal Hindi sentence commonly takes 1.5-3s to speak).

`RollingAudioBuffer`'s recording loop (both `startRollingBuffer()` at `RollingAudioBuffer.kt:102-121` and `resumeRollingBuffer()` at `:234-252`) responds to `isSuppressed.get() == true` by **zeroing the just-read microphone bytes before writing them into the ring buffer**:
```kotlin
if (isSuppressed.get()) {
    ...
    java.util.Arrays.fill(chunk, 0, bytesRead, 0.toByte())
}
synchronized(ringBuffer) { /* writes the (now-zeroed) chunk */ }
```
This is a deliberate anti-echo design: while the assistant is talking, mute the ring buffer so the assistant doesn't hear its own reply and treat it as a new command. But `RollingAudioBuffer` is **one shared instance for the whole app** — every mic button (SALE, STOCK, ASSISTANT) draws from the same physical `AudioRecord` stream and the same ring buffer. Suppression has no concept of "which intent" is recording; it blanks the buffer for **everyone** simultaneously.

**Consequence**: if the shopkeeper presses SALE while the assistant's TTS reply is still playing — or in the small window between the reply finishing and `finally { setSuppressed(false) }` actually running — the SALE press's `[pressStartMs-300, releaseMs+300]` window overlaps a span of the ring buffer that was overwritten with zeros. `extractAudioWindow()` still succeeds (there are enough *bytes*, they're just silent), so a real `SttJobRecord` is created and uploaded — but the WAV file is empty audio. STT returns a blank/near-blank transcript, the job lands in the Unmatched Queue as an unrecognized recording (or, per `SttWorker.kt`'s `rawTranscript.ifBlank { "Voice Recording (Pending Review)" }` fallback, a placeholder with no real content) — the sale never becomes a booked transaction. This matches "the sale never reaches the log" as reported: the *recording* exists, but its content was destroyed before it ever reached the STT pipeline.

**Why `resumeRollingBuffer()` doesn't dodge this**: the ASSISTANT job runs the full network `SttWorker` path (confirmed in `Docs/sttworker_execution_window_cancellation_fix_plan.md` §1 — `AssistantFastPath.handle()`, which never touches the ring buffer, currently has zero call sites and doesn't run). So every ASSISTANT press both extracts real audio from the ring buffer for itself *and* later suppresses that same shared buffer while speaking its answer — and a SALE press has no way to know that window is compromised.

---

## 2. Why this wasn't caught by the existing ambient-noise mitigation

`Docs/post_assistant_ambient_fix_plan.md` (ISSUE-075, Step 4) added exactly this suppression mechanism, but its stated purpose was narrower: muting the shopkeeper's own verbal acknowledgement ("हाँ जी") after hearing the assistant's reply, for **1 extra second after TTS**, so it doesn't land in the *next ASSISTANT press's* pre-roll. That plan's own "What Step 1-4 do NOT fix" section (lines 279-288) doesn't mention cross-intent silencing of a concurrent SALE/STOCK press — this is a real gap the previous plan didn't anticipate, not a regression of it.

---

## 3. Fix options — pick one, this is a real design tradeoff, not purely mechanical

**Do not silently pick one and implement — this needs a decision**, because both options have a genuine cost:

**Option A — narrow the suppression window instead of removing it.** Keep suppression, but shrink it to only the actual `MediaPlayer`/TTS-engine playback span (which is already roughly what happens), and additionally make `PttBurstCoalescer`/`extractAudioWindow` aware of *which byte ranges were suppressed* so a SALE/STOCK press whose window overlaps a suppressed range gets a **clear signal** (e.g. a toast "थोड़ा रुककर बोलिए, सहायक अभी बोल रहा है" / "wait a moment, the assistant is still speaking") instead of silently uploading dead air. This doesn't recover the audio, but it stops a silent, confusing failure — the shopkeeper knows to just say it again immediately.

**Option B — stop zeroing the buffer at all.** `MediaRecorder.AudioSource.VOICE_RECOGNITION` (used in both `startRollingBuffer()` and `resumeRollingBuffer()`, `RollingAudioBuffer.kt:86` and `:219`) already requests platform-level acoustic echo cancellation on most devices — its entire purpose is capturing clean speech while other audio may be playing. If AEC is doing its job, the self-hearing risk `setSuppressed` was defending against may already be handled by the audio source itself, making the suppression redundant *and* actively harmful to concurrent presses. **Risk**: if AEC is weak/absent on the user's specific device (measured to be a real problem historically per the `AssistantFastPath` comments about "the assistant hearing itself"), removing suppression could reintroduce that bug for the ASSISTANT's own next press.

**Recommendation for the user to confirm, not a decision made here**: Option A is lower-risk (keeps the existing anti-echo protection, only adds a warning for the rare overlap case) and should be tried first.

---

## 4. Verification plan (for whichever option is chosen)

1. Reproduce before any fix: press ASSISTANT, say something that gets a multi-second spoken reply, and press+release SALE **while the reply is still audibly playing**, saying a clear distinct phrase (e.g. "पांच किलो प्याज़"). Query `stt_job_logs` for the SALE job's `raw_transcript` and confirm it's blank/silent, and check the WAV upload size vs. a normal recording of the same hold duration (a silent recording still has real byte count, so compare `audioFileSize` to a known-good recording of similar `holdDurationMs` as a sanity check, not conclusive proof alone).
2. Repeat after the fix: same sequence, confirm either (Option A) the SALE press gets a clear "wait" warning and the shopkeeper's re-attempt afterward transcribes correctly, or (Option B) the SALE recording made *during* the TTS reply itself now captures the real spoken phrase.
3. Regression-check the original anti-echo case Step 4 of ISSUE-075 was defending: press ASSISTANT, let the reply finish, then immediately press ASSISTANT again and say something — confirm the second press does NOT transcribe the first press's own TTS reply as if it were user speech.
4. Log as `Docs/audit.md` ISSUE-081 (verify 080 — from `Docs/sttworker_execution_window_cancellation_fix_plan.md` — is the next number in use by then) with actual before/after `stt_job_logs` rows, not just a build log.

---

## Open question for the user

Which fix direction do you want — Option A (keep suppression, add a warning when a concurrent press overlaps it) or Option B (remove suppression, rely on `VOICE_RECOGNITION`'s built-in echo cancellation, verified against a real device before shipping)? This plan intentionally stops here rather than guessing.
