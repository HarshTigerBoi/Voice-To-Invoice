# Cross-Intent Audio Window Contamination in PttBurstCoalescer — Diagnosis & Fix Plan

**Written**: 2026-07-31 (Claude Code) · **For**: Antigravity · **Severity**: P0 — a recording can be submitted under one mic button (e.g. STOCK_IN) while actually containing audio from a different button's press, and the other press's own recording silently vanishes

## 1. What the evidence shows

**User-reported symptom** (their words): STOCK_IN recordings are "a mess... recording of a different time than when the button was pressed," while SALE recordings are proper.

**Live DB evidence** (`stt_job_logs`, queried directly): recent STOCK_IN jobs produced nonsense transcripts —
```
job 0e0adb90 (STOCK_IN, 10:24:30): raw_transcript "हाँ हाँ हाँ हाँ हाँ हाँ हाँ हाँ हाँ हाँ", holdDurationMs 1733
job 6e0f7cef (STOCK_IN, 10:25:17): raw_transcript "हाँ जी हाँ जी",                        holdDurationMs 1958
```
Both STT engines (Grok + Sarvam) returned HTTP 200 with real latencies, so these aren't upload/timeout failures — they transcribed *something*, just not a real stock phrase. A near-identical pattern (`"हाँ हाँ हाँ"` → parsed as `"आम"`/mango) recurs six times on 2026-07-29 across several distinct capture attempts, meaning this is a repeating class of failure, not a one-off. **Caveat, stated plainly**: the two 07-31 rows above are 47 seconds apart from each other and 1-3 minutes from the nearest other-intent job — too far apart for the mechanism below to have caused *these specific* rows via cross-intent merging (that requires two presses within 600ms of each other). I'm not claiming these exact rows are proof of the bug; I'm reporting them as the symptom that prompted the investigation. The bug found below is a verified code-level defect that fully explains the *class* of symptom the user described ("different time than button pressed"), and needs a fresh, deliberately-timed on-device reproduction to nail to a specific row (Step 3 below).

### The verified bug

`MainActivity.kt:282-284` constructs **one** `PttBurstCoalescer` instance for the entire app lifetime:
```kotlin
val sharedBurstCoalescer = remember(sharedRollingBuffer) {
    PttBurstCoalescer(300L, 300L, (sharedRollingBuffer.getBufferDurationSeconds() - 5) * 1000L)
}
```
and passes that **same instance** to three independent call sites, each representing a different `CaptureIntent`:
- `:406` → `HomeScreen` (`sharedPttBurstCoalescer`) → drives the **SALE**/**CREDIT_SALE** mic buttons
- `:577` → `StockInScreen` (`sharedPttBurstCoalescer`) → drives the **STOCK_IN**/**WASTE** mic buttons
- `:796` → `AssistantFloatingButton` (`pttBurstCoalescer`, non-optional param) → drives the **ASSISTANT** mic, visible on every screen except onboarding (`MainActivity.kt:791`)

`PttBurstCoalescer.kt` has no concept of `CaptureIntent` anywhere in its implementation. `recordPressRelease` (`:47-66`) keeps one mutable list, `currentGroupPairs`, and merges any new press into the existing pending group whenever the gap since the last release is under `gapThresholdMs` (`preRollMs + postRollMs` = **600ms**, `:42`):
```kotlin
val gapMs = pressMs - lastReleaseMs
if (gapMs >= gapThresholdMs || potentialSpanMs >= maxGroupSpanMs) {
    // flush the OLD group, start a new one
} else {
    currentGroupPairs.add(PressReleasePair(pressMs, safeReleaseMs))  // <-- merged, regardless of which button
}
```
Concretely: press SALE (releases at t=1500), then within 600ms press STOCK_IN (t=1600-2000) — the second press is appended to the *same* group as the first. When the group eventually flushes (via `checkAndFlushIfIdle`, `:68-78`, triggered by whichever screen's delayed idle-check coroutine happens to match the group's current last-release timestamp), `buildGroupLocked` (`:93-116`) computes:
```kotlin
val rawStartMs = max(0L, firstPressMs - preRollMs)   // anchored to the FIRST press in the group
```
— i.e. the extracted audio window starts at the *earlier* button's press time, not the button that actually got submitted. The job is tagged with whichever screen's `processGroup` closure happened to receive the flush, carrying audio that spans both utterances. The other button's own recording is never separately submitted — it was absorbed into this one job and its intent/content is lost.

This is a genuine architecture defect, not a tuning issue: `PttBurstCoalescer`'s entire purpose is intentional multi-press grouping (so a shopkeeper can say "5 kg आलू" ... "2 kg प्याज़" across two presses of the *same* button and have them treated as one multi-item sale) — but nothing stops it from grouping presses across *different* buttons/intents, which should never merge.

## 2. Fix plan

Give each `CaptureIntent` family its own `PttBurstCoalescer` instance instead of sharing one across all mic buttons. `PttWindowLedger` (the *other* shared singleton, `MainActivity.kt:281`, used only for `lastConsumedEndMs()`/`commitWindow()`) is correctly left as-is — its job is to prevent any two jobs of any intent from re-using overlapping ring-buffer byte ranges, which is a real cross-intent concern since there is only one physical microphone stream. Do not touch `PttWindowLedger` or `RollingAudioBuffer`.

### Step 1 — Three independent coalescer instances in `MainActivity.kt`

Replace the single `sharedBurstCoalescer` (`:282-284`) with three separately-`remember`'d instances, same constructor args (`300L, 300L, (sharedRollingBuffer.getBufferDurationSeconds() - 5) * 1000L)`), one per intent family:
```kotlin
val salePttBurstCoalescer = remember(sharedRollingBuffer) {
    PttBurstCoalescer(300L, 300L, (sharedRollingBuffer.getBufferDurationSeconds() - 5) * 1000L)
}
val stockPttBurstCoalescer = remember(sharedRollingBuffer) {
    PttBurstCoalescer(300L, 300L, (sharedRollingBuffer.getBufferDurationSeconds() - 5) * 1000L)
}
val assistantPttBurstCoalescer = remember(sharedRollingBuffer) {
    PttBurstCoalescer(300L, 300L, (sharedRollingBuffer.getBufferDurationSeconds() - 5) * 1000L)
}
```
Rename to make the intent split obvious at each call site, don't leave three same-named locals that are easy to mix up again.

### Step 2 — Rewire the three call sites

- `:406` (HomeScreen): `sharedPttBurstCoalescer = salePttBurstCoalescer`
- `:577` (StockInScreen): `sharedPttBurstCoalescer = stockPttBurstCoalescer`
- `:796` (AssistantFloatingButton): `pttBurstCoalescer = assistantPttBurstCoalescer`

No changes needed inside `HomeScreen.kt` or `StockInScreen.kt` themselves — both already have the `sharedPttBurstCoalescer: PttBurstCoalescer? = null` optional-param-with-local-fallback pattern (`HomeScreen.kt:101`, `StockInScreen.kt:46`); they just need to keep receiving a non-null, intent-scoped instance from `MainActivity`, which Step 1/2 provides. `AssistantFloatingButton.kt:29` takes a non-optional `PttBurstCoalescer` — no signature change needed there either, just pass the new dedicated instance.

### Step 3 — Verification: deliberately reproduce the cross-intent merge, then confirm it's gone

Before this fix, on the current (or a temporarily-reverted) build: press SALE mic and release, then within well under 600ms press STOCK_IN mic and speak a *distinct*, recognizable phrase (e.g. SALE: "पांच केला", release, immediately STOCK_IN: "दस संतरे"). Confirm via `stt_job_logs` that one of the two phrases never produced its own job, and that the job which *did* get submitted has a `holdDurationMs`/transcript inconsistent with a single clean press (e.g. contains fragments of both fruits, or a `pressCount` of 2 in `step_1_ptt_recording_metadata` when only one press was expected for that intent).

After the fix: repeat the exact same two-button rapid-press sequence. Confirm **two** separate jobs are created — one tagged SALE with "पांच केला", one tagged STOCK_IN with "दस संतरे" — each with its own correct `holdDurationMs` and audio window, and that pressing multiple times on the *same* button within 600ms still correctly coalesces into one multi-item job (don't break the feature this class exists for — verify with two SALE presses in a row, e.g. "पांच केला" then quickly "दस संतरे" both on the SALE button, and confirm that *still* produces one combined multi-item SALE job as before).

## 3. Scope boundaries

- **Do not modify `PttBurstCoalescer.kt` itself** (e.g. don't add an intent parameter to `recordPressRelease`/`PressReleasePair`). Giving each intent its own instance is strictly simpler and lower-risk than making the shared class intent-aware, and preserves the existing multi-press-same-intent behavior with zero logic changes.
- **Do not touch `PttWindowLedger`, `RollingAudioBuffer`, or the pre/post-roll constants** (`300L`/`300L` everywhere) — those are correctly shared/global and are not implicated by this bug.
- **Do not change `STOCK_PRE_ROLL_MS`/`STOCK_POST_ROLL_MS` in `StockInScreen.kt`** — they're already identical to `HomeScreen.kt`'s `PRE_ROLL_MS`/`POST_ROLL_MS` (300L/300L each); verified by direct comparison, this was never a config-mismatch issue.
- Do not assume this fully explains the two specific 07-31 garbage STOCK_IN rows cited in §1 — their timing doesn't fit the 600ms window for this specific mechanism. If Step 3's reproduction doesn't account for those exact symptoms, say so plainly rather than closing the issue on this fix alone; a second, separate cause (e.g. genuine ambient-noise hallucination by the STT engines) may still need investigating.

## 4. Verification

1. Build (`./gradlew.bat testDebugUnitTest`, `./gradlew.bat assembleDebug`), install, and run Step 3's reproduction test both before and after the fix as described.
2. Confirm ordinary single-button multi-item bursts (same intent, multiple quick presses) still coalesce correctly post-fix — this is the regression risk of this change.
3. Query `stt_job_logs` for the two test jobs from Step 3 and paste the actual `raw_transcript`/`captureIntent`/`holdDurationMs` values as proof, not just "build succeeded."
4. Log this in `Docs/audit.md` under RESOLVED with the next sequential `ISSUE-0NN` (check the current highest number first) — this is a distinct root cause from the shopId-literal-"null" bug (`Docs/shopid_null_literal_fix_plan.md`) and the zero-line-job feedback gaps (`Docs/voice_capture_feedback_fix_plan.md`); don't fold it into either of those entries.
5. End with a **Deviations** section, including the actual before/after `stt_job_logs` rows from Step 3.
