# Voice To Invoice: Master System Audit, Living Issue Log & Source-of-Truth

> **Document Purpose**: Single source of truth for system contracts, architecture, verified source-code constants, and a dated living log of open and resolved issues.

---

## 1. Ground-Truth Source-Code Verified Constants

*(Verified directly against source code in `process-voice-job/index.ts` and `SttWorker.kt` on July 25, 2026)*

| Metric / Parameter | Actual Source Code Value | Source Location | Discrepancy Note |
| :--- | :--- | :--- | :--- |
| **Auto-Confirm Confidence Threshold** | **`confidence >= 0.80`** | `supabase/functions/process-voice-job/index.ts` (L697) & `BackgroundSttProcessor.kt` (L336) | *Unified auto-confirm threshold aligned to 0.80 across client and server on July 25, 2026.* |
| **Catalog Exact Match Confidence** | **`0.95`** | `process-voice-job/index.ts` (L682) | Assigned when item matches DB catalog SKU or explicit keyterm. |
| **Phonetic Inferred Confidence** | **`0.60` / `0.70`** | `process-voice-job/index.ts` (L682) | Assigned to phonetically resolved/unlisted items. |
| **Edge Function Client Timeout** | **`30s` (connect) / `60s` (read)** | `SttWorker.kt` (L93-94) | Prevents local HTTP timeout fallback rows. |
| **Background Polling Window** | **`30 seconds`** (every 2s) | `SttWorker.kt` (L161-162) | Drains `stt_job_logs` queue following HTTP 202 ack. |

---

## 2. Living Issues Log (Dated History)

### 🔴 OPEN ISSUES

#### [ISSUE-004] [2026-07-24] Acoustic Consonant Blending on Unlisted Items
- **Symptom**: Spoken orders like `"तीन किलो बैंगन"` transcribed as `"Tinggal benggan"`. When consonant shifts are extreme (e.g. `क` $\leftrightarrow$ `ग`, `ब` $\leftrightarrow$ `प`), both STT engines output phonetically noisy text.
- **Root Cause**: Speech-To-Text acoustic mishearing when Indian shopkeepers speak without word pauses.
- **Current Mitigation**:
  1. Pure Levenshtein Edit-Distance Combinatorial Segmenter (`editDistance`) deployed in `process-voice-job/index.ts`.
  2. Phonetic-Aware Indian Shopkeeper Prompt deployed to Grok AI (Step 4).
- **Status**: OPEN — Monitoring production transcripts to refine phonetic distance weights.

---

### 🟢 RESOLVED ISSUES

#### [ISSUE-009] [2026-07-25] On-Device Emulator Verification, "Set Price" Tap Fix & Single-Source Review UI Consolidation
- **Symptom**: During live on-device emulator testing of `PendingConfirmationsSheet`, setting `enabled = isConfirmable` caused the button to be disabled when `parsedTotal == 0.0`, preventing users from tapping "Set Price" to open the edit dialog. Furthermore, an orphaned duplicate screen (`UnmatchedQueueScreen.kt`) existed alongside the live `PendingConfirmationsSheet.kt`.
- **Root Cause**:
  1. Over-strict `enabled = isConfirmable` prop disabled the button UI node before the `onClick` handler could open `editingJob = job`.
  2. Legacy dead code path (`UnmatchedQueueScreen.kt` / `Screen.UNMATCHED_QUEUE`) created implementation drift risk against the live `PendingConfirmationsSheet.kt` bottom sheet.
- **Resolution**:
  1. Kept the button enabled (`enabled = true`), allowing tapping "Set Price" to open the edit dialog where the shopkeeper enters a valid price $>0$.
  2. Deleted orphaned `UnmatchedQueueScreen.kt` and removed `Screen.UNMATCHED_QUEUE` from `MainActivity.kt`, establishing **`PendingConfirmationsSheet.kt`** as the single source of truth for review queue rendering.
  3. Verified end-to-end on running emulator: confirmed unlisted item (`TestGhostItem`) at ₹175, verified automatic insertion into Room `catalog_items`, verified display in `DailySummaryScreen`, and verified rebuilt `step_6_final_outcome` trace in `stt_jobs`.
  4. Cleaned up transient test rows from production Supabase tables (`catalog_items`, `transactions`, `stt_job_logs`).
- **Verification Date**: 2026-07-25 (Tested live on Android emulator, APK rebuilt & verified).

#### [ISSUE-008] [2026-07-25] Live Confirmation Sheet Audit, Catalog SKU Persistence & Navigation Alignment
- **Symptom**: User test trace showed an unlisted sale (`Haldiram`) confirmed at ₹0 without persisting to catalog, and `stt_job_logs` in Supabase retained a stale pre-correction trace. Audit revealed that the live confirmation UI is `PendingConfirmationsSheet` (opened via Home screen bottom sheet), while `Screen.UNMATCHED_QUEUE` in `MainActivity.kt` was orphaned.
- **Root Cause**:
  1. `HomeScreen.kt`'s `onConfirmJob` handler updated `status` and `parsedTotal` on `stt_jobs`, but never called `catalogDao().insertOrUpdate()` for unlisted items, causing future sales of the same item to land back in pending review at ₹0.
  2. `HomeScreen.kt`'s handler never updated `diagnosticTraceJson` step 6, leaving `stt_job_logs` in Supabase with stale pre-confirmation trace data.
  3. `PendingConfirmationsSheet.kt` lacked the shared validation gate (`parsedTotal > 0.0`), allowing ₹0 sales to confirm.
- **Resolution**:
  1. Updated `HomeScreen.kt` `onConfirmJob` to persist newly-recognized catalog items to Room DB via `catalogDao().insertOrUpdate(item)`.
  2. Updated `HomeScreen.kt` to rebuild `step_6_final_outcome` in `diagnosticTraceJson` with `matchedCatalogId: item.id`, `autoConfirmedToLedger: true`, and `userResolved: true`, pushing clean updated traces to Supabase `stt_job_logs`.
  3. Added shared validation gate (`parsedTotal > 0.0 && parsedItemName != "Unrecognized Item"`) to `PendingConfirmationsSheet.kt`, disabling ₹0 confirmation until price is set.
  4. Wired navigation to `DailySummaryScreen` (`Screen.SUMMARY`) and aligned `UnmatchedQueueScreen` navigation in `MainActivity.kt`.
- **Verification Date**: 2026-07-25 (`BUILD SUCCESSFUL`, `VoiceToInvoice-v2.0-debug.apk` deployed).

#### [ISSUE-007] [2026-07-25] Unmatched Queue UI Pre-Filled Editable Card, Resolved SKU Persistence & Full Cloud Trace Sync
- **Symptom**: `UnmatchedQueueScreen` previously displayed only `rawTranscript` text, forcing the shopkeeper to manually re-enter sales details. Furthermore, `onResolveItem` in `MainActivity.kt` was an empty no-op callback `{}`, and resolving a review item left `stt_job_logs` in Supabase with stale pre-correction trace data.
- **Root Cause**: `UnmatchedQueueScreen.kt` lacked pre-filled form fields, did not surface AI-parsed candidates from `stt_jobs`, and did not update `stt_jobs` / `stt_job_logs` upon shopkeeper confirmation.
- **Resolution**:
  1. Updated `UnmatchedQueueScreen.kt` to join `unmatched_queue` items with `SttJobRecord` candidates by `job_id`.
  2. Built pre-filled editable Card displaying:
     - Secondary raw transcript (`"चार किलो सोना नौ"`).
     - Pre-filled Item Name, Quantity, and Unit fields.
     - `⚠ No catalog match` pill if `matchedCatalogItem` is null.
     - Price input field `Price: [______] × 4 = ₹0` with dynamic total calculation.
     - **`Confirm to Ledger`** button (disabled until `price > 0` AND `total > 0` AND non-empty valid item name).
     - **`Assign SKU`** button (opens interactive dialog to pick existing catalog SKU and auto-populate price/unit).
     - **`Discard`** button (marks status `DISCARDED`).
  3. Updated `onConfirmSale` in `MainActivity.kt` to write `resolvedItem.id` back to `unmatched_queue.resolvedItemId`, `transactions.itemId`, and update local `stt_jobs` record (`status = CONFIRMED`, `parsedItemId`, `parsedTotal`, and updated `diagnosticTraceJson` step 6).
  4. Updated `CloudSyncManager.kt` (`syncReviewItemToCloud` & `syncJobRecordToCloud`) to push `resolved_item_id` to Supabase `unmatched_queue` and push the updated trace log to `stt_job_logs`, preventing stale trace logs in future audits.
- **Verification Date**: 2026-07-25 (APK built & verified, `VoiceToInvoice-v2.0-debug.apk` deployed).

#### [ISSUE-006] [2026-07-25] Server-Side Zero-Price Sales Guard & Catalog Match Contradiction Fix
- **Symptom**: A 0.85-confidence sale with `matchedCatalogId: null` auto-confirmed to `transactions` table with `estimatedTotal: 0` (a ₹0 sale auto-booked into confirmed ledger). Additionally, `is_matched_to_catalog: true` was reported despite `matchedCatalogId` being `null`.
- **Root Cause**:
  1. `process-voice-job/index.ts` line 681 set `isMatched = true` whenever `rawItem.confidence >= 0.70`, conflating Grok AI interpretation confidence with real DB catalog SKU matching.
  2. Line 697 only checked `confidence >= 0.80` without verifying `price_at_sale > 0.0` or `total > 0.0`.
- **Resolution**:
  1. Fixed `isCatalogMatched = matched !== undefined`, ensuring `is_matched_to_catalog` is `true` **only** when a real DB catalog item matched.
  2. Updated `isAutoConfirmed` gate in `process-voice-job/index.ts` (L696-706) to require `confidence >= 0.80 && price_at_sale > 0.0 && total > 0.0 && item_name != "Unrecognized Item"`.
  3. Unpriced or ₹0 items (`total <= 0`) are now automatically routed to `unmatched_queue` (`status: "PENDING"`) for shopkeeper manual review, regardless of confidence.
- **Verification Date**: 2026-07-25 (Edge function deployed to `lyowklxsbfznnqridtgr`, APK rebuilt and verified).

#### [ISSUE-005] [2026-07-25] Auto-Confirm Confidence Threshold Mismatch Aligned to 0.80 & Room DB Migration 7->8 Added
- **Symptom**: Edge Function auto-confirmed items to Supabase `transactions` at `confidence >= 0.60`, while offline client processing auto-confirmed at `confidence >= 0.80f`, creating a financial ledger threshold mismatch. Furthermore, when `isAutoConfirmed` was `false` (`status = "PARSED"`, `confidence < 0.80`), `SttWorker.kt` updated `stt_job_logs` in Room DB but never inserted into local `unmatched_queue` table, causing unconfirmed items to be missing from the Unmatched Queue UI.
- **Root Cause**: Edge Function line 697 used `0.60`, `SttWorker.kt` lacked an `else` branch to insert `UnmatchedQueueItem` into `db.unmatchedQueueDao()`, and changing `UnmatchedQueueItem.shopId` to nullable `String?` required an explicit Room migration.
- **Resolution**:
  1. Aligned line 697 in `process-voice-job/index.ts` to `confidence >= 0.80`.
  2. Updated `SttWorker.kt` (L218-261) to wrap `transactions` insertion in `if (isAutoConfirmed)` and added `else` branch inserting `UnmatchedQueueItem` with `shopId = SupabaseConfig.getNullSafeShopId(null)` into `db.unmatchedQueueDao()`.
  3. Consolidated `SupabaseConfig.getNullSafeShopId()` across `SttWorker.kt` and `CloudSyncManager.kt` (`syncReviewItemToCloud`), removing dead `"default_shop"` checks.
  4. Added `MIGRATION_7_8` in `AppDatabase.kt` and bumped Room DB version to `8` to migrate SQLite table `unmatched_queue` and convert `"default_shop"` strings to `NULL`.
- **Verification Date**: 2026-07-25 (Edge function deployed to `lyowklxsbfznnqridtgr`, `AppDatabase.kt` migrated v7->v8, APK rebuilt and verified).

#### [ISSUE-003] [2026-07-24] Recognized Unlisted Items Missing in App Summary Ledger
- **Symptom**: Sales parsed as `Sona 4 KG` or `Paneer 2 KG` showed in Supabase `stt_job_logs` but failed to appear in the Android app ledger summary.
- **Root Cause**: `SttWorker.kt` evaluated `isAutoConfirmed = statusStr == "AUTO_CONFIRMED" && parsedItems.length() > 0`. During background polling, `parsedItems` was empty because detailed items are nested in `diagnostic_trace_json`.
- **Resolution**: Updated `SttWorker.kt` to extract `step_4_grok_ai_interpretation` from `diagnostic_trace_json` and insert local `TransactionRecord` rows into Room DB.
- **Verification Date**: 2026-07-24 (`BUILD SUCCESSFUL`, `VoiceToInvoice-v2.0-debug.apk` deployed).

#### [ISSUE-002] [2026-07-24] Postgres Foreign Key Violation (23503) on `shops` Table
- **Symptom**: Voice recording uploaded to Storage, but `stt_job_logs` and `unmatched_queue` writes vanished silently without inserting rows.
- **Root Cause**: `shops` table was empty (`0` rows). Hardcoding default UUID `'11111111-1111-1111-1111-111111111111'` triggered Postgres FK violation (23503).
- **Resolution**: Implemented `getNullSafeShopId(shopId)` returning `null` when no valid shop UUID exists. Ran `db-setup` DDL migration to drop NOT NULL on `shop_id`.
- **Verification Date**: 2026-07-24 (HTTP 200 OK from `db-setup`).

#### [ISSUE-001] [2026-07-23] Client-Side 20s Server Timeout & Silent Loss of Log Rows
- **Symptom**: Room DB stored fabricated fallback row `raw_transcript: "Voice Recording (Server Timeout)"`. Supabase stored 0 rows.
- **Root Cause**: Synchronous processing stacked dual STT (16s) + Grok AI (8s) + DB calls beyond client timeout.
- **Resolution**: Implemented `EdgeRuntime.waitUntil(...)` background architecture. Edge function responds with HTTP 202 `QUEUED` in ~1.2s while AI executes asynchronously.
- **Verification Date**: 2026-07-23 (Deployed live to project `lyowklxsbfznnqridtgr`).

---

## 3. Database Schema Contract (Supabase Postgres)

```sql
-- 1. STT Audit Logs
CREATE TABLE IF NOT EXISTS public.stt_job_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id TEXT UNIQUE NOT NULL,
    shop_id UUID NULL REFERENCES public.shops(id),
    recorded_at_ms BIGINT,
    hold_duration_ms BIGINT,
    status TEXT, -- 'QUEUED', 'AUTO_CONFIRMED', 'PARSED', 'ERROR'
    raw_transcript TEXT,
    parsed_item_name TEXT,
    parsed_qty DOUBLE PRECISION,
    parsed_unit TEXT,
    parsed_total DOUBLE PRECISION,
    is_sanity_flagged BOOLEAN DEFAULT false,
    error_message TEXT,
    diagnostic_trace_json TEXT,
    audio_cloud_url TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_stt_job_logs_job_id_unique ON public.stt_job_logs(job_id);

-- 2. Unmatched Queue (Review Items)
CREATE TABLE IF NOT EXISTS public.unmatched_queue (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id TEXT UNIQUE,
    shop_id UUID NULL REFERENCES public.shops(id),
    audio_ref TEXT,
    raw_transcript TEXT,
    resolved_item_id UUID NULL,
    status TEXT, -- 'PENDING', 'RESOLVED', 'DISCARDED', 'ERROR'
    timestamp TIMESTAMPTZ DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_unmatched_queue_job_id_unique ON public.unmatched_queue(job_id);

-- 3. Transactions (Auto-Confirmed Ledger)
CREATE TABLE IF NOT EXISTS public.transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id TEXT,
    shop_id UUID NULL REFERENCES public.shops(id),
    item_id UUID NULL,
    audio_cloud_url TEXT,
    raw_transcript TEXT,
    item_name TEXT,
    quantity DOUBLE PRECISION,
    price_at_sale DOUBLE PRECISION,
    total DOUBLE PRECISION,
    payment_mode TEXT DEFAULT 'CASH',
    source TEXT DEFAULT 'VOICE',
    timestamp TIMESTAMPTZ DEFAULT NOW()
);
```

---

## 4. End-to-End Pipeline Data Flow

```
[Android Client (SttWorker.kt)]
       │ (Multipart HTTP POST)
       ▼
[Supabase Edge Function: process-voice-job]
       ├─► Storage Upload (voice-recordings/${jobId}.wav)
       ├─► Initial Write: stt_job_logs (status: 'QUEUED')
       ├─► Immediate HTTP 202 Response (~1.2s)
       │
       ▼ (EdgeRuntime.waitUntil Async Pipeline)
[Dual STT & Combinatorial AI Engine]
       ├─► Grok STT + Sarvam STT saarika:v2 (parallel 8s timeouts)
       ├─► Combinatorial Levenshtein Segmenter (editDistance matching)
       ├─► Grok AI (Phonetic-Aware Shopkeeper System Prompt)
       └─► Final Write: stt_job_logs + transactions (if confidence >= 0.60) / unmatched_queue (if PENDING)
```

---

## 5. Master Source File Index

- **Master System Audit & Living Log**: [Docs/audit.md](file:///C:/Users/harsh/Documents/Voice%20To%20Invoice/Docs/audit.md)
- **Primary Edge Function**: [process-voice-job/index.ts](file:///C:/Users/harsh/Documents/Voice%20To%20Invoice/supabase/functions/process-voice-job/index.ts)
- **DB Setup Function**: [db-setup/index.ts](file:///C:/Users/harsh/Documents/Voice%20To%20Invoice/supabase/functions/db-setup/index.ts)
- **Android Background Worker**: [SttWorker.kt](file:///C:/Users/harsh/Documents/Voice%20To%20Invoice/app/src/main/java/com/voicetoinvoice/app/domain/processor/SttWorker.kt)
- **Latest Debug APK**: `C:\Users\harsh\OneDrive\Desktop\VoiceToInvoice_APKs\VoiceToInvoice-v2.0-debug.apk`
