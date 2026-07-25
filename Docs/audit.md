# Voice To Invoice: Master System Audit, Living Issue Log & Source-of-Truth

> **Document Purpose**: Single source of truth for system contracts, architecture, verified source-code constants, and a dated living log of open and resolved issues.

---

## 1. Ground-Truth Source-Code Verified Constants

*(Verified directly against source code in `process-voice-job/index.ts`, `BackgroundSttProcessor.kt`, and `SttWorker.kt` on July 25, 2026; confidence-gate line numbers re-verified July 26, 2026 after ISSUE-019)*

| Metric / Parameter | Actual Source Code Value | Source Location | Discrepancy Note |
| :--- | :--- | :--- | :--- |
| **Auto-Confirm Confidence Threshold** | **`confidence >= 0.80`** | `supabase/functions/process-voice-job/index.ts` (L778) & `BackgroundSttProcessor.kt` (L336) | *Unified auto-confirm threshold aligned to 0.80 across client and server on July 25, 2026.* |
| **Catalog Exact Match Confidence** | **`0.95`** | `process-voice-job/index.ts` (L757-759) | Assigned when item matches a DB catalog SKU (server-side); fallback is capped at `0.60` for unmatched items — as of ISSUE-019 this floor is a hard `Math.min`, not just a default, so a self-reported LLM confidence can no longer bypass it. |
| **Phonetic Inferred Confidence** | **`0.70`** (matched) **`0.90`** (server-confirmed match) | `BackgroundSttProcessor.kt` (L181) | Client-side fallback path, not the edge function — assigns `0.90f` when a catalog item matches, `0.70f` otherwise. |
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
  3. [2026-07-25, see ISSUE-011] Segmenter's hardcoded Devanagari vocab expanded + confidence floor added; Grok prompt now explicitly distrusts the segmenter's output and was given aspirated/unaspirated consonant hints.
- **Status**: OPEN — Monitoring production transcripts to refine phonetic distance weights. ISSUE-011 fixed one concrete failure mode (missing catalog vocab word) of this broader class; other unlisted items can still hit the same failure pattern until their Devanagari alias is added.

#### [ISSUE-018] [2026-07-26] Test-Only Broadcast Receiver Exported Unconditionally in `UpiNotificationListenerService` — Allows Any App to Inject Fake Transactions or Fake UPI Reconciliation
- **Symptom**: `UpiNotificationListenerService.onCreate()` registers a `BroadcastReceiver` for two custom actions — `com.voicetoinvoice.app.SEED_TEST_TX` and `com.voicetoinvoice.app.TEST_UPI` — apparently added to let a developer drive the UPI-reconciliation flow via `adb shell am broadcast` without a real Paytm/PhonePe/GPay notification. The receiver is registered with `RECEIVER_EXPORTED` on API 33+ (and is implicitly exported pre-33), and is **not** gated behind any debug/build-type check (`app/build.gradle.kts` has `buildFeatures.buildConfig = false`, so a `BuildConfig.DEBUG` guard isn't even wired up).
- **Root Cause**: Debug/manual-test scaffolding was left wired into the always-running production code path instead of being removed or gated behind a debug-only build flag.
- **Impact**: Any other app installed on the shopkeeper's phone (or an attacker with `adb` access) can, without any permission check:
  1. Broadcast `SEED_TEST_TX` with an `amount` extra to insert a fake `"Test Pyaz"` CASH sale directly into the real `transactions` ledger (which then syncs to Supabase).
  2. Broadcast `TEST_UPI` with `title`/`text` extras containing a rupee amount and a keyword like "paytm"/"phonepe"/"gpay" to falsely mark a real pending/Udhaar sale as `paymentMode = UPI` (i.e. spoof a payment that never happened).
- **Status**: OPEN — flagged during a general app review, not yet fixed pending a decision on whether the shopkeeper/dev still needs this for manual testing. Recommended fix: delete the test receiver entirely (it has no legitimate place in a shipped ledger app), or at minimum register it with a signature-level `broadcastPermission` so only this app can trigger it.

---

### 🟢 RESOLVED ISSUES

#### [ISSUE-019] [2026-07-26] "एक किलो चांदी" Misparsed as "2 PACKET Chaandi" — STT-Elided Unit Word Corrected to a Number Instead of a Unit, Compounded by an Unenforced Confidence Floor
- **Symptom**: Trace `24fb3b5b-ed17-43b9-bea2-f0df1137e17f` — shopkeeper said "एक किलो चांदी" (1 kg silver), Grok STT heard "एक लो चांदी" (leading "कि" of "किलो" dropped — a common fast-speech elision), and the pipeline emitted `quantity: 2, unit: PACKET` with `confidence: 0.85` despite `is_matched_to_catalog: false`. Not auto-confirmed (price stayed ₹0, so the existing safety gate held), but landed in the review queue with a badly garbled parse instead of an easily-correctable one.
- **Root Cause** (three compounding bugs, found by tracing the full pipeline against the actual code, not just the symptom):
  1. `combinatorialFuzzySegmenter` in `process-voice-job/index.ts` checked the orphaned fragment "लो" against `CANONICAL_NUMBERS` before `CANONICAL_UNITS` and took the first match under threshold — "लो" is edit-distance 1 from "दो" (two) but edit-distance 2+ from "किलो" (kilo) by plain Levenshtein, so the number match won even though "लो" is really the truncated tail of "किलो", not a typo of "दो". The single-token check had no way to recognize a truncated-prefix (elision) match at all.
  2. Once the transcript read "एक दो चांदी", the Step 3 deterministic segmenter hit two number tokens back-to-back with no item between them and **silently overwrote** the quantity (`एक`=1 discarded, `दो`=2 kept) with no sanity flag — unlike `OrderingSegmenter.kt` on the client, which already flags this exact "two quantities, no item" pattern as `isSanityFlagged = true`. This was a real, unnoticed client/server behavior gap.
  3. Independently, the confidence pipeline had no actual floor: `Docs/audit.md` documented a `0.60` confidence ceiling for catalog-unmatched items, but the code was `rawItem.confidence || (isCatalogMatched ? 0.95 : 0.60)` — a plain default, not an enforced cap, so Grok self-reporting `0.85` for an item it simultaneously marked `is_matched_to_catalog: false` (violating its own prompt's rule 5) sailed straight through. The Step 3 fallback path separately hardcoded `confidence: 0.85, matched_catalog: true` regardless of what the deterministic segmenter actually found.
- **Resolution**:
  1. **[Kotlin, `OrderingSegmenter.kt`]** Replaced the greedy per-token classifier with a grammar-aware Viterbi lattice decoder (`GrammarLatticeDecoder`): every token gets scored candidates for NUM/UNIT/ITEM (exact match cost 0, unit-elision-suffix match cost 0.5, edit-distance-1 fuzzy match cost 1.0, plain-item fallback cost 1.2 — only offered when no exact match exists, so an unambiguous vocabulary word can never be "cheated" into a fake item reading), and a transition-cost table encoding the shopkeeper's `[QTY][UNIT][ITEM]` grammar (`NUM→NUM` costs 4.0, `NUM→UNIT`/`UNIT→ITEM` cost 0). This makes the decoder prefer "1 KG Chaandi" over "2 PACKET Chaandi" for the exact reason a human would. Verified against 11 unit tests in `OrderingSegmenterTest.kt` (9 pre-existing + 2 new regression tests for this trace), all passing, plus the full 27-test client suite passing (`./gradlew testDebugUnitTest`, `BUILD SUCCESSFUL`).
  2. **[TypeScript, `process-voice-job/index.ts`]** Ported the same fix without the full lattice (lower risk for a deployed edge function): `combinatorialFuzzySegmenter`'s single-token check now scores NUM/UNIT/UNIT-elision/catalog candidates by cost and takes the cheapest, instead of checking numbers first and stopping at the first match. Also ported the client's `isSanityFlagged` double-quantity tracking into the Step 3 segmenter (`hasPendingQty`/`ambiguousDoubleQty`, mirroring `OrderingSegmenter.kt` exactly) as a safety net for genuinely ambiguous cases the elision fix doesn't catch.
  3. **[TypeScript, confidence floor]** The Step 3 fallback mapping no longer hardcodes `confidence: 0.85, matched_catalog: true` — it leaves confidence unset (letting the real catalog-match step compute it) and caps it at `0.3` when the segment was itself flagged ambiguous. The catalog-match confidence line now does `Math.min(rawItem.confidence || 0.60, 0.60)` for unmatched items instead of `||`, so a self-reported LLM confidence can no longer bypass the documented floor.
- **Files Touched**: `OrderingSegmenter.kt` (rewritten), `OrderingSegmenterTest.kt` (+2 tests), `supabase/functions/process-voice-job/index.ts` (`combinatorialFuzzySegmenter`, Step 3 segmenter, confidence computation — 3 separate edits).
- **Verification Date**: 2026-07-26. Kotlin side: `./gradlew testDebugUnitTest` full suite `BUILD SUCCESSFUL`, and the exact "एक लो चांदी" trace was hand-traced through the updated TypeScript pipeline end-to-end (confirmed it now resolves to `quantity=1, unit=KG, confidence=0.60, is_matched_to_catalog=false` — still correctly routed to review since Chaandi isn't a real catalog item, but with a correct, easily-actionable parse instead of a garbled one). Deployed to production via `npx supabase functions deploy process-voice-job` on 2026-07-26 (project `lyowklxsbfznnqridtgr`, confirmed via the CLI's `"message":"Deployed Functions."` response) — **not yet spot-checked against a live recording post-deploy**; the trace-level verification above was static/hand-traced, not an actual production STT round-trip. Next real "किलो"-adjacent utterance should be checked against `stt_job_logs` to confirm the fix behaves the same live as it did on paper. The Kotlin fix ships automatically in the next APK build.


#### [ISSUE-017] [2026-07-26] Est. Margin Line Computed Wildly Wrong Numbers — Total Stock-In Cost Mistaken for Per-Unit Cost Price
- **Symptom**: The new "📈 Est. Margin" line (`DailySummaryScreen.kt`, added under ISSUE-016) could show large, obviously-wrong negative margins whenever a stock-in batch quantity was anything other than 1.
- **Root Cause**: `StockInScreen.kt`'s "Total Cost Price (₹)" field stores the **total** cost of the whole stock-in batch into `StockInRecord.costPrice` (e.g. ₹1000 for 50kg of potatoes), not a per-unit rate. `StockInDao.getLatestCostPricePerItem()` returned that raw `costPrice` unmodified, and `DailySummaryScreen.kt` then computed `cost * tx.quantity` treating it as a per-unit rate — multiplying the *total* batch cost by the *sold* quantity instead of dividing first.
- **Resolution**: Changed `getLatestCostPricePerItem()` in [StockInDao.kt](file:///C:/Users/harsh/Documents/Voice%20To%20Invoice/app/src/main/java/com/voicetoinvoice/app/data/local/dao/StockInDao.kt) to select `s.costPrice / s.quantity AS costPrice` (with a `WHERE s.quantity > 0` guard against divide-by-zero), so the map consumed by `DailySummaryScreen.kt` now holds a true per-unit cost.
- **Verification Date**: 2026-07-26 — verified `./gradlew compileDebugKotlin` succeeds (`BUILD SUCCESSFUL`). Not yet re-verified against a live device/manual margin calculation — recommend spot-checking one stock-in + sale pair on-device before relying on the margin figure.

#### [ISSUE-016] [2026-07-25] Sales Range Mode (Day/7-Day/30-Day) & Est. Margin Line
- **Feature Overview**: Added `RangeMode` (`DAY`, `WEEK`, `MONTH`) filter chips to `DailySummaryScreen.kt`, backed by `TransactionDao.getTransactionsBetween(startMs, endMs)`, and an "Est. Margin" figure computed from `StockInDao.getLatestCostPricePerItem()` (see ISSUE-017 for a bug found in the cost calc, now fixed).
- **Known Nuance**: `MainActivity.kt`'s `summaryRangeBounds` computes the WEEK/MONTH start as `todayMidnight - 7 days` / `- 30 days` and the end as `todayMidnight + 24h` — this is an inclusive 8-day / 31-day window (today plus N full prior days), one day wider than the "7 Days"/"30 Days" chip labels literally suggest. Not corrected; low-severity labeling nuance, not a data-correctness bug.
- **Files Touched**: `TransactionDao.kt`, `StockInDao.kt`, `DailySummaryScreen.kt`, `MainActivity.kt`.
- **Verification Date**: 2026-07-25 (per prior session record — `gradlew assembleDebug` reported `BUILD SUCCESSFUL`, `VoiceToInvoice_v63.apk`). Margin bug (ISSUE-017) found and fixed the next day during a follow-up review; this entry corrected to link it rather than a standalone unlogged claim.

#### [ISSUE-015] [2026-07-25] Supplier Ledger (Accounts Payable / "Who do I owe?")
- **Feature Overview**: New `suppliers` table (`SupplierRecord.kt`, `SupplierDao.kt`) tracking `balanceOwed` per supplier. `StockInScreen.kt` gained a saved-supplier dropdown; picking one (or logging stock-in against `supplierId`) increments that supplier's `balanceOwed` by the batch's total cost via `supplierDao().addToBalance(supplierId, cost)`. New `SupplierScreen.kt` lists suppliers with a "Settle" button that zeroes the balance.
- **Files Touched**: `SupplierRecord.kt`, `SupplierDao.kt`, `AppDatabase.kt` (`MIGRATION_9_10`), `StockInRecord.kt` (+`supplierId`), `StockInScreen.kt`, `SupplierScreen.kt`, `HomeScreen.kt` (+`Suppliers` nav button), `MainActivity.kt`, `CloudSyncManager.kt` (+`syncSupplierToCloud`), `SyncEngine.kt` (+`syncUnsyncedSuppliers`), `supabase/schema.sql` (+`public.suppliers`, +`stock_in.supplier_id`).
- **Verification Date**: 2026-07-25 (per prior session record — `BUILD SUCCESSFUL`, `VoiceToInvoice_v62.apk`). Re-compiled successfully as part of the 2026-07-26 review; supplier sync payload does not send `shop_id` to Supabase, consistent with the existing (pre-existing, not new) pattern in `syncStockInToCloud` — `suppliers.shop_id` is nullable in `schema.sql` so this doesn't fail, just means multi-shop separation isn't enforced yet for this table, matching the rest of the sync layer.

#### [ISSUE-014] [2026-07-25] Stock Awareness — Low Stock Alerts ("What do I have left?")
- **Feature Overview**: Added nullable `CatalogItem.lowStockThreshold`. `CatalogDao.getStockLevels()` computes on-hand quantity per item as `SUM(stock_in.quantity) - SUM(transactions.quantity)`. `CatalogManagementScreen.kt` shows "On hand: X <unit>" per item, highlights the card when on-hand ≤ threshold, and lets the shopkeeper tap a card to set/clear the threshold.
- **Files Touched**: `CatalogItem.kt`, `AppDatabase.kt` (`MIGRATION_8_9`), `CatalogDao.kt`, `CatalogManagementScreen.kt`, `MainActivity.kt`.
- **Verification Date**: 2026-07-25 (per prior session record — `BUILD SUCCESSFUL`, `VoiceToInvoice_v61.apk`).

#### [ISSUE-013] [2026-07-25] UPI Auto-Reconciliation & Udhaar WhatsApp Reminders
- **Feature Overview**:
  1. **Passive UPI Reconciliation**: Connected `UpiNotificationListenerService.kt` to Room DB. When a Paytm/PhonePe/GPay notification with ₹ amount lands, it queries `transactions` for sales in the last **2 minutes** (120,000 ms) with matching total.
     - **Match Window Mitigation**: Shrank match window from 15 minutes to 2 minutes to eliminate false-positive flips of unrelated cash sales recorded minutes earlier.
     - **Ambiguity Rule**: If **exactly 1** candidate matches within 2 minutes, updates `paymentMode = UPI`, `synced = 0`, and sweeps sync to cloud. If 0 or 2+ candidates match, leaves untouched (never guesses).
  2. **Udhaar WhatsApp Reminders**: Added a **"Remind"** button to `UdhaarScreen.kt` for pending credit rows. Launches native Android share sheet (`Intent.ACTION_SEND`), pre-filling Hindi/Hinglish reminder text (*"नमस्ते {Customer} जी, आपका ₹{Amount} का बकाया (Udhaar) बाकी है..."*). Requires zero API keys or stored phone numbers.
- **Files Touched**:
  - `TransactionDao.kt`: Added `getRecentTransactionsByAmount` and `markTransactionPaidViaUpi`.
  - `UpiNotificationListenerService.kt`: Added passive 2-minute lookup, ambiguity guard, and `SyncEngine` sweep.
  - `UdhaarScreen.kt`: Added **Remind** `OutlinedButton` next to **Mark Paid**.
  - `MainActivity.kt`: Added `sendWhatsAppReminder` via `Intent.ACTION_SEND`.
- **Verification Date**: 2026-07-25 (`BUILD SUCCESSFUL in 28s`, built into `VoiceToInvoice_v60.apk`).

#### [ISSUE-012] [2026-07-25] Rupee-Word-Gated Rate vs. Bulk Sale Price Handling & Catalog Corruption Fix
- **Symptom**: Spoken prices were previously ambiguous between per-unit rate updates and bulk/discount sales. Furthermore, manual/typed-text entry in `MainActivity.kt` (`onConfirmSale`) unconditionally overwrote `CatalogItem.price` on normal sales, corrupting standing catalog prices.
- **Root Cause**:
  1. `VoiceParser.kt` lacked explicit classification distinguishing rate updates from bulk sales.
  2. Numbers were treated as prices even without a rupee-word, causing false-positive price overrides.
  3. Manual entry handler in `MainActivity.kt` called `catalogDao().insertOrUpdate(targetItem)` on every sale where price was overridden.
- **Resolution**:
  1. Introduced `PriceIntent` enum (`NONE`, `RATE_UPDATE`, `BULK_SALE_TOTAL`, `AMBIGUOUS_UNTRUSTED`) in `VoiceParser.kt`.
  2. Implemented strict Rupee-Word Gate (`rupay`, `rupaye`, `rs`, `₹`, `rupees`, `रुपये`, etc.): numbers without an adjacent rupee-word are classified as `AMBIGUOUS_UNTRUSTED` and routed to the review queue (`isPendingPrice = true`).
  3. Classified `RATE_UPDATE` (price + rupee-word, no quantity) to update `CatalogItem.price` with zero transaction created.
  4. Classified `BULK_SALE_TOTAL` (price + rupee-word + quantity) to record a `TransactionRecord` with `priceAtSale = total / qty`, keeping standing `CatalogItem.price` untouched (except for brand-new unpriced items).
  5. Implemented >50% rate-jump sanity check (`isSanityFlagged = true`), routing large rate jumps to review instead of auto-applying.
  6. Fixed `MainActivity.kt` `onConfirmSale` and `BackgroundSttProcessor.kt` to respect `PriceIntent` and preserve catalog rates.
  7. Added 8 new unit tests in `VoiceParserTest.kt` verifying all `PriceIntent` branches and sanity checks.
- **Verification Date**: 2026-07-25 (All 27 `VoiceParserTest` unit tests PASSED, APK `VoiceToInvoice_v58.apk` built and verified).

#### [ISSUE-011] [2026-07-25] Segmenter Corrupted "बिंडी" (Bhindi) → "घी" (Ghee) Due to Missing Devanagari Vocab Entry, Auto-Confirmed Wrong Item to Ledger
- **Symptom**: Trace `6690cc7b-89cf-41f0-a4fc-04d5ae766fec` — shopkeeper said "दो किलो भिंडी" (2kg Bhindi/okra), Grok STT correctly heard it as `"दो किलोबिंडी"` (fused, unaspirated `ब` for `भ` — a common STT confusion), but `combinatorialFuzzySegmenter` rewrote it to `"दो किलो घी"` (Ghee) and auto-confirmed **Desi Ghee × 2KG = ₹1300** to the ledger instead of Bhindi. This is a real wrong-item, wrong-price transaction silently booked to the confirmed ledger.
- **Root Cause**:
  1. Catalog items are stored in `catalog_items.name` as English/Latin strings only (e.g. `"Bhindi"` — confirmed via direct query, no Hindi/alias column exists on the table). The segmenter's `catalogVocab` in `process-voice-job/index.ts` mixes these English catalog names with Devanagari transcript tokens and runs raw Unicode edit-distance across them — cross-script comparison (Devanagari vs Latin) never matches, so `"Bhindi"` was mathematically unreachable from any Hindi utterance.
  2. The segmenter's small **hardcoded** Devanagari fallback vocab (14 words) was the only real anchor available, and it didn't include `भिंडी` — so when the genuinely correct word wasn't a candidate, the segmenter still force-picked the closest available wrong word (`घी`) rather than leaving the token alone.
  3. The Android client already solves this correctly via a maintained `indicAliasMap` (~60 entries, includes `भिंडी` → `Bhindi`) in `FuzzyCatalogMatcher.kt` — the edge function had reinvented a much smaller, out-of-sync version of the same idea instead of mirroring it.
  4. Step 4's Grok AI prompt *did* receive the raw STT transcripts alongside the segmenter's (wrong) output, so it could in principle have self-corrected, but the prompt presented the segmenter's output as an already-cleaned "Preprocessed Transcript" with no warning it could be wrong, and its consonant-confusion hint list omitted aspirated/unaspirated pairs (ब↔भ) — exactly the error class that occurred.
- **Resolution**:
  1. Expanded `combinatorialFuzzySegmenter`'s hardcoded Devanagari vocab in `process-voice-job/index.ts` to mirror the Devanagari keys in `FuzzyCatalogMatcher.kt`'s `indicAliasMap` (added `भिंडी` plus ~35 other common produce/FMCG words), with a code comment tying the two files together so they don't drift again.
  2. Added a confidence floor to the segmenter: 2-way/3-way token splits are only accepted when average edit-distance per part is ≤1; otherwise the raw token is left untouched instead of being force-corrected to a weak/wrong match.
  3. Rewrote the Step 4 Grok AI prompt to explicitly tell the model the "Preprocessed Transcript" is a rigid rule-based guess that can be confidently wrong, and to weight the two raw STT transcripts + its own phonetic/catalog reasoning over it.
  4. Added aspirated/unaspirated consonant-confusion pairs (क↔ख, ग↔घ, च↔छ, ज↔झ, ट↔ठ, ड↔ढ, त↔थ, द↔ध, प↔फ, ब↔भ) to the prompt with the बिंडी/Bhindi case as a worked example.
  5. Upgraded the Step 4 chat model from the stale `grok-beta` alias to `grok-2-latest` (matching `term-interpret/index.ts`, which already used the newer model).
- **Verification Date**: 2026-07-25 (Edge function `process-voice-job` deployed live to Supabase project `lyowklxsbfznnqridtgr`, **Version 26 ACTIVE**).

#### [ISSUE-010] [2026-07-25] Dedicated Summary Bottom Navigation Tab & Documentation Consistency Audit
- **Symptom**:
  1. `DailySummaryScreen` was built and fully functional, but lacked a dedicated primary tab in the bottom navigation bar (`NavigationBar`).
  2. `CLAUDE.md` listed `AppDatabase (version 7)` instead of `version 8`, and `Docs/audit.md` contained drifted line numbers and a typo stating `confidence >= 0.60` instead of `0.80`.
- **Root Cause**:
  1. Bottom navigation bar in `MainActivity.kt` only contained Home, Catalog, Udhaar, and Settings tabs.
  2. Documentation drift after recent database schema migration and auto-confirm threshold alignment.
- **Resolution**:
  1. Added a dedicated **`Summary`** tab (`Icons.Default.DateRange`) directly to `NavigationBar` in `MainActivity.kt` (`Home` | `Catalog` | `Summary` | `Udhaar` | `Settings`).
  2. Updated `CLAUDE.md` to reflect `AppDatabase (version 8)`.
  3. Corrected line numbers, phonetic-confidence Kotlin attribution, and pipeline diagram auto-confirm threshold (`0.80`) in `Docs/audit.md`.
- **Verification Date**: 2026-07-25 (`BUILD SUCCESSFUL`, `VoiceToInvoice_v57.apk` deployed).

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
       └─► Final Write: stt_job_logs + transactions (if confidence >= 0.80 & price/total > 0) / unmatched_queue (if PENDING)
```

---

## 5. Master Source File Index

- **Master System Audit & Living Log**: [Docs/audit.md](file:///C:/Users/harsh/Documents/Voice%20To%20Invoice/Docs/audit.md)
- **Primary Edge Function**: [process-voice-job/index.ts](file:///C:/Users/harsh/Documents/Voice%20To%20Invoice/supabase/functions/process-voice-job/index.ts)
- **DB Setup Function**: [db-setup/index.ts](file:///C:/Users/harsh/Documents/Voice%20To%20Invoice/supabase/functions/db-setup/index.ts)
- **Android Background Worker**: [SttWorker.kt](file:///C:/Users/harsh/Documents/Voice%20To%20Invoice/app/src/main/java/com/voicetoinvoice/app/domain/processor/SttWorker.kt)
- **Latest Debug APK**: `C:\Users\harsh\OneDrive\Desktop\VoiceToInvoice_APKs\VoiceToInvoice-v2.0-debug.apk`
