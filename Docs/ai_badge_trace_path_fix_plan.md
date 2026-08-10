# Fix: AI/FAST/RULES path badges never render on Diagnostic Logs screen

## Status
Diagnosed and planned by Claude Code, 2026-08-08. Not yet implemented — Antigravity should execute this plan verbatim.

## User-reported symptom
"Installed v127 but still don't see the AI bot sign on recordings which used AI." The 🤖 badge (and, per the same code path, ⚡ FAST / 🧠 MEMO / 📐 RULES) never appears on any card in the Diagnostic Logs screen, regardless of what the server actually did.

## Root cause (verified against live data + source, not guessed)

**Verified via Supabase `stt_job_logs` query** (project `lyowklxsbfznnqridtgr`) against the 25 most recent jobs: the server-side trace is correct and complete. E.g. job `76892c70-...` has `diagnostic_trace_json ->> 'step_4_interpretation_source' = 'grok_ai'` and `-> 'step_4_ai_model' = 'grok-4.5'`, `trace_len` ~3800 bytes (a real trace, not a stub). So the AI path *is* running and *is* being recorded server-side — the bug is purely client-side rendering.

**Verified via source**: [`SttWorker.kt:375`](../app/src/main/java/com/voicetoinvoice/app/domain/processor/SttWorker.kt) `mergeClientTrace()` is the *only* place `SttJobRecord.diagnosticTraceJson` is ever written (6 call sites, all route through it), and it always wraps the server's trace JSON one level deep:

```kotlin
private fun mergeClientTrace(clientTrace: JSONObject, serverTraceJson: String?): String {
    val merged = JSONObject()
    merged.put("client", clientTrace)
    if (!serverTraceJson.isNullOrBlank()) {
        merged.put("server", JSONObject(serverTraceJson))   // <-- nested under "server"
    }
    return merged.toString()
}
```

This is unchanged from `HEAD` (last commit `b728bf2`) — it is not part of any uncommitted work.

But [`DiagnosticLogsScreen.kt:218-253`](../app/src/main/java/com/voicetoinvoice/app/ui/screens/logs/DiagnosticLogsScreen.kt) — new, uncommitted code, added per `Docs/unit_conversion_and_ai_visibility_fix_plan.md` — reads `step_4_interpretation_source`, `step_4_fast_path`, `step_4_ai_model`, and `step_0_server_diagnostics` off the **root** of `log.diagnosticTraceJson`:

```kotlin
val root = JSONObject(log.diagnosticTraceJson)
val fp = root.optJSONObject("step_4_fast_path")          // always null — these keys live under root.server, not root
val src = root.optString("step_4_interpretation_source", "")  // always ""
```

Since these keys only ever exist at `root.server.step_4_*` (confirmed: the edge function `process-voice-job/index.ts:2191-2220` writes them flat into the object it returns as `traceJson`, which `mergeClientTrace` then nests under `"server"`), `root.optString(...)` / `root.optJSONObject(...)` always hit the JSONObject defaults (`""` / `null`). The `when` block in `pathBadge` therefore always falls through to `else -> null` — no badge, ever, for any job, regardless of what path was actually taken server-side.

**This is a planning bug, not an Antigravity deviation.** `Docs/unit_conversion_and_ai_visibility_fix_plan.md` (the plan that specified this badge code, lines ~427-465) never accounts for `mergeClientTrace`'s nesting and was written as if `log.diagnosticTraceJson` equals the server's raw trace JSON directly. Antigravity implemented it exactly as specified.

**Disproof attempted**: could this instead be a v127-build-staleness issue (per `project_apk_builds_were_stale.md`)? No — the mismatch is structural (wrong JSON path) and present in the current working tree regardless of which commit v127 was built from; even a freshly-built, fully up-to-date APK would show this bug, because the client code has never once written `step_4_*` at the trace root.

## The fix

In `app/src/main/java/com/voicetoinvoice/app/ui/screens/logs/DiagnosticLogsScreen.kt`, all three blocks that read `step_4_*` / `step_0_server_diagnostics` must unwrap the `"server"` object first, falling back to `root` itself (so nothing breaks if the shape is ever simplified later, and so failed jobs with no server trace degrade to "no badge" instead of crashing).

### 1. `pathBadge` (currently lines 218-234)

Replace:
```kotlin
    val pathBadge: Pair<String, Color>? = remember(log.diagnosticTraceJson) {
        runCatching {
            if (log.diagnosticTraceJson.isBlank()) return@runCatching null
            val root = JSONObject(log.diagnosticTraceJson)
            val fp = root.optJSONObject("step_4_fast_path")
            val src = root.optString("step_4_interpretation_source", "")
            val aiModel = root.optString("step_4_ai_model", "").takeIf { it.isNotBlank() && it != "null" && it != "fast_path" }
```
with:
```kotlin
    val pathBadge: Pair<String, Color>? = remember(log.diagnosticTraceJson) {
        runCatching {
            if (log.diagnosticTraceJson.isBlank()) return@runCatching null
            val root = JSONObject(log.diagnosticTraceJson)
            val serverTrace = root.optJSONObject("server") ?: root
            val fp = serverTrace.optJSONObject("step_4_fast_path")
            val src = serverTrace.optString("step_4_interpretation_source", "")
            val aiModel = serverTrace.optString("step_4_ai_model", "").takeIf { it.isNotBlank() && it != "null" && it != "fast_path" }
```
(the rest of the `when` block is unchanged).

### 2. `serverIssues` (currently lines 236-246)

Replace:
```kotlin
    val serverIssues: List<String> = remember(log.diagnosticTraceJson) {
        runCatching {
            if (log.diagnosticTraceJson.isBlank()) return@runCatching emptyList()
            val arr = JSONObject(log.diagnosticTraceJson).optJSONArray("step_0_server_diagnostics")
                ?: return@runCatching emptyList()
```
with:
```kotlin
    val serverIssues: List<String> = remember(log.diagnosticTraceJson) {
        runCatching {
            if (log.diagnosticTraceJson.isBlank()) return@runCatching emptyList()
            val root = JSONObject(log.diagnosticTraceJson)
            val serverTrace = root.optJSONObject("server") ?: root
            val arr = serverTrace.optJSONArray("step_0_server_diagnostics")
                ?: return@runCatching emptyList()
```
(the rest — the `mapNotNull` body — is unchanged).

### 3. `fastPathSkipReason` (currently lines 248-253)

Replace:
```kotlin
    val fastPathSkipReason: String? = remember(log.diagnosticTraceJson) {
        runCatching {
            JSONObject(log.diagnosticTraceJson).optJSONObject("step_4_fast_path")
                ?.optString("skipReason")?.takeIf { it.isNotBlank() && it != "null" }
        }.getOrNull()
    }
```
with:
```kotlin
    val fastPathSkipReason: String? = remember(log.diagnosticTraceJson) {
        runCatching {
            val root = JSONObject(log.diagnosticTraceJson)
            val serverTrace = root.optJSONObject("server") ?: root
            serverTrace.optJSONObject("step_4_fast_path")
                ?.optString("skipReason")?.takeIf { it.isNotBlank() && it != "null" }
        }.getOrNull()
    }
```

## Scope note — server side is untouched

No changes needed in `supabase/functions/process-voice-job/index.ts` — its trace shape is already correct and verified live (§ above). This is a client-only fix, one file, three call sites.

## Verification steps (do not skip)

1. `./gradlew assembleDebug`, copy to `VoiceToInvoice_v<N+1>.apk` per the standing APK rule, md5-diff against the previous APK to confirm it's a real new build.
2. Install on device, make one recording that's expected to hit the AI path (e.g. an item with an ambiguous/unlisted name that the fast-path segmenter would reject — check `Docs/audit.md` §1 for the current `FAST_PATH_KEY_MAX_NORM` gate to pick a reliably-AI-routed phrase), and one that's expected to hit the fast path (a clean `"<N> किलो <known-catalog-item>"`).
3. Open the Diagnostic Logs screen and confirm: the AI-routed recording shows `🤖 grok-4.5` (or `🤖 AI` if `aiModel` comes back blank), and the fast-path recording shows `⚡ FAST`.
4. Cross-check against `stt_job_logs` for the same `job_id` (via Supabase `execute_sql`) to confirm the badge shown matches `diagnostic_trace_json ->> 'step_4_interpretation_source'` for that row — this is the actual proof, not just "a badge appeared."
5. Expand a card with server issues (if any exist in a recent job) and confirm the server-diagnostics list now renders instead of staying empty.

## Bug class note

This fixes the specific instance (3 read sites in one screen). The bug class — a plan or piece of code assuming `log.diagnosticTraceJson` is flat when `mergeClientTrace` always nests it under `client`/`server` — could recur if any future UI reads this column directly. Worth a one-line comment at `mergeClientTrace`'s definition warning readers about the nesting (optional, not required to close this issue).
