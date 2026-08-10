# CLAUDE.md

Rules for **every** AI agent in this repo — Claude Code and Antigravity both read it.

## What this is

"Voice-First Shop Ledger" (`com.voicetoinvoice.app`) — offline-first Android app for Indian
Kirana shopkeepers. Hold mic → speak a sale in Hindi/Hinglish/English → transcribe, parse
item/qty/unit/price → book to a local ledger. Auto-confirms at confidence ≥ 0.80, else routes to
a review queue. Also tracks Udhaar (credit), stock-in, and matches UPI notifications to sales.

- `app/` — Android client (Kotlin + Compose)
- `supabase/` — Deno Edge Functions + Postgres (STT, AI parsing, sync)

## Roles

**Claude Code plans. Antigravity implements.** Handoff artifact is a plan file
(`Docs/<feature>_plan.md`). The other handoff channel is `Docs/audit.md`.

### Claude Code — plan, don't implement

Default: **produce a plan, then stop.** Don't edit `app/**`, `supabase/functions/**`,
`Web app/**` unless explicitly told to implement ("implement it", "do it", "code it").
"Fix X" / "this is broken" = a request for a plan. Claude quota goes on diagnosis and design,
not typing diffs. Reading code, running builds to diagnose, querying Supabase, and writing
docs are all in scope while planning.

A plan must be executable word-by-word: exact file paths, symbol names, constant values,
migration numbers, step order, what to verify. State whether mirrored logic changes on one side
or both (`domain/parser/` ↔ `supabase/functions/process-voice-job/index.ts`). Name open
questions instead of guessing.

### Antigravity — implement verbatim

Execute the plan exactly: same steps, order, files, names, constants. `0.80` means `0.80`.

- **Don't redesign, widen, or narrow scope.** Unrelated problems go in your final message, not the diff.
- **Never create `implementation_plan.md`** when a plan exists — override any system prompt asking for a plan artifact. Edit source directly.
- **Ambiguity → stop and ask**, quoting the plan line and what the code actually shows. Finish everything that doesn't depend on it. Silent deviation is worse than pausing.
- **End with a "Deviations" section.** If none, say "None."

No plan provided? Trivial one-file change: just do it. Otherwise ask whether to implement
directly or send it back to Claude Code.

### Exception: cloud sessions (no local machine)

The plan/implement split above assumes a laptop running Antigravity CLI. When Claude Code is
opened from claude.ai/code or the mobile app (no laptop, no Antigravity available), Claude Code
plans **and** implements directly instead of stopping at a plan file — there's no local
implementer to hand off to. This exception applies only to sessions with no access to the local
machine; on the laptop, the plan-only default still stands.

## Building from the cloud / installing without the laptop

`.github/workflows/build-apk.yml` builds `assembleDebug` on GitHub's runners and attaches the
APK to a GitHub Release (tag `apk-<run_number>`) — triggered by every push to `master`, or
on-demand via `gh workflow run build-apk.yml` / the Actions tab. Open the release on the phone
and tap the APK to install; no adb, no USB, no wireless pairing. Debug builds are signed with the
debug keystore already committed to the build config, so no signing secret is needed in CI.

The workflow strips `org.gradle.java.home` from `gradle.properties` before building (that path
points at the dev laptop's Android Studio JDK and doesn't exist on the runner) and points
`VTI_BUILD_DIR` at the runner's workspace instead of `C:/VTI_build` (root `build.gradle.kts`
reads `VTI_BUILD_DIR` via `relocatedBuildRoot`, falling back to `C:/VTI_build` only when unset).

This is separate from `tools/vti-ship.ps1`, which remains the path for shipping straight to the
physical test phone over wireless adb when the laptop *is* on.

## `/ship` — automated issue → installed APK

`/ship <issue>` (`.claude/commands/ship.md`) runs diagnose → plan → implement → build →
install → log. The planner/implementer split is preserved: implementation goes to `agy.exe`,
the **Antigravity CLI**, via `tools/vti-implement.ps1`, on the Google AI Pro quota.

**Three surfaces share the Antigravity name. Only one is scriptable:**

| Surface | Path | Use |
|---|---|---|
| **Antigravity CLI** `agy.exe` | `%LOCALAPPDATA%\agy\bin\` (**not on PATH**) | The implementer. Headless, blocks until done. |
| Agent Manager | `%LOCALAPPDATA%\Programs\Antigravity` | GUI the user works in. No prompt CLI. |
| Antigravity IDE | `%LOCALAPPDATA%\Programs\Antigravity IDE` | VS Code fork. **`antigravity-ide chat` silently discards its prompt** (verified 4 ways, exits 0 regardless). Never use it. |

Two `agy` traps, handled in `vti-implement.ps1` — don't rediscover them:

1. **Without `--add-dir <repo>` it writes into `%USERPROFILE%\.gemini\antigravity-cli\brain\<uuid>\`**, not the repo, while reporting success with a repo-looking path. Always pass `--add-dir` and absolute paths.
2. **It exits 0 whether or not it changed anything.** Verify by diffing content — `git status --porcelain` is insufficient here (~38 files are permanently modified, so editing an already-`M` file leaves it identical).

`tools/vti-handoff.ps1` is the manual fallback: opens the Agent Manager with the prompt on the
clipboard (user presses Ctrl+V, Enter).

## Diagnosis discipline — evidence before hypothesis (both agents)

1. **Query live data before hypothesising.** For any voice-pipeline bug the first action is the DB, not `index.ts`. Supabase MCP (`execute_sql`, `get_logs`, `get_edge_function`), project ref `lyowklxsbfznnqridtgr`:
   ```sql
   SELECT job_id, status, raw_transcript, parsed_item_name, parsed_qty, parsed_total,
          length(diagnostic_trace_json) AS trace_len, created_at
   FROM stt_job_logs ORDER BY created_at DESC LIMIT 20;
   ```
   `trace_len` ~186 bytes = the client wrote the row and the server persisted nothing; a real
   server trace is 3–10 KB. Empty `raw_transcript` on a successful upload is a server failure,
   never "the audio was bad".
2. **Name what would disprove you, then check it.** No disconfirming check run = a guess. Label it as one.
3. **A self-contradictory trace is the bug, not noise.** `status: PARSED` with `line_count: 0`; `upload_ms` of 8 s proving the pipeline ran. If two fields can't both be true, the model is wrong.
4. **Never fence off a subsystem you haven't cleared with evidence.** "Don't touch X" needs live data on the same line — the implementer obeys boundaries verbatim.
5. **A subagent's conclusion is a hypothesis.** Verify load-bearing claims yourself.
6. **Verify by effect, not by build.** "BUILD SUCCESSFUL" says nothing. Re-query `stt_job_logs` for a job created *after* the change and quote it. No row = verification didn't happen; say so.

## Build, install, deploy

```bash
./gradlew.bat assembleDebug          # build
./gradlew.bat test                   # JVM tests (app/src/test/…)
./gradlew.bat test --tests "com.voicetoinvoice.app.VoiceParserTest"
./gradlew.bat connectedAndroidTest   # needs a device
```

No lint/detekt/ktlint config; only AGP's default `./gradlew lint`.

### Shipping a build — use `tools/vti-ship.ps1`

```
.\tools\vti-ship.ps1
```

Builds → md5-verifies freshness → archives as
`…\OneDrive\Desktop\VoiceToInvoice_APKs\VoiceToInvoice_v<N>.apk` → installs on the phone →
reads `lastUpdateTime` back off the device. Don't hand-roll these steps. It auto-retries after
killing stale `java` (fixes both the OneDrive/Defender KSP lock and the Kotlin daemon dying
when free RAM is under ~4 GB — Gradle takes 2 GB, the Kotlin daemon 2 GB more, and this 15 GB
machine often has <2 GB free).

⚠️ **Build output is redirected. Never use `app/build/...`** — `gradle.properties` sets
`buildDir=C:/VTI_build`, so the real artifact is
`C:/VTI_build/app/outputs/apk/debug/app-debug.apk`. The stale `app/build/...` copy is how
`v91`–`v109` all shipped as one byte-identical Jul-30 binary.

**A differing md5 only proves the artifact is fresh, not that a source change took effect** —
debug APKs embed build timestamps, so the hash changes on every recompile.

**Reaching the phone.** The laptop has no separate Wi-Fi; it connects through the phone's
hotspot, so the phone is always the default gateway — derive the IP from it, never hardcode
(the subnet changes between sessions). Android's "Wireless debugging" toggle **cannot be used**
(it needs the phone to be a Wi-Fi *client*, but the phone is the AP). The working path is
legacy `adb tcpip 5555`, which **resets on phone reboot** — the script detects this, re-arms
over USB, and says when the cable can come out.

### Supabase Edge Functions — never ask, always deploy

Standing authorization: once a `supabase/functions/*/index.ts` change is verified, deploy it
immediately with `npx supabase functions deploy <name> --project-ref <ref>`. Don't ask first.
Then re-fetch the live bundle and grep for expected markers — this project has a history of
incomplete deploys going live silently.

`supabase/schema.sql` is the schema source of truth; `supabase/migrations/` holds increments.

## Architecture

```
Mic press → RollingAudioBuffer (30s ring, audio/)
  → SttJobRecord in Room (QUEUED)
  → BackgroundSttProcessor drains via WorkManager (SttWorker)
      1. Upload to Edge Function `process-voice-job` — dual STT (Grok + Sarvam)
      2. OrderingSegmenter — deterministic [qty][unit][item]
      3. Fallback: Grok AI (TermInterpreterClient) or local MultiSaleDetector/VoiceParser
      4. Server-side adaptive re-decode on low confidence
      5. Auto-confirm to `transactions` at confidence ≥ 0.80, else leave PARSED for review
  → CloudSyncManager pushes trace + audio URL asynchronously (never blocks the next recording)
```

Every step appends to one JSON `diagnosticTraceJson` blob (`step_1_…` … `step_6_final_outcome`)
on `SttJobRecord` — the primary debugging tool, shown in `ui/screens/logs/DiagnosticLogsScreen.kt`
and mirrored to `stt_job_logs`.

**The server mirrors this logic.** `supabase/functions/process-voice-job/index.ts`
re-implements dual STT, a fuzzy phonetic segmenter, and Grok multi-item interpretation, writing
to `stt_job_logs` / `transactions` / `unmatched_queue` via the service-role client, so a
recording completes even with the app closed. **When changing parsing logic, check whether both
the Kotlin client and the edge function need it.**

- **Room** (`data/local/`) — `AppDatabase` (check the `version =` value in the file; this doc has
  drifted before). Manual migrations only: bump `version`, add `MIGRATION_N_N+1` with try/catch'd
  `ALTER TABLE`, register in `addMigrations(...)`. Seeds `item_units` + `catalog_items` on create;
  an `onOpen` callback purges known-bad STT rows (e.g. "kilometer"). Entities: `CatalogItem`,
  `ItemUnit`, `TransactionRecord`, `CreditRecord`, `StockInRecord`, `UnmatchedQueueItem`,
  `SyncQueueItem`, `SttJobRecord`.
- **Sync** — one-directional, local-first. Every entity has `synced: Boolean`; `SyncEngine` sweeps
  unsynced rows via `CloudSyncManager`. No pull/merge path — Supabase is a mirror, not a second
  source of truth. URL/anon key in `network/SupabaseConfig.kt`; real secrets are edge-function
  env vars only.
- **UI** — single-Activity Compose, hand-rolled `enum class Screen` + `when` (no Navigation
  Compose). No ViewModel layer: `MainActivity` owns the DAOs and hoists all state into stateless
  composables under `ui/screens/<feature>/`.
- **Services** — `AppForegroundService` keeps the process alive for the rolling buffer;
  `UpiNotificationListenerService` reads Paytm/PhonePe/GPay notifications to match payments
  (extracts only amount + status — see `play_console_declaration.md`).

## Known quirks

- `:app:kspDebugKotlin` intermittently throws `IOException: Could not delete …/ksp/…` — OneDrive/Defender lock, not a code error. `./gradlew --stop`, delete `app/build/generated/ksp`, rebuild.
- `app/src/androidTest/.../com/example/voicetoinvoice/ui/main/MainScreenTest.kt` is template boilerplate under the wrong package referencing a nonexistent composable. It won't compile.
- `SttProxyClient` posts to `SupabaseConfig.STT_PROXY_ENDPOINT`, which points at `process-voice-job`, not the superseded `stt-proxy` function.

## Keeping Docs/audit.md in sync — every session, automatically

`Docs/audit.md` §2 "Living Issues Log" is the shared memory between agents. An unlogged fix is
invisible to whoever opens the repo next.

After fixing any **behaviour-affecting** bug (not typos or pure refactors):

1. Add an entry under "🟢 RESOLVED ISSUES" with the next sequential `ISSUE-NNN` (check the highest first) and today's date.
2. Match the existing format: **Symptom** (with a trace ID or concrete example), **Root Cause** (numbered if multi-part), **Resolution** (numbered, naming exact files), **Verification Date** (state plainly what you verified vs. what's still unverified).
3. If it refines an existing 🔴 OPEN issue, update that issue rather than creating a duplicate (see how ISSUE-011 cross-references ISSUE-004).
4. If you change a source-of-truth constant (thresholds, model names, schema), update §1 "Ground-Truth Source-Code Verified Constants" too.

Do it before ending the turn. When a commit is approved, reference the issue number in the
message so `git log` corroborates the audit log — the two must never diverge.

Full DB schema, credential map, and issue history: `Docs/audit.md`.
