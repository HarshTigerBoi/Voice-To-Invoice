# Silent Sale/Udhaar Drop on Short Recordings — Diagnosis & Fix Plan

**Written**: 2026-07-31 (Claude Code) · **For**: Antigravity · **Severity**: P1 — sales are landing in an unreachable-feeling review queue instead of the ledger, with no signal to the shopkeeper that anything went wrong

## 1. What the two traces actually show

```
Trace A (ASSISTANT mic, question button):
{"fast_path":true,"on_device_transcript":"","press_start_ms":1785488380274,"release_ms":1785488382079,
 "outcome":"blank_transcript","answer":"समझ नहीं आया, कृपया फिर से बोलिए"}

Trace B (SALE mic, appears twice in the logs for one recording):
{"capture_intent":"SALE","audio_bytes":75788,"upload_ms":8576,"upload_response_null":false,
 "initial_status":"PARSED","outcome":"processed","line_count":0,"committed_count":0}
```

These are **two different mic buttons, 93 seconds apart** — not two attempts at the same press. Both degrade to "nothing recognized," but through different subsystems.

**Trace B, in full**: `audio_bytes=75788` ÷ 32,000 bytes/sec (16kHz/16-bit mono) ≈ **2.37s of captured audio**, meaning the actual hold was ≈1.77s once the 300ms pre/post-roll (`PttBurstCoalescer.kt:38-39`) is subtracted — the same order of magnitude as Trace A's measured hold (`release_ms - press_start_ms = 1805ms`). The upload itself succeeded (`upload_response_null:false`, `upload_ms:8576`) and the server's inline pipeline ran to completion (`initial_status:"PARSED"`) — this is not a network or crash failure. `supabase/functions/process-voice-job/index.ts` ran dual STT (Grok + Sarvam), the on-device transcript, and the adaptive re-decode pass (`needsReDecode` triggers whenever `scoreTranscript` returns 0, `index.ts:463,988`) — and **all of them came back empty**. The "forced AI fallback" added for exactly this class of bug (`index.ts:1322-1335`, from `Docs/stt_blackout_and_review_fix_plan.md` Step 5.2 / ISSUE-065) is explicitly gated on `transcript.trim().length > 0` — there's nothing for it to force-interpret when the transcript itself is empty, so `finalParsedItems` stays `[]`, `lineCount=0`, and `finalStatus="PARSED"` (`index.ts:1753-1760`, since `saleEntries.length === 0`).

**This is not data loss.** The Step 5.3 "widen safety net" (`index.ts:2066-2088`) already writes a fallback `unmatched_queue` row whenever a job produces zero ledger rows and zero review rows, and the client's own `SttWorker.kt:468-479` does the same locally. Verified the reachability chain is fully wired:
- `SttJobDao.getParsedJobsFlow()` (`SttJobDao.kt:49-50`) selects `status IN ('PARSED','PARTIALLY_CONFIRMED','ERROR','FAILED')` — Trace B's job (status PARSED) is included.
- `HomeScreen.kt:145-148`: `if (lines.isEmpty()) 1 else lines.count {...}` — a zero-line job counts as **1** toward the pending badge, not 0. (This is the exact fix from `stt_blackout_and_review_fix_plan.md` Step 4 — already shipped.)
- `PendingConfirmationsSheet.kt:248-251`: a job with no pending/committed lines renders `"⚠️ आवाज़ समझ नहीं आई"` with **"फिर कोशिश करें"** (retry) and **"हाथ से भरें"** (manual entry) actions (`:303`, `:331`) — also already shipped.

So architecturally, Trace B's job **should** have surfaced as a "1" on the Home badge, openable into a card with retry/manual-entry actions. The user's "nothing got recorded" report means one of three things, and the plan below addresses all three:

1. **The badge is real but was missed** — it's a passive, silent UI change with no distinct feedback at the moment of failure. The haptic pulse on mic release (`PttMicButton.kt:159-163`) fires identically whether the recording will succeed or fail 8+ seconds later; there is no negative-outcome signal.
2. **The installed APK predates these fixes.** This can't be fully ruled out from the trace alone — see Open Question 1.
3. **Genuinely marginal audio.** A ~1.7-1.8s hold is short for a full "[qty][unit][item]" phrase; both the on-device recognizer and both cloud STT engines failing independently on the same clip is consistent with audio that's simply too short/clipped to transcribe, not a parsing bug.

### Open Question 1 — Trace A may be from a stale build (needs your confirmation)

Under current `HEAD`, `AssistantFastPath.handle()` (`AssistantFastPath.kt:44-186`) is only ever called from one site: `PttMicButton.kt:172-180`, guarded by `if (result.transcript.isNotBlank())`. Since `clean = transcript.trim()` inside `handle()` (`AssistantFastPath.kt:55`) operates on that exact same string, `clean.isBlank()` (the condition that produces `outcome:"blank_transcript"`, `AssistantFastPath.kt:66-69`) **cannot be true** if the call-site guard already passed — trimming can't erase a character that made `isNotBlank()` true. This means Trace A, as literally reproduced, isn't reachable from the code currently in the repo.

**Please confirm which APK build produced these two traces** (check the file's `versionName`/`versionCode`, or when it was installed) against `git log` — the repo's last build was `v95.apk` (commit `b728bf2`). If the phone is running something older than the STT-blackout fix round (commits `6390287`/`1764f4c` and earlier), that alone would explain Trace A and likely worsen Trace B too. If it's confirmed to be v95 and this still reproduces, that's a new, currently-unexplained regression in the guard and needs a fresh trace with `adb logcat` around the press to catch it live — don't guess a fix for it blind.

## 2. Fix plan

Two independent problems, both worth fixing regardless of the answer to Open Question 1: audio this short should get instant feedback before a network round-trip, and a job that comes back with zero lines should announce itself instead of waiting to be noticed.

### Step 1 — Give short-hold recordings instant, local feedback (no network round-trip)

`PttMicButton.kt` already has an upper-bound advisory warning (`LONG_HOLD_WARNING_MS = 20_000L`, `:76`, checked at `:151` and `:233`) but no lower bound at all. Add a companion constant and check, mirroring the existing pattern exactly, in both the `CaptureIntent.ASSISTANT` branch (after computing `holdDurationMs` at `:149`) and the main branch (after `:231`):

```kotlin
// Sits alongside LONG_HOLD_WARNING_MS at :76
val SHORT_HOLD_ADVISORY_MS = 1000L
```

```kotlin
if (holdDurationMs < SHORT_HOLD_ADVISORY_MS) {
    Toast.makeText(
        context,
        "बहुत छोटी रिकॉर्डिंग हो सकती है — ज़रूरत हो तो दोबारा बोलिए",
        Toast.LENGTH_SHORT
    ).show()
}
```

This must be **advisory only, never blocking** — do not skip the upload/enqueue path below it. A genuinely short but complete phrase ("बीस आलू") can still transcribe fine; the point is to tell the user something *might* need a repeat, the same way the long-hold warning tells them something might need splitting, without taking away their ability to proceed. Use `1000L` as the starting value — it's below both observed failing holds (≈1.77-1.8s) so it won't fire on them and prove nothing either way; treat it as a tunable pending real usage data, not a value to defend. Do **not** turn this into a hard rejection (no `return@detectTapGestures` before the upload logic) — that would risk blocking legitimately short, valid sales.

### Step 2 — Make a zero-line outcome announce itself, not wait to be found

The reachability chain (badge + sheet) is already correct; what's missing is a proactive signal at the moment the outcome is known, for the two capture intents where silence is costliest: `SALE` and `CREDIT_SALE`.

In `SttWorker.kt`, at the point immediately after `committedCount` is computed for a non-ASSISTANT job (`:190-211`, where `clientTrace.put("committed_count", committedCount)` already happens), when `lineCount == 0 && (jobRecord.captureIntent == CaptureIntent.SALE || jobRecord.captureIntent == CaptureIntent.CREDIT_SALE)`, post a local notification (or, if the app is foregrounded, rely on a broadcast the currently-open `HomeScreen` observes) distinct from the normal "processed" outcome. Concretely:

1. Add a `SttJobRecord` boolean-style signal the UI can react to as it happens, rather than only on the next `pendingJobs` Flow recomposition tick — check whether `SttJobDao` already exposes a `Flow` keyed to "most recent job update" that `HomeScreen` could `LaunchedEffect` on to fire a one-shot `snackbarHostState.showSnackbar(...)` (the `SnackbarHostState` already exists at `HomeScreen.kt:214`, used elsewhere at `:668/:672`). If no such single-latest-update flow exists, the simplest correct addition is a `Flow<SttJobRecord?>` for "most recently updated job with `status IN ('PARSED','FAILED') AND lineCount == 0`", surfaced via a `LaunchedEffect(latestZeroLineJob?.id)` that shows: `"रिकॉर्डिंग समझ नहीं आई — समीक्षा में देखें"` (or, when `job.rawTranscript.isNotBlank()`, include it: `"\"${job.rawTranscript}\" समझ नहीं आया — समीक्षा में देखें"`).
2. Keep this **additive** to the existing badge/sheet flow, not a replacement — the badge must still work for jobs that finish while the app is backgrounded (WorkManager keeps running per the "server-first instant processing" design in `CLAUDE.md`); the snackbar is only for the case where the user is still looking at the screen when the result lands.

### Step 3 — Give the client-side unmatched-queue write the same placeholder text the server already uses

`SttWorker.kt:458` and `:473` write `rawTranscript = rawTranscript` into the local `UnmatchedQueueItem`, where `rawTranscript` (`:143`) is `jsonRes.optString("raw_transcript", "")` — genuinely empty string when the server's transcript was blank. `PendingConfirmationsSheet.kt:251` already displays a fallback label when this is blank, so the **on-screen** UX is fine — but the **synced Supabase row** (`cloudSyncManager.syncReviewItemToCloud`, same line) will carry an empty `raw_transcript` where the server's own equivalent fallback row (`index.ts:2075`) uses `"Voice Recording (Pending Review)"`. For consistency when someone reads `unmatched_queue` directly (e.g. via Supabase dashboard or `Docs/audit.md` diagnosis), change both sites to:

```kotlin
rawTranscript = rawTranscript.ifBlank { "Voice Recording (Pending Review)" },
```

This is cosmetic/diagnostic-quality-of-life, not a functional fix — do it as part of the same commit since it's a one-line change adjacent to Step 2's edits, not a separate pass.

## 3. Scope boundaries

- **Do not touch `RollingAudioBuffer`, `PttWindowLedger`, `PttBurstCoalescer`, or the server's segmenter/Grok-interpretation logic.** Per `Docs/stt_blackout_and_review_fix_plan.md` §3, capture and parsing are confirmed working when given real audio; this plan is about *feedback*, not *recognition accuracy*. Do not try to lower STT thresholds or add more retry passes to rescue a transcript that's genuinely empty on all three engines — there's nothing there to rescue.
- **Do not make Step 1's threshold a hard block.** A false rejection of a valid short sale is worse than an occasional missed advisory.
- **Do not duplicate the badge/sheet mechanism.** It already works (verified above) — Step 2 is additive (an immediate nudge), not a rebuild.
- **Do not add a new Kotlin parser retry using `OrderingSegmenter`/`MultiSaleDetector`/`VoiceParser`** — those classes are not in the live SALE/CREDIT_SALE pipeline (confirmed: server-side `index.ts` owns all real parsing; the Kotlin parsers are reachable only from the manual text-entry fallback in `HomeScreen.kt`).

## 4. Verification

1. Confirm Open Question 1 (APK version) before writing any code — if the installed build predates the STT-blackout fix commits, note that explicitly in the Deviations section since it changes how much of this plan is actually addressing a live bug vs. an already-fixed one.
2. On-device: record a deliberately short (~0.7-1s) genuine phrase and confirm the new Toast fires and the recording still uploads/processes normally afterward (not blocked).
3. Record a deliberately garbled/silent ~2s clip (mimic Trace B); confirm within ~15s a Snackbar (Step 2) appears while the app is foregrounded, and independently confirm the Home badge shows "1" and the sheet's card renders per the existing `PendingConfirmationsSheet.kt:251` behavior.
4. Check the synced `unmatched_queue` row in Supabase for that test job and confirm `raw_transcript` is `"Voice Recording (Pending Review)"`, not empty.
5. Run `./gradlew.bat test`, then `./gradlew.bat assembleDebug`, then copy the APK per the standing rule in `CLAUDE.md` (`ls` the output folder first for the current highest version number).
6. Log a new `ISSUE-0NN` entry in `Docs/audit.md` under RESOLVED (check the current highest number first — the sub-investigation for this plan found `ISSUE-065` as the most recent related entry) describing this as a refinement of ISSUE-065's residual gap: "empty-transcript jobs were reachable but silent."
7. End with a **Deviations** section, especially noting the answer to Open Question 1 and the final `SHORT_HOLD_ADVISORY_MS` value used if changed from `1000L`.
