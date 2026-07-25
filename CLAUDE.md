# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

"Voice-First Shop Ledger" (package `com.voicetoinvoice.app`) — an offline-first Android app for Indian Kirana/vegetable shopkeepers. A shopkeeper holds a mic button, speaks a sale in Hindi/Hinglish/English (e.g. "चार किलो आलू"), and the app transcribes, parses items/quantity/unit/price, and books it to a local ledger — auto-confirming high-confidence sales and routing ambiguous ones to a review queue. It also tracks customer credit (Udhaar), stock-in, and reconciles UPI payment notifications against pending sales.

The repo has two parts:
- **`app/`** — the Android client (Kotlin + Jetpack Compose).
- **`supabase/`** — Deno Edge Functions + Postgres schema for the cloud backend (STT + AI parsing + sync).

## Build & test commands

Run from the repo root (Windows: use `gradlew.bat`; the examples below use the Unix wrapper name).

```bash
./gradlew assembleDebug
```

```bash
./gradlew installDebug
```

```bash
./gradlew test
```

Run a single JVM unit test class or method (tests live in `app/src/test/java/com/voicetoinvoice/app/`):

```bash
./gradlew test --tests "com.voicetoinvoice.app.VoiceParserTest"
```

```bash
./gradlew test --tests "com.voicetoinvoice.app.VoiceParserTest.someTestMethod"
```

Instrumented tests (require a connected device/emulator):

```bash
./gradlew connectedAndroidTest
```

There is no lint/detekt/ktlint config in this repo — just AGP's default `./gradlew lint` if needed.

### Supabase Edge Functions

Functions live in `supabase/functions/*/index.ts` (Deno). Deploy with the Supabase CLI:

```bash
npx supabase functions deploy process-voice-job
```

The project is linked via `supabase/.temp/project-ref`; `supabase/config.toml` holds local dev config. `supabase/schema.sql` is the source of truth for the Postgres schema (RLS-enabled tables); numbered files in `supabase/migrations/` are incremental changes applied on top.

## Architecture

### Client-side voice pipeline (the core of the app)

```
Mic button press → RollingAudioBuffer (30s ring buffer, audio/AudioRecorder.kt + DirectAudioRecorder.kt)
  → on release, SttJobRecord written to Room (status=QUEUED)
  → BackgroundSttProcessor (domain/processor/) drains the queue via WorkManager (SttWorker)
      1. Uploads audio to Supabase Edge Function `process-voice-job` (SttProxyClient) — dual STT (Grok + Sarvam)
      2. OrderingSegmenter (domain/parser/) — deterministic [qty][unit][item] segmentation
      3. Falls back to Grok AI (TermInterpreterClient) or local MultiSaleDetector/VoiceParser when segments are ambiguous
      4. Adaptive Audio Expansion Engine — re-extracts a slightly wider audio window (±100ms/pass, up to 3 passes)
         from RollingAudioBuffer and re-transcribes when confidence is low
      5. Auto-confirms to `transactions` table when confidence ≥ 0.80 and item/price are resolved;
         otherwise leaves the job as PARSED for the Unmatched Queue / Pending Confirmations UI
  → CloudSyncManager pushes the diagnostic trace, audio URL, and any auto-confirmed transaction to Supabase
    asynchronously (non-blocking — never delays the next recording)
```

Every processing step writes into a single JSON `diagnosticTraceJson` blob (`step_1_ptt_recording_metadata` … `step_6_final_outcome`) stored on the `SttJobRecord` — this is the primary debugging tool for parsing issues; it's surfaced in `ui/screens/logs/DiagnosticLogsScreen.kt` and mirrored to Supabase's `stt_job_logs` table.

**Server-side mirror of this logic exists too.** `supabase/functions/process-voice-job/index.ts` independently re-implements dual STT (Grok `/v1/stt` + Sarvam), a combinatorial fuzzy phonetic segmenter, and Grok-based multi-item interpretation, then writes directly to `stt_job_logs` / `transactions` / `unmatched_queue` via the service-role client (see `implementation_plan.md` for the "server-first instant processing" rationale — the goal is that a recording finishes processing even if the app is closed). When touching parsing logic, check whether the equivalent needs to change in both the Kotlin client (`domain/parser/`, `domain/processor/`) *and* the edge function.

### Local persistence (Room, `data/local/`)

`AppDatabase` (version 8) is the single Room database, manually migrated with one `Migration` object per version bump (no auto-migrations) — follow that existing pattern (bump `version`, add a `MIGRATION_N_N+1` with try/catch'd `ALTER TABLE`, register it in `addMigrations(...)`) when changing entities. It seeds `item_units` and a large default `catalog_items` list on first create (`seedItemUnits`/`seedMasterCatalog`), and has an `onOpen` callback that purges a hardcoded list of known bad STT-parsed catalog/transaction rows (e.g. "kilometer", "किलोमीटर") — a workaround for recurring STT misfires rather than a general mechanism.

Entities: `CatalogItem`, `ItemUnit`, `TransactionRecord` (append-only sale events), `CreditRecord` (Udhaar), `StockInRecord`, `UnmatchedQueueItem`, `SyncQueueItem`, `SttJobRecord` (the voice pipeline's per-recording state machine — see `SttJobStatus`).

### Cloud sync

Sync is one-directional, local-first: every writable entity has a `synced: Boolean` column, and `SyncEngine` (`data/sync/SyncEngine.kt`) sweeps each DAO's unsynced rows and pushes them individually via `CloudSyncManager` (`network/CloudSyncManager.kt`), marking rows synced on success. `MainActivity` triggers a sync sweep on every screen load and after most local writes. There's no pull/merge path — the Supabase side is a mirror/log, not a second source of truth the app reads back from (except where the server directly auto-confirms a transaction from `process-voice-job`, which the app picks up by polling/re-reading its own local DB after the job completes).

Supabase project URL/anon key live in `network/SupabaseConfig.kt` (client-safe anon key; real secrets like `XAI_API_KEY`/`SARVAM_API_KEY` are edge-function-only env vars, never in the app).

### UI

Single-Activity Compose app (`MainActivity.kt`) with a hand-rolled `enum class Screen` + `when` block for navigation (no Navigation Compose graph despite `androidx.navigation3` being a dependency). Screens live under `ui/screens/<feature>/`, shared widgets under `ui/components/`. `MainActivity` owns all the DAOs/state hoisting and passes callbacks down — there's no ViewModel layer; screens are stateless composables driven from `MainAppScreen`.

### Background services

- `service/AppForegroundService.kt` — keeps the process alive for the rolling audio buffer / background STT processing.
- `service/UpiNotificationListenerService.kt` — reads `BIND_NOTIFICATION_LISTENER_SERVICE` notifications from Paytm/PhonePe/Google Pay to auto-match incoming UPI payments against pending sales (see `play_console_declaration.md` for the exact Play Store permission justification — only numeric amount + payment status are ever extracted, everything else is ignored).

## Known repo quirks

- `app/src/androidTest/java/com/example/voicetoinvoice/ui/main/MainScreenTest.kt` is leftover template boilerplate under the stale `com.example.voicetoinvoice` package (the real app package is `com.voicetoinvoice.app`) and references a `MainScreen` composable that doesn't exist in this codebase — it will not compile as part of a real test run.
- The client's `SttProxyClient` posts to `SupabaseConfig.STT_PROXY_ENDPOINT`, which actually points at the `process-voice-job` function, not the older `stt-proxy` function (which still exists in `supabase/functions/stt-proxy/` but appears superseded — see `implementation_plan.md`).

For full DB schema, credential variable map, and known-issue history, see `C:\Users\harsh\Documents\Voice To Invoice\Docs\audit.md`

## Keeping Docs/audit.md in sync — do this automatically, every session

`Docs/audit.md`'s "Living Issues Log" (§2) is the shared handoff mechanism between every AI agent working on this repo (this tool and Antigravity, working in separate sessions with no shared memory otherwise). Updating it is part of finishing a fix, not an optional extra step — an unlogged fix is invisible to whichever agent opens the repo next.

Whenever you diagnose and fix a real bug (behavior-affecting — not a typo or pure refactor):
1. Add a new entry under "🟢 RESOLVED ISSUES", using the next sequential `ISSUE-NNN` number (check the highest existing number in the file first) and today's date.
2. Match the existing format exactly: **Symptom** (what was observed, with a trace ID or concrete example if you have one), **Root Cause** (numbered if multi-part), **Resolution** (numbered, one line per concrete change, naming exact files), **Verification Date** (state plainly what you actually verified vs. what's still unverified — don't imply testing that didn't happen).
3. If the fix is a refinement of an existing 🔴 OPEN issue (same root-cause class), update that issue's mitigation list / status to point at the new entry instead of creating an orphaned duplicate — see how ISSUE-011 cross-references ISSUE-004.
4. If you change a source-of-truth constant (confidence thresholds, model names, DB schema, line-number references cited elsewhere in the doc), also update the relevant row in "Ground-Truth Source-Code Verified Constants" (§1) so it doesn't silently drift out of date.

Do this before ending the turn, not "later." Also: when the user has approved a commit, reference the issue number in the commit message (e.g. `Fix ISSUE-011: segmenter vocab gap...`) so `git log` independently corroborates the audit log — the log and git history should never diverge.
