# Capture-Segment Ledger — Durable Fix Plan for Wrong-Time / Empty Mic Windows

## ISSUE-084 … ISSUE-089 — post-ISSUE-076 residue (Cursor's list) + two bugs Cursor did not see

**Written**: 2026-08-05 (Claude Code) · **For**: Antigravity · **Severity**: P0
**Input**: Cursor's 6-item issue list against the current working tree.

---

## 0. Read this before touching any audio code

### 0.1 STOP — the last 19 APKs were the same binary (**verified**, 2026-08-05)

```
md5 bbe344cceeaec228823d53fb05d72902 — v91, v92, v93, v94, v95, v96, v97, v98, v99,
                                        v100, v101, v102, v103, v104, v105, v107, v108, v109
md5 dfc0f2caa792f961081bbba08f7e7918 — v106   (the only distinct build since Jul 30)
md5 bbe344cceeaec228823d53fb05d72902 — app/build/outputs/apk/debug/app-debug.apk, mtime Jul 30 17:55
```

`VoiceToInvoice_v91.apk` … `v109.apk` (except `v106`) are **byte-identical to a Jul 30 17:55 build**. That build predates commit `1764f4c` (Jul 31) — i.e. it predates ISSUE-061…ISSUE-083. **Verified** by md5 over the APK folder and the build output directory.

Consequences that change how you read every prior issue report:

1. Every "fixed and shipped as v<N>" claim from Jul 31 onward, except v106, shipped **nothing**. The fixes exist in git; they were never on the phone.
2. The 2026-08-04 argument recorded in `CLAUDE.md` ("I have v101 installed" vs. "you're running an old APK") — **both were true**. v101 *was* installed and v101 *was* the Jul 30 binary.
3. Live `stt_job_logs` rows written today at 11:44 UTC contain `{"client":{"fast_path":true,...}}` traces from `AssistantFastPath`. **Nothing in the current tree calls `AssistantFastPath`** (verified by grep). So the device is running the stale binary, and today's symptoms are evidence about *Jul-30 code*, not about the tree Cursor reviewed.

**Therefore**: Cursor's issues 1–6 are code-reading findings about an **unbuilt tree**. They are still worth fixing (I verified 5 of the 6 in source — see §1), but no observed device symptom can currently be attributed to them. Step 0 below is a **blocker**: fix the build/copy pipeline first, or this plan joins the other nineteen.

### 0.2 Two bugs Cursor did not list, one of which has live evidence

**(A) Stale burst group flushed minutes later — evidenced.** Job `465084b9-9b75-4d61-9a0c-36e767feb862` (**verified** row in `stt_job_logs`):

```json
{"client":{"extraction_null":true,"burst_start_ms":1785871156853,"burst_end_ms":1785871156953}}
```
`recorded_at_ms = 1785871490968` — the job was inserted **334 seconds after** the burst window it describes, and the window is exactly **100 ms** wide.

Both numbers are diagnostic:
- 334 s late ⇒ `PttBurstCoalescer.currentGroupPairs` held a pending group for 5½ minutes and was flushed by a *later* press. The 600 ms idle-flush coroutine runs on `rememberCoroutineScope()` in `PttMicButton` — it dies when the composable leaves composition (screen navigation), stranding the group.
- 100 ms wide ⇒ `buildGroupLocked()` produced `clampedEndMs = clampedStartMs + 100L` because `lastConsumedEndMs` (ledger) was newer than `lastReleaseMs + postRollMs`. A 100 ms window is 3 200 bytes, always below `MIN_WINDOW_BYTES = 9600` ⇒ guaranteed `extraction_null`.

This is the mechanism most likely to produce the user's actual complaint — *"playback is from an earlier time when I wasn't recording."* A group stranded for 30 s and flushed on the next press extracts a **real, correctly-decoded, 30-second-old** window from the ring. No pause/resume needed, no gap arithmetic involved. Cursor's issues 1/2 require a background→foreground cycle; this one fires on plain screen navigation.

**(B) Degenerate-window emission.** `PttBurstCoalescer.buildGroupLocked()` line 99 emits `max(clampedStartMs + 100L, lastReleaseMs + postRollMs)`. When the ledger has already consumed past this group's end, the `+100L` branch wins and the coalescer *knowingly* emits an unusable window instead of dropping the group. Every such group becomes an `extraction_null` FAILED row.

### 0.3 Verdict on each of Cursor's six items

| # | Cursor's claim | Verdict |
|---|---|---|
| 1 | Ledger `lastEndMs` survives pause; `clampedStartMs` can land before `pausedAtMs`; gap clamp only fires when `startMs ∈ (pausedAtMs, resumeAtMs)` | **Half right — fix it anyway.** The clamp gap is real and verified (`RollingAudioBuffer.kt:287` tests the *raw* `startMs`, not `effectiveStartMs`). But the stated trigger is arithmetically near-impossible: `clampedStartMs = max(pressMs − 300, lastEndMs)`, so `lastEndMs` only wins when the new press is within ~300 ms of the previous window's end — which cannot happen across a background→foreground cycle. The *real* way `startMs` lands before the pause is §0.2(A), a stale group. |
| 2 | `resumeRollingBuffer()` sets `lastWriteAtMs = now` before any PCM is written | **Confirmed** (`RollingAudioBuffer.kt:198`). Between the resume call and the first `AudioRecord.read()` (50–200 ms typical, longer under load) the anchor says "byte `totalBytesWritten` is now" while those bytes are pre-stop audio. Worse: during that window `resumeAtMs` and `resumeByteOffset` still hold **previous-resume** values, so `rAt > pAt` is false and the gap clamp at line 287 is fully disabled. |
| 3 | Extraction failures ⇒ `audioFilePath = ""` + `extraction_null`, nothing to play | **Confirmed** (`PttMicButton.kt:281–300`), and confirmed present in live data (one row). Root causes are §0.2(A)/(B) and issues 1/2/4 — the failure row itself is correct behaviour, it just records no *reason*. |
| 4 | `resumeByteOffset` clamp still applied though the comment says v108 removed it | **Confirmed** — comment at `:316–323` contradicts code at `:327–330`. Note the clamp is currently *load-bearing*: it is the only thing preventing issue 2 from returning pre-stop audio. Do not delete it in isolation; it is replaced wholesale in Step 1. |
| 5 | `PttWindowLedger` never reset on cold start | **Confirmed** — `reset()` exists and has **zero call sites** in the whole app (verified by grep). Same for `PttBurstCoalescer.reset()`. After `startRollingBuffer()` wipes the ring, both keep pre-wipe state. |
| 6 | `AssistantFastPath` still inserts `audioFilePath = ""` rows | **Confirmed in source, and stronger than stated.** It has no caller in the tree — but it is *actively writing rows today* from the installed stale binary (see §0.1). Deleting it in the tree removes the class only once a real build ships. |

**Bug class statement** (per `CLAUDE.md` rule 9): issues 1, 2, 4 and §0.2(A) are all instances of one class — **a single global wall-clock→byte anchor pretending capture was continuous**. Steps 1–2 eliminate the class by making discontinuity a first-class, queryable fact (a segment list) rather than something patched around with `pausedAtMs`/`resumeAtMs`/`resumeByteOffset` triples. Issues 3, 5, 6 and §0.2(B) are separate, smaller defects fixed individually in Steps 3–5.

---

## Step 0 — Build integrity (BLOCKER; do this first, verify before writing any other code)

**ISSUE-084.**

### 0.a Move the build directory out of OneDrive

**File:** `gradle.properties` (repo root) — append:

```properties
# The repo lives under a OneDrive-synced Documents folder. OneDrive both locks KSP
# output (the known :app:kspDebugKotlin IOException) and has silently restored an older
# app-debug.apk over a newer one -- 19 consecutive "new" APKs shipped as the same
# Jul 30 binary. Build output must not live in a synced folder.
buildDir=C:/VTI_build
```

If `buildDir` in `gradle.properties` is not honoured by this AGP/Gradle version, instead put this in the root `build.gradle.kts`:

```kotlin
allprojects {
    layout.buildDirectory.set(File("C:/VTI_build/${project.name}"))
}
```

Use whichever works; report which one you used in Deviations. The APK path changes accordingly — from here on it is `C:/VTI_build/app/outputs/apk/debug/app-debug.apk` (or `<buildDir>/outputs/apk/debug/app-debug.apk`).

### 0.b Stamp every build so "which binary is on the phone" is never a debate again

**File:** `app/build.gradle.kts`

1. In `buildFeatures`, change `buildConfig = false` → `buildConfig = true`.
2. In `defaultConfig`, add:

```kotlin
buildConfigField(
    "String",
    "BUILD_STAMP",
    "\"" + java.time.LocalDateTime.now().toString().substring(0, 19) + "\""
)
buildConfigField(
    "String",
    "GIT_SHA",
    "\"" + (try {
        val p = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(rootDir).start()
        p.inputStream.bufferedReader().readText().trim().ifEmpty { "nogit" }
    } catch (e: Exception) { "nogit" }) + "\""
)
```

**File:** `app/src/main/java/com/voicetoinvoice/app/ui/screens/logs/DiagnosticLogsScreen.kt`
In the `TopAppBar`, change the title block to show the stamp under the existing title:

```kotlin
title = {
    Column {
        Text("Voice & System Processing Logs")
        Text(
            "build ${com.voicetoinvoice.app.BuildConfig.BUILD_STAMP} · ${com.voicetoinvoice.app.BuildConfig.GIT_SHA}",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
},
```

### 0.c Never copy an APK you did not just build

Do **not** `cp` blindly. The copy step is now:

```bash
./gradlew --stop
rm -rf "C:/VTI_build/app/generated/ksp"
./gradlew assembleDebug
```

Then, only if the build printed `BUILD SUCCESSFUL`:

```bash
md5sum "C:/VTI_build/app/outputs/apk/debug/app-debug.apk"
```

Compare that md5 against `bbe344cceeaec228823d53fb05d72902` (the stale binary). **If it matches, the build did not produce new bytes — stop and report; do not copy.** Otherwise copy to the next free version number (`ls` the folder first — highest today is `v109`) and re-hash the copy to confirm it matches the source:

```bash
cp "C:/VTI_build/app/outputs/apk/debug/app-debug.apk" "C:/Users/harsh/OneDrive/Desktop/VoiceToInvoice_APKs/VoiceToInvoice_v110.apk"
md5sum "C:/Users/harsh/OneDrive/Desktop/VoiceToInvoice_APKs/VoiceToInvoice_v110.apk"
```

Report both hashes in your final message. **A build whose md5 you did not print did not happen.**

---

## Step 1 — Replace the global anchor with a capture-segment ledger

**ISSUE-085.** Fixes Cursor's issues 1, 2 and 4 by construction.
**File:** `app/src/main/java/com/voicetoinvoice/app/audio/RollingAudioBuffer.kt`

### 1.a Delete these fields and everything that reads them

`lastWriteAtMs`, `stoppedAtMs` *(keep — `smartStart()` still needs it)*, `pausedAtMs`, `resumeAtMs`, `resumeByteOffset`.
Delete: `pausedAtMs`, `resumeAtMs`, `resumeByteOffset`, and the `lastWriteAtMs`-as-anchor usage. Keep `lastWriteAtMs` only if you still want it for logging; it must no longer feed the byte math. Delete the v108 comment block at lines 316–323 and the clamp at 327–330 (both are superseded).

### 1.b Add the segment ledger

Insert near the other private fields:

```kotlin
/**
 * One contiguous run of captured PCM. The wall-clock -> byte-offset mapping is valid
 * ONLY inside a segment; between two segments the microphone was off and the ring still
 * holds older audio at those positions. Every previous "wrong time audio" bug came from
 * a single global anchor extrapolating straight through such a hole.
 */
private class CaptureSegment(val startWallMs: Long, val startByteOffset: Long) {
    @Volatile var endWallMs: Long = startWallMs
    @Volatile var endByteOffset: Long = startByteOffset
}

/** Guarded by `ringBuffer`. Newest last. */
private val segments = ArrayList<CaptureSegment>()
/** Guarded by `ringBuffer`. Non-null only while the capture thread is writing. */
private var currentSegment: CaptureSegment? = null
/** Incremented by startRollingBuffer() only -- a cold start invalidates all timestamps. */
@Volatile private var captureEpoch: Int = 0

fun getCaptureEpoch(): Int = captureEpoch
```

### 1.c One write path, used by both capture loops

Add:

```kotlin
private fun appendChunk(chunk: ByteArray, bytesRead: Int) {
    val now = System.currentTimeMillis()
    val chunkDurationMs = bytesRead * 1000L / bytesPerSecond
    synchronized(ringBuffer) {
        var seg = currentSegment
        if (seg == null) {
            // The chunk we are about to store was captured over the PRECEDING
            // chunkDurationMs, so the segment starts before `now`.
            seg = CaptureSegment(now - chunkDurationMs, totalBytesWritten)
            segments.add(seg)
            currentSegment = seg
        }
        for (i in 0 until bytesRead) {
            ringBuffer[writeHead] = chunk[i]
            writeHead = (writeHead + 1) % bufferCapacity
        }
        totalBytesWritten += bytesRead
        seg.endWallMs = now
        seg.endByteOffset = totalBytesWritten
        lastWriteAtMs = now
        // Drop segments whose bytes have all been overwritten in the ring.
        val oldest = totalBytesWritten - bufferCapacity
        while (segments.size > 1 && segments[0].endByteOffset <= oldest) segments.removeAt(0)
    }
}

private fun closeCurrentSegment() {
    synchronized(ringBuffer) { currentSegment = null }
}
```

In **both** capture loops (`startRollingBuffer()` and `resumeRollingBuffer()`), replace the inline `synchronized(ringBuffer) { for ... totalBytesWritten += ...; lastWriteAtMs = ... }` block with a single call to `appendChunk(chunk, bytesRead)`. Keep the existing suppression handling (the `Arrays.fill(chunk, 0, bytesRead, 0)` branch) exactly as it is, before the `appendChunk` call — suppressed audio is still *written* (as silence), so it must still advance the segment.

In both loops' `finally` blocks, add `closeCurrentSegment()` before `isRecordingRunning.set(false)`.

Delete the `isFirstChunk` / `resumeAtMs` / `resumeByteOffset` bookkeeping from `resumeRollingBuffer()`'s loop — `appendChunk` opens the new segment automatically on the first real write. **This is the fix for Cursor's issue 2**: a resume with no PCM yet has no open segment, so nothing can map onto it.

### 1.d `stopRollingBuffer()` / `startRollingBuffer()` / `smartStart()`

- `stopRollingBuffer()`: keep the join; after it, call `closeCurrentSegment()`. Keep `stoppedAtMs = now`. Delete `pausedAtMs = now`.
- `startRollingBuffer()`: in the existing `synchronized(ringBuffer)` reset block, also do `segments.clear()`, `currentSegment = null`, and `captureEpoch++`. Delete the `pausedAtMs`/`resumeAtMs`/`resumeByteOffset` resets.
- `resumeRollingBuffer()`: delete the `lastWriteAtMs = now` line (issue 2) and the comment paragraph beneath it that justifies it.
- **Change `smartStart()` to return `Boolean`** — `true` when it cold-started (ring wiped), `false` when it resumed or did nothing:

```kotlin
fun smartStart(): Boolean {
    if (isRecordingRunning.get()) return false
    val gapMs = if (stoppedAtMs > 0L) System.currentTimeMillis() - stoppedAtMs else Long.MAX_VALUE
    return if (totalBytesWritten == 0L || gapMs > RESUME_MAX_GAP_MS) {
        startRollingBuffer(); true
    } else {
        resumeRollingBuffer(); false
    }
}
```

---

## Step 2 — Segment-bounded extraction with typed failure reasons

**ISSUE-086.** Fixes Cursor's issue 3's diagnosability and closes issue 1's clamp hole.
**File:** same.

### 2.a Pure, unit-testable resolver (replaces `resolveWindowBytes`)

Replace the whole `resolveWindowBytes` companion function with:

```kotlin
/** Result of mapping a wall-clock window onto ring bytes. */
sealed class WindowResolution {
    data class Ok(
        val startByte: Long,
        val endByte: Long,
        val clampedToSegmentStart: Boolean
    ) : WindowResolution()
    data class Failed(val reason: String) : WindowResolution()
}

/**
 * Maps [startMs, endMs] onto absolute byte offsets INSIDE one capture segment.
 * `segEndByteOffset` is the byte offset that corresponds to wall-clock `segEndWallMs`.
 * Never extrapolates outside the segment: audio that was not captured cannot be returned.
 */
fun resolveSegmentWindowBytes(
    startMs: Long, endMs: Long,
    segStartWallMs: Long, segStartByteOffset: Long,
    segEndWallMs: Long, segEndByteOffset: Long,
    totalWritten: Long, bufferCapacity: Int, bytesPerSecond: Int
): WindowResolution {
    if (endMs <= startMs) return WindowResolution.Failed("end_before_start")
    if (segEndByteOffset <= segStartByteOffset) return WindowResolution.Failed("empty_segment")
    if (endMs <= segStartWallMs) return WindowResolution.Failed("window_before_segment")
    if (startMs >= segEndWallMs) return WindowResolution.Failed("window_after_segment")

    val clampedToSegmentStart = startMs < segStartWallMs
    val effStart = Math.max(startMs, segStartWallMs)
    val effEnd = Math.min(endMs, segEndWallMs)

    fun byteFor(t: Long): Long =
        segEndByteOffset - (segEndWallMs - t) * bytesPerSecond.toLong() / 1000L

    var startByte = byteFor(effStart).coerceIn(segStartByteOffset, segEndByteOffset)
    val endByte = byteFor(effEnd).coerceIn(startByte, segEndByteOffset)

    val oldestAvailable = Math.max(0L, totalWritten - bufferCapacity)
    if (endByte <= oldestAvailable) return WindowResolution.Failed("window_overwritten")
    if (startByte < oldestAvailable) startByte = oldestAvailable

    if (endByte - startByte < MIN_WINDOW_BYTES) {
        return WindowResolution.Failed("window_too_small_${endByte - startByte}")
    }
    return WindowResolution.Ok(startByte, endByte, clampedToSegmentStart)
}
```

### 2.b New extraction entry point

```kotlin
sealed class ExtractionResult {
    data class Success(val file: File, val clampedToSegmentStart: Boolean, val bytes: Int) : ExtractionResult()
    data class Failure(val reason: String) : ExtractionResult()
}

fun extractAudioWindowDetailed(startMs: Long, endMs: Long, outputFile: File): ExtractionResult {
    return try {
        synchronized(ringBuffer) {
            if (totalBytesWritten <= 0L) return ExtractionResult.Failure("buffer_never_wrote")
            // Newest segment that overlaps the requested window. Audio for a just-released
            // press is always in the newest segment; anything older is a stale request.
            val seg = segments.lastOrNull { it.startWallMs < endMs && it.endWallMs > startMs }
                ?: return ExtractionResult.Failure("no_segment_overlaps_window")

            when (val r = resolveSegmentWindowBytes(
                startMs, endMs,
                seg.startWallMs, seg.startByteOffset,
                seg.endWallMs, seg.endByteOffset,
                totalBytesWritten, bufferCapacity, bytesPerSecond
            )) {
                is WindowResolution.Failed -> ExtractionResult.Failure(r.reason)
                is WindowResolution.Ok -> {
                    val n = (r.endByte - r.startByte).toInt()
                    val out = ByteArray(n)
                    val startRingIndex = (r.startByte % bufferCapacity).toInt()
                    for (i in 0 until n) out[i] = ringBuffer[(startRingIndex + i) % bufferCapacity]
                    AudioWavWriter.writePcmToWav(out, outputFile, sampleRate = sampleRate)
                    ExtractionResult.Success(outputFile, r.clampedToSegmentStart, n)
                }
            }
        }
    } catch (e: Exception) {
        Log.e("RollingAudioBuffer", "extractAudioWindowDetailed failed", e)
        ExtractionResult.Failure("exception_${e.javaClass.simpleName}")
    }
}
```

Keep a thin compatibility wrapper so `androidTest/.../RollingBufferRestartTest.kt` still compiles:

```kotlin
fun extractAudioWindow(startMs: Long, endMs: Long, outputFile: File): File? =
    (extractAudioWindowDetailed(startMs, endMs, outputFile) as? ExtractionResult.Success)?.file
```

Note the wrapper **drops the `floorStartMs` parameter**. Update `PttMicButton.kt` (the only main-source caller) in Step 3; if `RollingBufferRestartTest.kt` passes `floorStartMs`, it does not — it calls the 3-arg form (verified). If any other call site breaks, stop and ask.

---

## Step 3 — Coalescer: no stale groups, no degenerate windows, no composable-scoped timers

**ISSUE-087.** This is §0.2(A)+(B) — the only part of this plan with live-data evidence behind it.

### 3.a Typed flush outcome

**File:** `app/src/main/java/com/voicetoinvoice/app/audio/PttBurstCoalescer.kt`

Add above the class:

```kotlin
sealed class BurstFlush {
    data class Ready(val group: CoalescedBurstGroup) : BurstFlush()
    /** Group was discarded before extraction; recorded so Diagnostic Logs shows WHY. */
    data class Dropped(val reason: String, val firstPressMs: Long, val lastReleaseMs: Long) : BurstFlush()
}
```

Add to the constructor defaults: nothing. Add to the class body:

```kotlin
/**
 * A group older than this cannot be trusted: the ring may have rolled past its window, and
 * even when it has not, extracting it plays audio from minutes ago as if it were the press
 * the shopkeeper just made. Evidence: job 465084b9 was inserted 334s after its own burst
 * window (Docs/capture_segment_ledger_fix_plan.md §0.2).
 */
private val maxGroupAgeMs: Long = 5_000L
```

Change all three flush methods to return `BurstFlush?` (null still means "nothing pending"), and rewrite `buildGroupLocked` to take `nowMs`:

```kotlin
private fun buildGroupLocked(lastConsumedEndMs: Long, nowMs: Long): BurstFlush {
    val firstPressMs = currentGroupPairs.first().pressMs
    val lastReleaseMs = currentGroupPairs.last().releaseMs

    if (nowMs - lastReleaseMs > maxGroupAgeMs) {
        return BurstFlush.Dropped("stale_group_age_${nowMs - lastReleaseMs}ms", firstPressMs, lastReleaseMs)
    }

    val rawStartMs = max(0L, firstPressMs - preRollMs)
    val clampedStartMs = max(rawStartMs, lastConsumedEndMs)
    val rawEndMs = lastReleaseMs + postRollMs

    // Previously this emitted `max(clampedStartMs + 100L, rawEndMs)` -- a 100ms window that
    // is always below MIN_WINDOW_BYTES, i.e. a guaranteed extraction_null. Drop instead.
    if (rawEndMs - clampedStartMs < MIN_USABLE_WINDOW_MS) {
        return BurstFlush.Dropped("window_consumed_by_ledger", firstPressMs, lastReleaseMs)
    }

    val boundaries = currentGroupPairs.map { pair ->
        UtteranceBoundary(
            pressOffsetMs = max(0L, pair.pressMs - clampedStartMs),
            releaseOffsetMs = max(0L, pair.releaseMs - clampedStartMs)
        )
    }

    return BurstFlush.Ready(
        CoalescedBurstGroup(
            startMs = clampedStartMs,
            endMs = rawEndMs,
            firstPressMs = firstPressMs,
            lastReleaseMs = lastReleaseMs,
            pressCount = currentGroupPairs.size,
            boundaries = boundaries
        )
    )
}

companion object { const val MIN_USABLE_WINDOW_MS = 400L }
```

Callers pass `nowMs`: `recordPressRelease` passes `pressMs` (the new press is "now"), `checkAndFlushIfIdle` passes its `nowMs`, `forceFlush` passes `System.currentTimeMillis()`. Every path must still `currentGroupPairs.clear()` exactly as today, including on `Dropped`.

### 3.b App-lifetime scope for flush + job insert

**New file:** `app/src/main/java/com/voicetoinvoice/app/audio/PttCaptureScope.kt`

```kotlin
package com.voicetoinvoice.app.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Capture work outlives the composable that started it. The 600ms idle-flush timer used to
 * run on rememberCoroutineScope(): navigating away between press and flush cancelled it and
 * stranded the burst group, which was then flushed minutes later by an unrelated press and
 * extracted a window from that earlier time. Process-lifetime scope, never cancelled.
 */
object PttCaptureScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
```

**File:** `app/src/main/java/com/voicetoinvoice/app/ui/components/PttMicButton.kt`

Replace **both** `scope.launch(Dispatchers.IO)` calls (the one inside `processGroup`, and the delayed idle-flush one) with `PttCaptureScope.scope.launch`. Leave the `rememberCoroutineScope()` declaration only if something else still uses it; otherwise delete it and its import.

### 3.c Handle `BurstFlush` in `PttMicButton`

Rewrite the tail of `onPress` (from `val immediateFlushed = …` to the end) as:

```kotlin
val handleFlush: (BurstFlush?) -> Unit = { flush ->
    when (flush) {
        is BurstFlush.Ready -> processGroup(flush.group)
        is BurstFlush.Dropped -> PttCaptureScope.scope.launch {
            db.sttJobDao().insertJob(
                SttJobRecord(
                    audioFilePath = "",
                    status = SttJobStatus.FAILED,
                    captureIntent = intent,
                    rawTranscript = "burst_dropped",
                    pressStartMs = flush.firstPressMs,
                    releaseMs = flush.lastReleaseMs,
                    diagnosticTraceJson = org.json.JSONObject().apply {
                        put("client", org.json.JSONObject().apply {
                            put("burst_dropped", true)
                            put("reason", flush.reason)
                            put("first_press_ms", flush.firstPressMs)
                            put("last_release_ms", flush.lastReleaseMs)
                        })
                    }.toString(),
                    synced = false
                )
            )
        }
        null -> Unit
    }
}

handleFlush(pttBurstCoalescer.recordPressRelease(pressTs, releaseTs, pttWindowLedger.lastConsumedEndMs()))

PttCaptureScope.scope.launch {
    delay(pttBurstCoalescer.gapThresholdMs)
    handleFlush(
        pttBurstCoalescer.checkAndFlushIfIdle(
            lastReleaseMs = releaseTs,
            nowMs = System.currentTimeMillis(),
            lastConsumedEndMs = pttWindowLedger.lastConsumedEndMs()
        )
    )
}
```

### 3.d Record the extraction reason on failure

In `processGroup`, replace the `extractAudioWindow(...)` call and the `if (extractedAudio != null …)` branch with:

```kotlin
val result = rollingAudioBuffer.extractAudioWindowDetailed(
    startMs = burstGroup.startMs,
    endMs = burstGroup.endMs,
    outputFile = targetFile
)

when (result) {
    is RollingAudioBuffer.ExtractionResult.Success -> {
        // ...existing success body, using result.file instead of extractedAudio...
    }
    is RollingAudioBuffer.ExtractionResult.Failure -> {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "रिकॉर्डिंग नहीं हुई — दोबारा बोलिए", Toast.LENGTH_SHORT).show()
        }
        db.sttJobDao().insertJob(
            SttJobRecord(
                audioFilePath = "",
                status = SttJobStatus.FAILED,
                captureIntent = intent,
                rawTranscript = "extraction_null",
                pressStartMs = burstGroup.firstPressMs,
                releaseMs = burstGroup.lastReleaseMs,
                diagnosticTraceJson = org.json.JSONObject().apply {
                    put("client", org.json.JSONObject().apply {
                        put("extraction_null", true)
                        put("reason", result.reason)
                        put("burst_start_ms", burstGroup.startMs)
                        put("burst_end_ms", burstGroup.endMs)
                        put("capture_epoch", rollingAudioBuffer.getCaptureEpoch())
                    })
                }.toString(),
                synced = false
            )
        )
    }
}
```

Also add `put("clamped_to_segment_start", result.clampedToSegmentStart)` into the **success** job's trace if the job record has a client-trace field available at insert time; if it does not, skip it and note that in Deviations rather than reshaping `SttJobRecord`.

---

## Step 4 — Reset ledger and coalescers on a cold buffer epoch

**ISSUE-088.** Cursor's issue 5.
**File:** `app/src/main/java/com/voicetoinvoice/app/MainActivity.kt` (the `DisposableEffect(lifecycleOwner)` block, ~line 317)

```kotlin
val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
    when (event) {
        androidx.lifecycle.Lifecycle.Event.ON_START -> {
            val coldStarted = sharedRollingBuffer.smartStart()
            if (coldStarted) {
                // The ring was wiped and totalBytesWritten reset to 0. Every timestamp the
                // ledger and the coalescers hold refers to the previous epoch's bytes.
                PttWindowLedger.getInstance().reset()
                assistantPttWindowLedger.reset()
                salePttBurstCoalescer.reset()
                stockPttBurstCoalescer.reset()
                assistantPttBurstCoalescer.reset()
            }
        }
        androidx.lifecycle.Lifecycle.Event.ON_STOP -> sharedRollingBuffer.stopRollingBuffer()
        else -> Unit
    }
}
```

Do **not** reset on `ON_STOP`: a group pending at background time is still extractable (the bytes stay in the ring after `stopRollingBuffer()`, and the segment stays in `segments` with a valid `endWallMs`), and the Step-3 timer now survives to flush it. The 5 s staleness guard covers the case where it does not.

`PttWindowLedger.reset()` already exists and needs no change — this step gives it its first call sites.

---

## Step 5 — Delete `AssistantFastPath`

**ISSUE-089.** Cursor's issue 6.

- Delete `app/src/main/java/com/voicetoinvoice/app/domain/voice/AssistantFastPath.kt`.
- Remove the two stale comment references in `app/src/main/java/com/voicetoinvoice/app/domain/processor/SttWorker.kt` (~lines 148 and 737) — reword them so they do not name a class that no longer exists.
- Build. If anything else fails to compile, **stop and ask** rather than re-adding the file or stubbing it.

Rationale: it is the only remaining producer of `audioFilePath = ""` ASSISTANT rows, it has no caller in the tree, and leaving a working "instant path" in the source is how it gets re-wired by the next session. Old rows in Room/`stt_job_logs` keep their empty audio path — that is history, not a bug; the Diagnostic Logs screen should keep rendering them without a play button.

---

## Step 6 — Unit tests (JVM, no device)

**File:** `app/src/test/java/com/voicetoinvoice/app/audio/RollingBufferWindowTest.kt` — rewrite against `resolveSegmentWindowBytes`. Required cases:

1. `windowFullyInsideSegmentMapsExactly` — 2 s window ending at `segEndWallMs`; assert `endByte - startByte == 64_000` and `startByte == segEndByteOffset - 64_000`.
2. `windowStartingBeforeSegmentIsClampedNotExtrapolated` — `startMs = segStartWallMs - 5_000`; assert `Ok`, `startByte == segStartByteOffset`, `clampedToSegmentStart == true`. **This is Cursor's issue 1 as a test.**
3. `windowEntirelyBeforeSegmentFails` — `endMs <= segStartWallMs` ⇒ `Failed("window_before_segment")`. **This is Cursor's issue 2 as a test** (a resume that has written nothing yet has no segment covering "now").
4. `windowOlderThanRingCapacityFails` ⇒ `Failed("window_overwritten")`.
5. `subMinimumWindowFails` — 100 ms window ⇒ `Failed` starting with `window_too_small`.
6. `endBeforeStartFails`.

**New file:** `app/src/test/java/com/voicetoinvoice/app/audio/PttBurstCoalescerTest.kt`:

1. `singlePressFlushesReadyGroup` — press/release, `checkAndFlushIfIdle` 600 ms later ⇒ `Ready`, window ≈ press−300 … release+300.
2. `groupOlderThanFiveSecondsIsDropped` — flush with `nowMs = releaseMs + 6_000` ⇒ `Dropped("stale_group_age_6000ms", …)`. **This is §0.2(A) as a test.**
3. `windowAlreadyConsumedByLedgerIsDropped` — `lastConsumedEndMs = releaseMs + 1_000` ⇒ `Dropped("window_consumed_by_ledger", …)`, never a 100 ms group. **This is §0.2(B) as a test.**
4. `twoPressesWithinThresholdCoalesceIntoOneGroup` — unchanged behaviour, guards against regression.

Run:

```bash
./gradlew test --tests "com.voicetoinvoice.app.audio.*"
```

---

## Step 7 — Verification (mandatory; a build is not a verification)

### 7.a Artifact check

Print, in your final message: the `assembleDebug` result, the md5 of the built APK, the md5 of the copied `VoiceToInvoice_v<N>.apk`, and confirm the md5 is **not** `bbe344cceeaec228823d53fb05d72902`.

### 7.b On-device, after installing that exact APK

Open **Diagnostic Logs first** and read the build stamp line added in Step 0.b. Report it. If it does not match the build you just made, everything below is void.

Then, in order:

1. Fresh launch → SALE "दो किलो आलू" → play back the row: it must be that utterance.
2. SALE → **navigate to another screen within 300 ms of release** → return. The job must still appear (Step 3.b), with audio, with the right utterance. *(This is the §0.2(A) repro.)*
3. Background (Home button) 5 s → foreground → SALE "पाँच किलो टमाटर" → playback is the tomato utterance, not pre-background audio. *(Issues 1/2/4.)*
4. Background 30 s (> `RESUME_MAX_GAP_MS`) → foreground → SALE → correct audio, no `burst_dropped`/`extraction_null`. *(Step 4 cold-epoch reset.)*
5. Assistant press → row has **playable audio** and a server trace.
6. Leave open 5 min idle → SALE → correct audio.

### 7.c Database check (run after the device session)

```sql
SELECT job_id, status, hold_duration_ms,
       left(coalesce(raw_transcript,''),40) AS transcript,
       length(diagnostic_trace_json) AS trace_len,
       recorded_at_ms, created_at
FROM stt_job_logs
WHERE created_at > now() - interval '1 hour'
ORDER BY created_at DESC;
```

Pass criteria — state each explicitly as pass/fail with the row evidence:
- No row where `recorded_at_ms` differs from its trace's `burst_start_ms` by more than ~2 s.
- No `extraction_null` row without a `reason` field.
- No `raw_transcript = 'burst_dropped'` row for a press the shopkeeper made normally (one appearing only in test 2's aggressive-navigation repro is acceptable **only** if audio was genuinely unrecoverable — say so).
- No `{"client":{"fast_path":true …}}` row at all (proves the new binary is running — `AssistantFastPath` is gone).

That last one is the cheapest positive proof that the APK on the phone is the one you built.

---

## Step 8 — Audit log

Add to `Docs/audit.md` §2 under 🟢 RESOLVED (or 🔴 OPEN until 7.b passes), dated 2026-08-05:

- **ISSUE-084** — Build/copy pipeline shipped 19 identical APKs (v91–v109 except v106); include the md5 evidence from §0.1 and the Step-0 remedy.
- **ISSUE-085** — Global wall-clock anchor extrapolated across capture holes → capture-segment ledger.
- **ISSUE-086** — Extraction failures were silent; typed reasons now recorded.
- **ISSUE-087** — Burst groups stranded on a cancelled composable scope and flushed minutes later (evidence: job `465084b9`), plus degenerate 100 ms windows.
- **ISSUE-088** — `PttWindowLedger`/`PttBurstCoalescer` never reset across a cold buffer epoch.
- **ISSUE-089** — `AssistantFastPath` deleted.

Cross-reference ISSUE-076 (never-stop buffer): its architecture stands; ISSUE-085 supersedes the `pausedAtMs`/`resumeAtMs`/`resumeByteOffset` mitigations it introduced. Update §1 "Ground-Truth Source-Code Verified Constants" for: `MIN_USABLE_WINDOW_MS = 400`, `maxGroupAgeMs = 5000`, removal of `resumeByteOffset`, and `smartStart()` now returning `Boolean`.

---

## 9. Scope boundaries — each cleared with evidence

- **Do not touch `supabase/functions/process-voice-job/index.ts` or `domain/parser/`.** Cleared by live data: today's rows (`19b3277b`, `c9fba88e`) show `trace_len` 3.4–4.0 KB, `step_7_persistence.transactions.ok = true`, and correct item resolution from the transcript the client uploaded. The server is decoding whatever WAV it is given, correctly.
- **Do not retune `preRollMs` / `postRollMs` / `gapThresholdMs`.** Wrong-*time* audio is a coordinate bug, not a roll-length bug; §0.2 shows the failing windows were 334 s and 100 ms off, not 300 ms off.
- **Do not reintroduce `stopRollingBuffer()` before an on-device recognizer** (ISSUE-076's decision stands).
- **Do not change Room schema, confidence thresholds, or the segmenter.**

## 10. Open questions — ask, do not guess

1. Step 0.a moves the build directory to `C:/VTI_build`. If the user objects to an out-of-repo build dir, the alternative is excluding `app/build` from OneDrive sync in the OneDrive client — that is a GUI action only the user can take. **Ask which they prefer before changing `gradle.properties`.**
2. `maxGroupAgeMs = 5_000`: a group dropped at 5 s loses a real utterance if the phone was severely stalled. If the user would rather *attempt* extraction and let it fail with a reason, say so and it becomes a `Ready` group instead. Default as written: drop.
3. Step 3.c inserts a FAILED `burst_dropped` row for every dropped group. If that is judged log noise, the alternative is `Log.w` only — but then the failure is invisible in Diagnostic Logs. Default as written: insert the row.

## 11. Deviations section

End your run with **Deviations** — anything changed, skipped, or interpreted differently from the literal text above, and why. If none, write "None."
