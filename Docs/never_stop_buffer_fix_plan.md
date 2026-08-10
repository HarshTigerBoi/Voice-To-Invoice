# Never-Stop Ring Buffer — Durable Fix Plan
## ISSUE-076 — Wrong-time SALE audio after assistant / after a few minutes; assistant logs have no audio

**Written**: 2026-08-04 (Claude Code) · **For**: Antigravity · **Severity**: P0

---

## 0. Plain-language diagnosis (read this first)

You are not imagining it. Two symptoms, **one design conflict**:

| What you see | Why |
|---|---|
| Fresh open → SALE records correctly | Ring buffer just started; `writeHead` and `totalBytesWritten` agree; no pause gaps yet |
| After assistant → SALE playback is wrong-time / “I was saying nothing” | Assistant **stops** the always-on mic so on-device SpeechRecognizer can own it. That punch a hole in the ring. Wall-clock → byte math then pulls **old ambient** (or silence) from the wrong place |
| Assistant logs have **no audio file** | By design today: `AssistantFastPath` inserts `audioFilePath = ""`. There is nothing to play back |
| “After a few minutes” without touching assistant | Same class of hole: app briefly backgrounds (`ON_STOP` → stop buffer → later `smartStart` resume), or TTS suppression / join races leave the coordinate system skewed |

**This is not an STT / parser / server bug.** Live traces from earlier sessions already showed correct server processing of whatever WAV the client uploaded — the WAV itself was the wrong window. Do not touch `process-voice-job/index.ts` or `domain/parser/` for this.

**Why the last 5 fixes keep failing:** ISSUE-061…075 patched *around* “stop buffer for assistant” (`resumeRollingBuffer`, `smartStart`, remove gap extraction, post-TTS mute, singleton wiring). Each patch fixed one failure mode and left the hole. As long as assistant **stops** the ring mid-session, SALE audio will keep getting corrupted under real shop use.

---

## 1. What the code does today (verified in tree, 2026-08-04)

### Assistant path — stops the continuous recorder

`PttMicButton.kt` (ASSISTANT branch):

```
stopRollingBuffer()
→ SpeechRecognizer.startListening()
→ hold …
→ finishListening()
→ resumeRollingBuffer()
→ if transcript blank: Toast only (no audio job)
→ if transcript non-blank: AssistantFastPath.handle() with audioFilePath=""
```

### SALE path — extracts a wall-clock window from the ring

```
pttBurstCoalescer → extractAudioWindow(startMs, endMs)
→ resolveWindowBytes(anchor = lastWriteAtMs, …)
```

`resolveWindowBytes` assumes audio was written **continuously** at 32 000 bytes/sec. After a stop/resume gap, timestamps that fall in (or near) the gap map onto **pre-gap bytes still sitting in the ring** — audio from earlier when nobody was pressing SALE. That matches “logs play a different time / I was saying nothing.”

### Already-applied patches that are necessary but **not sufficient**

Present in working tree (uncommitted relative to last commit): `setSharedInstance`, `resumeRollingBuffer`, `smartStart` on `ON_START`, assistant blank → Toast (no gap extract), AssistantFastPath write-intents → redirect (no extract), 1 s post-TTS mute, coalescers split per intent.

These reduce damage. They do **not** remove the root conflict.

---

## 2. The durable fix (one decision)

**Never stop `RollingAudioBuffer` for an assistant press.**

Only stop it on app background (`ON_STOP`), as today. Assistant uses the **same capture path as SALE**: real WAV + `SttJobRecord` with `captureIntent = ASSISTANT`.

Trade-off (accept explicitly):
- On-device SpeechRecognizer will usually fail while the ring holds the mic (historical 100% concurrent failure). That is OK.
- Assistant questions become ~server-round-trip latency when the fast path cannot run — slower than the ideal 1.5 s, but **correct**, and SALE stays correct.
- Optional opportunistic on-device attempt **without** stopping the buffer: if it returns text, use `AssistantFastPath` for READ_QUERY/etc.; if blank, fall through to the audio job. Never tear down the ring for that attempt.

This is Option A from `Docs/audio_pipeline_regression_fix_plan.md` §3 — recommended then, never shipped because ISSUE-072 chose the opposite (stop buffer for exclusive mic).

---

## 3. Fix steps (execute in order)

### Step 1 — Rewrite ASSISTANT branch to share SALE capture (no stop/resume)

**File:** `app/src/main/java/com/voicetoinvoice/app/ui/components/PttMicButton.kt`

Delete the entire exclusive-mic ASSISTANT block that calls:
- `rollingAudioBuffer.stopRollingBuffer()`
- `onDeviceRecognizer.startListening` / `finishListening` / `awaitResult`
- `rollingAudioBuffer.resumeRollingBuffer()`
- blank Toast / `AssistantFastPath.handle` from on-device transcript

Replace with: **fall through to the same path as SALE/STOCK** (ledger `recordPress` → `tryAwaitRelease` → coalescer → `extractAudioWindow` → insert `SttJobRecord` with `captureIntent = intent` → enqueue `SttWorker`).

Because `intent` is already `CaptureIntent.ASSISTANT` when this composable is the assistant button, the existing SALE enqueue code already stamps the right intent — no special case required for job creation.

**Optional fast path (keep only if it does not stop the buffer):**
After release, *opportunistically* call `onDeviceRecognizer.startListening` **without** stopping the ring. If `awaitResult(1500)` returns non-blank **and** `IntentRouter` classifies as READ_QUERY / VOID_LAST / PAYMENT_RECEIVED / ACTION_COMMAND, call `AssistantFastPath.handle(...)` and **skip** enqueueing the audio job for that press (or cancel it). If blank or write-shaped, keep the audio job. **Never** call `stopRollingBuffer` here.

If concurrent on-device STT is too flaky to bother, skip the optional path entirely in v1 — server path only for assistant.

### Step 2 — Make `SttWorker` answer ASSISTANT jobs from audio (server transcript)

**File:** `app/src/main/java/com/voicetoinvoice/app/domain/processor/SttWorker.kt`

Confirm `handleAssistantJob` (or equivalent branch on `captureIntent == ASSISTANT`) already:
1. Uploads audio via `process-voice-job` (or uses returned `raw_transcript`)
2. Routes READ_QUERY → spoken answer via `SpeechOutput`
3. Routes write-shaped utterances to the same confirm/redirect logic as today

If the assistant branch currently assumes a pre-filled on-device transcript and empty audio path, change it to require a real `audioFilePath` and use the server/local STT transcript. Do **not** invent a second parser — reuse existing assistant routing after transcript is known.

**Verify against:** `AssistantFastPath` / `IntentRouter` / existing `SttWorker` ASSISTANT handling from ISSUE-058 era. If a symbol is missing, stop and ask — do not invent a parallel router.

### Step 3 — Stop writing empty-audio ASSISTANT log rows from the fast path as the primary path

**File:** `app/src/main/java/com/voicetoinvoice/app/domain/voice/AssistantFastPath.kt`

Keep the object for the **optional** on-device success path only. Update the file doc comment: remove “caller must release RollingAudioBuffer.”

When used, it may still insert a job with `audioFilePath = ""` for instant READ_QUERY answers — that is fine as a secondary path. Primary path is Step 1’s real WAV job so Diagnostic Logs **always** have playable audio for assistant presses that went through the ring.

### Step 4 — Gap-safe resume for background only (defence in depth)

**File:** `app/src/main/java/com/voicetoinvoice/app/audio/RollingAudioBuffer.kt`

Assistant will no longer call stop/resume. Background still will.

Add discontinuity tracking so a SALE pressed during a dead gap cannot map onto old ring bytes:

```kotlin
@Volatile private var pausedAtMs: Long = 0L
@Volatile private var resumeAtMs: Long = 0L
```

- In `stopRollingBuffer()`, set `pausedAtMs = System.currentTimeMillis()` (under same lock section that readers care about, or immediately after join).
- In `resumeRollingBuffer()`, on first successful write (or at thread start), set `resumeAtMs = System.currentTimeMillis()`.
- In `extractAudioWindow`, if the requested `[effectiveStartMs, endMs]` overlaps `(pausedAtMs, resumeAtMs)` when `pausedAtMs > 0 && resumeAtMs > pausedAtMs`, **clamp** `effectiveStartMs = max(effectiveStartMs, resumeAtMs)` (and if that empties the window below `MIN_WINDOW_BYTES`, return null and log). Clear the pause markers after a successful post-resume window or after `startRollingBuffer()` cold reset.

Also in `startRollingBuffer()` cold reset: clear `pausedAtMs` / `resumeAtMs`.

Unit-test the clamp in `RollingBufferWindowTest` (or a small new pure helper) — no device required.

### Step 5 — Dead code cleanup (only what Step 1 made unreachable)

- Remove ASSISTANT-only `stopRollingBuffer` / `resumeRollingBuffer` call sites from `PttMicButton` (done in Step 1).
- Leave `resumeRollingBuffer` / `smartStart` in place — still required for `ON_START`.
- Do **not** retune `PRE_ROLL_MS` / `POST_ROLL_MS` / coalescer thresholds. Wrong-time audio is not a pre-roll tuning problem.

### Step 6 — Verification (device + DB — mandatory)

After `assembleDebug`, copy APK to Desktop folder as next `VoiceToInvoice_v<N>.apk` (ls the folder first).

On device, in order:

1. **Fresh launch → SALE** “दो किलो आलू” → playback matches; transcript matches.
2. **ASSISTANT press** “आज कितनी बिक्री?” → job appears in Diagnostic Logs **with playable audio**; spoken answer correct (may be slower than old fast path).
3. **Immediate SALE after assistant** “पाँच किलो टमाटर” → playback is the tomato utterance, **not** shop ambient from earlier, **not** TTS echo.
4. **Background 10s → foreground → SALE** → still correct (gap clamp must not null every window; post-resume speech must work).
5. **Leave app open 5+ minutes, no assistant, then SALE** → still correct.

DB check after the session:

```sql
SELECT job_id, status, capture_intent, hold_duration_ms, raw_transcript,
       length(diagnostic_trace_json) AS trace_len, created_at
FROM stt_job_logs
ORDER BY created_at DESC
LIMIT 20;
```

- ASSISTANT rows from the primary path should have real server traces (`trace_len` ≫ 186) when upload succeeded.
- No SALE row immediately after ASSISTANT should show ambient-only / previous-utterance transcripts when the shopkeeper clearly spoke a sale.

### Step 7 — Audit

Add **ISSUE-076** under RESOLVED (or OPEN until device verification). Cross-link ISSUE-073 / ISSUE-075 as “mitigations superseded by never-stop architecture.” Update §1 constant row if join timeout / invariants changed.

---

## 4. Scope boundaries

- **Do not** edit `supabase/functions/process-voice-job/index.ts` unless Step 2 proves the server rejects `capture_intent=ASSISTANT` audio jobs — cite the failing response before changing.
- **Do not** change confidence thresholds, segmenter, or Room schema.
- **Do not** reintroduce `stopRollingBuffer()` before SpeechRecognizer for assistant.
- **Do not** “fix” wrong audio by widening/narrowing pre-roll.

---

## 5. Open questions (ask user before guessing)

1. **Is slower assistant (~4–8 s server path) acceptable** in exchange for SALE always being correct? (Recommendation: **yes**. Correct ledger > fast wrong mic.)
2. Ship **optional** concurrent on-device fast path in the same PR, or server-only assistant first?

If unanswered, implementer defaults to: **server-only assistant in Step 1 (no on-device attempt)**, then optional fast path as a follow-up.

---

## 6. What Antigravity should put in “Deviations”

Anything not done literally from Steps 1–7, especially if on-device fast path was added or skipped.
