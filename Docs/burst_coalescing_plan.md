# Burst Coalescing: Replace Fixed Pre/Post-Roll Split With Gap-Based Audio Merging

**Status:** approved design, not yet implemented. Written for an agent with no memory of the discussion that produced it — everything needed to execute is below. Reference file paths are relative to repo root as of 2026-07-29.

## 1. Problem this replaces

Today, every PTT (push-to-talk) recording is cut into its own audio clip using a fixed pre-roll/post-roll window:

- `PRE_ROLL_MS = 300L`, `POST_ROLL_MS = 300L`, `PREROLL_RESERVE_MS = 200L` — all defined in [`app/src/main/java/com/voicetoinvoice/app/ui/screens/home/HomeScreen.kt`](../app/src/main/java/com/voicetoinvoice/app/ui/screens/home/HomeScreen.kt) lines 63-69.
- The window-arbitration logic lives inline in the `pointerInput` press/release handler, `HomeScreen.kt` lines 295-322, and reads/writes [`PttWindowLedger`](../app/src/main/java/com/voicetoinvoice/app/audio/PttWindowLedger.kt) to prevent two recordings from claiming overlapping ring-buffer audio.
- Bug: when a shopkeeper presses again quickly after releasing (rapid multi-item recording), the code decides how much post-roll job N gets by sleeping exactly `POST_ROLL_MS` (300ms) after release and checking once whether a new press has landed (`HomeScreen.kt:301-302`, `nextPressAfter`). If the next press arrives within that 300ms window, one formula runs (graceful split, reserving 200ms for job N+1's pre-roll). If it arrives even 1ms later, a completely different formula runs (job N grabs the full 300ms unclamped). This produces a discontinuity: at gap=300ms job N+1 gets ~200ms of pre-roll; at gap=301ms it gets ~1ms. The leading word of the next item (almost always the *quantity* — "चार किलो आलू") lands on the wrong job's audio clip, corrupting one job's transcript and starving the other of the token whose loss is most expensive (a dropped quantity silently defaults to 1 and under-bills the sale).
- This is a recurrence of `ISSUE-028` in `Docs/audit.md` (§2, dated 2026-07-26) — the original fix added `PREROLL_RESERVE_MS` but only closed the gap band it happened to be tested against; the underlying two-formula-with-a-seam shape is still present and will resurface at any gap threshold chosen.

**Root cause:** the code tries to *decide, in advance, how to cut* a shared piece of audio between two jobs using only a timing heuristic. Cutting audio based on timing alone throws away information — whether "चार किलो" belongs to the previous sale or the next one is a semantic question, not an acoustic one, and no amount of millisecond arithmetic can recover an answer that only the parser has enough context to give.

## 2. Design: don't cut — coalesce and let the parser decide

When two (or more) presses are close enough together that their desired pre-roll/post-roll windows would overlap, **do not split the audio into separate jobs.** Instead, merge them into a single job spanning the whole rapid burst, and let the existing multi-item parsing pipeline (which already splits one recording into multiple sale lines) handle the split at the semantic layer, where it belongs.

### 2.1 Grouping rule — gap-based, dynamic, no fixed item/time cap

Do **not** cap a group at a fixed item count (e.g. "3 items then flush") or a fixed duration (e.g. "12 seconds then flush"). A fixed cap just reintroduces the exact same cut-in-the-middle problem this design exists to eliminate, at a different boundary (e.g. between item 3 and item 4 instead of mid-word).

Instead:
- Maintain one **open group** while the shopkeeper keeps recording in rapid succession.
- A new press joins the currently open group if the gap since the previous release is `< GAP_THRESHOLD_MS`.
- `GAP_THRESHOLD_MS = PRE_ROLL_MS + POST_ROLL_MS = 600` — this is **derived, not tuned**: it is exactly the gap below which job N's desired post-roll and job N+1's desired pre-roll would physically overlap. If `PRE_ROLL_MS`/`POST_ROLL_MS` ever change, this threshold must be computed from them, never hardcoded separately, so it cannot drift out of sync.
- The group closes (flushes for transcription) only when:
  1. A gap `≥ GAP_THRESHOLD_MS` occurs (genuine pause — the shopkeeper is done with this burst), **or**
  2. Continuing to extend the group would risk running the group's start past the ring buffer's retention window. The buffer (`RollingAudioBuffer`, see `app/src/main/java/com/voicetoinvoice/app/audio/RollingAudioBuffer.kt`) holds a fixed `bufferDurationSeconds` of PCM (`bufferCapacity = bytesPerSecond * bufferDurationSeconds`, line ~19); if a group's total elapsed span approaches that ceiling, its own leading audio starts getting silently overwritten (`minStartAllowed` truncation, `RollingAudioBuffer.kt` lines 137-141, logged but not surfaced to the caller as an error). This is the same failure class as `ISSUE-029` (`Docs/audit.md`).

  **As part of this change, increase `bufferDurationSeconds` from 30 to 120** (`RollingAudioBuffer.kt:18`). Memory cost is trivial (16-bit mono PCM @ 32,000 bytes/sec → 120s ≈ 3.8MB, negligible on any Android device this app targets) — 30s was never a memory constraint, it was just never revisited. Do **not** make it unbounded: every group is one multipart upload, and this app explicitly targets low-end devices on patchy/rural connectivity (see root `CLAUDE.md`), so a much larger buffer directly means a much larger, slower, more data-hungry upload for every rapid-fire burst. There is also currently no local guard against Grok/Sarvam's own audio duration or file-size limits (checked `process-voice-job/index.ts` — none exists), so an arbitrarily large buffer risks a burst silently exceeding a provider-side limit and failing the *entire* group (the "total group loss" failure mode from §2.3, made more likely rather than less). 120s gives ~4x headroom over a realistic rapid-fire order (10-15 items) without courting either cost.

  The group safety-flush margin must be **derived from `bufferDurationSeconds`, not a hardcoded literal** — e.g. `SAFETY_FLUSH_MS = (bufferDurationSeconds - 5) * 1000`, a 5-second margin below actual buffer capacity, mirroring the spirit of the existing `LONG_HOLD_WARNING_MS = 25000L` single-hold mitigation in `HomeScreen.kt:75` (which itself was 5s below the *old* 30s buffer) without hardcoding a number that silently goes stale if the buffer size changes again later. Surface the same "बहुत लंबी रिकॉर्डिंग" toast used for single long holds (`HomeScreen.kt:278-284`) if a group hits this flush.
- There is no other cap. If gaps stay under 600ms, the group keeps growing until the buffer-proximity safety flush (~115s at the new 120s buffer size). This is intentional — do not add an item-count limit "for safety"; the buffer-proximity flush is the only real physical constraint and is already sufficient.

### 2.2 What gets sent, and what doesn't change

- One audio extraction per group: `RollingAudioBuffer.extractAudioWindow(startMs = firstPress - PRE_ROLL_MS, endMs = lastRelease + POST_ROLL_MS, ...)`. Only the very first press in a group gets pre-roll and only the very last release gets post-roll — internal boundaries within the group are not cut at all, so there is nothing to arbitrate.
- One `SttJobRecord` per group, one upload to `process-voice-job`, one STT call, one AI/segmenter parse.
- The existing multi-item detection/segmentation pipeline (`OrderingSegmenter.kt`, `MultiSaleDetector.kt` client-side; the combinatorial fuzzy phonetic segmenter + Grok interpretation server-side in `supabase/functions/process-voice-job/index.ts`) already turns one transcript into N sale lines. This is not new machinery — a burst group is handled exactly like today's existing case of "shopkeeper says 3 items in one long single hold." Nothing about the multi-item parsing logic changes.
- **Transactions are never merged.** Each parsed line stays independently confirmable exactly as it is today. Grouping is an audio/transcription-layer decision only; it must not be read anywhere as "these items are the same sale" or "same customer." Verify: the pending-lines counter at `HomeScreen.kt:122-126` already sums `parsePendingLines(job).count { ... }` per job (i.e. per-line, not per-job) — this already works correctly for multi-line jobs today and needs no change.
- Boundaries are not thrown away, just not used to cut. Record each press/release offset *relative to the merged audio's start* and pass them to the parser as **soft priors**, not hard cuts: `utteranceBoundaries: [{ pressOffsetMs, releaseOffsetMs }, ...]`. The segmenter should prefer placing an item-boundary near a press offset but must remain free to cross one when the grammar/quantity evidence disagrees — e.g. if the shopkeeper paused mid-item to think, that pause should not force a split.

### 2.3 Failure-mode reasoning (why this is not riskier than today)

Two distinct failure points exist in the pipeline; they were previously conflated and need to be kept separate:

1. **Transcription failure** — the STT API call itself errors (network/outage/5xx). This affects the *whole* audio blob for that job at once, since it's one API call per job. A burst-coalesced job of, say, 5 items fails together on this path. **This is not a new risk**: a shopkeeper today can already speak 5 items in a single long hold, producing one job with 5 items, and that job already fails-together on STT outage. Coalescing changes *how often* multi-item jobs occur, not whether that failure mode exists. It is also already handled: `SttWorker.kt` returns `Result.retry()` on failure (lines ~404, 408) and WorkManager retries automatically.
2. **Parsing/segmentation failure** — STT succeeds, but the segmenter/Grok mis-splits the transcript (drops one item, merges two, misreads one quantity). This is the far more common failure mode in practice, and it is typically **partial** — one item wrong, not all of them. This is exactly what §2.4 below is designed to catch, and coalescing makes it *more* detectable than today, not less, because today a dropped item inside a multi-item single-hold recording is completely invisible (no cross-check exists at all).

Net effect: coalescing does not introduce a new failure category. It extends the frequency of an already-accepted one (multi-item-per-job), and adds a detection mechanism (§2.4) that does not exist today.

### 2.4 Cross-check: quantity-mention count vs. segmented-line count

Add a validation signal that does not exist today: **count how many explicit quantity/number mentions appear in the raw transcript, and compare that to how many sale lines the parser produced.**

- Server-side, the building blocks already exist: `extractSpokenNumbers` and `HINDI_NUMBER_MAP` are already imported and used in `supabase/functions/process-voice-job/index.ts` (see lines 876-878, 1400, 1431-1432 for existing call patterns). Reuse `extractSpokenNumbers(chosenRaw).length` (plus digit/Hindi-number-word detection already used at line 1400) as the "expected item count" signal.
- Compare that count against the number of lines the segmenter/Grok actually returned for the job.
- If they match: trust the result, proceed as today (auto-confirm high-confidence lines, queue ambiguous ones — no change to existing confidence logic).
- If they don't match: something is wrong — a quantity was likely spoken but not turned into a line (or vice versa). Do not silently auto-confirm any line from this job; route the whole job to the review queue with an explicit reason string (follow the existing `implausibility` reason-string pattern already used for other guards in this file, e.g. lines 1403-1406, 1413-1416, 1419-1422).
- **Press count is a secondary/weaker signal**, not the primary one — a single press can already legitimately contain multiple items (existing `MultiSaleDetector` behavior), so "press count == item count" is not a valid invariant. Only use press count as a soft floor check (at least this many distinct utterance-attempts occurred), never as the primary mismatch trigger. Quantity-mention count vs. segmented-line count is the primary, reliable cross-check.
- **Efficiency refinement (do this if straightforward, skip if it meaningfully complicates the change):** if the deterministic segmenter (`OrderingSegmenter.kt` client-side / the phonetic segmenter server-side) already confidently resolved most of the transcript into clean `[qty][unit][item]` triples and only a small remainder is ambiguous or unresolved, send **only the ambiguous remainder** to Grok for interpretation, not the full transcript. This keeps AI-parse blast radius limited to the actually-uncertain span and reduces token cost/latency on the (common) case where the deterministic segmenter got everything right. This mirrors the existing `needsReDecode` / `bestScore < 3` gating pattern already in `index.ts` (lines 917-918) that avoids paying for a second STT pass when the first one already produced a usable parse — apply the same "only pay for what's uncertain" principle to the AI-interpretation step.

## 3. Implementation steps

### 3.0 Ring buffer size

In `app/src/main/java/com/voicetoinvoice/app/audio/RollingAudioBuffer.kt:18`, change:
```kotlin
private val bufferDurationSeconds = 120  // was 30
```
No other code depends on the literal value 30 for this buffer (verified — the only other `30s`-ish constants in the repo are an unrelated WorkManager poll interval in `SttWorker.kt`/`SttProxyClient.kt` and an unrelated foreground-service sweep in `AppForegroundService.kt:64`; neither is coupled to `RollingAudioBuffer`). This is a one-line change; `bufferCapacity` is already computed from this constant.

### 3.1 Client — new burst coalescer

Create `app/src/main/java/com/voicetoinvoice/app/audio/PttBurstCoalescer.kt` (alongside the existing `PttWindowLedger.kt` in the same package). Responsibilities:
- Accumulate presses into an open group while `gap < GAP_THRESHOLD_MS` (600, derived from `PRE_ROLL_MS + POST_ROLL_MS`, not hardcoded).
- Force-flush a group if its span would reach the buffer-proximity safety margin: `(bufferDurationSeconds - 5) * 1000` ms — derived from `RollingAudioBuffer`'s actual configured duration (120s per §3.0, so ~115s), not a hardcoded literal. Read `bufferDurationSeconds` from `RollingAudioBuffer` (expose a getter if it isn't already accessible) rather than duplicating the number in the coalescer.
- On flush, emit: the resolved `[startMs, endMs]` window (first press − `PRE_ROLL_MS` to last release + `POST_ROLL_MS`, clamped against `PttWindowLedger.lastConsumedEndMs()` exactly as today, since two *different* groups still must never overlap), plus the list of `{pressOffsetMs, releaseOffsetMs}` boundaries relative to the window start.
- Keep this class pure/testable — no Android framework calls, no coroutines inside the core grouping logic (the caller in `HomeScreen.kt` owns timing/coroutines; this class owns the grouping decision function given a sequence of press/release timestamps).

### 3.2 Client — HomeScreen.kt wiring

Replace the inline block at `HomeScreen.kt:298-322` (`delay(POST_ROLL_MS)` + manual `clampedStartMs`/`clampedEndMs`/`nextPress` arithmetic) with a call into `PttBurstCoalescer`. Concretely:
- On each press/release cycle, hand the timestamps to the coalescer instead of computing a window directly.
- Only extract audio and enqueue an `SttJobRecord` when the coalescer reports a group has flushed (either because a real gap occurred or the safety margin was hit).
- Delete `PREROLL_RESERVE_MS` (`HomeScreen.kt:69`) and the branch that used it (`HomeScreen.kt:305-306`) — this is not being fixed, it is being removed, because the coalescer makes the scenario it was patching (two jobs contending for the same audio) no longer occur.
- Delete the dead `lastAudioEndMs` variable (`HomeScreen.kt:139`) and its stale "Fix 1" comment — it is unused (superseded by `PttWindowLedger`) and misleads about where the overlap invariant actually lives.
- Correct the doc comment on `PRE_ROLL_MS` (`HomeScreen.kt:61-63`) to describe the new behavior (full pre-roll on the *first* press of a group; internal presses within a group get no cut at all).

### 3.3 Data model

`SttJobRecord` (`app/src/main/java/com/voicetoinvoice/app/data/local/entity/SttJobRecord.kt`) needs two new fields:
- `utteranceBoundariesJson: String` — JSON array of `{pressOffsetMs, releaseOffsetMs}`, relative to the job's audio window start.
- `pressCount: Int` — number of presses that were coalesced into this job (1 for a normal solo recording).

`AppDatabase` is currently at `version = 14` (`app/src/main/java/com/voicetoinvoice/app/data/local/AppDatabase.kt:28`) with migrations `MIGRATION_1_2` through `MIGRATION_13_14` registered via `.addMigrations(...)` (line 256). Follow the exact existing pattern: bump to `version = 15`, add `MIGRATION_14_15` with try/catch'd `ALTER TABLE stt_jobs ADD COLUMN ...` statements (see `MIGRATION_7_8` around line 136 for the established style used for prior `stt_jobs` column additions), register it in the `.addMigrations(...)` chain. Do not use auto-migrations — this codebase does not use them.

### 3.4 Client → server payload

`SttWorker.kt` builds the metadata JSON sent to the edge function at lines 68-76 (`pressStartMs`, `releaseMs`, `holdDurationMs`, `audioStartMs`, `audioEndMs`). Add `utteranceBoundariesJson` and `pressCount` to this same `JSONObject`.

### 3.5 Server — `supabase/functions/process-voice-job/index.ts`

- Read `pressCount` and `utteranceBoundaries` from the incoming metadata (mirrors how `pressStartMs`/`audioStartMs`/etc. are already read via `metadata.xxx` — see the existing pattern at lines 1393-1395).
- Feed `utteranceBoundaries` into the segmentation step as soft priors (prefer a line-split near a boundary offset; do not force one) — the exact injection point is wherever segments are currently produced/scored (`grokScored.segments` / `sarvamScored.segments` / `onDeviceScored.segments`, referenced around line 897-898) and wherever the multi-item Grok prompt is constructed (`systemPrompt`/`userPrompt` around lines 999-1188). Add the boundary list to the user prompt context so Grok can use it as a hint, and/or use it to bias the deterministic segmenter's split points if that is architecturally simpler — either is acceptable, the requirement is "prefer, don't force."
- Add the quantity-count-vs-line-count cross-check described in §2.4, using the existing `extractSpokenNumbers`/`HINDI_NUMBER_MAP` imports already present in this file (see current usages at lines 876-878, 1400, 1431-1432 for the established call pattern). On mismatch, append an `implausibility` reason string using the same accumulation pattern already used at lines 1403-1406 / 1413-1416 / 1419-1422 (`implausibility = implausibility ? \`${implausibility} | ${newReason}\` : newReason`), and ensure this blocks auto-confirm for every line in the job (follow how `preRollActualMs < 150 && isDefaultedQty` already blocks auto-confirm today).
- Keep the existing single-job pre-roll clip guard (lines 1392-1406) as-is for jobs where `pressCount === 1` — it is still valid for the (still-common) solo-recording case.
- After implementing, this repo has standing authorization to deploy without asking (see root `CLAUDE.md` — "Supabase Edge Function deploys — never ask, always deploy"): once tests pass and the diff is sane, run `npx supabase functions deploy process-voice-job --project-ref <ref>` immediately, then re-fetch the live bundle and grep for a marker string from the new code to confirm the deploy actually carried the change (this project has a history of placeholder/incomplete deploys silently going live — see `CLAUDE.md`).

### 3.6 Tests

- New `PttBurstCoalescerTest.kt` (JVM unit test, `app/src/test/java/com/voicetoinvoice/app/`): sweep synthetic press/release sequences with gaps from 0–1200ms and assert:
  1. No two emitted job windows ever overlap.
  2. No audio time-span is claimed by zero jobs when gaps are `< GAP_THRESHOLD_MS` (i.e. no silent drop between rapid presses).
  3. A group never exceeds the 25s safety span.
  4. A gap `≥ GAP_THRESHOLD_MS` always starts a new group (never silently merged).
  5. `pressCount` on the emitted job equals the number of presses actually coalesced into it.
- New Deno test for the server-side boundary-hint path and the quantity/line-count mismatch guard, following the existing test conventions already present for `price_intent_test.ts` (referenced in recent commit history) — place alongside existing edge-function tests in `supabase/functions/process-voice-job/` or wherever sibling tests for this function currently live.

### 3.7 Docs (do this as part of the same change, not a follow-up)

- `Docs/audit.md`: add a new `ISSUE-0XX` entry (check the current highest number in the file first — do not assume the number) under "🟢 RESOLVED ISSUES" once implemented and verified, following the file's existing Symptom/Root Cause/Resolution/Verification Date format exactly. Cross-reference `ISSUE-028` as the same root-cause class this change fully supersedes (that issue's mitigation should be updated to point here, per the file's own stated convention for superseding entries — see how `ISSUE-011` cross-references `ISSUE-004`). Update the "Ground-Truth Source-Code Verified Constants" table (§1) to reflect: `PREROLL_RESERVE_MS` removed, `AppDatabase` version bumped to 15, `GAP_THRESHOLD_MS` added.
- Root `CLAUDE.md` (lines ~74): currently describes a client-side "Adaptive Audio Expansion Engine" (±100ms/pass, up to 3 passes) that re-extracts wider audio windows. This is stale — confirmed dead by `Docs/voice_to_ledger_blueprint.md:78`, which states this code path was deleted and that re-decoding is now server-side and varies decode parameters, not audio boundaries. Fix this line while in the area, since this plan's audio-window logic is exactly what that stale claim would otherwise confuse a future reader about.
- `Docs/voice_to_ledger_blueprint.md`: update the line describing "a 300ms pre-roll and post-roll are added around the actual press/release" (currently ~line 48) and the `PttWindowLedger` description (~line 49) to describe burst coalescing instead of the pre-roll/post-roll tug-of-war it currently documents.

## 4. Explicit non-goals (do not do these)

- Do not add a fixed item-count or fixed-duration cap "for safety" — the 25s ring-buffer-proximity flush is the only real ceiling and is already sufficient. Re-adding an arbitrary cap reintroduces the exact class of bug this design removes.
- Do not merge transactions/sale-lines across a coalesced group. Grouping is audio/transcription-layer only.
- Do not use press count as the primary mismatch signal for the review-queue gate — a single press can legitimately contain multiple items today (`MultiSaleDetector`). Quantity-mention count vs. segmented-line count is the primary check; press count is only a soft supporting floor.
- Do not touch the Adaptive Re-Decode logic (`index.ts` lines ~903-930) — that is a separate, already-correct mechanism (varies decode parameters on low confidence) and is unrelated to audio-window boundaries.

## 5. Parameters already decided (do not re-litigate without new evidence)

- `GAP_THRESHOLD_MS = PRE_ROLL_MS + POST_ROLL_MS` (currently 600, derived — must stay derived, not hardcoded).
- `bufferDurationSeconds` in `RollingAudioBuffer.kt`: 30 → 120. Bounded intentionally (not unlimited) — see §2.1 for the upload-bandwidth and STT-provider-limit reasoning. Do not increase further without re-checking those two costs.
- Group safety-flush span: derived as `(bufferDurationSeconds - 5) * 1000` ms, **not** a hardcoded literal — must track `RollingAudioBuffer`'s actual configured duration.
- `PRE_ROLL_MS = 300`, `POST_ROLL_MS = 300` unchanged.
- No fixed item-count cap per group.
