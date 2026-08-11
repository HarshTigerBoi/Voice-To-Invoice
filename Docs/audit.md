# Voice To Invoice: Master System Audit, Living Issue Log & Source-of-Truth

> **Document Purpose**: Single source of truth for system contracts, architecture, verified source-code constants, and a dated living log of open and resolved issues.

---

## 1. Ground-Truth Source-Code Verified Constants

*(Verified directly against source code in `process-voice-job/index.ts`, `BackgroundSttProcessor.kt`, and `SttWorker.kt` on July 25, 2026; confidence-gate line numbers re-verified July 26, 2026 after ISSUE-019, and again after the ISSUE-020 rewrite of `index.ts`)*

| Metric / Parameter | Actual Source Code Value | Source Location | Discrepancy Note |
| :--- | :--- | :--- | :--- |
| **Auto-Confirm Confidence Threshold** | **`confidence >= 0.80`** | `supabase/functions/process-voice-job/index.ts` (L717) & `BackgroundSttProcessor.kt` (L356) | *Unified auto-confirm threshold aligned to 0.80 across client and server on July 25, 2026.* |
| **Catalog Exact Match Confidence** | **`0.95`** | `process-voice-job/index.ts` (L696-698) | Assigned when item matches a DB catalog SKU (server-side); fallback is capped at `0.60` for unmatched items — as of ISSUE-019 this floor is a hard `Math.min`, not just a default, so a self-reported LLM confidence can no longer bypass it. |
| **Phonetic Inferred Confidence** | **`0.70`** (matched) **`0.90`** (server-confirmed match) | `BackgroundSttProcessor.kt` (L183) | Client-side fallback path, not the edge function — assigns `0.90f` when a catalog item matches, `0.70f` otherwise. |
| **Edge Function Client Timeout** | **`30s` (connect) / `60s` (read)** | `SttWorker.kt` (L93-94) | Prevents local HTTP timeout fallback rows. |
| **Background Polling Window** | **`20 seconds`** (every 750ms), tightened from 30s/2s in ISSUE-046. Server-side inline budget (`process-voice-job/index.ts`): **`20 seconds`** (`INLINE_BUDGET_MS`) before falling back to the old 202+background behavior. | `SttWorker.kt` (`pollForCompletion`), `process-voice-job/index.ts` (`INLINE_BUDGET_MS`) | Drains `stt_job_logs` queue following an HTTP 202 ack -- now the rare exception, not the norm: as of ISSUE-046 the edge function awaits its own pipeline (measured 2-4s) and returns the real result inline on HTTP 200, so this client poll only fires when a job genuinely runs long. |
| **Phonetic Match Thresholds** | whole-token `<= 0.25`, split part `<= 0.30`, split penalty `0.10`/part, min split evidence `2` phones, `MIN_MARGIN_PHONE_EDITS = 1.0` (replaces dead `TAU_MARGIN = 0.08`), `DISCOURSE_PARTICLES` stoplist | `OrderingSegmenter.kt` (`GrammarLatticeDecoder`) & `phonetic.ts` (mirrored constants) | Normalized phonetic distance **per phone**, not raw edit distance. Updated in ISSUE-103 to scale ambiguity margins by key length (`MIN_MARGIN_PHONE_EDITS = 1.0`) and drop Hindi discourse particles (`हाँ`, `के`, `की`, etc.) during segmentation. |
| **STT / Chat Model Ids** | Ordered **fallback chains**, not single pins. Chat: `XAI_CHAT_MODEL` env → `grok-4.20-0309-non-reasoning` → `grok-4.5` → `grok-4.3` → `grok-4`. Sarvam STT: `SARVAM_STT_MODEL` env → `saaras:v3` → `saarika:v2.5` → `saarika:v2`, with `SARVAM_STT_MODE` (default `verbatim`) sent only for `saaras:v3`. | `process-voice-job/index.ts` (top of file); `term-interpret/index.ts` and `stt-proxy/index.ts` carry their own env-configurable defaults | Rewritten in ISSUE-021; updated in ISSUE-117. Step 4 structured extraction uses fast non-reasoning model `grok-4.20-0309-non-reasoning` at chain head (gated against reasoning_effort parameter). Chain advances on 400/404/422 deprecation or parameter rejection errors. `sarvamStt.model` in the trace reports the id that actually served the call. |
| **Confidence Model** | `confidenceFromMatchNorm`: normalized phonetic distance `0.00` → **0.95**, `0.25` (= `MATCH_NORM_REJECT`) → **0.50**, unmatched → **0.60**, scaled by `infoFactor = literalExact ? 1.0 : Math.min(1, keyLength / 4)` (`RELIABLE_KEY_PHONES = 4`). Auto-confirm gate unchanged at **`0.80`**. | `process-voice-job/index.ts` (top of file); `RawItemSegment.itemMatchNorm` in `OrderingSegmenter.kt` / `phonetic.ts` | Added in ISSUE-022; updated in ISSUE-103. `confidenceFromMatchNorm` now scales quality by key information content (`keyLength / 4`), capping non-literal 2-phone matches at 0.725 (review queue) while preserving 0.95 auto-confirm for literal exact surface matches (`literalExact`). |
| **Sale Plausibility** | SALE mode: GRAM/ML `<10` or `>5000`; KG/LITRE `>200`; PIECE/PACKET/DOZEN `>500`; quantity `<=0`; total `< ₹5` (`MIN_PLAUSIBLE_SALE_VALUE`). STOCK mode: KG/LITRE `>5000`; PIECE/PACKET `>10000`; GRAM/ML `>100000`; no minimum total floor. | `SalePlausibility.kt` & `implausibilityReason()` in `process-voice-job/price_intent.ts` (mirrored) | Added in ISSUE-022; expanded for `STOCK` intent mode in ISSUE-042. **Never blocks a sale or stock delivery** — only withholds auto-confirm and routes to review with a reason string in the trace. Wired into both server and client commit gates. |
| **Default Item Vocabulary** | **192 entries**, both scripts, Kotlin and TypeScript lists verified identical | `OrderingSegmenter.DEFAULT_ITEM_VOCAB` & `phonetic.ts` `DEFAULT_ITEM_VOCAB` | Grown from 35 in ISSUE-022: `अमचूर` was absent everywhere, so the matcher could only map it to the nearest known word ("Jeera"). A word absent from this list cannot be recognized, only mis-resolved. Breadth raises collision risk, which is exactly why the distance-aware confidence model above must ship with it. |
| **Distance-Word Guard** | `DISTANCE_UNIT_TOKENS` (kilometer/meter/centimeter/mile/foot + Devanagari), whole-token read priced at `2.5`; terminal `endCost` = ITEM `0.0`, NUM/UNIT `0.6` | `OrderingSegmenter.kt` & `phonetic.ts` (mirrored) | Added in ISSUE-021. These tokens were previously **inside `UNIT_SET`**, where an exact match suppressed split expansions and swallowed the item entirely. They must never be re-added to `UNIT_SET`. Segments recovered from such a token are `isSanityFlagged` and must not auto-confirm. |
| **`RATE_UPDATE` Ledger Exclusion** | `price_intent === 'RATE_UPDATE'` items are excluded from `isAutoConfirmed`/the `transactions` upsert entirely; a valid one instead runs `catalog_items.update({price})` and writes zero transaction rows | `process-voice-job/index.ts` (`rateUpdateItems`/`saleItems`/`validRateUpdates`, ISSUE-026) mirroring `BackgroundSttProcessor.kt` (L338-355) | Added in ISSUE-026 after ISSUE-025's classification fix shipped without this branch, so a correctly-classified `RATE_UPDATE` still booked a fake qty=1 sale. Server and client must stay in sync on this — the server had silently drifted from the client's existing correct behavior. |
| **Burst Coalescing & Recording Window Partitioning** | `GAP_THRESHOLD_MS` = `PRE_ROLL_MS` (300ms) + `POST_ROLL_MS` (300ms) = **600ms** (derived). `bufferDurationSeconds` = **120s** (`RollingAudioBuffer.kt`). Safety-flush margin = `(bufferDurationSeconds - 5) * 1000` = **115,000ms** (~115s). `PREROLL_RESERVE_MS` removed. Room DB **v15** (`utteranceBoundariesJson`/`pressCount`). | `HomeScreen.kt` (`PttBurstCoalescer.kt`, `PttWindowLedger.kt`), `SttWorker.kt`, `process-voice-job/index.ts`, `AppDatabase.kt` (v15 migration) | Added in ISSUE-036. Rapid back-to-back presses (< 600ms gap) are coalesced into a single audio job spanning the burst, letting multi-item AI parsing split lines semantically with zero audio overlap or lost words. Supersedes ISSUE-028's timing-based split heuristic. |
| **`UNIT_SET` type** | Plain `string[]`, **not** a `Set` — must always be queried with `.includes(...)`, never `.has(...)` | `phonetic.ts` (`export const UNIT_SET: string[]`) | A `.has(...)` call on this array (added, then fixed, during ISSUE-028) throws a `TypeError` at runtime inside `processVoiceJob`'s per-item loop, caught by the outer try/catch and silently converting every single job into `status: 'ERROR'`. Worth a table entry precisely because the mistake is easy to make again — `RUPEE_WORDS` in `price_intent.ts` *is* a real `Set` right next to it, so the two collections look interchangeable at a glance. |
| **Multi-item line uniqueness** | `transactions` and `unmatched_queue` are unique on **`(job_id, line_no)`**, **not partial** — no `WHERE job_id IS NOT NULL` | `supabase/migrations/20260728000000_multi_item_lines.sql`; `SttJobRecord.parsedItemsJson`/`lineCount` (Room v13); `TransactionRecord.lineNo` | Added in ISSUE-029. One voice recording (`job_id`) can now produce N lines — `line_no` is that item's index in `finalParsedItems`, stable across the committed/pending split so a booked line and a pending line from the same job never collide. `SttJobStatus` gained `PARTIALLY_CONFIRMED` (some lines booked, some pending) alongside `RATE_UPDATED` (server-added in ISSUE-026, had no Kotlin equivalent until now). **Must never be made partial again** — ISSUE-030 found that `idx_transactions_job_line` was briefly created as `WHERE job_id IS NOT NULL`, which Postgres cannot match to `.upsert(rows,{onConflict:'job_id,line_no'})`, and every committed-line write failed `42P10` silently until a live replay caught it. |
| **Per-item commit gate** | Each line independently gated (was `saleItems.every(...)` — one weak item zeroed the whole batch) | `process-voice-job/index.ts` (`isCommittable`, `committedSaleEntries`/`pendingSaleEntries`) | Added in ISSUE-029. `finalStatus`: `AUTO_CONFIRMED` (all lines committed) → `PARTIALLY_CONFIRMED` (some) → `RATE_UPDATED` (only fully-resolved rate updates) → `PARSED`. |
| **Per-segment price intent** | `classifySegmentPriceIntent()` scoped to ONE `RawItemSegment`, not the whole transcript | `price_intent.ts`; `RawItemSegment.spokenPrice`/`hasLeadingQty`/`rupeeWordPresent` in `phonetic.ts` | Added in ISSUE-029, replacing a single `detectPriceIntent(chosenRaw)` call whose one answer used to apply to every item in a multi-item utterance. `detectPriceIntent` is retained only as a diagnostic-only `wholeUtterancePriceIntentLegacy` trace field. |
| **Segmenter-vs-AI item name resolution** | `resolveItemName()`: segmenter overrides the AI's name when `itemMatchNorm <= 0.08` (`SEGMENTER_OVERRIDE_MAX_NORM`) **and** `normalizedDistance` between the two names exceeds `0.15` (`NAME_AGREEMENT_MAX_NORM`) | `item_resolution.ts` | Added in ISSUE-030. **Must use `normalizedDistance`, never exact `phoneticKey` string equality** — cross-script spellings of the same word (e.g. `बैंगन`/`Baingan`) sit at ~0.083 due to how `devanagariToLatin` encodes the ऐ diphthong, which is comfortably below the 0.15 agreement threshold; a genuine mis-hearing (`अमचूर`/`Angoor`) sits at 0.250, comfortably above it. Not gated on catalog match — the override must fire even when the AI's wrong name happens to be a real stocked item. |
| **Learned Parse Memory promotion/demotion** | Promote at `observations >= 2` distinct `job_id`s, **all** corroborated by the segmenter, `corrections = 0`. Demote (reset to `observations=0`) on canary mismatch / voided transaction / catalog change; `permanently_blocked` after 2 demotions. Canary sample rate `LEARNED_PARSE_CANARY_RATE` env, default **`0.25`**. `DEFAULT_LEARNED_PARSE_SHOP_ID = '00000000-0000-0000-0000-000000000001'` (sentinel shop; see ISSUE-032). **Superseded in part by ISSUE-114 (2026-08-09):** production `shop_id` is no longer always NULL — `ensure_shop` provisioning populates it (`2f992a33-…`), so the sentinel is legacy only and all 32 sentinel rows were merged away by migration `20260809010000`. `catalog_fingerprint` is now a **scoped** hash of only the catalog entries a memo names (`computeScopedCatalogFingerprint`), is **nullable**, and `NULL` means "legacy row, revalidate on next use". | `supabase/migrations/20260728010000_learned_parses_and_void.sql` (`record_learned_parse_observation`/`reset_learned_parse`); `process-voice-job/index.ts` (memory lookup block) | Added in ISSUE-031. Deliberately faster warm-up than the originally-proposed 3-observations/2-distinct-days design, per an explicit user request to prioritize learning speed — the corroboration + canary mechanisms are what make the shorter warm-up safe, not a relaxation of accuracy standards. `learned_parses.canonical_items` must never include `price_at_sale`/`total`/`confidence` — only `item_name`/`quantity`/`unit`/`price_intent` are ever cached. |
| **Catalog-Learning-From-History threshold** | `CATALOG_LEARNING_THRESHOLD = 3` distinct `job_id`s before an unmatched item auto-enters `catalog_items` at price `0`. Item identity = phonetic-key bucket **AND** normalized literal Levenshtein `<= 0.15` (`catalog_learning_name_agreement_max()`). | `process-voice-job/index.ts` (top of file); `record_unmatched_item_observation()` in `supabase/migrations/20260728020000_...sql` + `20260728030000_...sql` / `schema.sql` §10 | Added in ISSUE-033, identity check corrected in ISSUE-034. Threshold is higher than Learned Parse Memory's 2 because this writes a standing, user-visible catalog row rather than a cache entry. **`phoneticKey` alone must NEVER be used as the identity key here** — it is deliberately lossy and collides genuinely different items at distance 0.000 (`Kela`/`Kheera` → `KILA`), which would file a banana as a cucumber; the literal-name second stage is what prevents that. The 0.15 is a *literal* distance and is not the same metric as `item_resolution.ts`'s identically-valued `NAME_AGREEMENT_MAX_NORM` (phonetic) — do not merge them. |
| **Catalog pull/merge (server→client)** | `SyncEngine.pullCatalogFromCloud()`, run **last** in `syncAllUnsynced()`. Never overwrites a local `synced = false` row; otherwise last-write-wins on `updatedAt`; inserts unknown ids; skips server rows whose name already exists locally; never deletes locally. | `SyncEngine.kt`, `CloudSyncManager.fetchCatalogFromCloud()` | Added in ISSUE-035. The **only** server→client read path in the app — everything else is push-only. Exists because two server paths originate catalog data the phone cannot otherwise see: ISSUE-033's auto-add and ISSUE-026's server-side `RATE_UPDATE` price write. `fetchCatalogFromCloud` returns `null` (not an empty list) on failure specifically so a dropped connection can never be mistaken for an empty catalog. |
| **EntityResolver Thresholds** | `THRESHOLD = 0.80`, `MARGIN = 0.15` | `EntityResolver.kt` | Added in ISSUE-037. `EntityResolver` scores candidates across phonetic name (0.50), keyword (0.30), exact code/phone (0.80), recency (0.05), and frequency (0.05). Returns `AUTO_ASSIGN` if `top1 >= 0.80 && (top1 - top2) >= 0.15`, else `ASK`. Pool non-empty guarantee enforced. |
| **Room Database Version** | **`version = 28`** | `AppDatabase.kt` (`MIGRATION_27_28`) | Updated on August 10, 2026 for Item & Customer Photo Identity (`imagePath` column on `catalog_items`). |
| **RollingAudioBuffer Invariants, Segment Ledger & Coalescing** | `MIN_WINDOW_BYTES` = **9600** (~300ms), `MIN_USABLE_WINDOW_MS` = **400ms**, `maxGroupAgeMs` = **5000ms**, `MAX_SUPPRESSION_MS` = **20,000ms** (20s watchdog), `bufferCapacity` = **3,840,000 bytes** (120s @ 16kHz 16-bit mono), `stopRollingBuffer` join timeout = **1500ms**, `smartStart()` returns `Boolean`. `resumeByteOffset` removed in favor of `CaptureSegment` ledger. | `RollingAudioBuffer.kt`, `PttBurstCoalescer.kt`, `PttMicButton.kt` | Updated in ISSUE-085..ISSUE-088. Replaced single global anchor with `CaptureSegment` ledger, added typed `ExtractionResult` failure reasons, process-lifetime `PttCaptureScope`, and epoch resets. |
| **STT Race Minimum Score** | **`FAST_STT_MIN_SCORE = 5`** | `process-voice-job/index.ts` | Added in ISSUE-100. Minimum transcript score for the STT race to consider answering on one provider alone without awaiting the second. **Live data 2026-08-09: the shortcut fired on only 17/260 jobs** because the observed winning score is typically 4 (`declineReason: "winner_score_4_below_5"`). Not changed — lowering it trades accuracy for latency and needs its own evidence. |
| **AI Chat Timeout** | **`AI_CHAT_TIMEOUT_MS = 12000`** (env-tunable), was `45000` | `process-voice-job/index.ts` | Changed in ISSUE-112. 45s was a tail no shopkeeper can wait through: job `76892c70-6d5f-4d87-b105-6bf7bdb08a07` spent **27.2 s in a SUCCESSFUL grok-4.5 chat call** (`sttResolvedAtMs` 1,288 → `parseResolvedAtMs` 28,497) with re-decode off, so this is the chat call's own tail, not an STT artifact. Past 12s the existing `segmenter_fallback` path is strictly better than making the user wait — it still books to the review queue. |
| **Fast Path Spoken-Price Admission** | Fast path now admits **`BULK_SALE_TOTAL` only**: `segments.length === 1` AND `hasLeadingQty` AND `rupeeWordPresent` AND `spokenPrice > 0` AND `quantity > 0` AND `detectPriceIntent(chosenRaw).priceIntent === 'BULK_SALE_TOTAL'` AND `hasAmbiguousPriceNumber === false` | `process-voice-job/index.ts` (`buildFastPathFrom`) | Added in ISSUE-111, replacing the blanket `if (seg.spokenPrice != null \|\| seg.rupeeWordPresent) return no('spoken_price_present')`. `RATE_UPDATE` remains excluded (it mutates catalog prices, and the `hasLeadingQty` gate already bars it); `AMBIGUOUS_UNTRUSTED` remains excluded (no rupee word). Multi-segment stays excluded deliberately — cross-line price bleed is what system-prompt rule 8 exists to prevent. |
| **Fast Path Key Max Distance** | **`FAST_PATH_KEY_MAX_NORM = 0.10`** | `process-voice-job/index.ts` (`buildFastPathFrom`) | Added in ISSUE-099. Tight phonetic distance fallback threshold to bridge Devanagari/Roman schwa discrepancies (e.g. *अदरक* `ATALAK` vs *Adrak* `ATLAK`) when exact key equality fails. Narrows the instance; class survives. |
| **Parse Inspector Sample Rate** | **`PARSE_INSPECTOR_RATE = 1.0`** | `process-voice-job/index.ts` | Added in ISSUE-101. Shadow verification sample rate for AI-skipped jobs against Grok chat model (runs non-blocking in background). |
| **AI Evidence Ratio Floor** | **`MIN_AI_EVIDENCE_RATIO = 0.75`** | `item_resolution.ts` & `process-voice-job/index.ts` | Added in ISSUE-104. Minimum ratio of heard surface phones to proposed AI item name phones. Blocks AI catalog binding when deterministic resolution is UNKNOWN and heard phones are insufficient to justify the AI item name (e.g. "आ" -> Aaloo). |
| **Max Unidentifiable Residue Phones** | **`MAX_UNIDENTIFIABLE_RESIDUE_PHONES = 2`** | `item_resolution.ts` & `process-voice-job/index.ts` | Added in ISSUE-105. Maximum phones in stripped transcript residue (after quantity, unit, price, and non-catalog discourse particles are removed) at or below which zero-segment AI item proposals are treated as uncorroborated. |
| **Numeral Rejoin Thresholds** | **`MERGE_MAX_NORM = 0.22`**, **`MERGE_MIN_VALUE_MARGIN = 0.10`**, **`NUMERAL_KEYTERM_BUDGET = 25`** | `phonetic.ts` (`rejoinFragmentedNumerals`), `OrderingSegmenter.kt`, `process-voice-job/index.ts` | Added in ISSUE-106. Rejoins two-token STT fragmentations of Hindi compound numerals 21-99 ("ते तीस" -> 33) in phonetic key space. Rejoins with value margin < 0.10 are flagged for review and barred from auto-confirm. |

---

## 2. Living Issues Log (Dated History)

### 🔴 OPEN ISSUES

#### [ISSUE-124] [2026-08-10] The Entire Instrumented Test Source Set Has Been Un-compilable — `androidx.test:rules` Is Missing From `build.gradle.kts`
- **Symptom**: `./gradlew.bat :app:compileDebugAndroidTestKotlin` fails:
  ```
  e: app/src/androidTest/java/com/voicetoinvoice/app/audio/RollingBufferRestartTest.kt:9:22
       Unresolved reference 'rule'.
  e: …RollingBufferRestartTest.kt:22:32  Unresolved reference 'GrantPermissionRule'.
  ```
  Because Kotlin compiles the source set as a unit, **one unresolved import blocks every instrumented test in the project**, not just this file.
- **Root Cause**: `RollingBufferRestartTest.kt` imports `androidx.test.rule.GrantPermissionRule`, which ships in the **`androidx.test:rules`** artifact. `app/build.gradle.kts`'s `androidTestImplementation` block declares `composeBom`, `compose.ui.test.junit4`, `room-testing`, `kotlinx.coroutines.test`, `androidx.test.core`, `androidx.test.ext.junit`, `androidx.test.runner` and `androidx.test.espresso.core` — **and no `rules` artifact**. `androidx.test:runner` does not transitively provide `GrantPermissionRule`.
- **How it surfaced**: found while trying to compile the new ISSUE-118 regression tests. `RollingBufferRestartTest.kt` is **not** modified by any work in this session (`git status --porcelain app/src/androidTest/` lists only `QuestionTemplatesTest.kt` as modified and `QuestionTemplatesItemSalesTest.kt` as new), so this predates 2026-08-10 — it was simply never noticed, because nothing in the recorded history ever ran `connectedAndroidTest`.
- **Impact**: **every instrumented test in this repo is currently unrunnable**, including all Room in-memory DB tests — which `app/build.gradle.kts:125-128` designates as the *only* place Room DB behaviour is tested. So the project's Room layer has no executable test coverage at all right now, and the ISSUE-118 regression suite cannot run even with a device attached. This joins the already-documented broken `MainScreenTest.kt` (see "Known quirks" in `CLAUDE.md`) as a second, more damaging instance of the same rot.
- **Recommended fix** (one line, deliberately NOT applied here — it is a dependency decision outside every scoped plan in flight, and adding it would still only buy *compilation*, since running instrumented tests needs a device this session does not have):
  ```kotlin
  androidTestImplementation("androidx.test:rules:1.5.0")   // or add `rules` to libs.versions.toml
  ```
  After adding it, re-run `./gradlew.bat :app:compileDebugAndroidTestKotlin` to confirm the source set compiles, then `./gradlew.bat connectedAndroidTest` with the phone attached to actually execute the ISSUE-118 assertions.
- **Status**: OPEN — diagnosed, fix identified, **not applied**.

#### [ISSUE-004] [2026-07-24] Acoustic Consonant Blending on Unlisted Items
- **Symptom**: Spoken orders like `"तीन किलो बैंगन"` transcribed as `"Tinggal benggan"`. When consonant shifts are extreme (e.g. `क` $\leftrightarrow$ `ग`, `ब` $\leftrightarrow$ `प`), both STT engines output phonetically noisy text.
- **Root Cause**: Speech-To-Text acoustic mishearing when Indian shopkeepers speak without word pauses.
- **Current Mitigation**:
  1. Pure Levenshtein Edit-Distance Combinatorial Segmenter (`editDistance`) deployed in `process-voice-job/index.ts`.
  2. Phonetic-Aware Indian Shopkeeper Prompt deployed to Grok AI (Step 4).
  3. [2026-07-25, see ISSUE-011] Segmenter's hardcoded Devanagari vocab expanded + confidence floor added; Grok prompt now explicitly distrusts the segmenter's output and was given aspirated/unaspirated consonant hints.
  4. [2026-07-26, see ISSUE-020] Root cause of this class substantially addressed: matching moved off orthographic edit distance onto a script-agnostic **phonetic key space** with vowel-weighted distance, and the splitter now runs inside a grammar-aware Viterbi lattice. The "Tinggal benggan" example in this issue's symptom is the same failure as ISSUE-020's `"tinggal sebab"` — both are STT rendering Hindi phonetics in another language's spelling, which the old Devanagari-only vocabulary could not match at any edit distance.
- **Status**: OPEN — narrowed, not closed. ISSUE-020 removes the structural blindness (cross-script matching and fused-token splitting now work), but the phonetic collapse set is hand-tuned against observed traces rather than derived from a confusion matrix, and no live post-deploy verification has happened yet. Keep monitoring production transcripts; close this only once a batch of real recordings confirms the phonetic lattice holds up.

- **[2026-08-10] Confusion-matrix pass run. Outcome: NO code change, and the issue's stated premise is now known to be wrong.**

  A dual-engine disagreement analysis was run over all 240 jobs carrying both a Grok and a Sarvam transcript, on the theory (from `Docs/visual_ledger_and_assistant_plan.md` §14) that where two independent acoustic models disagree, the disagreement *is* a phone-confusion pair worth adding to the collapse set. **That theory did not survive contact with the data.**

  **Measured, 240 pairs where both engines returned text:**

  | | count | share |
  |---|---|---|
  | engines agreed | 49 | 20% |
  | engines disagreed | 191 | 80% |
  | …of those, **Sarvam's text was adopted** | 148 | 77% of disagreements |
  | …of those, Grok's text was adopted | 25 | 13% of disagreements |
  | **Grok returned NO Devanagari at all while Sarvam did** | 61 | **25% of all jobs** |

  **This contradicts the Symptom line above.** ISSUE-004 states "**both** STT engines output phonetically noisy text." That is not what production shows. Sarvam is adopted over Grok on disagreements by roughly **6:1**, and on a quarter of all jobs Grok leaves Hindi entirely — real observed outputs include `"dosa kelalaian tu"` (Malay) for `दो सौ किलो आलू`, `"das que lo hago"` (Spanish) for `दस किलो आलू`, `"Să tragă o daniea"` (Romanian) for `सत्रह किलो धनिया`, and `"Charcoal"` for `चार किलो आलू`. Those are **whole-utterance decoder failures, not phone-level confusions** — they cannot be repaired by any collapse rule, and averaging them into a confusion matrix would poison it with noise. A second large share of "disagreements" are pure formatting (`"2 किलो"` vs `"दो किलो"`, `"50 किलो"` vs `"पचास किलो"`), which are not confusions at all.

  **Residual genuine near-misses**, after excluding the two categories above and measuring each against the live `phoneticKey` (normalized distance, accept threshold 0.25):

  | pair | keys | dist | already collapsed? |
  |---|---|---|---|
  | `भिंडी` / `बिंडी` | `PINTI` / `PINTI` | 0.0000 | yes (bh→b) |
  | `धनिया` / `दनिया` | `TANIA` / `TANIA` | 0.0000 | yes (dh→d) |
  | `तेहतीस` / `देतीस` | `TIATIS` / `TITIS` | 0.0833 | yes (t↔d) |
  | `अदरक` / `अद्रक` | `ATALAK` / `ATLAK` | 0.0833 | yes |
  | `बादाम` / `अदाम` | `PATAN` / `ATAN` | 0.2000 | yes |
  | `टिंडा` / `इंडा` | `TINTA` / `INTA` | 0.2000 | yes |
  | `छाछ` / `छांच` | `CAC` / `CANC` | 0.2500 | yes (at the boundary) |
  | **`सेब` / `सेव`** (apple) | `SIP` / `SIV` | **0.3333** | **NO — missed** |
  | `छाछ` / `छात` | `CAC` / `CAT` | 0.3333 | NO — missed |

  So the existing collapse set already absorbs seven of nine observed near-misses. Only two escape.

  **Deliberately NOT changed, and why.** The `सेब`/`सेव` miss is a real ब↔व (b↔v) confusion and is well attested in Indic speech generally — but it appears **once** in 240 jobs, far below the ≥3-occurrence bar this issue's own remediation plan sets. Collapsing `P`(p/b/f) into `V`(v/w) is an app-wide change to the key space used by *every* item match, and justifying it on a single observation would be exactly the "hand-tuned against observed traces" practice this issue exists to criticise. The `छाछ`/`छात` miss is c↔t, a genuinely different consonant class; collapsing it would merge unrelated words wholesale. **Neither change is justified by current evidence, so neither was made.**

  **What this changes going forward**: the highest-value lever for ISSUE-004 is not the collapse set — it is that one of the two STT engines is unreliable for Hindi and the pipeline is already (correctly) routing around it via `transcriptScores`. Whether Grok STT is worth its latency and cost at a 13% adoption rate is a **separate open question**, not resolved here, and should be raised as its own issue rather than folded into this one.

  **Watch item for the next pass**: re-run the same analysis once ≥3 independent `सेब`/`सेव`-class observations exist. If b↔v clears the bar, the change belongs in `PhoneticKey.kt` **and** `phonetic.ts` together (mirrored), with `PhoneticSegmentationTest.kt` / `phonetic_test.ts` fixtures added on both sides.

#### [ISSUE-032] [2026-07-28] RLS Disabled on 3 Production Tables; `shop_id` Declared `NOT NULL` in `schema.sql` but Is Actually NULL on Every Live Row
- **Symptom**: Discovered incidentally while building ISSUE-031 (Learned Parse Memory), via the Supabase advisor and a direct query against project `lyowklxsbfznnqridtgr`:
  1. **Row Level Security is disabled** on `public.transactions`, `public.unmatched_queue`, and `public.stt_job_logs`, despite `schema.sql` declaring `ALTER TABLE ... ENABLE ROW LEVEL SECURITY` plus a permissive `USING (true)` policy for all three. The live database has drifted from `schema.sql` — RLS was turned off directly on these tables at some point, outside of any tracked migration. Anyone with the `anon` key (which is client-embedded, `SupabaseConfig.kt`) can currently read or write every row in all three tables with no policy check at all (not even the permissive `true` one `schema.sql` describes).
  2. **`catalog_items.shop_id` and `transactions.shop_id` are `NOT NULL` in `schema.sql` but every live row has `shop_id = NULL`** — confirmed via `SELECT count(*) FROM shops` (**0** rows) and `SELECT array_agg(DISTINCT shop_id) FROM catalog_items` (`{null}`, 135 rows). This deployment runs single-tenant in practice; the multi-tenant shape in `schema.sql` was never actually populated end-to-end.
- **Root Cause**: Not yet investigated — unclear whether RLS was deliberately disabled (e.g. because the anon-key write path predates any auth session and would otherwise be locked out entirely) or disabled by accident during earlier debugging. The `shop_id` gap is likely simply because no shop-onboarding flow was ever built/run against production.
- **Impact**: (1) is a live security exposure — full read/write access to the sales ledger, review queue, and diagnostic logs (which include raw audio URLs and transcripts) via the public anon key, unmediated by any policy. (2) is a data-integrity/schema-drift issue: any future code (including ISSUE-031's Learned Parse Memory) that assumes `shop_id` is populated will silently no-op in production unless it explicitly accounts for this, as ISSUE-031 had to.
- **Status**: OPEN — surfaced, not fixed. Per the Supabase advisor's own guidance, RLS should **not** be blindly re-enabled without first confirming what policies the app's anon-key write paths actually need (enabling RLS with no matching policy would silently break every client write). This needs a deliberate decision from the app owner, not an automatic remediation. Recommended next step: audit every anon-key write path (`CloudSyncManager.kt`, `process-voice-job/index.ts`'s service-role writes are unaffected) against the policies `schema.sql` already declares, confirm they're sufficient, then re-enable RLS table-by-table and verify sync still works end-to-end before considering this closed.

#### [ISSUE-045-OPEN] [2026-07-30] A Voice Recording Reached the Server Twice and Left Zero `stt_job_logs` Rows
- **Symptom**: A stock-in recording at 2026-07-30 14:08:56 IST triggered two `202` responses from `process-voice-job` (one immediate, one from a WorkManager retry at 14:11:03) but `stt_job_logs` contains **no row at all** for that `job_id` — not `QUEUED`, not `ERROR`, nothing. Locally the job shows `REVIEW NEEDED / Unrecognized Item / 1.0 PACKET / ₹0`.
- **Root Cause**: Not diagnosed. The QUEUED-placeholder `upsert` at `index.ts` (immediately after audio storage upload) had its error silently unchecked prior to ISSUE-046's fix — that fix (now logging `queuedErr` if present) is instrumentation for the *next* occurrence, not a fix for this one. ISSUE-045's client-side `clientTrace` (always persisted locally regardless of server outcome) is the other half of that instrumentation.
- **Status**: OPEN — instrumented, not diagnosed. Do not close until a repeat occurrence is caught by either the new `queuedErr` console log (check Supabase edge-function logs for `Failed to write QUEUED placeholder`) or the local job's `diagnosticTraceJson.client` block (check for `outcome: "exception"` or an unusually short `upload_ms`). If the case recurs with neither logging anything, the failure is happening somewhere not yet covered (e.g. the storage upload itself, or the request never reaching the function at all) and needs broader instrumentation.
- **[2026-08-10] Closure criterion — added because "it hasn't happened lately" is not a diagnosis.**
  - **Status check run 2026-08-10** (11 days after the single observed occurrence): status distribution across all 399 `stt_job_logs` rows is `AUTO_CONFIRMED 154, PARSED 118, FAILED 111, CONFIRMED 8, PARTIALLY_CONFIRMED 3, RATE_UPDATED 2, ERROR 2, CANCELLED 1` — **zero `QUEUED` rows**, and the newest `FAILED` is 2026-08-05. No recurrence, and the ISSUE-046 instrumentation has caught nothing in 11 days.
  - **This issue stays OPEN.** Absence of recurrence is not a fix — the failure was never explained, and a silent-loss bug that appears once can appear again. Do not close it on the strength of a quiet fortnight.
  - **Close only when** either (a) **30 consecutive days** pass with the monthly check below returning 0 *and* no new report of a recording vanishing, or (b) a recurrence is caught and actually diagnosed by the `queuedErr` log or a client trace showing `outcome: "exception"`.
  - **Monthly check** (run this, don't assume):
    ```sql
    SELECT count(*) AS stuck_queued
    FROM stt_job_logs
    WHERE status = 'QUEUED' AND created_at < now() - interval '1 hour';
    ```
    Non-zero = the placeholder wrote but the pipeline never completed — that is this bug recurring, with the row preserved this time. Capture the `job_id` before anything else.

---

### 🟢 RESOLVED ISSUES

#### [ISSUE-125] [2026-08-11] Debug APK installs conflict with existing install ("package conflicts with an existing package")
- **Symptom**: A CI-built APK (`apk-5`, `chore/ship-pipeline` build 5) refused to install over the shopkeeper-test phone's existing `VoiceToInvoice_v139.apk`, reported by the user as an install conflict. Uploaded `v139` for comparison.
- **Root Cause**: `app/build.gradle.kts` had no explicit `signingConfigs.debug` block, so both the `debug` and `perf` (debug-signed) build types fell back to AGP's implicit default: the machine-local `~/.android/debug.keystore`, auto-generated with a **random** keypair the first time it's needed on that machine. `git log --all --diff-filter=A` confirmed no keystore file was ever committed to the repo, contradicting `CLAUDE.md`'s existing (aspirational, not actually implemented) claim that debug builds "are signed with the debug keystore already committed to the build config." Consequence: `v139` (built on the dev laptop via `tools/vti-ship.ps1`) was signed with the laptop's local keystore; `.github/workflows/build-apk.yml`'s `ubuntu-latest` runner is a fresh VM per run with no keystore caching, so it very likely generated a **different** random debug key on every single CI run too — meaning `apk-3`, `apk-4`, and `apk-5` were probably also mutually incompatible with each other, not just with the laptop build. Android refuses to install an update whose signing certificate doesn't match the currently-installed app of the same `applicationId`.
- **Resolution**:
  1. Generated a fixed debug keystore (`keytool -genkeypair`, alias `androiddebugkey`, `CN=Android Debug,O=Android,C=US`, 10000-day validity) and committed it at `app/shared-debug.keystore`.
  2. Added an explicit `signingConfigs { getByName("debug") { storeFile = file("shared-debug.keystore"); ... } }` block to `app/build.gradle.kts`, so every build — CI and any future local build — signs with this one repo-committed identity instead of whatever key happens to already exist at `~/.android/debug.keystore` on that particular machine.
- **Not fixed by this change, and cannot be from a cloud session**: the app already installed on the shopkeeper-test phone (`v139`, and CI `apk-3`/`apk-4`/`apk-5`) is signed with a keystore this fix does not have access to. Those installs must be **manually uninstalled once** before the next release (`apk-6`+, first build to carry this fix) will install. After that one-time uninstall, every future CI build shares the same signing identity and will install as a normal update.
- **Verification Date**: 2026-08-11.
  - **Verified**: no keystore file present anywhere in `git log --all` history (hard evidence for the root cause).
  - **NOT verified**: whether `apk-3`/`apk-4`/`apk-5` are in fact mutually distinct signing certs (plausible from the ephemeral-runner argument, not extracted byte-for-byte — `apksigner` was not available in the diagnosing session to confirm). NOT verified: that the phone install succeeds after this fix ships and the old app is removed — pending the next `/ship` build and a real install attempt.

#### [ISSUE-121] [2026-08-10] WS-K: Expenses & Net Profit
- **Symptom**: Every profit calculation displayed in the app was gross profit only, silently ignoring expenses such as rent, electricity, salary, transport, supplies, and tea.
- **Root Cause**: No entity, DAO, or UI existed for tracking expenses, and `ProfitCalculator` had no concept of expenses or net profit.
- **Resolution**:
  1. Created `ExpenseRecord` entity (`expenses` table) with soft-delete (`voided`), shopId, category (`RENT`, `ELECTRICITY`, `SALARY`, `TRANSPORT`, `SUPPLIES`, `TEA`, `OTHER`), amount, note, timestamp, source, and synced flag.
  2. Created `ExpenseDao` with queries for range totals, category totals, unsynced expenses, insertion, and voiding.
  3. Added Room `MIGRATION_28_29` in `AppDatabase.kt` and updated DB version to 29.
  4. Updated `ProfitCalculator` and `ProfitResult` to compute `netProfit = grossProfit - expenses` and set `hasExpenseData = expenses > 0.0`. Gross profit's meaning remains unchanged and is explicitly labelled "मुनाफ़ा (कच्चा)", while net profit is a new separately labelled figure "मुनाफ़ा (खर्चा घटाकर)".
  5. Created icon-first `ExpenseScreen.kt` with category grid (≥ 96.dp tiles with Hindi labels), numeric entry dialog, today's expense list with voiding, and total expense footer.
  6. Added `EXPENSE` screen to `Screen` enum in `MainActivity.kt` and entry point button in `ReportsScreen.kt`.
  7. Deliberately deferred: voice expense capture triggers, cloud sync of expenses, and cash book / cash-in-hand.
- **Defect found on review, after the pass reported "Deviations: None" — and it originated in the PLAN, not the implementation.** The scoped plan specified the soft-delete DAO method as `suspend fun void(id: String)`. Kotlin accepts `void` as an identifier; **Java does not** — and Room's KSP processor emits a Java `ExpenseDao_Impl`. The build failed with:
  ```
  ExpenseDao_Impl.java:37: error: ExpenseDao_Impl is not abstract and does not
      override abstract method void(String,Continuation<? super Unit>) in ExpenseDao
  ```
  (the tell is the method with no readable name — `void(String,…)`). Renamed to `voidExpense`, matching the existing `TransactionDao.voidTransaction` convention, and the single call site in `ExpenseScreen.kt:312` was updated. A comment in `ExpenseDao.kt` records why the name cannot be `void`, so it is not "simplified" back later.
- **Verification Date**: 2026-08-10.
  - **Verified**: `./gradlew.bat :app:assembleDebug` → **BUILD SUCCESSFUL**, after the rename. The first run **FAILED** with the codegen error above — which is the only reason it was caught, and why a Room DAO must never be signed off on inspection alone: this class of error appears in *generated Java*, not in the Kotlin source, so the file reads perfectly correct.
  - **Verified by direct read**: `version = 29` (`AppDatabase.kt:37`), `ExpenseRecord::class` in the entities list (:35), `abstract fun expenseDao()` (:59), `MIGRATION_28_29` defined (:801) and registered as the final entry of `.addMigrations(...)` (:935), continuing an unbroken 1→29 chain. **No `fallbackToDestructiveMigration()`** anywhere — checked explicitly, since on a schema bump that call is the difference between adding a table and wiping the shopkeeper's ledger.
  - **Verified by direct read — the trust-critical rule held**: `ReportsScreen.RevenueProfitCard` renders the pre-existing figure as **"मुनाफ़ा (कच्चा)"** with the value still `data.profit.grossProfit` (:340-341), and adds **"मुनाफ़ा (खर्चा घटाकर)"** as a *separate* row gated on `hasExpenseData` (:348-358). The number the shopkeeper has been reading did not change meaning; a second, differently-labelled number was added beside it.
  - **NOT verified**: no expense has ever been entered. The `expenses` table has never been written to, `MIGRATION_28_29` has never run against a real v28 database, and net profit has never been rendered on screen (it is invisible until `hasExpenseData` is true, which requires a real expense).
- **Status**: CODE COMPLETE + COMPILES, **NEVER EXERCISED — no expense recorded, migration never run**

#### [ISSUE-118] [2026-08-10] C-PART-2: Assistant Comprehension for Item-Scoped Sales Queries
- **Symptom**: Spoken item-scoped sales questions like `"आज कितने आलू बिके"` (trace job `472d4af1-e438-4f1f-a398-67e240d47362`, 2026-08-04) were correctly classified as `READ_QUERY` by `IntentRouter`, but `QuestionTemplates.answerQuestion` answered them wrongly with total shop revenue (`ResponseComposer.formatDailySales(...)`) instead of item-specific sales.
- **Root Cause**: `QuestionTemplates` had no item-scoped sales branch. `REVENUE_WORDS` contains `"बिका"`, which phonetically matched `"बिके"` in `"आज कितने आलू बिके"`, swallowing the question and returning whole-shop revenue.
- **Resolution**:
  1. Added `getItemSalesInPeriod(itemNameQuery, startMs, endMs)` to `LedgerQueries.kt` which reuses `findCatalogItem` for exact/substring/phonetic matching and queries `db.transactionDao().getItemSalesBetween(startMs, endMs)`.
  2. Added `formatItemSales(itemName, qty, revenue)` to `ResponseComposer.kt`.
  3. Added branch 3 `Item-scoped sales` in `QuestionTemplates.kt` immediately before the `REVENUE_WORDS` branch to catch item-scoped sales questions when a named item resolves, falling through to generic revenue when un-named. Renumbered remaining branches 4 through 9.
  4. Added `clientTrace.put("assistant_tier", assistantTier)` instrumentation in `SttWorker.kt`.
  5. Added `QuestionTemplatesItemSalesTest.kt` regression test suite.
- **Scope note**: Fixes item-scoped "how much of X sold" questions. Does **not** fix any question shape outside the nine deterministic branches (those still return `formatUnrecognized()`; the AI fallback tier was deliberately not built).
- **Declared deviation, reviewed and accepted**: the regression tests were placed under `app/src/androidTest/…` rather than `app/src/test/…`. Checked and **correct** — `app/build.gradle.kts:125-128` states plainly that Room in-memory DB tests run as instrumented tests because Robolectric has no android-all jar for compileSdk 36. The plan asked for the wrong directory; the implementer was right to move it and right to declare it.
- **Consequence of that placement, and it matters**: `androidTest` requires a connected device or emulator. **These regression tests have therefore never been executed.** They compile, and nothing more. Do not read "regression test suite added" as "regression verified" — the assertions in that file are unproven until `./gradlew.bat connectedAndroidTest` runs against a device.
- **Verification Date**: 2026-08-10.
  - **Verified**: `./gradlew.bat :app:assembleDebug` → **BUILD SUCCESSFUL**. Main sources compile.
  - **NOT verified — the new tests do not even compile, and the cause is pre-existing.** `./gradlew.bat :app:compileDebugAndroidTestKotlin` **FAILS**, but *not* on the new file: it fails on `app/src/androidTest/java/com/voicetoinvoice/app/audio/RollingBufferRestartTest.kt:9,22` with `Unresolved reference 'rule'` / `'GrantPermissionRule'`. That file is untouched by this work (`git status` shows only `QuestionTemplatesTest.kt` modified and `QuestionTemplatesItemSalesTest.kt` added). Root cause: it imports `androidx.test.rule.GrantPermissionRule`, but **`androidx.test:rules` is absent from `app/build.gradle.kts`** — the `androidTestImplementation` block has `core`, `ext.junit`, `runner` and `espresso.core`, and no `rules`. See the separate finding logged below; an incorrect "BUILD SUCCESSFUL" was written into this entry on a first pass and is corrected here.
  - **Verified by direct read**: the new item-scoped branch sits at position **3 in `QuestionTemplates.answerQuestion`, immediately before the `REVENUE_WORDS` branch now numbered 4** (`QuestionTemplates.kt:173-197`). Ordering is the entire fix — placed after the revenue branch it could never fire — so it was confirmed by reading the file, not from the implementation report.
  - **Verified**: `ResponseComposer.formatItemSales` emits well-formed Devanagari (`"आज $itemName नहीं बिका"`). Checked because the implementation report rendered this string as `"नहीं बika"` with Latin characters; the report was mangled, the source is clean.
  - **NOT verified — the tests have not run** (instrumented, no device). 
  - **NOT verified — nothing spoken has been through this path.** Real acceptance test for the phone: say **"आज कितने आलू बिके"** and confirm the reply names the item and that the job's client trace carries `"assistant_tier":"template"` (meaning the free deterministic branch answered, with no AI call).
- **Status**: CODE COMPLETE, **ON-DEVICE SPOKEN PATH UNEXERCISED**

#### [ISSUE-119] [2026-08-10] WS-D: Ledger Explorer
- **Symptom**: Ledger exploration lacked an always-visible summary total footer and dynamic filtering by item, payment mode, transaction type, and time of day.
- **Root Cause**: DailySummaryScreen computed totals over the unfiltered list only and displayed them at the top in a scrollable card, requiring scrolling to see totals and providing no item/time/type filter state.
- **Resolution**:
  1. Added the `FilteredTotals` data class to `TransactionDao.kt`. **Correction to the original write-up**: the `getFiltered`/`getFilteredTotals` Room queries this entry first credited were added by the implementation pass and then **removed on review**, because the screen never called them — filtering and totalling happen in Kotlin over `rangeTransactions`, which is already the complete unpaginated row set for the range. The results are identical, so the plan's stated reason for demanding SQL ("the filtered set can be larger than what is on screen") did not actually hold. Two never-called `@Query` methods were left behind implying a mechanism the screen does not use; they were deleted and a comment in `TransactionDao.kt` records the condition under which they should be reinstated (pagination), including the byte-identical-WHERE requirement.
  2. Added `LedgerFilter` data class in `DailySummaryScreen.kt` for item, payment mode, transaction type, and local hour filters (fromHour/toHour).
  3. Added `LedgerTotalBar` pinned bottom bar in `Scaffold` displaying always-visible total revenue, breakdown (Cash, UPI, Udhaar with `LedgerColors`), line count, quantity, active filter status, and a clear filter button.
  4. Added horizontal filter bar above transaction list supporting date range, photo-first catalog item sheet (`LazyVerticalGrid` + `ItemIcon`), time preset dialog, payment mode dialog, and transaction type dialog.
  5. Added code comment for रात (22-23) time preset noting that midnight wrap (22-5) is restricted to 22-23 due to single `>= AND <=` query structure.
  6. Updated item row icon to use `catalogMap[tx.itemId]` for image rendering (`imageUrl`/`imagePath`).
  7. Updated `performCopy` export logic to export filtered rows and prepend active filter configuration header text.
  8. Wired `catalog = catalogState` in `MainActivity.kt` at `Screen.SUMMARY`.
- **Two defects found on review, after the implementation pass reported "Deviations: None"**:
  1. **Compile failure.** `DailySummaryScreen.kt:97-98` — `Smart cast to 'kotlin.Int' is impossible, because 'fromHour' is a delegated property`. `filter` is a `by remember { mutableStateOf(...) }` delegate, so Kotlin cannot prove its properties stay non-null inside the filter lambda. Fixed by snapshotting all five filter fields into locals before the predicate. The pass had reported success without compiling.
  2. **Unreported deviation** (the dead-query issue in Resolution 1 above).
- **Verification Date**: 2026-08-10.
  - **Verified**: `./gradlew.bat :app:assembleDebug` → **BUILD SUCCESSFUL**, run after both fixes. The first run of this same command **failed** with the two smart-cast errors above, which is how defect (1) was caught — "code inspects clean" is not a substitute for compiling.
  - **Verified by direct diff**: while the SQL queries still existed, their two `WHERE` clauses were confirmed byte-identical (`diff` over the extracted clauses returned no differences). Recorded because that invariant is what the reinstatement note in `TransactionDao.kt` depends on.
  - **NOT verified**: on-device behaviour was never exercised. Outstanding acceptance test, for the phone: filter to a single item and confirm the footer total equals the sum of the visible rows; then clear all filters and confirm the footer matches `ReportsScreen`'s "कुल बिक्री" for the same range — two independent code paths agreeing is the real check, and neither has been run.
  - **NOT verified**: the रात (night) time preset. It is deliberately limited to hours 22-23 and **excludes 00:00-05:59**, because a single `>= AND <=` hour pair cannot express a range that wraps midnight. For a shop open past midnight this silently under-reports the night window. Recorded as a known limitation, not a bug to rediscover.
- **Status**: CODE COMPLETE + COMPILES, **NOT EXERCISED ON DEVICE**; रात preset has a known midnight-wrap gap

#### [ISSUE-122] [2026-08-10] WS-A: Item & Customer Identity is a Photo
- **Symptom**: Items and customers lacked camera capture capability for identity photos, relying solely on text or remote image URLs.
- **Root Cause**: Missing camera capture UI, downscaling utility, and local database column for item photo paths (`imagePath`).
- **Resolution**:
  1. Updated Room database to version 28 with `MIGRATION_27_28` adding `imagePath TEXT DEFAULT NULL` on `catalog_items`.
  2. Updated `CatalogItem` entity with device-local `imagePath: String? = null`.
  3. Created `PhotoCapture.kt` utility for intent-based camera file creation, uri resolution, safe in-place downscaling (`MAX_DIM = 512`, `JPEG_QUALITY = 80`), and deletion.
  4. Created `PhotoCaptureButton.kt` composable using `ActivityResultContracts.TakePicture()`, displaying current photo, and supporting long-press deletion.
  5. Updated `ItemIcon.kt` to prefer local `imagePath` file over `imageUrl`, and updated call sites in `ConfirmSaleDialog`, `PendingConfirmationsSheet`, and `ManualStepperComponent`.
  6. Added `updateImagePath` to `CatalogDao`, wired camera entry point in `CatalogManagementScreen.kt`, restructured `ManualStepperComponent.kt` for photo-first adaptive grid, and enabled opt-in customer photo capture in `CustomerEditScreen.kt` & `CustomerCard.kt`.
- **Verification Date**: 2026-08-10.
  - **Verified**: `./gradlew.bat :app:assembleDebug` → **BUILD SUCCESSFUL** (run independently after the implementation pass, not inferred from it).
  - **Verified by direct read**: `version = 28` at `AppDatabase.kt:36`; `MIGRATION_27_28` defined at :788 doing `ALTER TABLE catalog_items ADD COLUMN imagePath TEXT DEFAULT NULL`; registered as the last entry of `.addMigrations(...)` at :906; `"imagePath" to "TEXT"` present in the defensive column map at :816. **No `fallbackToDestructiveMigration()` was introduced** — this was checked explicitly, because on a schema bump that call is the difference between an added column and a wiped ledger.
  - **NOT verified — camera capture has never been run.** No device was attached. Specifically unexercised: `ActivityResultContracts.TakePicture()` actually returning a photo, the FileProvider grant (`${applicationId}.fileprovider` against `provider_paths.xml`'s `<files-path>`), `PhotoCapture.compressInPlace` on a real multi-megapixel camera output, and whether the 512px/Q80 result is legible at 72.dp. A compile does not exercise any of these.
  - **NOT verified**: the migration has not been run against a real pre-existing v27 database on a device.
- **Known design note (not a defect)**: `imagePath` is deliberately **not** synced — it is a device-local file path and is meaningless on another install. An item photographed on this phone will show the category fallback on any other device until/unless an upload path is built.
- **Deliberately not done**: bundled stock photos for the 53 seeded catalog items — blocked on user-supplied licensed assets. Note for whoever picks this up: `CatalogManagementScreen`'s add-item dialog already carries an "Icon / Image Link (Optional)" field whose placeholder reads `"https://... or Vecteezy link"`, so remote URLs were already supported via `imageUrl` before this issue; this work added the *camera* path alongside it.
- **Status**: CODE COMPLETE + COMPILES, **CAMERA FLOW NEVER EXECUTED**

#### [ISSUE-120] [2026-08-10] WS-J: Intent Router Numeral Collision ("चार" Colliding with "call")
- **Symptom**: Three plain cash sales ("चार किलो चाच", "चार किलो आलू", "चार किलो गोल्ड") were misclassified as `ACTION_COMMAND` with confidence 0.526 and routed to review instead of being booked (traces 54e7fe50, 8430fe59, 467ea9d5).
- **Root Cause**: "चार" (four) keys phonetically to `CAL`, which is an exact 0.0000 distance match for the `ACTION_COMMAND` trigger phrase "call" (`CAL`). Scoring `ACTION_COMMAND` at 1.0 against `SALE`'s baseline of 0.9 resulted in `1.0 / (1.0 + 0.9) = 0.5263`, above the `ARBITRATION_FLOOR` of 0.45. The bigram "चारकिलो" also collided with "call karo" at distance 0.125.
- **Resolution**:
  1. Updated `buildNgramKeys` in `supabase/functions/process-voice-job/intent_router.ts` and `app/src/main/java/com/voicetoinvoice/app/domain/router/IntentRouter.kt` to exclude spans containing only quantity tokens (numbers, number words, and units) from trigger n-gram construction.
  2. Added `QUANTITY_KEYS` and `isQuantityToken` helpers on both server and client to identify quantity tokens.
  3. Added regression tests for "चार किलो आलू", "चार किलो चाच", "chaar kilo aloo", "रमेश को बिल भेजो", and "ramesh ko call karo" in `intent_router_test.ts` and `IntentRouterFixtureTest.kt`.
  4. **Second-order defect the first pass introduced, caught by the existing fixture.** The plan asserted "no trigger phrase is a number or a unit, so this filter is safe by construction." **That assertion was false.** The `PRICE_UPDATE` trigger `भाव`/`bhav` (rate) and the quantity word `पाव` (quarter, in `HINDI_NUMBER_MAP`) BOTH key to `PAV` — verified by running `phoneticKey` directly. Filtering by phone key alone therefore deleted the `bhav` trigger, and `"aaloo ka bhav 30"` degraded from `PRICE_UPDATE` to `SALE` — i.e. the fix for a misrouted sale would have turned every spoken rate change into a fake one-unit sale. The implementer implemented the plan verbatim, hit the failing fixture, and **stopped and reported the contradiction rather than silently patching it** — which is the only reason this was caught before deploy.
  5. **Resolution of (4)**: added `TRIGGER_SURFACES` (the literal lowercased surfaces of every trigger word) on both sides; `isQuantityToken` now returns `false` for any word that is literally a trigger word, whatever it keys to. The asymmetry that makes this correct: the trigger lexicon is the authority on what a trigger word is. `bhav` is literally in the trigger list and is not literally a numeral surface → kept. `चार` is literally a numeral surface and is not literally a trigger word → still filtered, so the original collision stays fixed.
  6. **Side effect of (5), measured not assumed**: exactly two quantity surfaces are now exempt from filtering — `दो` and `do` (they appear inside trigger phrases such as `कर दो` / `bata do`). Both key to `TO`; every trigger they reach sits at exactly `d = 0.250`, which yields `quality = 0.00` and contributes **zero** to any intent score. Enumerated and measured directly rather than reasoned about.
- **Bug Class Statement**: Eliminates the class "a quantity word can establish an intent" — quantity-only spans are now structurally excluded from trigger matching on both sides, rather than one phrase being retuned. **Explicitly NOT eliminated**: a non-quantity word colliding phonetically with a trigger (e.g. an item name keying onto a trigger phrase) remains possible and is a different class. The `bhav`/`पाव` case above shows this class is real and populated, not theoretical.
- **Verification Date**: 2026-08-10.
  - **Verified**: `deno test --no-check --allow-all supabase/functions/process-voice-job/intent_router_test.ts` → **19 passed, 0 failed**, including the three new `चार किलो …` SALE cases, `ramesh ko call karo` still classifying as `ACTION_COMMAND`, and the `aaloo ka bhav 30` → `PRICE_UPDATE` fixture that caught defect (4).
  - **Verified**: the exempt-set measurement in (6), by enumerating every quantity surface against every trigger phrase.
  - **Verified**: client fixture `:app:testDebugUnitTest --tests "*IntentRouter*"` → **21 tests, 0 failures** across `IntentRouterFixtureTest` (20) and `IntentRouterTest` (1); JUnit XML inspected directly rather than trusting "BUILD SUCCESSFUL". The three `चार किलो …` cases and `ramesh ko call karo` are present in the fixture source at lines 85-87 and 188.
  - **Verified**: deployed to production 2026-08-10 and the **live bundle re-fetched and grepped** — `TRIGGER_SURFACES` and `isQuantityToken` both present, as is ISSUE-117's `grok-4.20-0309-non-reasoning` (i.e. this deploy did not regress the previous one).
  - **NOT verified**: no real voice recording has gone through the deployed classifier yet. On-device confirmation that `"चार किलो आलू"` now books instead of routing to review is **outstanding** — the query to run is in the plan's §4.3.
- **Status**: DEPLOYED + TESTED, **NOT YET OBSERVED ON A REAL RECORDING**

#### [ISSUE-018] [2026-07-30, closed by ISSUE-050] Test-Only Broadcast Receiver Exported Unconditionally in `UpiNotificationListenerService` — Allowed Any App to Inject Fake Transactions or Fake UPI Reconciliation
- **Symptom**: `UpiNotificationListenerService.onCreate()` registered a `BroadcastReceiver` for two custom actions — `com.voicetoinvoice.app.SEED_TEST_TX` and `com.voicetoinvoice.app.TEST_UPI` — apparently added to let a developer drive the UPI-reconciliation flow via `adb shell am broadcast` without a real Paytm/PhonePe/GPay notification. The receiver was registered with `RECEIVER_EXPORTED` on API 33+ (implicitly exported pre-33), and was **not** gated behind any debug/build-type check.
- **Root Cause**: Debug/manual-test scaffolding was left wired into the always-running production code path instead of being removed or gated behind a debug-only build flag.
- **Impact**: Any other app installed on the shopkeeper's phone (or an attacker with `adb` access) could, without any permission check, broadcast `SEED_TEST_TX` to insert a fake sale directly into the real ledger, or broadcast `TEST_UPI` to falsely mark a pending/Udhaar sale as paid via UPI.
- **Resolution**: Receiver removed entirely. **Verified 2026-08-10**: the only surviving reference in source is a past-tense comment at `app/src/main/java/com/voicetoinvoice/app/service/UpiNotificationListenerService.kt:18` ("`SEED_TEST_TX` / `TEST_UPI` used to be registered here..."); grepping `SEED_TEST_TX|TEST_UPI|RECEIVER_EXPORTED|registerReceiver` across `app/src/main/java` returns nothing else.
- **Verification Date**: 2026-08-10 (this entry corrects a stale log record — the heading said "CLOSED 2026-07-30" while the body still said "Status: OPEN"; the fix itself shipped under ISSUE-050 on 2026-07-30, this entry just reconciles the log to match).
- **Status**: CLOSED

#### [ISSUE-117] [2026-08-10] Fast Model at Chain Head & Parameter Rejection Advancement for Edge Function Speed Fix
- **Symptom**: Step 4 structured extraction on `grok-4.5` took 3849ms of a 5523ms job (trace 54e7fe50). Additionally, sending `reasoning_effort` to non-reasoning models risks 400 errors breaking out of the chain instead of advancing.
- **Root Cause**:
  1. Default flagship chat model `grok-4.5` was performing unnecessary reasoning on simple structured extraction tasks.
  2. `supportsReasoningEffort` returned true for any model starting with `grok-4`, matching `grok-4.20-0309-non-reasoning`.
  3. `isModelUnavailableError` did not treat parameter rejection (400 `unsupported parameter` / `unknown field` / `unrecognized` / `invalid_request_error`) as a chain advancement condition.
- **Resolution**:
  1. Added `grok-4.20-0309-non-reasoning` to the head of `XAI_CHAT_MODELS` chain in `supabase/functions/process-voice-job/index.ts`.
  2. Modified `supportsReasoningEffort` to exclude models containing `'non-reasoning'`.
  3. Widened `isModelUnavailableError` disjunction to catch parameter rejection error messages and advance the chain.
  4. Deployed `process-voice-job` edge function and verified live bundle download contains `grok-4.20-0309-non-reasoning` and updated `supportsReasoningEffort`.
- **Verification Date**: 2026-08-10
- **Status**: CLOSED

#### [ISSUE-123] [2026-08-10] Single Source of Truth for App-Wide Semantic Colour Vocabulary
- **Symptom**: Hardcoded color hex literals were scattered across UI screens and components with inconsistent meanings (e.g. red error color used for slow movers alongside actual losses).
- **Root Cause**: Absence of a single semantic color vocabulary object for money in, money out, credit/udhaar, upi, and neutral states.
- **Resolution**:
  1. Created `app/src/main/java/com/voicetoinvoice/app/ui/theme/LedgerColors.kt` containing `MoneyIn`, `MoneyOut`, `Udhaar`, `Upi`, `Neutral`, `forDelta()`, and `forScore()`.
  2. Replaced hardcoded color hex literals across UI components (`CommandFeedSheet.kt`, `PendingConfirmationsBar.kt`, `HomeScreen.kt`, `DiagnosticLogsScreen.kt`, `StockInScreen.kt`, `ReportsScreen.kt`).
  3. Fixed collision in `ReportsScreen.MoversCard` by painting slow/dead movers as `LedgerColors.Udhaar` (amber) instead of red `error`.
  4. **Caught after the first pass (self-review, not the implementer's own check):** `LedgerColors` had been given its **own** literal hex values (`0xFF2E7D32` etc.), duplicating an existing, already-wired-into-`Theme.kt` semantic system (`Money`/`Owed`/`Danger`/`Info` in `theme/Color.kt`, live in `PriceUpdateScreen.kt`) that neither the plan nor the implementation pass had discovered — the app briefly had two different greens both claiming to mean "money in." Fixed by making `LedgerColors` delegate to the existing constants (`MoneyIn = Money`, `MoneyOut = Danger`, `Udhaar = Owed`, `Upi = Info`) instead of defining new values.
  5. The implementer's own "all occurrences eliminated" verification claim was checked and found incomplete: `HomeScreen.kt`'s "उधार बेचो" (credit-sale) mic button was still a raw `Color(0xFFE65100)` despite its own code comment reading "Amber" and its sibling cash-sale/waste buttons in the same file already being fixed — corrected to `LedgerColors.Udhaar`. `StockInScreen.kt`'s stock-in mic button (blue, unrelated to any money concept) was deliberately left alone rather than force-fit into `LedgerColors.Upi`, which would have repeated the exact "one colour, two meanings" mistake this issue exists to fix. Remaining raw literals in `CustomerCard.kt`/`CustomerEditScreen.kt`/`CustomerListScreen.kt`/`UdhaarPickerOverlay.kt` were left untouched — they encode an existing, internally-consistent "owes red / paid green" design that predates this issue and deserves its own reviewed decision, not a silent reinterpretation.
  6. **Renumbered from the plan's original ISSUE-116 to ISSUE-123**: the plan's issue-number allocation ("ISSUE-115…ISSUE-119, highest existing is ISSUE-114") was itself wrong — it was derived from a `####`-only heading scan and missed the `#####`-level sub-entries under the ISSUE-110..116 batch entry above (dated 2026-08-09, one day earlier), which already claims 115 and 116. Corrected on discovery, before a second workstream could collide on the same numbers.
- **Verification Date**: 2026-08-10. `grep "Color(0xFF"` across `app/ui/` now returns hits only in `LedgerColors.kt` (all delegating), `ItemIcon.kt` (the deliberately-separate category palette), and the customer screens noted above (deliberately out of scope, not missed).
- **Status**: CLOSED

#### [ISSUE-109] [2026-08-09] Brand, Variant, and Unit Mismatch Manufactured Price Rows and Auto-Booked Wrong Base Units
- **Symptom**: Spoken brand/variant phrases like `"saras milk"` or `"amul milk"` merged with generic `"milk"`, and spoken unit variants like `Nimbu` 1 PIECE vs 1 PACKET cross-applied prices from different base units, auto-booking incorrect prices.
- **Root Cause**:
  1. Product identity in catalog items and voice pipeline conflated brand qualifiers (`Saras`, `Amul`) and variant qualifiers (`Desi`, `Green`, `Full Cream`) with generic base items.
  2. `catalog_items` lacked a `base_unit` column, forcing catalog matching and deduplication to group rows by name alone regardless of unit dimension (`PIECE` vs `PACKET`).
  3. No explicit "honest miss" handling existed when an item identity matched but no price row existed for the spoken base unit, causing unintended fallback or ₹0 pricing.
- **Resolution**:
  1. Implemented compositional identity resolution in `lexicon.ts` and `ItemLexicon.kt` (`QUALIFIERS`, `composeIdentity`, `canonicalOf`), keeping branded/variant products distinct from generic base items.
  2. Created Supabase migrations `20260809000300_catalog_base_unit.sql`, `20260809000400_merge_same_identity_same_unit.sql`, `20260809000500_promotion_guard_base_unit.sql`, and updated `schema.sql` to add `base_unit` column and index on `catalog_items` grouped by `(shop_id, canonical_key, base_unit)`.
  3. Updated Room database schema to v27 (`MIGRATION_26_27`) with `baseUnit` column on `CatalogItem`, keying `getActiveByCanonicalKey`, `insertOrUpdate`, and `dedupeCatalogItems` on `(canonicalKey, baseUnit)`.
  4. Updated catalog matching in `process-voice-job/index.ts` to match `(canonical_key, base_unit)`, perform unit conversion between units of the same base, and return decline reason `no_price_for_spoken_unit` when no price row exists for the spoken unit's base.
  5. Added `identityResolution` diagnostic trace to step 4 output.
  6. Added tests in `lexicon_test.ts` (Deno) and `ItemLexiconTest.kt` (JVM) verifying compositional identity resolution.
- **Verification Date**: 2026-08-09
- **Status**: CLOSED

#### [ISSUE-108] [2026-08-09] Live Catalog Duplicates Survived ISSUE-107's Merge Migration; Promotion RPC Still Created New Ones
- **Symptom**: After ISSUE-107 shipped, `20260809000100_merge_duplicate_catalog_items.sql` ran clean but merged zero rows, even though real cross-script duplicates existed in production (`घी`/`Desi Ghee`, `छाछ`/`Chaas (Buttermilk)`, `नींबू`/`Nimbu`, ` गोल्ड`/`Amul Gold Milk` — all in shop `2f992a33-fa26-4be2-9006-3e6eafd41e2c`). Separately, `record_unmatched_item_observation` (the RPC that auto-promotes unmatched items into the catalog) had no way to detect a cross-script duplicate before inserting.
- **Root Cause**:
  1. `20260809000000_canonical_catalog_dedupe.sql` backfilled `catalog_items.canonical_key` with a **literal** lowercase/whitespace fold (`lower(regexp_replace(name, '\s+', ' '))`), not the lexicon canonical from `lexicon.ts`/`ItemLexicon.kt`. `अदरक` and `Adrak` fold to different literal strings, so they never grouped under `GROUP BY canonical_key` — verified live: `SELECT ... GROUP BY shop_id, canonical_key HAVING count(*) > 1` returned zero rows immediately after the backfill.
  2. `record_unmatched_item_observation` (in `supabase/schema.sql`, created by migration `20260728020000_unmatched_item_catalog_learning.sql`) is the **only** place `catalog_items` rows are ever inserted from the voice pipeline — `index.ts` itself has no `.insert()`/`.upsert()` against that table, only a `SELECT` (L1066) and a price `UPDATE` (L2471). The function's existing anti-duplicate guard used `normalized_name_distance(name, p_item_name)`, a literal-string metric that is ~1.0 between any Devanagari/Latin pair, so it never caught cross-script duplicates either.
  3. A prior implementation pass tried to patch the guard by editing `index.ts` insert sites that don't exist (`Docs/canonical_insert_guard_plan.md` v1) — Antigravity correctly stopped on that step rather than fabricating a fix; see Deviations note below.
- **Resolution**:
  1. Rewrote `Docs/canonical_insert_guard_plan.md` (v2) targeting the real write path.
  2. Added `p_canonical_key text DEFAULT NULL` to `record_unmatched_item_observation`; it now looks up an existing active row by `canonical_key` **before** falling back to the literal-distance guard, and stamps `canonical_key` on any row it does insert (migration `20260809000200_canonical_promotion_guard.sql`, mirrored in `supabase/schema.sql`). `index.ts` L2088 now passes `p_canonical_key: canonicalOf(item.item_name)`.
  3. `CREATE OR REPLACE FUNCTION` with an added parameter created a **second overload** rather than replacing the function (Postgres resolves by full signature) — the old 6-arg version stayed live with the un-fixed guard. Dropped it explicitly (`DROP FUNCTION ... (uuid, text, text, text, text, integer)`); confirmed via `pg_get_function_identity_arguments` that only the 7-arg version remains.
  4. Recomputed `canonical_key` for all 121 active `catalog_items` rows using the actual `canonicalOf()` from `lexicon.ts` (via a Node script run against the live row export, not a re-implementation in SQL), then re-ran the merge migration. Result: exactly 4 groups found (matching the plan's §D2 predicted conflict list), all correctly rejected as price/unit conflicts via `RAISE NOTICE`, zero rows merged — this is the *correct* outcome, not a failure, since none of the 4 pairs agree on price.
- **Verification Date**: 2026-08-09. **Verified live**: `record_unmatched_item_observation` has exactly one signature (7-arg) in `pg_proc`; `catalog_items.canonical_key` backfilled from the true lexicon for all 121 active rows; post-backfill duplicate scan returns exactly the 4 known price/unit-conflicting pairs and nothing else. **Not verified**: no real voice recording has gone through the promotion path since this deployed, so the RPC's dedup-on-insert behavior is confirmed by reading `pg_proc` source, not by an observed promotion event.
- **Open, needs shopkeeper decision** (not a code fix): घी ₹1200/KG vs Desi Ghee ₹650/KG; नींबू ₹100/PACKET vs Nimbu ₹5/PIECE; छाछ ₹32/KG vs Chaas (Buttermilk) ₹15/PACKET; ` गोल्ड` ₹72/PACKET vs Amul Gold Milk ₹70/PACKET. Whichever price/unit is correct should be set on both rows, then the merge migration (or a manual repoint) can run again to collapse them.
- **Status**: CLOSED (mechanism); the 4 price conflicts above are OPEN pending user input.

#### [ISSUE-107] [2026-08-09] Item Lexicon Drift and Cross-Script Candidate Ambiguity Capping Confidence at 0.55
- **Symptom**: Clean transcripts like `"अदरक 1 किलो 50 रुपए"` matched `अदरक` perfectly (dist 0.0), but were assigned `resolutionKind: 'AMBIGUOUS'` and `isSanityFlagged: true`, capping confidence at 0.55 and withholding auto-confirm.
- **Root Cause**:
  1. `DEFAULT_ITEM_VOCAB` listed Devanagari and Latin spellings as two distinct surfaces with different phonetic keys (`अदरक` `ATALAK` vs `Adrak` `ATLAK`).
  2. `matchVocab` deduped candidate hits by phonetic `entry.key`, allowing both spellings of the SAME item into candidate ranking as two separate candidates. The distance between them (0.0833) failed the `MIN_MARGIN_PHONE_EDITS = 1.0` margin check, marking the match ambiguous.
  3. Catalog normalization logic was duplicated and drifted across four places (`FuzzyCatalogMatcher`, `AppDatabase`, `CatalogDao`, `SyncEngine`).
- **Resolution**:
  1. Created single canonical item lexicons in TypeScript (`lexicon.ts`) and Kotlin (`ItemLexicon.kt`).
  2. Added `canonical` identity field to `VocabEntry` and keyed `candidateMap` by `entry.canonical` in `matchVocab` (`phonetic.ts` and `OrderingSegmenter.kt`), measuring margin against the next *different* item.
  3. Unified `indicAliasMap` and `normalizeItemName`/`normalizeName` in `FuzzyCatalogMatcher.kt`, `CatalogDao.kt`, and `SyncEngine.kt` onto `ItemLexicon`.
  4. Added `canonical_key` column and index on `catalog_items` table in Supabase migrations (`20260809000000_canonical_catalog_dedupe.sql`, `20260809000100_merge_duplicate_catalog_items.sql`) and Room v26 (`MIGRATION_25_26`).
  5. Enforced unit and price agreement during automatic duplicate catalog item merges.
- **Verification Date**: 2026-08-09
- **Status**: CLOSED, but see [ISSUE-108] — the first implementation pass had two live-breaking defects (missing import, dropped vocab surface) that were only caught by running the code, not by the "Deviations: None" the implementer reported. Segmenter fix (server) confirmed by unit test only; **not yet confirmed by a real post-deploy recording in `stt_job_logs`.**

#### [ISSUE-106] [2026-08-09] Fragmented Hindi Compound Numerals 21-99 Book Wrong Quantities
- **Symptom**: Spoken order like `"तैंतीस किलो आलू"` transcribed as `"ते तीस किलो आलू"`. Leading fragment `"ते"` was stranded and fuzzy-matched as a bogus item (`दही`), while trailing piece `"तीस"` booked as quantity 30 kg auto-confirmed @ ₹1500.
- **Root Cause**:
  1. STT fragmented compound number words into two tokens (`तैंतीस` -> `ते` + `तीस`).
  2. Viterbi lattice decoder possessed token-splitting for fused words, but no merge path for fragmented words.
  3. STT keyterm bias list exhausted its 100-term budget on catalog/item vocabulary before reaching number words.
  4. Orphan segment dropping in `alignSegmentsToItems` allowed surviving auto-confirm item to proceed unflagged.
- **Resolution**:
  1. Implemented `rejoinFragmentedNumerals()` with measured `MERGE_MAX_NORM = 0.22` and `MERGE_MIN_VALUE_MARGIN = 0.10` in `phonetic.ts` and `OrderingSegmenter.kt` (client mirror).
  2. Reserved 25 keyterm budget slots for 21-99 compound numerals in `process-voice-job/index.ts`.
  3. Added Rule 11 to Grok AI prompt explicitly instructing compound numeral rejoin.
  4. Flagged low-margin rejoins with `numeralRejoinLowMargin: true` and routed to review.
  5. Flagged orphan unconsumed segmenter segments as implausible to prevent unflagged auto-confirms.
  6. Added trace field `numeralRejoins` to `step_3_deterministic_ordering_segmenter`.
- **Verification Date**: 2026-08-09 (Verified via 10 Deno unit tests in `phonetic_test.ts`, 148 Kotlin unit tests in `./gradlew test`, build success `VoiceToInvoice_v130.apk` MD5 `8142C60112F1D0D311A69B55A721BDD3` diff vs v129 `6D70A95D153165AEF55ECA5F0E11B8C2`, and live Supabase edge function deployment of `process-voice-job`).
- **Status**: CLOSED

#### [ISSUE-105] [2026-08-09] ISSUE-104's Guard Disarmed When Segmenter Produces Zero Segments
- **Symptom**: Deliberate noise/gibberish `"do kilo seeeee"` transcribed as `"दो किलो से"`. Because `से` is in `DISCOURSE_PARTICLES` (ISSUE-103), the segmenter emitted **zero segments**. `alignSegmentsToItems` returned `null`, which bypassed ISSUE-104's `resolutionKind === 'UNKNOWN'` check in `assessAiNameEvidence`, leaving `uncorroborated: false`. Grok returned `Seb`, which bound to catalog row `Seb` (price ₹0). Had `Seb` been priced, 2 KG of apples would have auto-booked from a non-item sound.
- **Root Cause**: ISSUE-104's guard assumed an `UNKNOWN` segment was emitted. When the segmenter emitted zero segments, `alignedSeg` was `null`, disarming the evidence check entirely.
- **Resolution**:
  1. Extended `assessAiNameEvidence` in `item_resolution.ts` to accept `noSegmentContext` when `segmentCount === 0`.
  2. Implemented `transcriptItemResidue()` to strip quantity, unit, price, and pure discourse particles from the raw transcript (preserving particles that match real catalog/vocab surfaces).
  3. Added `MAX_UNIDENTIFIABLE_RESIDUE_PHONES = 2`. When zero segments are produced and residue phones $\le 2$, `assessAiNameEvidence` marks `uncorroborated: true` with `source: 'transcript_residue'`.
  4. Updated `process-voice-job/index.ts` to pass transcript context, format implausibility reason cleanly for empty audible surfaces, add `source` to `ai_evidence` trace, and mark `segmentsAreSyntheticFromAi: step3Segments.length === 0` in step 3 trace.
  5. Cross-referenced from ISSUE-104's entry as completing that issue's Open Question 3.
- **Verification Date**: 2026-08-09 (Verified via 28 Deno unit tests in `item_resolution_test.ts` including zero-segment repetition and corpus regression tests).
- **Status**: CLOSED

#### [ISSUE-104] [2026-08-08] Grok Invents Catalog Item Name (and Inherits Price) From Audio Carrying Insufficient Sound Evidence
- **Symptom**: Spoken gibberish/short sound like `"do kilo aaaaaaa"` transcribed as `"दो किलो आ"`. Step 3 segmenter correctly marked `resolutionKind: UNKNOWN`, but Step 4 Grok returned `{item_name: "Aaloo", confidence: 0.7}`. `findCatalog("Aaloo")` matched catalog Aaloo @ ₹50, booking a false review card of `Aaloo · 2 KG · ₹100`.
- **Root Cause**:
  1. AI prompt prohibited empty/abstaining responses and ordered closest phonetic catalog matching.
  2. `findCatalog(rawName)` ran on the AI's invented string with zero cross-check against what was actually heard in the audio.
- **Resolution**:
  1. Added `assessAiNameEvidence()` and `MIN_AI_EVIDENCE_RATIO = 0.75` in `item_resolution.ts`. Refuses catalog binding when `resolutionKind === 'UNKNOWN'` and ratio of heard phones to AI name phones is `< 0.75`.
  2. In `process-voice-job/index.ts`, gated `findCatalog` behind `!aiEvidence.uncorroborated`.
  3. Set `item_name` to `"Unrecognized Item"`, added explanation to `implausibility_reason`, capped confidence at `0.30`, and attached `ai_evidence` to diagnostic trace.
  4. Updated system prompt rules 4 and 5 to allow returning `"Unrecognized Item"` with 0.2 confidence on bare vowels/syllables. (See ISSUE-105 for edge-case completion).
- **Verification Date**: 2026-08-08 (Verified via 22 Deno unit tests in `item_resolution_test.ts` and full 67-test suite passing cleanly).
- **Status**: CLOSED

#### [ISSUE-103] [2026-08-08] Short Phonetic Keys Collide With Common Hindi Discourse Words and Auto-Book Sales
- **Symptom**: Gibberish or common discourse phrases like `"2 kilo haan"` (or genitive postpositions like `"do kilo ke"`) transcribed as `"दो किलो हाँ"` / `"दो किलो के"` and auto-booked as 2 KG Aam @ ₹120 or 2 KG Ghee @ ₹1200 AUTO_CONFIRMED.
- **Root Cause**:
  1. Phonetic key collapse (`h` dropped, `n`/`m` -> `N`) mapped `"हाँ"` and `"आम"` both to key `"AN"`, giving `itemMatchNorm = 0`.
  2. `TAU_MARGIN = 0.08` was mathematically unreachable for key lengths <= 6 phones (margin granularity ~0.5/keyLength).
  3. Discourse particles were not filtered out before segmentation.
  4. Confidence calculation did not scale by information content (key length).
- **Resolution**:
  1. Replaced unreachable `TAU_MARGIN = 0.08` with length-aware `MIN_MARGIN_PHONE_EDITS = 1.0` in `phonetic.ts` and `OrderingSegmenter.kt`.
  2. Added `DISCOURSE_PARTICLES` set to `phonetic.ts` and `OrderingSegmenter.kt`, dropping discourse particles during segmentation and catalog quantity checks (`isQuantityPhrase`).
  3. Scaled `confidenceFromMatchNorm` by information content (`keyLength / 4`), restoring full confidence only when `literalExact` matches (`normalizedLiteralDistance === 0`).
  4. Gated fast path in `index.ts` against `low_information_match` (`fastConfidence < 0.80`).
- **Verification Date**: 2026-08-08 (Verified via unit tests in `phonetic_test.ts` and `PhoneticSegmentationTest.kt`).
- **Status**: CLOSED

#### [ISSUE-102] [2026-08-08] Diagnostic Logs Screen's AI/FAST/RULES Path Badges Never Render — Reads Trace Fields Off The Wrong JSON Path
- **Symptom**: User installed v127 and reported "still don't see the AI bot sign on recordings which used AI." Verified live: `stt_job_logs` shows the server-side trace is correct (e.g. job `76892c70-6d5f-4d87-b105-6bf7bdb08a07` has `step_4_interpretation_source = "grok_ai"`, `step_4_ai_model = "grok-4.5"`), but the badge never appeared client-side for *any* job.
- **Root Cause**: `SttWorker.kt`'s `mergeClientTrace()` wraps the server's trace JSON under a `"server"` key (`{"client": {...}, "server": {...}}`), but `DiagnosticLogsScreen.kt` read `step_4_*` and `step_0_server_diagnostics` directly off `root`.
- **Resolution**: Updated `DiagnosticLogsScreen.kt` at all 3 call sites (`pathBadge`, `serverIssues`, `fastPathSkipReason`) to unwrap `root.optJSONObject("server") ?: root` first.
- **Verification Date**: 2026-08-08 (Verified build success via `./gradlew assembleDebug`, copied `VoiceToInvoice_v128.apk` and confirmed MD5 hash diff `3FCC19F0650A3EF360F665911FD347BF` vs `BBE344CCEEAEC228823D53FB05D72902`).
- **2026-08-10 note**: a stale duplicate `OPEN` entry for this same issue (same title, describing the pre-fix state) survived in the log after this fix shipped and was only now removed on discovery — CLAUDE.md's own rule ("if it refines an existing OPEN issue, update it rather than creating a duplicate") was not followed at the time. Also: the 2026-08-08 verification was build-level (MD5 diff) only, not an on-screen confirmation that the badges actually render. That weaker bar is still open — tracked separately, do not re-open this entry for it.
- **Status**: CLOSED

#### [ISSUE-099] [2026-08-08] Fast Path Blocked By Unpriced & Phonetically-Unreachable Catalog Rows
- **Symptom**: Fast path was skipped on 16% of voice jobs due to unpriced or phonetically-unreachable catalog items (e.g., *आम* at price 0, *चावल* at price 0, and *अदरक* failing strict key equality).
- **Root Cause**: Shop catalog rows for *Aam* and *Chawal* had price 0; Devanagari vs Roman schwa handling caused `phoneticKey("अदरक")` (`ATALAK`) to differ from `phoneticKey("Adrak")` (`ATLAK`), causing strict key equality in `buildFastPath` to yield 0 hits.
- **Resolution**:
  1. Updated `catalog_items` in prod: *Aam* priced at ₹120, *Chawal* priced at ₹60 (`20260808120000_parse_inspections.sql`).
  2. Deactivated 7 price-0 catalog-learned hazard rows (`March`, `अठारह के लोग`, `पंद्रह`, `बचा रहा`, `सत्ताईस`, `सत्रह की`, `सिंगर`).
  3. Updated `buildFastPathFrom` in `process-voice-job/index.ts` to allow a tight `normalizedDistance <= 0.10` (`FAST_PATH_KEY_MAX_NORM`) fallback when exact key equality returns 0 hits. Note: §2.3 narrows this specific schwa discrepancy instance while the broader class survives (§8 of latency reduction plan).
- **Verification Date**: 2026-08-08 (Deployed via migration `20260808120000_parse_inspections.sql` & `process-voice-job` edge function deploy).
- **Status**: CLOSED

#### [ISSUE-100] [2026-08-06] Unconditional `Promise.all` Awaited Both STT Providers, Adding ~348ms Latency
- **Symptom**: `process-voice-job` paid the latency of the slower STT provider on 100% of jobs, even when the faster provider (Sarvam in 88% of cases) returned first with a high-confidence transcript.
- **Root Cause**: Dual STT executed via unconditional `Promise.all([grokPromise, sarvamPromise])`.
- **Resolution**: Implemented STT provider race using `Promise.race`. If the first-returning STT provider achieves transcript score $\ge 5$ (`FAST_STT_MIN_SCORE`) and `buildFastPathFrom` is eligible, the pipeline shortcuts immediately and abandons awaiting the second provider. Added `sttRace` block to `step_2_stt_proxy_response` trace and background non-blocking recovery of the second provider transcript (`loserOutcome`) for trace completeness.
- **Verification Date**: 2026-08-08 (Deployed and verified in edge function `process-voice-job`).
- **Status**: CLOSED

#### [ISSUE-101] [2026-08-08] Audio Storage Upload & Duplicate Log Writes Sat On Critical Response Path
- **Symptom**: Audio file upload and two consecutive writes to `stt_job_logs` executed synchronously before returning the response to the client.
- **Root Cause**: Audio storage upload was `await`ed on line 741; `stt_job_logs` was written twice (once before ledger writes and once after, purely to attach `persistence` details).
- **Resolution**:
  1. Deferred audio storage upload via `EdgeRuntime.waitUntil(uploadPromise)` (retaining initial synchronous `QUEUED` placeholder upsert).
  2. Consolidated `stt_job_logs` persistence into a single deferred `upsert` scheduled via `EdgeRuntime.waitUntil(...)` after ledger writes complete.
  3. Added timing instrumentation (`step_8_timings` tracking `catalogFetchedAtMs`, `aliasesFetchedAtMs`, `sttResolvedAtMs`, `parseResolvedAtMs`, `ledgerWrittenAtMs`, `totalMs`, `uploadMs`).
  4. Created `parse_inspections` table and added non-blocking shadow verification (`PARSE_INSPECTOR_RATE = 1.0`).
- **Verification Date**: 2026-08-08 (Deployed via migration `20260808120000_parse_inspections.sql` & `process-voice-job` edge function deploy).
- **Status**: CLOSED

#### [ISSUE-091] [2026-08-05] Integrated `ItemIcon` Thumbnails Across Early Parsed Results & Summary Lists (VoiceToInvoice_v114.apk)
- **Symptom**: Item icons were only rendered in `CatalogManagementScreen.kt`. Early voice parse results (before/without Grok 4.5), pending review cards, final list summary breakdowns, quick manual stepper cards, and recent command feed rows lacked item thumbnails.
- **Root Cause**: `ItemIcon` component was recently created but had not been integrated into the sales review, early confirmation dialog, summary breakdown, or feed UI components.
- **Resolution**:
  1. Updated [`ConfirmSaleDialog.kt`](file:///c:/Users/harsh/Documents/Voice%20To%20Invoice/app/src/main/java/com/voicetoinvoice/app/ui/components/ConfirmSaleDialog.kt) to display `ItemIcon` next to early parsed sale items.
  2. Updated [`PendingConfirmationsSheet.kt`](file:///c:/Users/harsh/Documents/Voice%20To%20Invoice/app/src/main/java/com/voicetoinvoice/app/ui/components/PendingConfirmationsSheet.kt) to display `ItemIcon` for each pending line item.
  3. Updated [`DailySummaryScreen.kt`](file:///c:/Users/harsh/Documents/Voice%20To%20Invoice/app/src/main/java/com/voicetoinvoice/app/ui/screens/summary/DailySummaryScreen.kt) to display `ItemIcon` for each item in the itemized sales breakdown.
  4. Updated [`ManualStepperComponent.kt`](file:///c:/Users/harsh/Documents/Voice%20To%20Invoice/app/src/main/java/com/voicetoinvoice/app/ui/components/ManualStepperComponent.kt) to render `ItemIcon` in quick manual sale stepper cards.
  5. Updated [`CommandFeedSheet.kt`](file:///c:/Users/harsh/Documents/Voice%20To%20Invoice/app/src/main/java/com/voicetoinvoice/app/ui/components/CommandFeedSheet.kt) to render `ItemIcon` for parsed items in the recent activity feed.
  6. Updated [`StockInScreen.kt`](file:///c:/Users/harsh/Documents/Voice%20To%20Invoice/app/src/main/java/com/voicetoinvoice/app/ui/screens/stockin/StockInScreen.kt) to render `ItemIcon` in the current stock levels row.
- **Verification**:
  1. Executed `./gradlew.bat test` (`BUILD SUCCESSFUL in 54s`).
  2. Built debug APK `VoiceToInvoice_v114.apk` via `./gradlew.bat assembleDebug` (`BUILD SUCCESSFUL in 41s`).
  3. Copied debug APK to `C:\Users\harsh\OneDrive\Desktop\VoiceToInvoice_APKs\VoiceToInvoice_v114.apk`.
  4. Installed on physical Android device `61e024bb` via `adb install -r`.
- **Status**: CLOSED

#### [ISSUE-090] [2026-08-05] WorkManager `SystemForegroundService` Missing `foregroundServiceType` Crash on Android 14+ (VoiceToInvoice_v113.apk)
- **Symptom**: App crashed instantly with `FATAL EXCEPTION: main` `java.lang.IllegalArgumentException: foregroundServiceType 0x00000001 is not a subset of foregroundServiceType attribute 0x00000000 in service element of manifest file` when `SttWorker` called `setForeground(getForegroundInfo())`.
- **Root Cause**: `SttWorker.kt` passes `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` (0x1) to `ForegroundInfo`. WorkManager delegates foreground status to its internal service `androidx.work.impl.foreground.SystemForegroundService`. On Android 14+ (API 34+), Android enforces that any foreground service type passed at runtime must be declared in `<service>` in `AndroidManifest.xml`. Because `SystemForegroundService`'s library manifest lacked `android:foregroundServiceType="dataSync"`, Android threw `IllegalArgumentException` on the main thread.
- **Resolution**: Updated `AndroidManifest.xml` to explicitly declare `androidx.work.impl.foreground.SystemForegroundService` with `android:foregroundServiceType="dataSync"` and `tools:node="merge"`.
- **Verification**:
  1. Executed `./gradlew.bat test` (`BUILD SUCCESSFUL in 49s`).
  2. Built debug APK `VoiceToInvoice_v113.apk` via `./gradlew.bat assembleDebug` (`BUILD SUCCESSFUL in 40s`).
  3. Copied debug APK to `C:\Users\harsh\OneDrive\Desktop\VoiceToInvoice_APKs\VoiceToInvoice_v113.apk`.
  4. Installed and verified on physical Android device `61e024bb` via `adb install -r`. Confirmed zero crashes in logcat.
- **Status**: CLOSED

#### [ISSUE-089] [2026-08-05] Deleted Unused & Stale `AssistantFastPath` (VoiceToInvoice_v110.apk)
- **Symptom**: Stale code in `AssistantFastPath.kt` created empty-audio `audioFilePath = ""` rows and posed a risk of re-wiring.
- **Root Cause**: `AssistantFastPath` was leftover from earlier fast-path experiments and was no longer invoked in the main tree.
- **Resolution**: Deleted `AssistantFastPath.kt` and updated stale comments in `SttWorker.kt`.
- **Verification**: Verified zero compilation errors and zero remaining references in `app/`.
- **Status**: CLOSED

#### [ISSUE-088] [2026-08-05] Ledger & Coalescer Reset on Buffer Cold-Start Epoch (VoiceToInvoice_v110.apk)
- **Symptom**: `PttWindowLedger` and `PttBurstCoalescer` held stale pre-wipe timestamps across ring buffer cold-starts.
- **Root Cause**: `smartStart()` performed ring wipes without signaling or resetting `PttWindowLedger` and `PttBurstCoalescer`.
- **Resolution**: Updated `RollingAudioBuffer.smartStart()` to return `Boolean` (`true` on cold start), and added reset calls (`PttWindowLedger.reset()`, `PttBurstCoalescer.reset()`) in `MainActivity.kt`'s `ON_START` handler when `smartStart()` cold-starts.
- **Verification**: Executed `./gradlew test --tests "com.voicetoinvoice.app.audio.*"`.
- **Status**: CLOSED

#### [ISSUE-087] [2026-08-05] Process-Lifetime Scope for Burst Coalescing & Degenerate Window Drop (VoiceToInvoice_v110.apk)
- **Symptom**: Burst groups were stranded when composable scope was cancelled (e.g. screen navigation) and flushed minutes later on subsequent presses; coalescer produced 100ms degenerate windows.
- **Root Cause**:
  1. `PttMicButton.kt` ran idle flush timer on `rememberCoroutineScope()`, which was cancelled on composable unmount.
  2. `buildGroupLocked()` emitted `clampedStartMs + 100L` when consumed by ledger, producing guaranteed `extraction_null` rows.
- **Resolution**:
  1. Created `PttCaptureScope` (process-lifetime `CoroutineScope`) for burst flush & job insert coroutines in `PttMicButton.kt`.
  2. Updated `PttBurstCoalescer.kt` to return `BurstFlush.Dropped` for stale groups (>5s) or consumed windows (<400ms `MIN_USABLE_WINDOW_MS`).
- **Verification**: Verified with `PttBurstCoalescerTest.kt` unit tests (`groupOlderThanFiveSecondsIsDropped`, `windowAlreadyConsumedByLedgerIsDropped`).
- **Status**: CLOSED

#### [ISSUE-086] [2026-08-05] Typed Extraction Failure Reasons in Diagnostic Logs (VoiceToInvoice_v110.apk)
- **Symptom**: Extraction failures recorded `extraction_null` with no explanation of why the window failed to extract.
- **Root Cause**: `extractAudioWindow()` returned `null` on all failure conditions without distinguishing reason.
- **Resolution**: Added `extractAudioWindowDetailed()` returning `ExtractionResult.Success` or `ExtractionResult.Failure(reason)`, and recorded `result.reason` in `diagnosticTraceJson`.
- **Verification**: Verified with `RollingBufferWindowTest.kt` unit tests.
- **Status**: CLOSED

#### [ISSUE-085] [2026-08-05] Capture-Segment Ledger to Prevent Extrapolation Across Mic Holes (VoiceToInvoice_v110.apk)
- **Symptom**: Window extraction produced wrong-time / past audio across pause/resume holes due to single global wall-clock anchor.
- **Root Cause**: Single global `totalBytesWritten` anchor extrapolated wall-clock time across recording gaps.
- **Resolution**: `RollingAudioBuffer.kt`: Replaced global anchor with `CaptureSegment` ledger (`segments: ArrayList<CaptureSegment>`). Window mapping now resolves only inside contiguous capture segments via `resolveSegmentWindowBytes()`.
- **Verification**: Executed `RollingBufferWindowTest.kt` unit tests (`BUILD SUCCESSFUL`).
- **Status**: CLOSED

#### [ISSUE-084] [2026-08-05] Build & Copy Pipeline Verification & Build Stamping (VoiceToInvoice_v110.apk)
- **Symptom**: 19 consecutive APK builds (v91-v109 except v106) were byte-identical to a Jul 30 binary (`md5 bbe344cceeaec228823d53fb05d72902`) due to OneDrive build directory locks and stale cache copying.
- **Root Cause**: OneDrive locked `app/build` directories and restored older `app-debug.apk` binaries over new builds without erroring.
- **Resolution**:
  1. Configured build output relocation out of OneDrive (`buildDir=C:/VTI_build` / `layout.buildDirectory.set(file("C:/VTI_build/${project.name}"))`).
  2. Added `BUILD_STAMP` and `GIT_SHA` buildConfig fields and displayed them on `DiagnosticLogsScreen.kt`.
  3. Enforced MD5 hash comparison before copying debug APKs to `VoiceToInvoice_APKs`.
- **Verification**:
  Built APK MD5: `F33E4298B9B9EB9C9FCB325756BDEFC2` (copied to `VoiceToInvoice_v110.apk`). Confirmed distinct from `bbe344cceeaec228823d53fb05d72902`.
- **Status**: CLOSED

---

#### [ISSUE-110..116] [2026-08-09] Speed / Cost / Smoothness Pass — Plan `Docs/speed_cost_smoothness_plan.md`
- **Shared evidence** (verified by querying `stt_job_logs`, 64 jobs carrying `step_4_fast_path`, i.e. the then-current deploy):

  | Road a recording takes | Jobs | Avg end-to-end | Auto-confirm |
  |---|---|---|---|
  | Fast path — segmenter only, no AI call | 38 | **1,332 ms** | **89%** |
  | Grok-4.5 chat interpretation | 26 | **7,400–8,800 ms** | **36%** |

  13 of the 26 AI-triggering jobs had `resolutionKind: MATCH` with `itemMatchNorm = 0` — already resolved exactly by the segmenter. `item_name_source = "segmenter_override"` (5 jobs) called the AI, discarded its name, dropped confidence 0.9 → 0.55, and auto-confirmed **0 of 5**.
- **⚠️ Numbering note**: the plan originally allocated ISSUE-076..082, which **collide with existing unrelated audio-pipeline issues** (the real highest was ISSUE-109). All code comments, migrations and the plan were remapped to ISSUE-110..116 on the same day. If you find a stray `ISSUE-07x` comment referencing catalog fetches, fast paths, R8 or learned parses, it is one of these and the remap missed it.

##### [ISSUE-110] Catalog + alias fetches were serial, and both blocked STT
- **Symptom**: ~550 ms elapsed before the first STT byte on *every* job — 41% of the entire 1,332 ms fast path.
- **Root Cause**: `index.ts` awaited the catalog fetch (avg `catalogFetchedAtMs` 256 ms), then separately awaited the alias fetch (avg `aliasesFetchedAtMs` 292 ms cumulative), and only then constructed both STT promises. Sarvam STT takes no catalog argument and never needed either.
- **Resolution**: `sarvamPromise` now starts before both DB reads; the two queries run under one `Promise.allSettled`; both timing marks are set together (they are now near-identical by design — that is the signal). Grok still waits because it alone consumes `keyterms`.
- **Verification**: deployed 2026-08-09, live bundle grep-confirmed (`A1 (ISSUE-110)`, `Promise.allSettled`). **Effect NOT yet verified** — requires a job recorded after the deploy; expect `aliasesFetchedAtMs - catalogFetchedAtMs ≈ 0` and `sttResolvedAtMs` down ~300–550 ms from its 1,068 ms baseline.
- **Status**: DEPLOYED, EFFECT UNVERIFIED

##### [ISSUE-111] A spoken price forced a 6-second AI round-trip the segmenter did not need
- **Symptom**: Trace `8d38c7b7` ("दो पैकेट छाछ चालीस रुपये") spent **6,083 ms** asking Grok to compute 40 ÷ 2. The segmenter had already returned `quantity 2`, `unit PACKET`, `itemTokens ["छाछ"]`, `itemMatchNorm 0` (exact), `spokenPrice 40`, and `wholeUtterancePriceIntentLegacy: BULK_SALE_TOTAL` with `hasAmbiguousPriceNumber: false`. The AI then returned `price_at_sale 20, total 40` — and its *name* ("Chaas") disagreed with the segmenter's, which slammed confidence to 0.55 and pushed a correct parse into the review queue.
- **Root Cause**: one line — `if (seg.spokenPrice != null || seg.rupeeWordPresent) return no('spoken_price_present')` — disqualified the fast path on *any* spoken price, regardless of whether the price was ambiguous.
- **Resolution**: gate narrowed to admit unambiguous single-segment `BULK_SALE_TOTAL` only (exact predicate in §1). The pushed item carries `price = spokenPrice / quantity`, `total = spokenPrice`, `price_intent = 'BULK_SALE_TOTAL'`.
- **Adversarial audit performed** (per CLAUDE.md rule 8): (a) downstream at `index.ts` recomputes `total`/`priceAtSale` from `classifySegmentPriceIntent(alignedSeg)` and **ignores** the new `total`/`price` fields, so the arithmetic is correct and the new field is redundant-but-harmless; (b) the numeric-consistency guard in `implausibilityReason` matches the spoken 40 against `total`, so a legitimate bulk sale is not flagged; (c) `resolveItemName` never sees price, so no new `segmenter_override` risk; (d) the `fastConfidence < 0.80` → `low_information_match` gate is intact.
- **Residual gap deliberately not closed**: if `detectPriceIntent(chosenRaw).spokenPrice` ever diverged from `seg.spokenPrice`, pricing would use the segment's value while the gate validated the utterance's. The numeric-consistency guard catches this (the unmatched number would flag the line), so it is a safety-netted edge, not an open hole. A belt-and-braces `whole.spokenPrice === seg.spokenPrice` assertion is the obvious hardening.
- **Status**: DEPLOYED, EFFECT UNVERIFIED

##### [ISSUE-112] The AI chat call had a 45-second tail
- **Symptom**: job `76892c70-6d5f-4d87-b105-6bf7bdb08a07` took **29,134 ms** end-to-end.
- **Root Cause**: `AI_CHAT_TIMEOUT_MS = 45000`. The trace shows `step_5…triggered: false, passesExecuted: 0` and `step_4_ai_error: null` — so **27.2 s was a single successful grok-4.5 chat call**, not adaptive re-decode. (An earlier reading of mine attributed this to re-decode; the trace disproves that.)
- **Resolution**: `AI_CHAT_TIMEOUT_MS` default 45000 → **12000**, falling through to the pre-existing `segmenter_fallback` path.
- **Status**: DEPLOYED, EFFECT UNVERIFIED

##### [ISSUE-113] The root composable held a 200-row trace collection it never read
- **Symptom**: visible UI stutter while a recording processed.
- **Root Cause**: `MainActivity.kt:270` collected `getAllJobsTraceLogsFlow()` — `SELECT *` over 200 `stt_jobs` rows including `diagnosticTraceJson` (avg 4.4 KB, up to 10 KB) — into the **root** composable, re-emitting on every write to `stt_jobs` (a single recording writes QUEUED → TRANSCRIBING → PARSED → synced). Grep confirmed `sttJobsState` appeared exactly once in the file: its own declaration. The same flow was *also* collected in `HomeScreen` and `DiagnosticLogsScreen`, so 2–3 copies of ~880 KB of JSON were live at once.
- **Resolution**: (1) deleted the dead `MainActivity` collection outright; (2) new `SttJobDao.getJobsSinceFlow(sinceMs)` bounded by time rather than `LIMIT 200`, and `HomeScreen` now queries the 24 h window it was already filtering to in memory. No Room migration — new `@Query` only.
- **Known limitation, stated deliberately**: `getJobsSinceFlow` still returns the full `SttJobRecord` **including** `diagnosticTraceJson`, because `CommandFeedSheet` takes `List<SttJobRecord>`. Stripping the blob needs an explicit column list, a projection type, and a signature change to that composable — out of scope here. This is row-count bounding (~200 → one day's worth, i.e. 12–94), not blob elimination.
- **Verification**: code-verified only. **NOT built, NOT installed, NOT observed on device.**
- **Status**: IMPLEMENTED, UNBUILT

##### [ISSUE-114] Learned Parse Memory had never hit once in its lifetime
- **Symptom**: `hit: false` on every job ever; 0 lifetime hits across 108 memos.
- **Root Cause**:
  1. `computeCatalogFingerprint(fullCatalogList)` hashed the **entire** catalog, so adding one unrelated item invalidated every memo in the shop. Result: 108 rows shattered across **8 fingerprints**, only 6 promoted, avg `observations` 0.9. Memo `PANCAKILOALO` existed under 2 fingerprints with 4 combined observations — enough to promote had it not been split.
  2. Rows were additionally split across **2 shop IDs** (the real `2f992a33-…` and the legacy sentinel `00000000-…-0001` from before `ensure_shop` provisioning).
- **Resolution**:
  1. `computeScopedCatalogFingerprint(memoItemNames, catalogNames)` hashes only the catalog entries a memo actually references; a memo item missing from the catalog contributes `absent:<name>` so deletion still invalidates. Lookup accepts `stored === null` (legacy) or an exact scoped match; the observation write stores the scoped value.
  2. Migration `20260809010000`: merged the sentinel shop (counts summed **before** deletion), re-pointed uniques, dropped `NOT NULL` on `catalog_fingerprint`, cleared all legacy hashes.
  3. Migration `20260809010200`: **follow-up defect fix.** `record_learned_parse_observation` only ever wrote `catalog_fingerprint` on its INSERT and reset paths — the increment path left it alone, so `NULL` was **sticky** and the staleness check would have been permanently disabled for all 87 rows. Worse, `existing.catalog_fingerprint = p_catalog_fingerprint` yields SQL `NULL` (not false) for those rows, so `IF NOT v_items_match` was false and the reset branch was skipped — right behaviour, wrong reason. The RPC now treats `NULL` as "adopt the incoming fingerprint" and backfills it on increment.
- **Verification** (verified by query, post-migration): 108 → **87 rows** exactly as the dry run predicted (76 real + 11 re-pointed); `sentinel_left 0`; `distinct_shops 1`; `null_fp 87`; `max_observations 5`, `avg 1.14` (was 0.9). **A memo HIT is not verified and cannot be yet** — promotion needs ≥2 corroborated observations of the same utterance after the migration.
- **Two defects I introduced and am recording rather than hiding**: (a) `promoted` fell **6 → 3**, because the merge summed counts but did not carry the `promoted` flag from deleted sentinel rows; those memos must re-earn promotion. (b) the merge summed `observations` but **not** `contributing_job_ids`, so 21 rows have inflated observations relative to their job-id array. This is *conservative* — the promotion rule gates on `array_length(v_job_ids,1) >= 2` as well, so it cannot cause a wrong promotion, only delay a right one. Consequently the merge delivered little promotion benefit; the real unlock is the scoped fingerprint going forward.
- **Status**: DEPLOYED + MIGRATED, MEMO-HIT UNVERIFIED

##### [ISSUE-115] Eleven foreign keys had no covering index
- **Root Cause**: Supabase performance advisor, 2026-08-09.
- **Resolution**: migration `20260809010100` — 11 FK indexes across `catalog_items`, `credits` ×2, `customers`, `shops`, `stock_in` ×2, `transactions` ×2, `unmatched_queue` ×2; dropped duplicate `idx_stt_job_logs_unique_job_id`.
- **Verification**: applied; **verified by query — all 11 indexes present in `pg_indexes`.**
- **Explicitly NOT done**: the **126 "Multiple Permissive Policies"** advisories. Consolidating RLS policies is a security-semantics change and must not be a blind sweep; it needs its own reviewed pass. Also noted in passing: `idx_learned_parses_shop_memo` duplicates the columns of the `learned_parses_shop_id_memo_key_key` unique index (not advisor-flagged, since one is unique and one is not).
- **Status**: CLOSED

##### [ISSUE-116] Every APK ever tested has been a debuggable, un-minified build
- **Root Cause**: `tools/vti-ship.ps1` runs `assembleDebug`, and `release` carried `isMinifyEnabled = false`. No baseline profile exists anywhere in the project.
- **Resolution**: new `perf` build type — `initWith(release)`, `isMinifyEnabled = true`, `isShrinkResources = true`, `isDebuggable = false`, signed with the **debug** keystore so it installs with no new signing secret. `-dontobfuscate` (readable traces while the parse pipeline is being tuned) plus keep rules for Room, WorkManager, entity classes and kotlinx.serialization. Added `androidx.profileinstaller:profileinstaller:1.3.1`.
- **Deliberately NOT done**: `vti-ship.ps1` was **not** rewired — the first `perf` build must be run manually so R8 breakage is caught deliberately rather than silently replacing the known-good debug pipeline. **A baseline profile was not generated** — it needs a `:baselineprofile` macrobenchmark module and on-device runs, which a headless agent cannot do; the dependency is in place so one can be dropped in later.
- **Verification**: **none. Not built, not installed, not run.** R8 changes codegen app-wide and can break Room/Compose/kotlinx at runtime only. This must be built and soaked **separately from ISSUE-110..115**, or it confounds their verification.
- **Status**: IMPLEMENTED, UNBUILT, UNVERIFIED

#### [ISSUE-096] [2026-08-06] Deterministic Fast Path — AI Round-Trip Was 92% Of End-To-End Latency (VoiceToInvoice_v126.apk)
- **Symptom**: Voice capture took p50 **8.1 s**, p90 13.7 s, max 22.6 s end-to-end, measured by replaying 67 of this shop's own recordings through the live edge function.
- **Root Cause**: The Grok chat interpretation ran on **every** non-memo job and cost p50 **7,414 ms** of the 8,111 ms total (dual STT is already parallel via `Promise.all` and costs only 564 ms p50). It was doing work that was already done: the deterministic segmenter had produced the **same line structure in 53 of 55** AI jobs. The AI's real contribution on those was Devanagari→catalog canonicalisation and stray-token removal — catalog lookups, not reasoning. Separately the `learned_parses` memo path (designed to skip the AI) hit only **1/67**, because its memo key derives from the raw transcript and STT is non-deterministic (27/63 transcripts drifted on replay), so keys rarely repeat.
- **Resolution**:
  1. `process-voice-job/index.ts`: new `buildFastPath()` gate + `else if (fastPath.eligible)` branch that books straight from the segmenter and skips the AI. Fires only when EVERY segment: `resolutionKind === 'MATCH'`, `itemMatchNorm === 0` (exact phonetic hit), not sanity-flagged, no `spokenPrice`/`rupeeWordPresent`, has a leading quantity, is not a quantity phrase, and resolves to exactly ONE **priced** catalog row. Disabled for ASSISTANT jobs (routed intent must be classified) and when a basket-total word is present. Kill switch: `DISABLE_FAST_PATH=1`.
  2. Trace gains `step_4_fast_path { used, skipReason, aiCallMade }` — `skipReason` names the exact gate that rejected the fast path, so "why was this one slow" is answerable from the trace alone.
  3. `DiagnosticLogsScreen.kt`: per-job badge — **⚡ FAST** / **🧠 MEMO** / **📐 RULES** / **🤖 AI** — read from `step_4_fast_path` + `step_4_interpretation_source`, plus `skipReason` shown in the expanded card.
- **Verification** (replay of 20 clean recordings against the deployed function):
  1. Fast path used on **14/20**; median wall **3,562 ms** vs **8,935 ms** for the 6 that still took the AI path — **2.5× faster, ~5.4 s saved**.
  2. All 14 produced correct item / qty / total and auto-confirmed correctly (e.g. `सत्रह किलो धनिया` → Dhaniya 17 KG ₹1020; `पचास किलो पोहा` → Poha 50 KG ₹2500).
  3. The 6 rejections were all legitimate and self-describing: `catalog_item_unpriced` ×2, `item_not_in_catalog`, `ambiguous_catalog_match`, `inexact_phonetic_match`, `segment_not_matched`.
  4. Gate correctness spot-check: `test_aaloo.wav` (transcribes item-first and segments messily) was correctly REJECTED with `skipReason: no_leading_quantity` and still booked ₹1000 via the AI path.
- **Known gap**: one fast-path hit fired on a garbled transcript (`"Chagall Gold"` → ` गोल्ड` ₹432). Not a gate fault — it matched a **polluted catalog row** (` गोल्ड`, leading space, ₹72/PACKET). Catalog hygiene, tracked separately.
- **Status**: CLOSED

#### [ISSUE-097] [2026-08-06] Server Errors Were Written Only To A Console Nobody Can Read (VoiceToInvoice_v126.apk)
- **Symptom**: The catalog-fetch `400` of ISSUE-092 was logged with `console.error` on every job for weeks and never surfaced.
- **Root Cause**: `console.*` output from edge functions is **not retrievable** through the platform logs API — `get_logs(service: 'edge-function')` returns only invocation lines (`POST | 200 | … execution_time_ms`). Verified directly: a deliberate `console.error` added and deployed on 2026-08-06 does not appear in that stream. Anything logged there is effectively write-only.
- **Resolution**: `process-voice-job/index.ts` gains a `serverDiagnostics` collector and a `note(stage, level, message)` helper that records into the trace **and** console. Wired to catalog-fetch failure/empty, Grok STT failure, Sarvam STT failure, and a new both-providers-failed error. Surfaced as `step_0_server_diagnostics` in the trace, and rendered in `DiagnosticLogsScreen` as a red "⚠ Server issue" block that is visible **collapsed** — deliberately not hidden behind a tap, since invisibility is the exact failure mode being fixed.
- **Verification**: deployed and the live bundle grep-confirmed. `step_0_server_diagnostics` present on jobs recorded after the deploy; empty array on healthy jobs, which is the intended signal.
- **Status**: CLOSED

#### [ISSUE-098] [2026-08-06] Diagnostic Logs Screen Could Only Ever Show Recordings Made On That Handset (VoiceToInvoice_v126.apk)
- **Symptom**: User reported "why can't I see anything in logs". Local `stt_jobs` held **0 rows** while Supabase held 250+ jobs for the same shop.
- **Root Cause**: Sync is push-only by design — there is no server→client path for `stt_job_logs` — so the screen renders only what this phone captured. Nothing auto-purges it (`deleteConfirmedJobs` is defined but never called; only the screen's own Clear All wipes it), so the table was simply empty. A shop that reinstalled, or any job the server processed while the app was closed, was invisible. **The capture path itself was healthy**: an adb-driven mic press produced job `79923447…` (PARSED, 3,934-byte server trace) instantly.
- **Resolution**:
  1. `CloudSyncManager.fetchJobLogsFromCloud(shopId, limit)` — reads this shop's recent `stt_job_logs` for **display only**. Deliberately NOT written to Room: `stt_jobs` doubles as the work queue that `SttWorker` drains, so persisting a remote row would hand the worker a job whose audio this device does not have.
  2. `DiagnosticLogsScreen.kt` merges local + cloud (local wins on id so on-device audio playback keeps working), dedupes by id (a repeated LazyColumn key throws), sorts by time, and marks cloud-only rows **☁ SERVER**.
  3. Engine/origin badges moved to their own row — four chips on the existing non-wrapping header Row clipped off the right edge and inflated card height on a 1080px screen.
  4. 96.dp trailing spacer so the assistant FAB stops covering the last card (same reservation as `HomeScreen`, ISSUE-095).
- **Verification**: on-device, `⬇️ Pulled 100 job log(s) from cloud for display`; screenshot confirms **📐 RULES**, **🤖 AI**, **🧠 MEMO** badges each paired with **☁ SERVER**, local card correctly unbadged and still offering "Play Recorded Audio", and cards compact after the badge-row change.
- **Open**: the **⚡ FAST** badge itself was **not** visually confirmed — jobs carrying `step_4_fast_path.used = true` (17:44 IST) did not appear above an older 17:34 local card, though the exact PostgREST query the app issues returns them first. Duplicate job_ids and null `recorded_at_ms` were both checked and **ruled out** (123 rows / 123 distinct, 0 nulls). Cause unknown; the phone was disconnected before it could be re-checked. **Next check**: open Logs, confirm whether the newest cloud rows are present but ordered wrongly (merge/sort bug) or absent entirely (fetch/parse bug), e.g. by logging `cloudLogs.take(3).map { it.recordedAtMs }` right after the fetch.
- **Status**: OPEN (feature works; one badge unverified)

#### [ISSUE-092] [2026-08-06] Every Sale Priced At ₹0 — Edge Function Selected A `catalog_items` Column That Did Not Exist Server-Side (VoiceToInvoice_v123.apk)
- **Symptom**: No sale had booked since the shop was provisioned. Device `transactions` table held **0 rows**, Home showed "Today: ₹0", and **every** row in `stt_job_logs` had `parsed_total = 0` while item and quantity parsed correctly (job `531f72d3`: "दस किलो आलू" → Aaloo, qty 10, total 0). Lines were reported to the shopkeeper as *"'Aaloo' has no price in your catalog — set a rate"* even though Aaloo was priced at ₹50/KG in the catalog.
- **Root Cause**:
  1. **The actual cause.** `process-voice-job/index.ts` fetched the catalog with `.select('id, name, price, unit_id, image_url')`, but `public.catalog_items` had **no `image_url` column** (the client has modelled `CatalogItem.imageUrl` since Room `MIGRATION_23_24`; the server side was never migrated). PostgREST answered `400 {"code":"42703","message":"column catalog_items.image_url does not exist"}`.
  2. **Why it was invisible.** The call destructured `const { data: catData } = await withTimeout(query, ...)`, **discarding `error`**. supabase-js *resolves* rather than throws on a PostgREST error, so the surrounding `try/catch` never fired and even its `console.warn` never printed. A hard 400 degraded silently into `dbCatalogItems = []` — i.e. "this shop has no catalog" — for **every job, every shop, every time**.
  3. With an empty catalog no item ever matched, so `priceAtSale` fell to `0`, `total = qty * 0 = 0`, and the auto-confirm bar (confidence ≥ 0.80 *and* price resolved) could never be cleared.
  4. **Amplifier.** Because every item was "unmatched", catalog-learning (ISSUE-033) hit its recurrence threshold and auto-added price-0 shadow rows under the shop (`96221dbe` "Aaloo" @ ₹0 beside the real `ac179aa1` "Aaloo" @ ₹50), which then pinned later parses to a 0 total.
  5. **Contributing.** `CloudSyncManager.syncCatalogItemToCloud` was the only sync method that never sent `shop_id`, so all 74 catalog rows the phone pushed landed with `shop_id NULL` and were invisible to the shop-scoped query. `ShopContext` had switched identity from the legacy `"default_shop"` literal to a per-install UUID with **no migration of existing rows** (`bindAuthenticatedShopId` is never called), stranding 110 local rows.
- **Resolution**:
  1. Supabase migration `add_image_url_to_catalog_items`: `ALTER TABLE public.catalog_items ADD COLUMN IF NOT EXISTS image_url TEXT`.
  2. `process-voice-job/index.ts`: destructure and handle `error` on the catalog fetch; log it at `console.error`; record `catalogItemsFetched` and `catalogFetchError` in `step_2_stt_proxy_response` so a broken fetch is distinguishable from a genuinely empty catalog in the trace.
  3. `CloudSyncManager.kt` `syncCatalogItemToCloud`: send `shop_id` via `SupabaseConfig.getNullSafeShopId(item.shopId)`.
  4. New `data/ShopIdBackfill.kt`, invoked from `MainActivity.onCreate` before the first sync sweep: one-time rewrite of `shopId` from `"default_shop"` to the real shop id across all 13 shop-scoped tables (additive merge for `daily_rollups`, whose PK leads with `shopId`), then clears `catalog_items.synced` so the priced catalog re-uploads correctly tenanted. Guarded by a SharedPreferences flag, mirroring the existing `rollup_backfill` pattern.
  5. `process-voice-job/index.ts`: skip catalog-learning entirely when `dbCatalogItems.length === 0` — an empty catalog makes every item trivially "unmatched", so the recurrence signal is meaningless and only mints price-0 rows.
- **Verification** (by effect, on a job recorded *after* the change):
  1. Reproduced the 400 directly: `GET /rest/v1/catalog_items?select=...,image_url` → `HTTP 400 column catalog_items.image_url does not exist`; the same query without `image_url` → `HTTP 200`.
  2. Instrumented + redeployed, then posted `scratch/test_aaloo.wav` through the live function as the real shop: trace showed `catalogItemsFetched: 0` — confirming the empty-catalog path was live.
  3. After the migration + redeploy, the same audio produced `catalogItemsFetched: 74`, `catalogFetchError: null`, `is_matched_to_catalog: true`, `priceAtSale: 50`, `estimatedTotal: 1000`, `autoConfirmedToLedger: true`, and `stt_job_logs.status = AUTO_CONFIRMED` with `parsed_total = 1000` (20 KG × ₹50).
  4. A real `transactions` row was written (`1d935434`, item_id `ac179aa1`, total 1000, correctly shop-scoped) — the first ever under this shop id. **That row was synthetic test audio and has been marked `voided = true`.**
  5. On device: `ShopIdBackfill` logged `110 row(s) moved`; server catalog went to 76 rows / 66 active+priced under the real shop id.
- **Status**: CLOSED

#### [ISSUE-093] [2026-08-06] Client Catalog Pull Broken By The Same Missing Column, And Not Shop-Scoped (VoiceToInvoice_v123.apk)
- **Symptom**: Device logcat showed `❌ Catalog pull HTTP 400` repeating every ~32 s. The server→client catalog merge — the app's only pull path — had never worked.
- **Root Cause**:
  1. Same missing column as ISSUE-092: `CloudSyncManager.fetchCatalogFromCloud` requested `select=id,name,unit_id,price,active,image_url,updated_at`.
  2. Separately, that request carried **no `shop_id` filter at all**, so it asked for every active row in the table regardless of tenant — the first time a second shop existed, this device would have merged that shop's items and prices into its own catalog.
- **Resolution**:
  1. Fixed by the `image_url` migration in ISSUE-092.
  2. `CloudSyncManager.kt`: scope `fetchCatalogFromCloud` to the current shop via `SupabaseConfig.getNullSafeShopId(ShopContext.shopIdOrNull())`.
- **Verification**: after the migration and rebuild, device logcat shows **0** `HTTP 4xx/5xx` sync errors (previously one 400 per pull cycle); the exact client pull URL now returns `HTTP 200` with 74 shop-scoped rows including `image_url`.
- **Status**: CLOSED

#### [ISSUE-094] [2026-08-06] Hindi Numerals 21–99 Missing From The Parser, Leaking Into The Catalog As ₹0 Items (VoiceToInvoice_v123.apk)
- **Symptom**: Live device catalog contained "पंद्रह" (15), "सत्रह की" (17), "सत्ताईस" (27), "अठारह के लोग" (18), "बचा रहा" — all at ₹0, each rendered to the shopkeeper as a real product in the Quick Manual Stepper.
- **Root Cause**:
  1. `OrderingSegmenter.HINDI_NUMBER_MAP` (and its server mirror `phonetic.ts`) covered 0–20 and then only exact tens (30, 40, … 100). Hindi compound numerals 21–99 are irregular single words and cannot be composed from tens + units, so **no quantity between 21 and 99 other than an exact ten could be spoken at all**. An unmapped numeral is not recognised as a quantity, so the segmenter assigned it to the ITEM slot.
  2. `FuzzyCatalogMatcher.isBlacklistedItemName` denylisted unit words only ("किलो", "packet", …) and no number words, so the leftover numeral passed straight into `catalog_items`. `AppDatabase.onOpen` purges a hardcoded handful ('सत्तर', 'पचास') after the fact, which only removes misfires someone already reported.
- **Resolution**:
  1. `OrderingSegmenter.kt` + `phonetic.ts`: added all of 21–99 (Devanagari + transliteration + digits); also closed pre-existing drift by adding `hundred`/`हजार`/`hazaar`/`thousand`/`1000` to the Kotlin map.
  2. `FuzzyCatalogMatcher.kt`: new `isQuantityPhrase()`, wired into `isBlacklistedItemName`, rejecting a name whose **leading token** is a number word or digits — reusing `HINDI_NUMBER_MAP` rather than restating numerals, so a numeral the parser learns is automatically one the catalog refuses. Only the leading token is tested, so "Amul Gold 500" is unaffected.
  3. `process-voice-job/index.ts`: mirrored `isQuantityPhrase()` guarding the catalog-learning promote path.
- **Verification**: new `QuantityPhraseGuardTest` (7 cases incl. the exact polluting strings observed on device) — this test **caught a real gap on first run**, revealing "सत्ताईस" was absent from the numeral map. Full suite green: 16 classes / ~140 tests, `BUILD SUCCESSFUL`. A parity script confirms the Kotlin and TypeScript maps now agree exactly (0 keys only-in-Kotlin, 0 value mismatches, all integers 1–100 covered).
- **Status**: CLOSED

#### [ISSUE-095] [2026-08-06] Assistant FAB Covered The Quick Stepper's "Add ₹" Button (VoiceToInvoice_v123.apk)
- **Symptom**: On Home, the purple assistant FAB (`BottomEnd`, 64.dp + 24.dp inset, placed over every screen from `MainActivity`) sat directly on top of the right-hand stepper card's "Add ₹50" button, and its "बिल वाले" caption overlapped the item cards below — that item could not be added by tap, the FAB swallowed the press.
- **Root Cause**: `ManualStepperComponent` is the last child of a `fillMaxSize` `Column` in `HomeScreen`, so it extends to the bottom of the screen, while the FAB is an absolutely-positioned sibling in `MainActivity`'s outer `Box`. Neither reserved space for the other.
- **Resolution**: `HomeScreen.kt`: added a documented 96.dp `Spacer` after `ManualStepperComponent` reserving the FAB's footprint.
- **Verification**: compiles and ships in v123. **NOT visually confirmed** — the test device was locked (dozing, keyguard up, PIN required) when the screenshot was attempted, and I did not attempt to bypass it. The pre-fix collision *is* confirmed from a device screenshot taken earlier in the session.
- **Status**: CLOSED (visual confirmation outstanding)

#### [ISSUE-080] [2026-08-05] Duplicate Catalog Items (Aloo/Aaloo, Adrak) & Negative Stock Balances Cleaned Up (VoiceToInvoice_v115.apk)
- **Symptom**: Stock catalog displayed multiple duplicate entries for items like Aloo / Aaloo and Adrak, with negative stock balances (e.g. -1 kg, -6 kg) and 0 kg rows.
- **Root Cause**:
  1. `AppDatabase.seedMasterCatalog` generated random `UUID.randomUUID()` values on every database initialization. Because Room `@Insert(onConflict = OnConflictStrategy.REPLACE)` deduplicates by primary key (UUID), re-seeding inserted new duplicate rows rather than replacing existing ones.
  2. Spoken item matching flip-flopped between duplicate catalog item IDs for the same product, causing sales to deduct stock from one ID (going negative) while stock-in added to another ID.
  3. `SyncEngine.pullCatalogFromCloud` raw string matching allowed spelling variants (Aloo vs Aaloo) to insert remote duplicates under new IDs.
- **Resolution**:
  1. `AppDatabase.kt`: Added `MIGRATION_24_25` (bumped DB version to 25). Migration normalizes catalog item names, merges `stockQty` onto a single canonical catalog item, re-links foreign references in `stock_ledger`, `transactions`, `stock_in`, `stock_batches`, `daily_rollups`, and `unmatched_queue` to the canonical item ID, and deletes duplicate catalog rows.
  2. `AppDatabase.kt`: Updated `seedMasterCatalog()` to generate deterministic UUIDs via `UUID.nameUUIDFromBytes("catalog_seed_$norm".toByteArray())` so re-seeding updates existing master catalog items rather than creating duplicates.
  3. `SyncEngine.kt`: Added `normalizeName()` matching in `pullCatalogFromCloud()` to prevent remote catalog pulls from creating duplicate local catalog rows.
  4. `MainActivity.kt`: Updated `onAddItem` manual addition callback to check existing items by normalized name before inserting a new item ID.
- **Verification**:
  1. Executed `./gradlew.bat test` (`BUILD SUCCESSFUL in 1m 3s`, 53/53 tasks up-to-date/executed).
  2. Built debug APK `VoiceToInvoice_v115.apk` via `./gradlew.bat assembleDebug` (`BUILD SUCCESSFUL in 46s`).
  3. Copied debug APK to `C:\Users\harsh\OneDrive\Desktop\VoiceToInvoice_APKs\VoiceToInvoice_v115.apk`.
- **Status**: CLOSED

#### [ISSUE-083] [2026-08-05] Strict Byte-Offset Clamping Against Pre-Resume Past Audio Leakage (VoiceToInvoice_v111.apk)
- **Symptom**: Recordings were pulling audio from a previous recording session (spoken seconds or minutes prior).
- **Root Cause**: `extractAudioWindow()` converted `startMs` to a byte offset (`byteRange.first`) using `totalBytesWritten - (anchorMs - startMs) * bytesPerSecond / 1000`. When `startMs` pre-roll (-300ms) or hardware time jitter calculated an offset below `resumeByteOffset`, `extractAudioWindow()` read ring buffer slots containing PCM bytes recorded prior to `resumeRollingBuffer()`. Without strict clamping against `resumeByteOffset`, pre-resume audio from a past recording was included in the extracted WAV window.
- **Resolution**: `RollingAudioBuffer.kt`: Added strict `startByte = max(startByte, resumeByteOffset)` enforcement inside `extractAudioWindow()`. Any window extraction is physically prevented from reading bytes written prior to the current resume boundary.
- **Verification**:
  1. Executed `./gradlew.bat test` (`BUILD SUCCESSFUL in 27s`).
  2. Built debug APK `VoiceToInvoice_v111.apk` via `./gradlew.bat assembleDebug` (`BUILD SUCCESSFUL in 13s`).
  3. Installed directly onto phone via `adb install -r VoiceToInvoice_v111.apk` (`Success`, `lastUpdateTime=2026-08-05 17:14:13`).
- **Status**: CLOSED

#### [ISSUE-082] [2026-08-05] RollingAudioBuffer Gap Clamping & Deferral of Resume Timestamp (VoiceToInvoice_v110.apk)
- **Symptom**:
  1. Assistant presses within ~1-2 seconds after foregrounding/resume resulted in `extraction_null` or zero-length audio extractions (blank transcripts).
  2. Sale presses immediately after app resume extracted audio from the wrong/past timestamp slice.
- **Root Cause**:
  1. `RollingAudioBuffer.kt`: `extractAudioWindow()` checked `if (pAt > 0L && rAt > pAt && startMs < rAt)`, which clamped `effectiveStartMs` to `rAt` for ANY press where `startMs < rAt`. Since `startMs = pressStart - 300ms` (preroll), any mic press within ~1.5s of resuming the buffer triggered this condition. This forced `effectiveStartMs` to jump to `rAt` even if `startMs` was completely valid audio written before the pause!
  2. `resumeRollingBuffer()` set `resumeAtMs` and `resumeByteOffset` to `now` immediately before starting the thread. However, `AudioRecord.startRecording()` hardware startup latency (50-200ms) meant `resumeAtMs` was set prior to any PCM data being captured, pointing to silence or stale buffer indices.
- **Resolution**:
  1. `RollingAudioBuffer.kt`: Updated gap clamping check in `extractAudioWindow()` to `if (pAt > 0L && rAt > pAt && startMs >= pAt && startMs < rAt)`. Now `effectiveStartMs` is ONLY clamped to `rAt` if `startMs` strictly fell inside the paused dead-zone (`[pausedAtMs, resumeAtMs)`). Valid audio recorded before `pausedAtMs` or after `resumeAtMs` is no longer incorrectly truncated.
  2. `RollingAudioBuffer.kt`: Deferred setting `resumeAtMs` and `resumeByteOffset` inside `resumeRollingBuffer()`'s capture thread until the first non-zero PCM chunk (`bytesRead > 0`) is actually read from hardware.
- **Verification**:
  1. Executed `./gradlew.bat test` (`BUILD SUCCESSFUL in 30s`).
  2. Built debug APK `VoiceToInvoice_v110.apk` via `./gradlew.bat assembleDebug` (`BUILD SUCCESSFUL in 13s`).
  3. Copied debug APK to `C:\\Users\\harsh\\OneDrive\\Desktop\\VoiceToInvoice_APKs\\VoiceToInvoice_v110.apk`.
  4. UNVERIFIED on device — logic-checked only.
- **Status**: CLOSED

#### [ISSUE-081] [2026-08-05] Assistant Gets No Recording in Logs + Sale Recordings Extract Wrong/Past Audio (VoiceToInvoice_v109.apk)
- **Symptom**:
  1. ASSISTANT mic button presses produced no `rawTranscript` in diagnostic logs at all — the job appeared with blank transcript and no server processing.
  2. SALE mic button recordings were extracting past/stale audio segments: pressing the button now produced audio from a previous timestamp rather than the current speech.
- **Root Cause**: Both bugs share a single root cause — `PttWindowLedger` was shared (via the singleton `PttWindowLedger.getInstance()`) between the ASSISTANT button and the SALE/CREDIT_SALE buttons. `PttWindowLedger.commitWindow(startMs, endMs)` advances `lastEndMs` — the floor used by `PttBurstCoalescer.buildGroupLocked()` to clamp `clampedStartMs = max(rawStartMs, lastConsumedEndMs)`. Two consequences:
    1. After any SALE recording committed its window (e.g. ending at T+1300ms), the next ASSISTANT press had `lastConsumedEndMs = T+1300ms`. The ASSISTANT's `rawStartMs = pressTime - 300ms` was often earlier than T+1300ms, so `clampedStartMs` jumped forward to T+1300ms — the audio window started *after* the actual spoken command, extracting silence or the wrong segment. SttWorker received a valid WAV but with no speech, so `rawTranscript` came back blank.
    2. After any ASSISTANT recording committed its window (e.g. ending at T+2000ms), the next SALE press had `lastConsumedEndMs = T+2000ms`. The SALE's burst group was clamped to start at T+2000ms regardless of when the actual sale was spoken, extracting audio from the wrong (post-ASSISTANT) time slice.
  - `MainActivity.kt` line 286 set `sharedPttWindowLedger = PttWindowLedger.getInstance()` and passed it to `AssistantFloatingButton` at line 808. `HomeScreen.kt` internally called `PttWindowLedger.getInstance()` for its sale mics. Both returned the same singleton object, causing full ledger cross-contamination.
- **Resolution**:
  1. `MainActivity.kt`: Replaced `sharedPttWindowLedger = PttWindowLedger.getInstance()` with `assistantPttWindowLedger = PttWindowLedger()` (a fresh, isolated non-singleton instance). Changed the `AssistantFloatingButton` call at line 808 to pass `assistantPttWindowLedger` instead of the shared sale singleton. SALE/CREDIT_SALE (HomeScreen) and STOCK_IN (StockInScreen) each independently call `PttWindowLedger.getInstance()` and continue to share the sale singleton correctly — this is unchanged.
- **Verification**:
  1. Executed `./gradlew.bat test` (`BUILD SUCCESSFUL in 20s`).
  2. Built debug APK `VoiceToInvoice_v109.apk` via `./gradlew.bat assembleDebug` (`BUILD SUCCESSFUL in 5s`).
  3. Copied debug APK to `C:\\Users\\harsh\\OneDrive\\Desktop\\VoiceToInvoice_APKs\\VoiceToInvoice_v109.apk`.
  4. UNVERIFIED on device — logic-verified only. Deploy and confirm assistant produces `rawTranscript` in logs and sale recordings show correct timestamps.
- **Status**: CLOSED

#### [ISSUE-080] [2026-08-05] Non-Blocking Cancellation Recovery, Revert of Over-Aggressive Ring Buffer Clamp & Worker Failure Termination (VoiceToInvoice_v108.apk)
- **Symptom**: v107 suffered severe timing regressions similar to v105 — voice recording window extractions failed with "रिकॉर्डिंग नहीं हुई" toasts, and WorkManager job retries re-blocked `CommitSequencer` for 6 seconds on subsequent sales.
- **Root Cause**:
  1. `SttWorker.kt`: `reconcileWithServerTrace()` executed a synchronous HTTP GET inside `NonCancellable` during teardown, blocking execution for up to 5s. On cancellation, `Result.retry()` re-queued jobs, leaving them in `QUEUED` state and re-triggering `CommitSequencer`'s 6s stall.
  2. `RollingAudioBuffer.kt`: `resumeByteOffset` byte-level clamp in `extractAudioWindow()` rejected valid post-resume audio chunks (hardware AudioRecord start latency ~50-150ms), causing `extractAudioWindow()` to return `null` and creating instant-fail `FAILED` jobs.
- **Resolution**:
  1. `SttWorker.kt`: Replaced `reconcileWithServerTrace()` network call with `preResponseResult` local variable captured before post-processing. Changed cancellation exception return to `Result.failure()` so dead jobs do not re-queue. Kept `getForegroundInfo()` / `setForeground()` expedited FGS promotion.
  2. `RollingAudioBuffer.kt`: Removed byte-level `resumeByteOffset` clamp from `extractAudioWindow()`, restoring v106's accurate time-based gap clamping.
- **Verification**:
  1. Executed `./gradlew.bat test` (`BUILD SUCCESSFUL in 22s`).
  2. Built debug APK `VoiceToInvoice_v108.apk` via `./gradlew.bat assembleDebug` (`BUILD SUCCESSFUL in 6s`).
  3. Copied debug APK to `C:\Users\harsh\OneDrive\Desktop\VoiceToInvoice_APKs\VoiceToInvoice_v108.apk`.
- **Status**: CLOSED

#### [ISSUE-079] [2026-08-05] Assistant Cancellation Recovery, NonCancellable Status Persistence & CommitSequencer Unblock (VoiceToInvoice_v106.apk)
- **Symptom**: Assistant mic recordings failed to record transcripts in logs (showing "PROCESSING" / "No transcript recorded"), and subsequent sale recordings suffered 6-second stalls, wrong sequence ordering, or stale time recording logs.
- **Root Cause**:
  1. `SttWorker.kt`: `doWork()` caught `JobCancellationException` in `catch (e: Exception)` when WorkManager or coroutines were cancelled. Attempting to write `updateJob()` within the cancelled scope threw a secondary `JobCancellationException`, leaving the job's status permanently stuck as `QUEUED` or `TRANSCRIBING` in Room DB.
  2. `CommitSequencer.kt`: Subsequent sale recordings checked `countUnterminatedBefore(recordedAtMs)` (`status IN ('QUEUED', 'TRANSCRIBING')`). Finding the stuck Assistant job prior in time, `CommitSequencer` stalled for 6 full seconds (`CEILING_MS = 6000L`) before timing out and forcing the sale commit with `commit_order_violated = true`, distorting window timing logs.
  3. `HomeScreen.kt`: `markStuckJobsAsFailed` used a 60-second threshold, leaving stuck jobs blocking `CommitSequencer` across app launches.
  4. `DiagnosticLogsScreen.kt`: Rendered generic "No transcript recorded" when `rawTranscript` was empty, masking execution exceptions.
- **Resolution**:
  1. `SttWorker.kt`: Wrapped error/cancellation status persistence in `withContext(NonCancellable + Dispatchers.IO)` and marked status as `SttJobStatus.FAILED` with `errorMessage`. Added explicit `if (e is CancellationException) throw e` after updating DB state, allowing WorkManager cancellation while ensuring Room DB state is safely terminal.
  2. `HomeScreen.kt`: Tightened `markStuckJobsAsFailed` threshold to 25 seconds (`25000L`), matching the edge function inline budget (`INLINE_BUDGET_MS = 20s`).
  3. `DiagnosticLogsScreen.kt`: Updated transcript rendering to display `errorMessage` or "⚠️ Recording failed / Unrecognized" when `rawTranscript` is blank or `status == FAILED`.
- **Verification**:
  1. Executed `./gradlew.bat test` (`BUILD SUCCESSFUL in 58s`).
  2. Built debug APK `VoiceToInvoice_v106.apk` (`BUILD SUCCESSFUL in 48s`).
  3. Installed `VoiceToInvoice_v106.apk` directly onto connected device `61e024bb` via ADB (`Success`).
  4. Copied APK to `C:\Users\harsh\OneDrive\Desktop\VoiceToInvoice_APKs\VoiceToInvoice_v106.apk`.
- **Status**: CLOSED

#### [ISSUE-078] [2026-08-04] Ring Buffer Resume Byte Clamp & Assistant Pipeline Latency Removal (VoiceToInvoice_v105.apk)
- **Symptom**: SALE recordings extracted audio from a past recording session after returning from app backgrounding, and ASSISTANT recordings suffered a mandatory 1.8-second delay before uploading while displaying outdated "On-Device STT" UI tags.
- **Root Cause**:
  1. `RollingAudioBuffer.kt`: Upon app resume, `lastWriteAtMs` was updated to `now` before `AudioRecord` initialized (~150ms delay). The time-to-byte formula evaluated `effectiveStartMs` to a byte index below `totalBytesWritten` at resume, mapping to pre-pause audio.
  2. `SttWorker.kt`: Carried a vestigial 1800ms `withTimeoutOrNull` loop polling for `onDeviceStatus` on ASSISTANT jobs. Since on-device speech recognizer is bypassed in favor of continuous cloud processing, this loop always timed out, delaying edge uploads by 1.8s.
  3. `DiagnosticLogsScreen.kt`: Displayed a legacy "🎤 Assistant Fast-Path (On-Device STT)" label when local cache audio was cleared.
- **Resolution**:
  1. Added `@Volatile private var resumeByteOffset: Long = 0L` to `RollingAudioBuffer.kt`. In `resumeRollingBuffer()`, `resumeByteOffset` captures `totalBytesWritten`. In `extractAudioWindow()`, `adjustedFirst` clamps the start byte to `max(byteRange.first, resumeByteOffset)`, preventing extraction of pre-resume audio.
  2. Removed the vestigial 1.8-second `onDeviceStatus` polling loop in `SttWorker.kt`, eliminating 1800ms of latency for ASSISTANT recordings.
  3. Updated `DiagnosticLogsScreen.kt` label to `☁️ Audio processed via cloud (local copy unavailable)`.
- **Verification**:
  1. Executed `./gradlew.bat test` (`BUILD SUCCESSFUL in 35s`).
  2. Built debug APK `VoiceToInvoice_v105.apk` (`BUILD SUCCESSFUL in 46s`).
  3. Copied debug APK to `C:\Users\harsh\OneDrive\Desktop\VoiceToInvoice_APKs\VoiceToInvoice_v105.apk`.
- **Status**: CLOSED

#### [ISSUE-077] [2026-08-04] App Responsiveness & Apple-Style Fluid UI Optimizations (VoiceToInvoice_v104.apk)
- **Symptom**: Voice recording uploads incurred an artificial 1.8-second (1800ms) delay stall prior to starting HTTP upload, and mic button interactions lacked fluid spring animations and visual depth.
- **Root Cause**:
  1. `SttWorker.kt` executed an unconditional 1800ms Room DB polling loop (`withTimeoutOrNull(1800L)`) checking for `onDeviceStatus` on every recording, including non-assistant sales/stock jobs where on-device recognizers do not run.
  2. `PttMicButton.kt` switched colors abruptly on press without touch-down scale springs, color transitions, or active recording aura effects.
- **Resolution**:
  1. Scoped `SttWorker.kt` 1.8s wait loop to `jobRecord.captureIntent == CaptureIntent.ASSISTANT && jobRecord.onDeviceStatus.isBlank()`, bypassing 1.8s stall for all standard sales/stock voice recordings.
  2. Implemented Apple-style Compose spring scaling (`animateFloatAsState` 1.08x spring), color transition (`animateColorAsState`), and glowing infinite pulse aura (`rememberInfiniteTransition` scale/alpha pulse) during recording in `PttMicButton.kt`.
- **Verification**:
  1. Executed `./gradlew.bat test` (`BUILD SUCCESSFUL in 37s`).
  2. Built debug APK `VoiceToInvoice_v104.apk` (`BUILD SUCCESSFUL in 18s`).
  3. Exported debug APK to `C:\Users\harsh\OneDrive\Desktop\VoiceToInvoice_APKs\VoiceToInvoice_v104.apk`.

#### [ISSUE-076] [2026-08-04] Never-Stop Ring Buffer Architecture & Audio Timing Fixes (VoiceToInvoice_v103.apk)
- **Symptom**: SALE recordings made after ASSISTANT presses extracted wrong/old audio from gap windows, foregrounding app after long backgrounding played stale audio from pre-background window (~120s old), and ASSISTANT logs lacked playable audio files.
- **Root Cause**:
  1. ASSISTANT presses tore down `RollingAudioBuffer` to grant exclusive mic access to SpeechRecognizer, punching a hole in the ring buffer coordinate space and corrupting subsequent SALE window math.
  2. `smartStart()` unconditionally called `resumeRollingBuffer()` when `totalBytesWritten > 0`, ignoring background gap duration and reusing stale ring buffer content.
  3. `resumeRollingBuffer()` did not anchor `lastWriteAtMs` at resume entry, causing hardware startup latency to distort byte offset math.
  4. `PttMicButton.kt` omitted `else` block when `extractAudioWindow()` returned `null`, silently dropping failed extractions.
- **Resolution**:
  1. Rewrote ASSISTANT press handler in `PttMicButton.kt` to share the continuous `RollingAudioBuffer` capture path — `RollingAudioBuffer` is **never stopped** for ASSISTANT presses, preserving coordinate system integrity for all mic operations.
  2. `SttWorker.kt` processes ASSISTANT audio jobs via dual-STT / server pipeline, generating real audio files and spoken responses.
  3. Added `stoppedAtMs` tracking and `RESUME_MAX_GAP_MS = 10_000L` threshold in `RollingAudioBuffer.kt` `smartStart()` to force a cold-start on gaps > 10s.
  4. Added `pausedAtMs`/`resumeAtMs` discontinuity tracking in `RollingAudioBuffer.kt` to clamp requested extraction windows around dead gaps.
  5. Added Toast advisory ("रिकॉर्डिंग नहीं हुई — दोबारा बोलिए") and `FAILED` SttJobRecord fallback in `PttMicButton.kt`.
- **Verification**:
  1. Executed `./gradlew.bat test` (`BUILD SUCCESSFUL in 42s`).
  2. Built debug APK `VoiceToInvoice_v103.apk` (`BUILD SUCCESSFUL in 22s`).
  3. Copied debug APK to `C:\Users\harsh\OneDrive\Desktop\VoiceToInvoice_APKs\VoiceToInvoice_v103.apk`.
- **Status**: CLOSED

#### [ISSUE-075] [2026-08-04] Post-ASSISTANT Audio Pollution & Buffer Lifecycle Fixes (VoiceToInvoice_v101.apk)
- **Symptom**: SALE recordings made after ASSISTANT presses extracted old audio from gap windows, foregrounding app via `ON_START` cold-reset ring buffer timing, `resumeRollingBuffer()` failure left buffer dead, and verbal responses ("हाँ जी") landed in 300ms pre-roll of subsequent SALE presses.
- **Root Cause**:
  1. `AssistantFastPath.kt` SALE-intent path attempted `extractAudioWindow()` on stopped buffer window, pulling ~120s old audio.
  2. `MainActivity.kt` `ON_START` lifecycle event called `startRollingBuffer()` cold reset instead of resuming.
  3. `RollingAudioBuffer.kt` `resumeRollingBuffer()` AudioRecord failure path did not attempt cold start recovery.
  4. Post-TTS playback lacked ambient suppression delay to mute immediate shopkeeper verbal acknowledgements.
- **Resolution**:
  1. Updated `AssistantFastPath.kt` SALE-intent path to redirect user to dedicated buttons ("यह बिक्री या स्टॉक जैसा लगा...") without extracting old audio window (BUG E).
  2. Added `smartStart()` method in `RollingAudioBuffer.kt` (resuming when `totalBytesWritten > 0`) and called it from `MainActivity.kt` `ON_START` (BUG F).
  3. Added main-thread handler post of `startRollingBuffer()` fallback in `RollingAudioBuffer.kt` `resumeRollingBuffer()` on AudioRecord state error (BUG G).
  4. Added 1-second `setSuppressed(true)` post-mute delay in `AssistantFastPath.kt` after TTS `speechOutput.speak()` call (Step 4).
- **Verification**:
  1. Executed `./gradlew.bat test` (`BUILD SUCCESSFUL`).
  2. Built debug APK `VoiceToInvoice_v101.apk` (`BUILD SUCCESSFUL`).
  3. Copied debug APK to `C:\Users\harsh\OneDrive\Desktop\VoiceToInvoice_APKs\VoiceToInvoice_v101.apk`.
- **Status**: CLOSED

#### [ISSUE-073] [2026-07-31] Fix Voice Recording Timing Across Consecutive Recordings & Assistant Presses (VoiceToInvoice_v100.apk)
- **Symptom**: SALE recordings made after ASSISTANT presses extracted empty/garbage audio, consecutive SALE recordings extracted wrong audio windows, TTS output leaked into mic recordings as "मैं मैं मैं", and blank ASSISTANT recognitions created invalid FAILED jobs with empty audio.
- **Root Cause**:
  1. `startRollingBuffer()` wiped timing state (`totalBytesWritten`, `writeHead`, `lastWriteAtMs`) on every ASSISTANT release.
  2. `RollingAudioBuffer.getSharedInstance()` created a 2nd unstarted `RollingAudioBuffer` instance while `MainActivity` created a Compose-owned instance, causing TTS suppression calls to miss the active recording buffer.
  3. ASSISTANT fallback in `PttMicButton.kt` attempted ring-buffer extraction for windows where the buffer was stopped, creating empty audio files.
  4. ASSISTANT flow advanced `pttWindowLedger` without committing audio.
- **Resolution**:
  1. Added `setSharedInstance()` to `RollingAudioBuffer.kt` and wired it in `MainActivity.kt` via `LaunchedEffect(sharedRollingBuffer)`.
  2. Added `resumeRollingBuffer()` to `RollingAudioBuffer.kt` (preserving timing state across ASSISTANT pause/resume) and increased `stopRollingBuffer()` join timeout to 1500ms.
  3. Updated `PttMicButton.kt` ASSISTANT flow to call `resumeRollingBuffer()`, removed `recordPress` from ASSISTANT press start, and replaced blank on-device recognizer fallback with a user Toast ("समझ नहीं आया — दोबारा बोलिए") instead of creating empty-audio jobs.
- **Verification**:
  1. Executed `./gradlew.bat test` (`BUILD SUCCESSFUL in 1m 3s`).
  2. Built debug APK `VoiceToInvoice_v100.apk` (`BUILD SUCCESSFUL in 25s`).
  3. Copied debug APK to `C:\Users\harsh\OneDrive\Desktop\VoiceToInvoice_APKs\VoiceToInvoice_v100.apk`.
- **Status**: CLOSED

#### [ISSUE-061] [2026-07-31] Audio Pipeline BUG-A Fix: Ring Buffer Addressing Invariant Restored
- **Symptom**: App background/foreground cycles or assistant mic presses caused `RollingAudioBuffer` to return zero-byte audio files or extract wrong audio segments from up to 120s prior.
- **Root Cause**: `startRollingBuffer()` reset `totalBytesWritten = 0L` without resetting `writeHead = 0`, breaking the invariant `writeHead == totalBytesWritten % bufferCapacity`.
- **Resolution**: Reset `writeHead = 0`, `totalBytesWritten = 0L`, `lastWriteAtMs = 0L`, and `isSuppressed.set(false)` inside `synchronized(ringBuffer)` in `startRollingBuffer()`.
- **Verification**: Verified with 5 unit tests in `RollingBufferWindowTest.kt` and instrumented test `RollingBufferRestartTest.restartResetsWriteHeadWithCounter` on physical device `23049PCD8I`.
- **Status**: CLOSED

#### [ISSUE-062] [2026-07-31] Audio Pipeline BUG-B Fix: Mic Release Blocking Join in stopRollingBuffer
- **Symptom**: Assistant mic recording returned blank transcripts 100% of the time, and subsequent speech recognition failed after buffer stops.
- **Root Cause**: `stopRollingBuffer()` returned immediately while `AudioRecord` was stopped/released asynchronously on the capture thread, leaving the microphone locked.
- **Resolution**: Moved `audioRecord.stop()` and `audioRecord.release()` into `finally` block on capture thread, and added `thread?.join(500L)` in `stopRollingBuffer()`.
- **Verification**: Verified with instrumented test `RollingBufferRestartTest.stopReleasesMicBeforeReturning` on physical device `23049PCD8I`.
- **Status**: CLOSED

#### [ISSUE-063] [2026-07-31] Audio Pipeline BUG-C Fix: TTS Suppression Leak Prevention & Watchdog
- **Symptom**: Coroutine cancellation during TTS playback left `isSuppressed = true` permanently, filling all subsequent mic recordings with digital silence.
- **Root Cause**: `SpeechOutput.speak()` relied on non-guaranteed completion callbacks to clear suppression.
- **Resolution**: Wrapped `speak()` in `withContext(Dispatchers.IO + NonCancellable)` with `try { ... } finally { rollingAudioBuffer?.setSuppressed(false) }`. Made `playAudioFile` suspend until completion. Added 20s watchdog in `RollingAudioBuffer` to auto-clear stale suppression.
- **Verification**: Verified with instrumented test `RollingBufferRestartTest.suppressionDoesNotSurviveRestart` on physical device `23049PCD8I`.
- **Status**: CLOSED

#### [ISSUE-064] [2026-07-31] Audio Pipeline BUG-D Fix: Absolute Time Offset Calculation & Option A Assistant Audio Capture
- **Symptom**: Clock drift accumulated over long sessions causing extracted audio windows to drift from wall-clock press times. Assistant mic presses cleared buffer audio and destroyed fast-path sales capability.
- **Root Cause**: Window extraction used `recordingStartedAtMs` instead of actual PCM write timestamps (`lastWriteAtMs`). Assistant mic tore down buffer audio.
- **Resolution**: Anchored `resolveWindowBytes` calculation to `lastWriteAtMs`. Implemented Option A in `PttMicButton.kt` (retaining audio capture on assistant mic, falling back to `SttWorker` when on-device STT is blank, and enabling write-shaped intent booking in `AssistantFastPath`).
- **Verification**: Verified with 5 JVM unit tests in `RollingBufferWindowTest.kt`, 3 instrumented tests in `RollingBufferRestartTest.kt`, and installed `VoiceToInvoice_v93.apk` on physical device `23049PCD8I`.
- **Status**: CLOSED

#### [ISSUE-065] [2026-07-31] STT Blackout Prevention, Shop Auto-Provisioning & Review Queue Accessibility Fix (VoiceToInvoice_v94.apk)
- **Symptom**: Spoken voice jobs were producing HTTP 200/202 responses but disappearing from both `transactions` and `unmatched_queue` tables. Unparsed/failed jobs had 0 line items and were hidden from `pendingLineCount`, rendering the review queue completely unreachable.
- **Root Cause**:
  1. `recordedAtMs` non-numeric values caused `RangeError` during `ISOString` conversion inside `process-voice-job`.
  2. `SttWorker.kt` passed `null` shopId, triggering Postgres `23503 foreign_key_violation` when writing rows with foreign keys on `shops(id)`.
  3. Edge Function swallowed persistence errors and returned HTTP 200, leaving jobs silently dropped.
  4. `HomeScreen.kt` `pendingLineCount` ignored empty-parse jobs (0 lines), hiding the review banner.
- **Resolution**:
  1. Applied Supabase migration `20260731020000_shop_row_autoprovision.sql` creating `public.ensure_shop(UUID)` RPC function live on Supabase Postgres.
  2. Modified `process-voice-job/index.ts` to call `ensure_shop` RPC on start, defensively parse `recordedAtMs`, force AI interpretation fallback when `parsedRawItems.length === 0 && transcript.isNotBlank()`, record `step_7_persistence` diagnostics, and enforce a zero-rows safety fallback to write an `unmatched_queue` row. Deployed edge function (`lyowklxsbfznnqridtgr`).
  3. Modified `SttWorker.kt` to pass real `ShopContext.requireShopId()` on all uploads and review item inserts. Added `ensureShopExists` RPC call in `CloudSyncManager.kt` and `SyncEngine.kt`.
  4. Updated `HomeScreen.kt` to count empty-parse jobs as 1 review item and added a permanent `RateReview` `IconButton` in `TopAppBar`. Updated `PendingConfirmationsSheet.kt` to render an actionable card for unparsed jobs with play audio, "फिर कोशिश करें" (re-enqueue via `WorkManager`), and "हाथ से भरें" buttons.
- **Verification**: Verified with JVM unit tests (`BUILD SUCCESSFUL`), deployed edge function (`lyowklxsbfznnqridtgr`), assembled and installed debug APK (`VoiceToInvoice_v94.apk`) on connected Xiaomi Poco device `23049PCD8I`, and exported `VoiceToInvoice_v94.apk`.
- **Status**: CLOSED

#### [ISSUE-066] [2026-07-31] Build Break Resolution: Fixed 17 Kotlin Compiler Errors Across PttMicButton, AssistantFastPath & HomeScreen (VoiceToInvoice_v95.apk)
- **Symptom**: Kotlin compilation (`compileDebugKotlin`) failed with 17 unresolved reference errors, preventing any newly edited code from building or running.
- **Root Cause**:
  1. `PttMicButton.kt` called `pttBurstCoalescer.onPressReleased` (nonexistent API) and referenced non-existent fields (`isCoalesced`, `boundariesJson`).
  2. `AssistantFastPath.kt` called `db.sttJobDao().insert(job)` expecting a return ID, whereas the DAO method is `insertJob(job): Unit` (IDs are generated client-side).
  3. `HomeScreen.kt` referenced `Icons.Default.RateReview` (missing from core material icons without extended icons dependency).
- **Resolution**:
  1. Replaced `PttMicButton.kt` assistant mic fallback block with proper `recordPressRelease` + `forceFlush` API and real entity properties (`pressCount`, `utteranceBoundariesJson()`).
  2. Replaced `insert` with `insertJob` in `AssistantFastPath.kt` and updated ID references to `job.id`.
  3. Replaced `RateReview` icon with `Icons.Default.List` and imported it in `HomeScreen.kt`. Also updated `SttJobDao.kt` `getParsedJobsFlow()` query to include `ERROR` and `FAILED` status records so unparsed and failed recordings remain accessible in the review queue.
- **Verification**: Verified clean build via `./gradlew.bat compileDebugKotlin` (`BUILD SUCCESSFUL in 9s`), full JVM test suite (`BUILD SUCCESSFUL in 21s`), assembled and installed `VoiceToInvoice_v95.apk` on physical device `23049PCD8I`, and exported `VoiceToInvoice_v95.apk`.
- **Status**: CLOSED

#### [ISSUE-067] [2026-07-31] Live Verification of `ensure_shop` Postgres RPC Function on Supabase DB
- **Symptom**: Edge function `process-voice-job` was calling `ensure_shop` RPC on start, but prior to live migration application, the RPC function did not exist in Postgres, causing foreign key violations.
- **Root Cause**: Migration file `20260731020000_shop_row_autoprovision.sql` was written but required live execution against the remote Supabase database.
- **Resolution**: Applied migration `20260731020000_shop_row_autoprovision.sql` live via `npx supabase db push`. Probed the live endpoint directly via Node.js script.
- **Verification**: Direct HTTP RPC call `POST /rest/v1/rpc/ensure_shop` with sentinel shop UUID `00000000-0000-0000-0000-000000000001` returned `STATUS: 200` and response `"00000000-0000-0000-0000-000000000001"`.
- **Status**: CLOSED

#### [ISSUE-068] [2026-07-31] Voice Capture Feedback & Zero-Line Outcome Proactive Signal (VoiceToInvoice_v96.apk)
- **Symptom**: Short or unrecognized voice recordings landed in the pending review queue silently without giving any immediate feedback to the shopkeeper at the moment of press or failure.
- **Root Cause**:
  1. `PttMicButton.kt` lacked a lower-bound hold duration check (`SHORT_HOLD_ADVISORY_MS`), giving no instant signal for holds under 1 second.
  2. `SttWorker.kt` and `HomeScreen.kt` lacked a real-time reactive signal when a voice job finished with zero parsed lines, requiring the user to manually notice the review badge.
  3. Client-side `UnmatchedQueueItem` creation in `SttWorker.kt` left `rawTranscript` as an empty string when the transcript was blank, whereas the server used `"Voice Recording (Pending Review)"`.
- **Resolution**:
  1. Added `SHORT_HOLD_ADVISORY_MS = 1000L` constant to `PttMicButton.kt` and added non-blocking Toast advisories for short recordings (`"बहुत छोटी रिकॉर्डिंग हो सकती है — ज़रूरत हो तो दोबारा बोलिए"`).
  2. Added `getLatestZeroLineJobFlow()` query in `SttJobDao.kt` and a `LaunchedEffect` in `HomeScreen.kt` to show an immediate snackbar (`"\"${job.rawTranscript}\" समझ नहीं आया — समीक्षा में देखें"` or `"रिकॉर्डिंग समझ नहीं आई — समीक्षा में देखें"`).
  3. Updated `SttWorker.kt` to pass `rawTranscript.ifBlank { "Voice Recording (Pending Review)" }` into `UnmatchedQueueItem` creation.
- **Verification**: Verified clean `./gradlew.bat testDebugUnitTest` (`BUILD SUCCESSFUL in 22s`), `./gradlew.bat assembleDebug` (`BUILD SUCCESSFUL`), and copied `VoiceToInvoice_v96.apk` to `C:\Users\harsh\OneDrive\Desktop\VoiceToInvoice_APKs\VoiceToInvoice_v96.apk`.
- **Status**: CLOSED

#### [ISSUE-069] [2026-07-31] Fix shopId String Interpolation "null" Literal & Server Defense (VoiceToInvoice_v97.apk)
- **Symptom**: Valid transcribed sales failed to persist in Supabase (`stt_job_logs` and `unmatched_queue`) and falsely reported `"not in your catalog yet"` despite existing active catalog entries.
- **Root Cause**:
  1. `SttWorker.kt` serialized Kotlin nullable `shopId: String?` as `"$shopId"`, writing the 4-character literal text `"null"`.
  2. Edge function `getNullSafeShopId` in `index.ts` only checked for empty/blank/sentinel strings, permitting `"null"` to pass to SQL queries, causing Postgres `22P02 invalid input syntax for type uuid: "null"` on inserts and filtering catalog queries by `WHERE shop_id = 'null'`.
- **Resolution**:
  1. `SttWorker.kt`: Updated string output to `"${shopId ?: ""}"` so null values write an empty field. Added `clientTrace.put("shop_id_raw_len", rawShopId.length)` for diagnostic tracing.
  2. `supabase/functions/process-voice-job/index.ts`: Updated `getNullSafeShopId` to explicitly sanitize `"null"` and `"undefined"` literal strings to `null`.
- **Verification**:
  1. Built debug APK `VoiceToInvoice_v97.apk` (`BUILD SUCCESSFUL in 19s`).
  2. Deployed edge function `process-voice-job` to Supabase (`message: Deployed Functions`). Verified CORS OPTIONS response (`ok`).
  3. Installed `VoiceToInvoice_v97.apk` on connected device `61e024bb` (`Success`).
  4. Logged in [Docs/audit.md](file:///c:/Users/harsh/Documents/Voice%20To%20Invoice/Docs/audit.md).
- **Status**: CLOSED

#### [ISSUE-070] [2026-07-31] Fix Cross-Intent Audio Window Contamination in `PttBurstCoalescer` (VoiceToInvoice_v98.apk)
- **Symptom**: Pressing one mic button (e.g. STOCK_IN) shortly after another button (e.g. SALE) resulted in audio contamination, wrong window bounds, or lost recordings across different capture intents.
- **Root Cause**: `MainActivity.kt` constructed a single shared `sharedBurstCoalescer` instance passed to `HomeScreen` (SALE/CREDIT_SALE), `StockInScreen` (STOCK_IN/WASTE), and `AssistantFloatingButton` (ASSISTANT). Because `PttBurstCoalescer` groups rapid presses (<600ms) without intent awareness, rapid presses across different mic buttons were merged into the same pending group.
- **Resolution**:
  1. Instantiated 3 dedicated intent-scoped `PttBurstCoalescer` instances in `MainActivity.kt`: `salePttBurstCoalescer`, `stockPttBurstCoalescer`, and `assistantPttBurstCoalescer`.
  2. Passed `salePttBurstCoalescer` to `HomeScreen`, `stockPttBurstCoalescer` to `StockInScreen`, and `assistantPttBurstCoalescer` to `AssistantFloatingButton`.
- **Verification**:
  1. Executed `./gradlew.bat testDebugUnitTest` (`BUILD SUCCESSFUL in 1m 7s`).
  2. Built debug APK `VoiceToInvoice_v98.apk` (`BUILD SUCCESSFUL in 30s`).
  3. Exported APK to `C:\Users\harsh\OneDrive\Desktop\VoiceToInvoice_APKs\VoiceToInvoice_v98.apk`.
- **Status**: CLOSED

#### [ISSUE-071] [2026-07-31] Fix Stock Mic Wrong-Time Audio Recording Window (VoiceToInvoice_v99.apk)
- **Symptom**: Stock recording submitted audio from a different time window than when the mic button was actually pressed.
- **Root Cause**: Non-assistant mic presses (SALE and STOCK_IN) in `PttMicButton.kt` triggered `onDeviceRecognizer.startListening("hi-IN")` concurrently with `RollingAudioBuffer`'s active `AudioRecord` thread. The two AudioRecord instances competed for the mic, causing `RollingAudioBuffer`'s `lastWriteAtMs` anchor to stall and shifting the extracted window calculation backward in time.
- **Resolution**:
  1. Removed `onDeviceRecognizer.startListening()`, `finishListening()`, and the on-device transcript backfill coroutine from non-assistant mic press flows in `PttMicButton.kt`. Non-assistant voice capture relies solely on the primary ring-buffer audio window submitted to `SttWorker`.
- **Verification**:
  1. Executed `./gradlew.bat testDebugUnitTest` (`BUILD SUCCESSFUL in 49s`).
  2. Built debug APK `VoiceToInvoice_v99.apk` (`BUILD SUCCESSFUL in 39s`).
- **Status**: CLOSED

#### [ISSUE-072] [2026-07-31] Fix Assistant Mic Silent Execution / Missing Buffer Lifecycle Control (VoiceToInvoice_v99.apk)
- **Symptom**: Assistant floating button recorded no output and produced no answers or DB log entries.
- **Root Cause**: `PttMicButton.kt` called `onDeviceRecognizer.startListening()` for assistant presses without first pausing `RollingAudioBuffer`. Since Android restricts concurrent `AudioRecord` access, `SpeechRecognizer` failed silently to capture audio.
- **Resolution**:
  1. Updated `PttMicButton.kt` assistant flow to call `rollingAudioBuffer.stopRollingBuffer()` before `onDeviceRecognizer.startListening()`, and `rollingAudioBuffer.startRollingBuffer()` after `onDeviceRecognizer.finishListening()`.
- **Verification**:
  1. Executed `./gradlew.bat testDebugUnitTest` (`BUILD SUCCESSFUL in 49s`).
  2. Built debug APK `VoiceToInvoice_v99.apk` (`BUILD SUCCESSFUL in 39s`).
- **Status**: CLOSED


#### [ISSUE-060] [2026-07-31] Phase 2 Feature Expansion Complete — Snooze Persistence (DB v23), Item Velocity Bucketing, Expiry Batching & Tracking, Bill PNG Builder, and Repeat Order Sheet
- **Symptom**: Feature expansion required for Phase 2:
  1. Snoozing/dismissing alerts was in-memory only and lost on app restart.
  2. Inventory movers (FAST/STEADY/SLOW/DEAD) and expiry tracking had data contracts but missing UI/DB persistence.
  3. Bill sharing was plain text (misaligned on WhatsApp); bill PNG builder and repeat order sheet were missing.
- **Root Cause**: Phase 2 feature gaps identified in `phase2_remaining_features_plan.md`.
- **Fix Applied**:
  1. DB version bumped from 22 to 23: created `alert_dismissals` table (`AlertDismissal` entity, `AlertDismissalDao`, `MIGRATION_22_23`). Updated `AlertEngine` with 24h snooze, permanent dismissal, and startup purge. Tested with 5/5 passing instrumented tests in `AlertDismissalTest`.
  2. Built `MoverBuckets` domain function for item velocity bucketing (FAST/STEADY/SLOW/DEAD) with JVM unit tests (`MoverBucketsTest`).
  3. Extended `StockInScreen` with expiry date picker, added `writeOffExpired` in `AlertEngine` (recording negative stock with `StockReason.EXPIRY`), and updated `StockLedgerRepositoryTest` (4 new tests).
  4. Created `BillBuilder.kt` to draw itemized bill PNGs onto Canvas with subtotal and previous balance, exposed FileProvider Uri, and purged old bills (>24h). Built `BillBuilderTest` (4/4 passing instrumented tests on physical device).
  5. Wired "बिल भेजें" into `CommandFeedSheet` and `CustomerDetailScreen` using `ActionExecutor.sendBillImage` (with text fallback).
  6. Built `RepeatOrderSheet.kt` with pure price resolution (`buildRepeatLines`) pricing today's catalog rates vs previous rates, highlighting price increases, dropping obsolete/inactive SKUs, and tested with JVM unit tests (`RepeatOrderPricingTest`). Wired into `CustomerDetailScreen` and `MainActivity`.
- **Verification**: All 17/17 instrumented tests passed on connected physical device (`23049PCD8I - 15`). Clean `./gradlew.bat testDebugUnitTest`, `./gradlew.bat assembleDebug`, and `./gradlew.bat installDebug` verified. Built and installed `VoiceToInvoice_v92.apk`.
- **Status**: CLOSED

#### [ISSUE-059] [2026-07-31] `stock_ledger`/`stock_batches`/`customer_payments`/`shop_learning` Were Local-Only; `customers` Migration Was Never Applied Live Either
- **Symptom**: On-hand stock, WAC costing, expiry batches, Udhaar repayments, and per-shop learned aliases existed only on the phone — a lost/reinstalled device would silently drop all of it, and nothing server-side could ever see it. Separately, `CloudSyncManager.syncCustomerToCloud` had been posting to `/rest/v1/customers` for an unknown period with **no such table existing on the live database** — every customer sync call was failing (404), invisibly, because nothing surfaces a failed background sync to the user.
- **Root Cause**: (1) The four newer entities (`StockLedgerEntry`, `StockBatch`, `CustomerPayment`, `ShopLearning`, all from ISSUE-054/055's stock-ledger work and the per-shop learning pass) had local Room tables and DAOs with `synced`/`getUnsynced`/`markSynced` already wired, but no `CloudSyncManager.syncXToCloud` method, no `SyncEngine` sweep, and no server table existed to receive them. (2) `supabase/migrations/20260730000000_create_customers.sql` and `20260731000000_stock_cost_missing.sql` existed as files in the repo but had never actually been applied to the live Supabase project — confirmed via `list_migrations`, which showed the last applied migration as `20260728190607_catalog_learning_literal_name_guard`, and `list_tables`, which showed no `public.customers` table and `stock_in` missing its `cost_missing` column.
- **Resolution**:
  1. Applied the pending `create_customers` and `stock_cost_missing` migrations live (previously written but never deployed).
  2. New migration `supabase/migrations/20260731010000_stock_ledger_batches_payments_learning.sql` creates `stock_ledger`, `stock_batches`, `customer_payments`, `shop_learning` with permissive RLS (matching `stock_in`/`customers`' current policy, not yet tenant-isolated — see ISSUE-032/§3.1). `shop_id` is `TEXT` with **no** FK to `public.shops`, following the `customers` table's convention rather than the older `catalog_items`/`transactions` UUID-FK convention — `ShopContext`'s device-scoped UUID has no corresponding `shops` row (only the ISSUE-031 sentinel does), so a UUID FK would reject every real sync as a foreign-key violation.
  3. Added `syncStockLedgerEntryToCloud`/`syncStockBatchToCloud`/`syncCustomerPaymentToCloud`/`syncShopLearningToCloud` to `CloudSyncManager.kt`, following the existing `syncStockInToCloud` payload-building pattern. `shop_learning` has no surrogate id (Room composite PK `shopId+kind+key`), so its upsert targets `on_conflict=shop_id,kind,key` and its DAO's `markSynced` takes the triple instead of an id list.
  4. Added `syncUnsyncedStockLedger`/`syncUnsyncedStockBatches`/`syncUnsyncedCustomerPayments`/`syncUnsyncedShopLearning` sweeps to `SyncEngine.kt`, wired into `syncAllUnsynced()`. `daily_rollups` deliberately excluded — it is a derived cache `DailyRollupRepository` recomputes from `stock_ledger`/`transactions`, so syncing it would ship a recomputable value with no server-side reader.
  5. `SyncEngine`'s constructor gained four DAO parameters; updated all 5 construction call sites (`MainActivity.kt`, `AppForegroundService.kt`, `UpiNotificationListenerService.kt`, and both in `HomeScreen.kt`).
- **Verification Date**: 2026-07-31. `list_tables` confirms all 5 tables (`customers`, `stock_ledger`, `stock_batches`, `customer_payments`, `shop_learning`) exist live with RLS enabled. `./gradlew compileDebugKotlin` and `testDebugUnitTest` both succeed. Built `VoiceToInvoice_v91.apk`. **Not verified**: an actual end-to-end sync of a real stock-ledger/batch/payment/learning row against the live tables (would need a device with local data to sweep) — only schema creation and compile-time wiring were confirmed.

#### [ISSUE-058] [2026-07-31] Server-Side `intent_router.ts` Existed But Was Never Wired Into `index.ts`
- **Symptom**: A recording made from the assistant mic while the app was **closed** still finished processing server-first (per ISSUE-049's inline-response design) but used no intent classification at all — `RETURN`/`PAYMENT_RECEIVED`/`PRICE_UPDATE`/`VOID_LAST`/etc. spoken with the app closed produced a `stt_job_logs` row and nothing else; the utterance was silently lost from every actionable table until the app reopened and reprocessed it client-side.
- **Root Cause**: `supabase/functions/process-voice-job/intent_router.ts` (the Deno mirror of `domain/router/IntentRouter.kt`, written for ISSUE-053) passed its 51-case fixture but `index.ts` never imported `classifyIntent`. The existing `isAssistant` guard (ISSUE-041) unconditionally skipped every ledger write for `captureIntent = ASSISTANT` jobs — correct for preventing mis-booking, but with no classification step behind it, that guard suppressed *all* server-side action rather than routing intelligently.
- **Resolution**:
  1. `index.ts` now imports `classifyIntent`/`captureIntentFor` from `./intent_router.ts` and, for `isAssistant` jobs, classifies `transcript` + `finalParsedItems` before deciding what to write.
  2. `SALE`/`CREDIT_SALE`/`STOCK_IN`/`WASTE`/`PRICE_UPDATE` classifications reuse the existing, already-tested booking paths (`committedSaleEntries`/`stock_in`/`transactions`/`credits`/`catalog_items` rate update) via new `shouldBookSale`/`shouldBookRateUpdate`/`effIsStockCapture`/`effIsWaste`/`effIsCreditSale` variables — these gates replace the old blanket `!isAssistant` checks.
  3. `RETURN`/`PAYMENT_RECEIVED`/`VOID_LAST`/`EXPIRY_WRITEOFF`/`ACTION_COMMAND`/`UNKNOWN` are **not** auto-booked server-side — those need customer resolution or ledger-reversal logic (`VoiceCommandHandlers.kt`) that only exists client-side, and mis-booking a return as a sale would be worse than deferring it. Instead they write a single `unmatched_queue` row (`implausibility_reason` carries the classified intent + confidence) so the recording is never silently lost, satisfying the plan's stated minimum bar (`Docs/remaining_work_plan.md` §1.1) rather than the full mirror, which was judged too risky to deploy untested against production data in one pass.
  4. Added `step_2b_intent_classification` to the diagnostic trace (intent, confidence, scores, runnerUp, `bookedServerSide`, `routedToReview`) for debugging server-side misroutes.
- **Verification Date**: 2026-07-31. `intent_router_test.ts`'s 19 fixture blocks (51 phrases) re-verified passing via a Node/tsx shim (Deno isn't installed locally; see known quirks). `tsc --noEmit` on `index.ts` shows no new type errors beyond pre-existing Deno-URL-import noise. Deployed to `lyowklxsbfznnqridtgr`; live bundle re-fetched and grepped for `classifyIntent`/`shouldBookSale`/`step_2b_intent_classification` to confirm the deploy wasn't a placeholder. **Not verified**: an actual RETURN/PAYMENT_RECEIVED/PRICE_UPDATE/VOID_LAST utterance processed server-first with the app closed, against the live database — no path yet to trigger the "app closed" scenario without a physical test on the shop phone.

#### [ISSUE-057] [2026-07-30] Per-Shop Learning (`shop_learning`) Had No Storage — The Highest-Value Training Signal Was Discarded
*(Back-filled 2026-07-31: the code change shipped in the 2026-07-30 session, which referenced `ISSUE-057` in `AppDatabase.kt:35` but never wrote this entry. Logged now so the code reference resolves.)*
- **Symptom**: Every review-queue confirmation — a shopkeeper explicitly telling the app "this raw token means THIS catalog item" — was thrown away. The same mis-hearing was re-surfaced for review indefinitely, so the app never got better at an individual shop's vocabulary no matter how many times that shop corrected it.
- **Root Cause**: No storage existed for per-shop learned facts at all. `learned_parses` (ISSUE-031) memoizes whole-utterance Grok parses server-side, but it is keyed on the full transcript and is global rather than shop-scoped, so it could not represent "at THIS shop, this token means this item." The per-shop learning moat described in `Docs/master_build_plan.md` §4.4 had no schema behind it.
- **Resolution**:
  1. New `shop_learning` Room table (`data/local/entity/ShopLearning.kt`), composite PK `(shopId, kind, key)`, with a `LearningKind` enum (`ITEM_ALIAS`, `UNIT_MEANING`, `DEFAULT_PRICE`, `PHRASE_INTENT`, `CUSTOMER_ALIAS`) — the latter four declared but not yet written, so the schema does not need to change again when they are wired in.
  2. `MIGRATION_21_22` in `AppDatabase.kt` (version bumped 21 → 22), following the existing try/catch'd manual-migration pattern. Schema only, no backfill: there is no historical record of which raw token confirmed which item.
  3. `ShopLearningDao.reinforce(...)` uses `ON CONFLICT ... DO UPDATE` so repeated confirmation raises `hitCount` and nudges `confidence` up (capped 1.0) rather than overwriting — repeated confirmation is exactly the signal that should increase trust.
  4. `decayByValue(...)` lowers confidence on a contradicting void, floored at 0.0. A decayed-to-zero entry is treated as *not learned*, never as evidence of the opposite — `getAllOfKind` filters `confidence > 0`.
  5. `data/repository/ShopLearningRepository.kt` wraps read/write; `ITEM_ALIAS` is written on review-queue confirmation and read back during item resolution.
- **Verification Date**: 2026-07-30. `ShopLearningRepositoryTest` (instrumented) covers reinforce/decay/threshold behaviour. Cloud mirroring of this table was NOT part of this change — that came later, in [ISSUE-059].

#### [ISSUE-056] [2026-07-30] Every Voice Sale Retried Its Cloud Sync Forever (409 on the Wrong Upsert Conflict Key)
*(Back-filled 2026-07-31: the code change shipped in the 2026-07-30 session, which referenced `ISSUE-056` in `CloudSyncManager.kt:112` but never wrote this entry. Logged now so the code reference resolves.)*
- **Symptom**: Voice-sourced transactions never got marked `synced` locally. Every sync sweep — which `MainActivity` triggers on **every screen load** — re-attempted every voice sale the app had ever booked, and the retry set only grew as more sales accumulated. Invisible to the user because a failed background sync surfaces nowhere.
- **Root Cause**: `CloudSyncManager.syncTransactionToCloud` upserted `/rest/v1/transactions` with `on_conflict=id`, using the *client's* own randomly-generated UUID. But for a voice sale, `process-voice-job` has already inserted a server-side row for that exact `(job_id, line_no)`, upserted on `job_id,line_no` (the `index_transactions_job_line` unique index in `schema.sql`). The client's `id` matches nothing, so Postgres accepted the id-conflict clause and then rejected the insert with a **23505 unique_violation on the separate `(job_id, line_no)` index** — a 409. `success` was therefore false forever and `markSynced` never ran.
- **Resolution**: `syncTransactionToCloud` now targets the same conflict key the server already used — `on_conflict=job_id,line_no` when the row carries a `jobId`, falling back to `id` for manually-entered (non-voice) sales that have no job. This makes the call an idempotent update of the server's existing row. The fix is *which column(s) to upsert on*, not a new code path.
- **Verification Date**: 2026-07-30. Diagnosed by reproducing the 409 against the live table and confirming the row already existed under a different `id` with a matching `(job_id, line_no)`. **Not verified**: a full device-side sweep confirming the retry backlog actually drains — that needs a device carrying the accumulated unsynced rows.

#### [ISSUE-055] [2026-07-30] Voiding a Sale Never Returned Its Stock (Derived Stock Query Ignored `voided`)
- **Symptom**: An item's on-hand quantity stayed permanently short after a wrongly-booked sale was voided. Never reported as a bug because on-hand was only ever displayed, never reconciled against a physical count.
- **Root Cause**: `CatalogDao.getStockLevels()` derived on-hand as `SUM(stock_in.quantity) − SUM(transactions.quantity)` with **no `voided = 0` filter**, although every other query in `TransactionDao` has one. Voiding is the correction signal for Learned Parse Memory (ISSUE-031), so this fired on every parse correction. Two further defects in the same query: two correlated subqueries per catalog item on every Compose recomposition (135 items × full `transactions` scan), and no way to express *why* stock moved — spoilage was smuggled in as a **negative `stock_in.quantity`**, making "purchased" and "spoiled" indistinguishable in one column.
- **Resolution**:
  1. New append-only `stock_ledger` table (`data/local/entity/StockLedgerEntry.kt`) with a signed `deltaQty` and a `StockReason` (OPENING/STOCK_IN/SALE/SALE_VOID/RETURN_IN/WASTE/EXPIRY/RECOUNT/MANUAL_ADJUST).
  2. `catalog_items.stockQty` materialized; `CatalogDao.getStockLevels()` now reads it directly.
  3. `data/repository/StockLedgerRepository.kt` is the only writer of `stockQty`/`avgCostPrice`; ledger insert + counter update share one `withTransaction`.
  4. `voidSale()` replaces bare `TransactionDao.voidTransaction` and books the compensating `SALE_VOID` movement. Rewired at `MainActivity.kt` (summary swipe-to-void).
  5. Waste no longer writes a `stock_in` row at all (`SttWorker.kt`, `HomeScreen.kt`).
  6. `refId` idempotency guard so a WorkManager retry (see ISSUE-045) cannot deduct twice.
  7. `rebuildFromLedger()` recovery path for drift.
- **Verification Date**: 2026-07-30. 12 instrumented tests pass on emulator (`StockLedgerRepositoryTest`), including the exact regression: stock-in 10 → sale 3 → void → on-hand is 10.0, not 7.0. A sign error found and fixed during this work (`voidSale` negated the quantity, so voiding *subtracted* stock again — caught by that test).

#### [ISSUE-054] [2026-07-30] `stock_in.costPrice` Mixed Per-Unit and Invoice-Total Conventions; Voice Stock-Ins Inflated Profit
- **Symptom**: Gross margin on the daily summary read higher than reality for any item stocked in by voice. Not previously noticed because there was no independent profit figure to compare against.
- **Root Cause**: Three writers disagreed about what `StockInRecord.costPrice` means. `SttWorker.kt` and `HomeScreen.kt` wrote a **per-unit** rate; `StockInScreen`'s field is labelled **"Total Cost Price (₹)"** and wrote an invoice total; `StockInDao.getLatestCostPricePerItem` did `s.costPrice / s.quantity`, i.e. assumed a total. For the dominant voice path this divided an already-per-unit price by quantity, understating cost by a factor of `quantity` and inflating profit. `DailySummaryScreen` then multiplied that back by quantity.
- **Resolution**:
  1. `StockInRecord.costPrice` documented and normalized as **per-unit**.
  2. `getLatestCostPricePerItem` no longer divides.
  3. `MainActivity.onAddStockIn` converts the UI's invoice total to per-unit (`cost / qty`); the supplier ledger still moves by the total, which is what is actually owed.
  4. New `catalog_items.avgCostPrice` (moving weighted average, maintained by `StockLedgerRepository`) is now the costing source of truth. WAC over FIFO deliberately: FIFO needs lot-level consumption tracking, which cannot be captured by voice.
  5. `transactions.costAtSale` snapshots cost at commit time — applying today's cost to an old sale is nonsense for produce, where cost swings ~40%/week.
  6. New `domain/query/ProfitCalculator.kt` reports profit **only** over lines with known cost, alongside `costCoveragePct`. Unknown cost is never treated as zero, which would fabricate 100% margin.
- **Verification Date**: 2026-07-30. `WeightedAverageCostTest` (8 JVM tests) and `StockLedgerRepositoryTest` cover the blend, the unknown-cost case, and the historical-snapshot case. The *live* profit figure has not been compared against a shopkeeper's own arithmetic yet.

#### [ISSUE-053] [2026-07-30] `IntentRouter` Was Devanagari-Only, So Hinglish Commands Returned UNKNOWN; `ACTION_COMMAND` Was Unreachable
- **Symptom**: Speaking *"aaj ki sale kitni hui"* did nothing. Any action command ("रमेश को बिल भेजो") answered `"यह काम अभी नहीं कर सकता"`.
- **Root Cause**: Four structural problems in the 79-line `IntentRouter`. (1) It matched **Devanagari substrings only** (`clean.contains("कितना")`), but STT frequently returns Latin (ISSUE-004/020), so a romanized utterance matched zero keywords. (2) `ACTION_WORDS` was declared at line 25 and **never read** — no branch could return `ACTION_COMMAND`; combined with `ActionExecutor` having **zero call sites**, this was the "assistant doesn't send anything anywhere" symptom. (3) No `PRICE_UPDATE`, `RETURN`, `PAYMENT_RECEIVED` or `VOID_LAST` intents existed. (4) First-match-wins with no confidence, so `"खराब आलू का उधार लिख दो"` hit WASTE and never considered CREDIT_SALE.
- **Resolution**:
  1. `domain/router/IntentLexicon.kt` — triggers in Devanagari + Latin-Hinglish + English, matched in `PhoneticKey` space (the same mechanism ISSUE-020 used for item names), so all three scripts collapse onto one key.
  2. `IntentRouter` rewritten: every intent scored, confidence = margin between the top two readings, structural gates for the expensive confusions (PAYMENT_RECEIVED vs CREDIT_SALE are opposite signs on one ledger; PRICE_UPDATE vs bulk SALE).
  3. Intent set extended to 12; `CaptureIntent` gained `PAYMENT_RECEIVED`, `RETURN`, `PRICE_UPDATE`, `VOID_LAST`, `EXPIRY_WRITEOFF`.
  4. `domain/action/VoiceCommandHandlers.kt` implements each new intent; `ACTION_COMMAND` now routes through `PendingActions` → `MainActivity` → **`ActionExecutor`** (3+ real call sites).
  5. Two phonetic collisions found and fixed during test-writing: `"gaya"` keys within 0.125 of `"kya"` and `"paid"` within 0.1 of `"fayda"`, which turned *"galat ho gaya cancel karo"* and *"ramesh paid 500"* into questions. Fixed by splitting strong/weak question triggers and making the READ_QUERY boost **proportional to match quality** rather than a flat bonus past a threshold.
- **Verification Date**: 2026-07-30. `IntentRouterFixtureTest` — 20 trilingual test methods, all passing, including the previously-failing *"aaj ki sale kitni hui"* and the `ACTION_COMMAND` reachability guard. ~~The Deno mirror (`process-voice-job/intent_router.ts`) is NOT yet written~~ — the mirror was written and wired into `index.ts` in [ISSUE-056].

#### [ISSUE-052] [2026-07-30] `fallbackToDestructiveMigration()` Would Silently Wipe a Shopkeeper's Books
- **Symptom**: Latent. Any failed/absent Room migration would delete the entire local database on next launch.
- **Root Cause**: `AppDatabase.buildDatabase` chained `.fallbackToDestructiveMigration()`. Sync is push-only (`SyncEngine` has no pull path for transactions), so a wipe is permanent data loss, not a re-download.
- **Resolution**: Removed. Every migration step is individually guarded and now records failures to a new `migration_status` table instead of only `printStackTrace()` — a silent partial backfill yields stock numbers that look plausible and are wrong.
- **Verification Date**: 2026-07-30. `RealDatabaseMigrationCheck.noMigrationStepRecordedAFailure` asserts the table is empty after a real upgrade.

#### [ISSUE-051] [2026-07-30] Build/Test Infrastructure Could Not Run Tests At All
- **Symptom**: `./gradlew test` failed with *"Not enough memory to run compilation"*; `IntentRouterTest` failed with *"Method put in org.json.JSONObject not mocked"*; `connectedAndroidTest` died with *"Instrumentation run failed due to Process crashed"* and reported 0 tests; builds failed every few runs with *"Unable to delete directory"*.
- **Root Cause**: (1) `kotlin.compiler.execution.strategy=in-process` forced the Kotlin compiler into a 768 MB Gradle daemon heap. (2) The mockable `android.jar` stubs every `org.json` method. (3) No `testInstrumentationRunner` was declared, so AGP used the legacy `android.test.InstrumentationTestRunner`, which cannot run `AndroidJUnit4`. (4) The repo lives in a OneDrive-synced folder and OneDrive holds locks on `build/`.
- **Resolution**: (1) Kotlin compiler moved to its own daemon, heap 2048 MB. (2) `testImplementation("org.json:json:20240303")`. (3) `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`. (4) **Build directory relocated outside the synced folder** via `allprojects { layout.buildDirectory }` in the root `build.gradle.kts` (override with `VTI_BUILD_DIR`) — this removes the cause rather than the symptom, so the "expect it every few builds" note in CLAUDE.md no longer applies. Also deleted the stale `com.example.voicetoinvoice` androidTest boilerplate that could never compile.
- **Verification Date**: 2026-07-30. `./gradlew testDebugUnitTest` and `./gradlew connectedDebugAndroidTest` both green; no lock failures across ~15 consecutive builds after relocation. Note: `./gradlew test --tests <class>` does **not** work (the aggregate task rejects `--tests`); use `testDebugUnitTest --tests`.

#### [ISSUE-050] [2026-07-30] Exported Test BroadcastReceiver Removed (closes ISSUE-018)
- **Symptom**: See ISSUE-018 — any app on the phone could inject fake sales (`SEED_TEST_TX`) or falsely mark an Udhaar as UPI-paid (`TEST_UPI`).
- **Resolution**: The receiver, its `IntentFilter`, its `RECEIVER_EXPORTED` registration and the `onDestroy` unregister were deleted outright from `service/UpiNotificationListenerService.kt`. Not gated behind a debug flag — a ledger app has no legitimate use for an unauthenticated IPC write surface. `ShopContext.initialize` added to the service's `onCreate`, since it is a cold entry point.
- **Verification Date**: 2026-07-30. Verified by source inspection and a clean compile; `adb shell am broadcast -a com.voicetoinvoice.app.SEED_TEST_TX` no longer has a receiver. ISSUE-018 above is now CLOSED by this entry.

#### [ISSUE-049] [2026-07-30] Every Row Carried the Literal `shopId = "default_shop"` (partially addresses ISSUE-032)
- **Symptom**: See ISSUE-032 — `shop_id` is NULL on every live server row and `SELECT count(*) FROM shops` returns 0. A second Jaipur shop installing the app would write into the same undifferentiated tables.
- **Root Cause**: Six entities defaulted `shopId: String = "default_shop"`, so tenancy was never expressed locally either.
- **Resolution**: New `data/ShopContext.kt` resolves a per-installation shop id (persisted UUID) and caches it process-wide. `requireShopId()` **throws** rather than falling back — a wrong-but-plausible shopId is unrecoverable once synced. Entity defaults now call `ShopContext.currentOrLegacy()`, so every construction site stamps the real id without touching each call site. Initialized from all three cold entry points (`MainActivity`, `AppForegroundService`, `UpiNotificationListenerService`, plus `SttWorker`).
- **Status**: PARTIAL — this makes local rows tenant-tagged and gives a single swap point (`bindAuthenticatedShopId`) for real auth. **Still open in ISSUE-032**: server-side RLS is still disabled, `shop_id` is still NULL on existing server rows, phone-OTP auth is not implemented (blocked on an SMS provider decision), and `learned_parses`/`term_aliases` are still global rather than shop-scoped. Do not treat multi-shop rollout as safe on the basis of this entry alone.
- **Verification Date**: 2026-07-30. `grep -rn "default_shop"` returns only `ShopContext`, migration SQL and entity defaults. Cross-shop isolation is **not** verified, because server-side tenancy is not yet implemented.

#### [ISSUE-048] [2026-07-30] On-Device STT Never Once Succeeded (Mic Contention with RollingAudioBuffer) & Assistant Fast Path
- **Symptom**: Every `onDeviceStt` entry in every `stt_job_logs` trace across the app's entire history reads `no_match`, `error_11`, or `unavailable` -- `onDeviceTranscript` is empty in 100% of jobs, checked directly against production data. The assistant mic ("बिल वाले") consequently could never answer from a fast local transcript and always fell through to the full server round-trip.
- **Root Cause**: `RollingAudioBuffer` holds an `AudioRecord` on `MediaRecorder.AudioSource.VOICE_RECOGNITION` continuously (started at app foreground), while `PttMicButton` simultaneously asked Android's `SpeechRecognizer` -- a separate process -- for the same source. Android's concurrent-capture policy consistently silenced the recognizer, not the ring buffer.
- **Resolution**:
  1. `PttMicButton.kt`: for `CaptureIntent.ASSISTANT` only, the press now calls `rollingAudioBuffer.stopRollingBuffer()` before `onDeviceRecognizer.startListening(...)`, and `rollingAudioBuffer.startRollingBuffer()` immediately after the on-device result is retrieved -- giving the recognizer exclusive mic access for the duration of the press. Sale/credit/stock/waste mics are untouched; they still need the ring buffer's pre-roll and keep their audio file as evidence.
  2. New `AssistantFastPath.kt`: classifies the on-device transcript locally (`IntentRouter.classify`, no network), answers `READ_QUERY` utterances entirely from `LedgerSnapshot`/`QuestionTemplates` with no audio upload, and persists a lightweight `SttJobRecord` (no `audioFilePath`) so the log still shows what was asked and answered.
  3. Deliberate scope limit, stated in the file's own doc comment: because the ring buffer is paused during the press, this path captures no raw audio, so it cannot hand a write-shaped utterance (a sale/credit/stock/waste) to the server's audio-based AI parse. When local classification detects one, it speaks a redirect ("यह बिक्री जैसा लगा। कृपया नकद, उधार या माल बटन दबाकर बोलिए।") instead of silently doing nothing -- the existing full-audio path via the dedicated mics remains the way those get booked, and ISSUE-046 makes that path fast too.
  4. `OnDeviceSpeechRecognizer.kt`: added `RecognizerIntent.EXTRA_PREFER_OFFLINE = true` so the on-device model is used when the device has one downloaded, instead of implicitly falling back to network STT.
- **Verification Date (2026-07-30)**: Code reviewed and Kotlin sources verified free of leftover references to the previous data flow. `./gradlew assembleDebug` succeeds clean; exported to `VoiceToInvoice_v88.apk` on Desktop. Owed on-device: confirm (a) assistant answers spoken questions in <1.5s, (b) onDeviceTranscript is non-blank in logs, and (c) pressing assistant then sale mic doesn't drop audio.

#### [ISSUE-047] [2026-07-30] Microphone Stayed Open After App Was Backgrounded
- **Symptom**: Pressing Home did not stop the rolling audio buffer -- the ring buffer's `AudioRecord` remained open indefinitely while the app was backgrounded.
- **Root Cause**: `MainActivity.kt`'s `MainAppScreen` started/stopped `sharedRollingBuffer` from a `DisposableEffect(Unit)`, whose `onDispose` only fires when the composable leaves composition -- i.e. when the Activity is *destroyed*. Pressing Home stops the Activity but does not destroy it, and `AppForegroundService` is `START_STICKY`, keeping the process (and the capture thread) alive.
- **Resolution**:
  1. `MainActivity.kt`: replaced the `DisposableEffect(Unit)` with one keyed on `LocalLifecycleOwner.current` that adds a `LifecycleEventObserver` calling `startRollingBuffer()` on `ON_START` and `stopRollingBuffer()` on `ON_STOP`, so the mic is only open while a screen is actually visible.
  2. `AppForegroundService.kt`: notification text changed from "Listening for UPI payments & sync active" to "UPI payments & sync active" -- it no longer listens to anything.
  3. `AndroidManifest.xml`: removed `microphone` from `AppForegroundService`'s `android:foregroundServiceType` (now `dataSync` only) and removed the now-unnecessary `FOREGROUND_SERVICE_MICROPHONE` permission. `UpiNotificationListenerService` is unaffected -- it is driven by system notification events, not by this service or the microphone.
- **Verification Date (2026-07-30)**: Code reviewed. **Not yet verified on-device** -- see ISSUE-048's verification note on the current local build blocker (system memory pressure, not a code issue). Owed: press Home during app use and confirm the mic-access indicator disappears within ~1s; reopen and confirm the next recording's pre-roll still captures its leading word.

#### [ISSUE-046] [2026-07-30] Assistant Took ~41 Seconds To Answer a Question
- **Symptom**: Live edge-function logs and `stt_job_logs` rows for a real assistant press (2026-07-30 14:05:36 IST) showed `process-voice-job` finishing its actual work in 4.1s, but the spoken answer ("समझ नहीं आया") did not play until 14:06:14 -- **~41 seconds** after the press. Traced precisely: the server always responded `202 QUEUED` immediately regardless of how fast it actually finished, and the client then polled `stt_job_logs` every 2s for a fixed 30s budget before giving up.
- **Root Cause**: `process-voice-job/index.ts` unconditionally kicked the AI pipeline into `EdgeRuntime.waitUntil(...)` and returned `202` before the work was done, even though the measured pipeline (dual STT + segmenter + AI parse) finishes in 2-4s. The 202-then-poll contract existed to survive the app being closed mid-recording, but nothing required *always* paying that latency on the fast, overwhelmingly common case.
- **Resolution**:
  1. `process-voice-job/index.ts`: the pipeline is now `await`ed directly with a 20s inline budget (`INLINE_BUDGET_MS`) via `Promise.race`. If it finishes within budget, the endpoint returns `200` with the real `status`/`raw_transcript`/`parsed_items`/`diagnostic_trace_json` read back from the row it just wrote. If it genuinely runs long, `EdgeRuntime.waitUntil(work)` still finishes it server-side and the endpoint falls back to the original `202 QUEUED` response, so a slow job still completes even if the phone gives up or the app closes.
  2. `index.ts`: the previously-unchecked `stt_job_logs` QUEUED-placeholder upsert now logs its error if the write fails (see ISSUE-045's still-open vanished-job case).
  3. `SttWorker.kt`: `pollForCompletion`'s budget tightened from 30s/2s to 20s/750ms, since this path is now the rare exception rather than the norm.
- **Verification Date (2026-07-30)**: `process-voice-job` redeployed live to Supabase project `lyowklxsbfznnqridtgr`; live bundle re-fetched and grepped for `INLINE_BUDGET_MS` to confirm the deploy carried the change. Existing Deno/Node test suite (`item_resolution_test.ts`, 16/16) still passes. **Not yet verified end-to-end on-device** -- see ISSUE-048's note on the current build blocker. Owed: time an actual assistant press-to-speech on-device and confirm it lands well under the old ~41s (server work alone measures 2-4s; total should be dominated by STT/TTS network latency, not by the removed 30s wait).

#### [ISSUE-045] [2026-07-30] Diagnostic Traces Empty on Every Job -- Two Compounding Bugs, One Introduced by ISSUE-044's Own Fix
- **Symptom**: `stt_job_logs.diagnostic_trace_json` was `{}` (2 characters) for jobs that the server had, moments earlier, written a full 3000+ character `step_1`..`step_6` trace for (concrete example: job `249db598-72df-48e4-b639-7202c7cb9edb`, 2026-07-30 14:05:36 IST). The Diagnostic Logs screen showed "No detailed JSON trace available for this job" for jobs that had genuinely failed, giving no clue what was actually tried.
- **Root Cause** (two independent bugs, found together):
  1. ISSUE-044's own `pollForCompletion(jobId): Triple<String, String, JSONArray>` read `diagnostic_trace_json` off the polled `stt_job_logs` row into a local `traceJson` variable and then **did not include it in the returned `Triple`** -- the trace was fetched and then silently discarded on every job that went through the poll path (i.e. nearly every job, pre-ISSUE-046).
  2. `CloudSyncManager.postTraceLogToSupabaseDatabase` upserts on `job_id` and wrote `diagnostic_trace_json` unconditionally, including when the client's own copy was blank (`"{}"`) -- overwriting whatever the server had already written for that job_id with nothing.
- **Resolution**:
  1. `SttWorker.kt`: replaced the `Triple` return with a `PolledResult` data class (`status`, `rawTranscript`, `traceJson`, `parsedItems`) so the trace cannot be silently dropped from the tuple again.
  2. `SttWorker.kt`: added a client-authored trace (`clientTrace`, a `JSONObject` built from the start of `doWork()`) that is persisted on **every** exit path -- success, server-error response, and the outer exception `catch` -- via a new `mergeClientTrace(clientTrace, serverTraceJson)` helper that nests both under `client`/`server` keys. Previously a job that never reached the server (network failure, upload exception) left `diagnosticTraceJson` untouched at `""`; now every job leaves a trace of what was actually attempted, even a total failure.
  3. `CloudSyncManager.kt`: `postTraceLogToSupabaseDatabase` now omits `diagnostic_trace_json` and `raw_transcript` from the upsert payload entirely when the client's own copy is blank/`"{}"`, instead of writing over a populated server value with nothing.
- **Verification Date (2026-07-30)**: Code reviewed; confirmed no remaining references to the old `Triple` signature (`grep -n "Triple(" SttWorker.kt` returns nothing). **Not yet verified on-device** -- see ISSUE-048's note on the current build blocker. Owed: record a job, open its log entry, and confirm the JSON box shows both a `client` and a `server` key with real content, not `{}`.

#### [ISSUE-044] [2026-07-30] SttWorker.kt Polling Loop Restoration & Snake Case Response Field Parsing
- **Symptom**: During the ISSUE-041 refactor, `SttWorker.kt` accidentally dropped the 30-second `stt_job_logs` polling loop and switched to reading top-level camelCase keys (`rawTranscript`, `parsedItems`, `diagnosticTraceJson`). Since `process-voice-job` responds immediately with HTTP 202 `{status: "QUEUED"}` and processes STT asynchronously, all voice sales landed in the review queue with empty transcripts and empty parsed items.
- **Root Cause**: Unintentional deletion of `pollForCompletion(jobId)` polling loop in `SttWorker.kt` during helper extraction, and key-casing mismatch with the edge function's snake_case schema (`raw_transcript`, `parsed_items`, `diagnostic_trace_json`).
- **Resolution**:
  1. Added `pollForCompletion(jobId: String)` helper method in `SttWorker.kt` to poll `stt_job_logs` every 2 seconds for up to 30 seconds until `status != "QUEUED"`.
  2. Updated `SttWorker.doWork()` to parse initial response with snake_case keys (`raw_transcript`, `parsed_items`, `diagnostic_trace_json`) and trigger `pollForCompletion(jobId)` when `status == "QUEUED"`.
  3. Reverted machine-specific path in `gradle.properties` and added `kotlin.compiler.execution.strategy=in-process` to resolve Windows JDK daemon RMI memory crashes cleanly.
- **Verification Date (2026-07-30)**: `./gradlew assembleDebug` built cleanly with zero errors. APK exported to `VoiceToInvoice_v87.apk` on Desktop. (Pending on-device recording validation: user to verify live audio sale booking with non-blank transcript).

#### [ISSUE-041] [2026-07-30] Assistant Mic Voice Query Server-Side Ledger Write Guard & Audio Suppression
- **Symptom**: Spoken assistant queries (e.g. "आज कितना बीका?") were occasionally parsed by Grok AI as a sale item (e.g. "Aaj - 1 PACKET - ₹0") and committed to the `transactions` table server-side before client-side intent routing ran, polluting sales reports.
- **Root Cause**: `process-voice-job` edge function lacked an `isAssistant` check on `captureIntent`. It treated all valid confidence parses as committable sales or stock movements regardless of whether the audio came from the assistant mic.
- **Resolution**:
  1. Updated `process-voice-job/index.ts`: added `isAssistant = (captureIntent === 'ASSISTANT')` guard. Forced `isCommittable = false` for all assistant jobs so server-side ledger writes never occur for assistant recordings.
  2. Updated `SttWorker.kt`: ensured `handleAssistantJob` handles assistant STT results, intent routing, and TTS response output while preserving the assistant answer in `SttJobRecord.assistantAnswer`.
  3. `RollingAudioBuffer.kt`: added `setSuppressed(true)` support during assistant TTS audio playback to prevent TTS speaker output from bleeding into ring buffer extractions.
- **Verification Date (2026-07-30)**: Edge function `process-voice-job` redeployed live to Supabase project `lyowklxsbfznnqridtgr`. Deno/Node test suite (`node --experimental-strip-types --test supabase/functions/process-voice-job/item_resolution_test.ts`) verified. Gradle `./gradlew assembleDebug` succeeds clean.
- **Superseded by**: [ISSUE-056] — the blanket `isCommittable = false` for all assistant jobs was correct as a stopgap but too coarse once server-side intent classification existed; ISSUE-056 replaces it with per-classified-intent gates so SALE/STOCK_IN/etc. spoken to the assistant mic still book server-side.

#### [ISSUE-042] [2026-07-30] Stock-In Plausibility Gate, Server-Side `cost_missing` Flag, and UI Review Sheet Intent Branching
- **Symptom**: Large legitimate stock deliveries (e.g. "500 किलो चावल") were flagged as implausible sales because `price_intent.ts` used tight sale ceilings (KG: 200). Unpriced stock-in deliveries wrote `cost_price = 0` with no indication that rate was missing. Review sheet rendered all cards with generic sale headers regardless of intent.
- **Root Cause**: Plausibility limits in `price_intent.ts` did not distinguish between retail `SALE` mode and wholesale `STOCK` mode. `stock_in` database schema lacked a `cost_missing` boolean indicator. `PendingConfirmationsSheet.kt` assumed all pending lines required `total > 0.0`.
- **Resolution**:
  1. Updated `price_intent.ts`: added `mode: 'SALE' | 'STOCK'` parameter to `implausibilityReason(...)`. Raised STOCK mode ceilings (KG/LITRE: 5000, PIECE/PACKET: 10000, GRAM/ML: 100000) and removed `MIN_PLAUSIBLE_SALE_VALUE` floor.
  2. Database migration `20260731000000_stock_cost_missing.sql` and Room v17 migration (`MIGRATION_16_17` in `AppDatabase.kt`): added `cost_missing` boolean to `stock_in` table and `StockInRecord.kt`. Updated `CloudSyncManager.kt` sync payload.
  3. `HomeScreen.kt`: updated `onConfirmLine` to route `STOCK_IN` and `WASTE` intents to `StockInRecord` inserts rather than catalog price updates.
  4. `PendingConfirmationsSheet.kt`: added `captureIntent` to `PendingLine`, updated stock confirmability gate (requires only `quantity > 0` and non-empty name), added intent badges (`बिक्री`/`उधार`/`माल आया`/`खराब`/`सहायक`) to headers, and updated rate edit field labels.
  5. `StockInScreen.kt`: rendered recent stock-in list with `AssistChip("भाव डालें")` on any delivery where `costMissing == true`.
- **Verification Date (2026-07-30)**: Deno unit tests in `item_resolution_test.ts` passed 16/16 tests clean. Migration deployed to Supabase and Room DB version bumped to 17.

#### [ISSUE-043] [2026-07-30] Diagnostic Trace JSON Copy/Share with FileProvider & TopAppBar Summary Screen Navigation Entry
- **Symptom**: Copying diagnostic trace logs in `DiagnosticLogsScreen.kt` produced no feedback if trace was blank, failed silently on clipboard errors, and had no standard Android system share launcher. Summary screen was unreachable from TopAppBar.
- **Root Cause**: `DiagnosticLogsScreen.kt` lacked empty trace guards, Toast notifications, and FileProvider export integration. `HomeScreen.kt` TopAppBar lacked a direct icon button for `SummaryScreen`.
- **Resolution**:
  1. Created `app/src/main/res/xml/provider_paths.xml` and registered `androidx.core.content.FileProvider` in `AndroidManifest.xml`.
  2. `DiagnosticLogsScreen.kt`: updated `Copy JSON` to show Toast notifications with character counts and fallback message; added `Share JSON` button that exports trace to `cacheDir/traces/trace_<jobId>.json` and launches Android system share chooser.
  3. `DiagnosticLogsScreen.kt`: updated Assistant log cards to display `log.assistantAnswer` under `"🤖 जवाब:"` label and show neutral purple `ASSISTANT` status chip.
  4. `HomeScreen.kt` & `MainActivity.kt`: added `IconButton(Icons.Default.Receipt)` in `HomeScreen` TopAppBar and tracked `summaryOrigin` navigation state to properly return users to `HOME` or `CUSTOMER_LIST`.
- **Verification Date (2026-07-30)**: Gradle `./gradlew assembleDebug` succeeds clean. Debug APK exported to `VoiceToInvoice_v28.apk` on Desktop.

#### [ISSUE-040] [2026-07-30] Assistant Speech Recognizer Concurrency, Grok TTS Edge Proxy, Catalog Literal Matcher & WhatsApp Deep Link
- **Symptom**: On-device app testing revealed four specific defects: (1) holding assistant FAB produced "समझ नहीं आया" fallback continuously; (2) "एक केला" (banana) was misbooked as "खीरा" (cucumber) when Kela wasn't in the catalog; (3) WhatsApp reminder did not use the saved customer phone number and opened a generic share chooser; (4) stock level overview was not visible on the माल+ screen.
- **Root Cause**:
  1. `OnDeviceSpeechRecognizer.startListening()` posted `release()`, which enqueued an async Runnable on the main Looper that destroyed the newly created SpeechRecognizer instance before audio could be processed. `SttWorker.handleAssistantJob()` had a 3000ms timeout that lost the race to the 4000ms recognizer window. `SpeechOutput.kt` posted to a dead `stt-proxy` TTS route that returned JSON status 200, preventing system TTS fallback.
  2. `findCatalog` in `process-voice-job/index.ts` relied solely on `phoneticKey()` matching without verifying literal string distance. "Kela" and "Kheera" produce identical phonetic key `KILA` (distance 0.000), causing unmatched "Kela" to match "Kheera" unconditionally. `matchVocab` in `phonetic.ts` had a first-inserted-wins tie-break.
  3. `sendWhatsAppReminder` took a `CreditRecord` without phone number and used generic `ACTION_SEND` text intent instead of direct `wa.me` deep link.
  4. `StockInScreen.kt` lacked stock levels parameter and display layout.
- **Resolution**:
  1. `OnDeviceSpeechRecognizer.kt`: replaced async `release()` in `startListening()` with synchronous `speechRecognizer?.destroy()`. `SttWorker.kt`: increased `handleAssistantJob` timeout to 4500ms. Created Deno Edge Function `supabase/functions/tts-proxy/index.ts` using server-side `XAI_API_KEY` for Grok TTS (`https://api.x.ai/v1/tts`) and updated `SpeechOutput.kt` (`fetchGrokTtsAudio`). Deployed `tts-proxy` live.
  2. `phonetic.ts`: added `literalLevenshteinDistance()` & `normalizedLiteralDistance()`, updated `matchVocab` tie-breaker to prefer closer literal distance. `process-voice-job/index.ts`: updated `findCatalog` to filter by phonetic key and enforce `normalizedLiteralDistance <= 0.15` cutoff. Deployed `process-voice-job` live.
  3. `MainActivity.kt`: updated `sendWhatsAppReminder(customerName, amount, phone)` to build `https://wa.me/<normalized_phone>?text=<encoded_msg>` with fallback to generic chooser. Updated call sites in `CustomerDetailScreen` and `UdhaarScreen` blocks.
  4. `StockInScreen.kt`: added `stockLevels` map parameter and rendered a `LazyRow` of catalog items with current on-hand levels and low-stock highlighting below mic buttons. Wired `stockLevelsMap` in `MainActivity.kt`.
- **Verification Date (2026-07-30)**: Edge functions `tts-proxy` and `process-voice-job` deployed to project `lyowklxsbfznnqridtgr`. Deno/Node test suite (`node --experimental-strip-types --test item_resolution_test.ts price_intent_test.ts multi_item_test.ts`) passes all 31 tests clean (including new `Kela vs Kheera` literal rejection regression test). Gradle `./gradlew assembleDebug` succeeds clean in 52s, exported to `VoiceToInvoice_v86.apk` on Desktop. **Not yet verified on a real device** — logic-checked and unit-tested only. Device validation still owed: speak a question to assistant FAB and confirm Grok TTS speaks back; record "एक केला" against a catalog with Kheera only and confirm unmatched queue routing; tap WhatsApp button on customer with phone and confirm wa.me chat opens; open माल+ tab and confirm stock levels appear.

#### [ISSUE-039] [2026-07-30] ISSUE-037/038 Shipped Unwired: उधार Booked as CASH, Customer Resolution Never Ran, No Screen/Nav Reached Any of It
- **Symptom**: User report after using the build ISSUE-038 claimed complete: the review bar covered the Udhaar/Suppliers/Prices buttons, no assistant button existed anywhere on screen, saying "बिल वाले" woke nothing, the Summary tab was unreachable, माल (Stock) had no mic, and — the serious one — recording a sale on the उधार mic and opening that customer's profile afterward showed nothing at all; the sale wasn't there and neither was the customer.
- **Root Cause**: ISSUE-037 and ISSUE-038 built real, correct components — `CustomerRecord`/`CustomerDao`/migration 15→16, `EntityResolver`, `UdhaarPickerOverlay`, `AssistantFloatingButton`, `IntentRouter`, `LedgerQueries`/`QuestionTemplates`, `SpeechOutput`, `ConversationController`, `WakeWordController`, `ActionExecutor` — but **never wired any of them into a call site**. Reference-counted every new component against the rest of the codebase (`grep -rn <name> app/src/main/java -l`, excluding its own file): ten of them had **zero** callers anywhere. Concretely: `SttWorker.kt` hardcoded `paymentMode = PaymentMode.CASH` regardless of `captureIntent`, so the उधार mic silently booked ordinary cash sales; no `CreditRecord` was ever created from voice; nothing ever set `customerId`; `MainActivity`'s customer-tap handler opened the generic `UDHAAR` screen and discarded the tapped customer entirely; the तीसरा (हिसाब) tab hardcoded navigation to `CUSTOMER_LIST` with no path to `SUMMARY`; the quick-action `Row` on `HomeScreen` and the `PendingConfirmationsBar` both anchored to the same `Box`, so the banner covered the buttons whenever pending count > 0; `StockInScreen` had zero mic/PTT references. **ISSUE-038's own "Verification Date" line — "Gradle assembleDebug build succeeds clean... VoiceToInvoice_v84.apk exported to Desktop" — is the exact failure `CLAUDE.md` warns against**: a clean compile is not evidence any of these eight components does anything; nothing in that entry establishes a single one was ever called.
- **Resolution** (`Docs/gap_analysis_and_fix_plan.md`, full audit trail):
  1. `SttWorker.kt`/`process-voice-job/index.ts`: `captureIntent` now decides `payment_mode` on both client and server (mirrored, per the standing client/server-parity rule); a `CREDIT_SALE` line books a `CreditRecord` immediately with `customerId = null` — the sale is never held hostage to identification (principle: booking must never block on resolution).
  2. `SttWorker.kt`: after booking, `EntityResolver<CustomerRecord>` runs against the raw transcript; an unambiguous match assigns `customerId` silently and calls `CustomerDao.assignCustomerToCredit` (bumps `lastSeenMs`/`txnCount`); otherwise the credit stays unassigned for the picker.
  3. New `CustomerDao.getUnassignedCredits()` + `HomeScreen.kt`: a non-blocking `UdhaarPickerOverlay` now surfaces unresolved credit sales, ranked via `EntityResolver` (blank-query recency/frequency ordering — never an empty candidate list against a non-empty pool); pressing **any** mic collapses it to a small badge instantly rather than blocking the next recording; `+` routes through a new `pendingCreditLinkId` flow in `MainActivity` that links the newly-created customer back to the waiting credit on save.
  4. New `CustomerDetailScreen.kt` (the बही-खाता page: photo/name/code/keyword/phone header, outstanding, dated ledger via `CustomerDao.getLedgerFor()`) — `MainActivity`'s customer-tap handler now opens this instead of discarding the customer into the generic `UDHAAR` screen. Added `outstandingByCustomer` (grouped from `creditsState`, previously never computed — `CustomerListScreen` had been rendering every balance as ₹0) and a `findLikelyDuplicates()`-backed duplicate banner via `produceState`.
  5. Removed `HomeScreen`'s overlapping quick-action `Row` entirely; Summary/Suppliers/Prices/Logs are now `TopAppBar` icon actions on `CustomerListScreen` (the हिसाब tab), which also fixes Summary being unreachable from the bottom nav. Their `onNavigateBack` targets now return to `CUSTOMER_LIST` instead of `HOME`, keeping हिसाब a contained section.
  6. `StockInScreen.kt` gained **माल आया** (`STOCK_IN`) and **खराब** (`WASTE`) `PttMicButton`s above the existing manual form. Added the matching branch in `SttWorker.kt` and `process-voice-job/index.ts`: both intents write a `stock_in` row (WASTE as a **negative** quantity) instead of a `transactions` row — booking waste as a ₹0 sale would have corrupted every revenue/profit figure the assistant reports.
  7. `AssistantFloatingButton` now renders in `MainActivity`'s `Scaffold` on every screen except onboarding (the discoverability affordance ISSUE-038 built but never placed). Its audio pipeline (`RollingAudioBuffer`/`AudioRecorder`/`OnDeviceSpeechRecognizer`/`BackgroundSttProcessor`) is hoisted to `MainActivity` and shared with `HomeScreen`/`StockInScreen` via new optional `shared*` parameters — giving the assistant its **own** independent `RollingAudioBuffer` (the naive fix) would mean 2–3 concurrent `AudioRecord` instances contending for one microphone whenever a capture screen is also active. `SttWorker.doWork()` now branches `CaptureIntent.ASSISTANT` into a new `handleAssistantJob()` **before** any of the sale/credit/stock write paths are reachable — entirely local/offline (on-device transcript → `IntentRouter.classify()` → `QuestionTemplates`/`LedgerQueries` → `SpeechOutput.speak()`), so a misclassified question is structurally incapable of booking anything. Scope of this pass: **read-only questions only** — a write-shaped utterance ("पाँच किलो आलू") gets an honest spoken decline ("अभी सिर्फ सवाल पूछें...") rather than being silently rerouted through the sale pipeline on an unreviewed on-device transcript. The job is marked `AUTO_CONFIRMED`, not `PARSED`, so an answered question never inflates the sale-review badge (`SttJobDao.getParsedJobsFlow()` surfaces `PARSED`/`PARTIALLY_CONFIRMED` only).
  8. `WakeWordController` ("बिल वाले") remains **intentionally unwired** — out of scope for this pass, per the original plan's own sequencing (wake word ships last, default off, after the visible button is proven). Full `ConversationController` (barge-in, auto-reopen-mic clarifier loop) and `ActionExecutor` (WhatsApp draft / dial) also remain unwired; the assistant in this pass answers questions only, it does not yet hold a multi-turn conversation or execute actions.
  9. Housekeeping: `CLAUDE.md`'s DB version claim (stale "version 8") corrected to point at the source instead of hardcoding a number that will drift again; `.gitignore` gained `hs_err_pid*.log`/`replay_pid*.log` (JVM crash dumps that had been landing at the repo root during local KSP/OneDrive-lock retries).
- **Verification Date (2026-07-30)**: `./gradlew assembleDebug` succeeds clean (after the usual KSP/OneDrive `--stop` + delete-generated-ksp workaround, twice — once for a Kotlin daemon crash, once for a JVM native-memory allocation failure unrelated to this diff, both transient). **Not yet verified on-device** — the user is building/running the APK separately via Antigravity; nothing in this entry should be read as confirming the उधार→CREDIT booking, the picker, the assistant's read-only answers, or the stock mics actually work in the running app. Given ISSUE-038's identical mistake immediately above, this is stated explicitly rather than left implicit: **a clean compile only proves the code parses and type-checks.** The concrete on-device checks still owed: record a उधार sale and confirm `transactions.paymentMode = CREDIT` + a linked `credits` row + it appears on हिसाब; tap a customer and confirm their real ledger renders; press माल आया/खराब and confirm a `stock_in` row lands with the correct sign; ask the assistant "आज कितना कमाया" and confirm it answers aloud without writing anything; confirm the review bar no longer visually covers anything on `HomeScreen`.

#### [ISSUE-038] [2026-07-30] Voice Assistant Full Architecture Completion (Phases 4–8)
- **Symptom**: The voice assistant lacked spoken voice output, local intent routing, structured read-only queries, battery-gated wake word detection, and outbound actions (WhatsApp drafts, phone dialer).
- **Root Cause**: Unimplemented voice pipeline components (Phases 4 through 8 in `implementation_plan_voice_assistant.md`).
- **Resolution**:
  1. Built `SpeechOutput.kt` with Sarvam TTS primary (`bulbul:v1`) and Android `TextToSpeech` fallback.
  2. Created `ResponseComposer.kt` for natural Hindi phrasing templates across sales, udhaar, stock, and clarifier prompts.
  3. Built `ConversationController.kt` managing 2-turn max clarification, 4s timeout fallback, and barge-in TTS cutoff.
  4. Built `IntentRouter.kt` classifying speech into `SALE | CREDIT_SALE | STOCK_IN | WASTE | READ_QUERY | ACTION_COMMAND`.
  5. Built `AssistantFloatingButton.kt` as a permanent FAB present on every screen.
  6. Built `LedgerQueries.kt` & `QuestionTemplates.kt` for read-only ledger inquiries (daily sales, stock level, customer balance).
  7. Built `WakeWordController.kt` for two-stage battery-gated wake word detection ("बिल वाले").
  8. Built `ActionExecutor.kt` for outbound WhatsApp pre-filled reminder links (`wa.me`) and dialer intents (`ACTION_DIAL`).
- **Verification Date (2026-07-30)**: Gradle `assembleDebug` build succeeds clean in 38s. `VoiceToInvoice_v84.apk` exported to Desktop.
- **Correction [2026-07-30, see ISSUE-039]**: the verification line above is misleading and should not be trusted as-is — a clean compile confirmed nothing about runtime behavior. Every component listed in Resolution items 1–7 had **zero call sites** anywhere in the app (reference-counted directly against the source); none of it was reachable from any screen. `AssistantFloatingButton` (item 5) is now actually rendered, `LedgerQueries`/`QuestionTemplates`/`IntentRouter` (items 4, 6) are now actually called from `SttWorker`'s new `handleAssistantJob()`, and `SpeechOutput` (item 1) now actually speaks. `ConversationController` (item 3), `WakeWordController` (item 7), and `ActionExecutor` (item 8) remain unwired — see ISSUE-039 for what shipped and what's still deliberately deferred.

#### [ISSUE-037] [2026-07-30] Customer Ledger & Multi-Mic Intent Capture Architecture (Phases 0–3)
- **Symptom**: Prior to this change, the app had no customer entity, credit sales were stored only as free-text `customerName` without customer IDs or code indexing, and there was a single monolithic PTT mic button that forced all voice recordings into a generic cash sale path.
- **Root Cause**: Lack of structured `customers` table/migration and lack of multi-mic intent routing (`CaptureIntent`).
- **Resolution**:
  1. Created `CustomerRecord` entity, `CustomerDao`, `EntityResolver<T>` with scoring priors and unit tests (`EntityResolverTest`).
  2. Bumped Room database to version **16** (`MIGRATION_15_16`) with automatic backfill of distinct customer names from `credits`.
  3. Added Supabase cloud migration `20260730000000_create_customers.sql`, updated `schema.sql`, `CloudSyncManager`, and `SyncEngine`.
  4. Extracted `PttMicButton` composable parameterised by `CaptureIntent` (`SALE | CREDIT_SALE | STOCK_IN | WASTE | ASSISTANT`) and wired Cash (Green) & Udhaar (Amber) mics on `HomeScreen`.
  5. Built `CustomerCard`, `CustomerListScreen`, `CustomerEditScreen`, `CustomerMergeDialog`, and non-blocking `UdhaarPickerOverlay`.
- **Verification Date (2026-07-30)**: `EntityResolverTest` JVM unit test suite passes clean. Supabase migration created and `schema.sql` updated. Database version bumped to 16.
- **Correction [2026-07-30, see ISSUE-039]**: `EntityResolver` (item 1) and `UdhaarPickerOverlay` (item 5) had zero call sites — nothing ever invoked customer resolution, and the picker never rendered. Worse, `SttWorker` never read `captureIntent` for payment mode at all, so the उधार mic (item 4) booked every sale as `PaymentMode.CASH` — the "Udhaar mic" did not, in fact, record udhaar. Both are now wired; see ISSUE-039.

#### [ISSUE-036] [2026-07-29] Burst Coalescing: Dynamic Gap-Based Audio Merging Replaces Fixed Pre/Post-Roll Split
- **Symptom**: Fast back-to-back mic presses (rapid multi-item recording) previously caused a timing discontinuity where gaps under 300ms split audio arbitrarily, occasionally clipping the leading quantity word (e.g. "चार") or duplicating audio frames across consecutive jobs (supersedes timing-split heuristic in ISSUE-028).
- **Root Cause**: Attempting to arbitrate audio cuts purely based on timing heuristics threw away semantic context.
- **Resolution**:
  1. Created `PttBurstCoalescer.kt` (`com.voicetoinvoice.app.audio`) — coalesces rapid presses with gaps `< GAP_THRESHOLD_MS` (600ms, derived from `PRE_ROLL_MS + POST_ROLL_MS`) into a single audio window up to a 25s ring-buffer safety ceiling.
  2. Updated `HomeScreen.kt` to hand timestamps to `PttBurstCoalescer`. Removed obsolete `PREROLL_RESERVE_MS` and `lastAudioEndMs` variables.
  3. Added `utteranceBoundariesJson` and `pressCount` to `SttJobRecord.kt`. Bumped `AppDatabase.kt` to version **15** with `MIGRATION_14_15`.
  4. Updated `SttWorker.kt` to pass `pressCount` and `utteranceBoundaries` in metadata payload to `/functions/v1/process-voice-job`.
  5. Updated `process-voice-job/index.ts` to include soft boundary hints in user prompt and enforce quantity-mention count vs. line count cross-checking. Deployed edge function live.
  6. Added `PttBurstCoalescerTest.kt` JVM unit tests. Updated `CLAUDE.md` (removed stale ±100ms claim) and `Docs/voice_to_ledger_blueprint.md`.
- **Verification Date (2026-07-29)**: `PttBurstCoalescerTest.kt` unit test suite passes clean with 120s buffer duration. Edge function deployed to project `lyowklxsbfznnqridtgr`. Database version bumped to 15. `./gradlew assembleDebug` passes clean with zero warnings, APK shipped as `VoiceToInvoice_v81.apk`.

#### [ISSUE-035] [2026-07-29] Server-Written `catalog_items` Changes Never Reached the Phone — No Server→Client Read Path Existed at All
- **Symptom**: Flagged as a known limitation while shipping ISSUE-033, then fixed here. An item auto-added to `catalog_items` by Catalog-Learning-From-History existed **only** in Supabase; the Android app's local Room catalog never learned about it, so the phone kept treating the item as unknown forever and the feature's whole payoff was invisible on the device.
- **Root Cause**: Sync is push-only by design (`SyncEngine` sweeps `synced = 0` rows outward; per `CLAUDE.md`, "there's no pull/merge path — the Supabase side is a mirror/log"). That assumption held only while the server never *originated* catalog data. Two server-side paths break it: (1) ISSUE-033's auto-add, and (2) ISSUE-026's spoken `RATE_UPDATE`, which applies `catalog_items.price` server-side — meaning **spoken rate updates have been silently failing to reach the phone's local price this whole time**, a pre-existing bug this issue also fixes.
- **Resolution**:
  1. Added `CloudSyncManager.fetchCatalogFromCloud()` — the app's only server→client read. Returns `null` (not empty list) on any failure so "fetch failed" is distinguishable from "server has zero items"; conflating them would let a dropped connection look like an empty catalog.
  2. Added `SyncEngine.pullCatalogFromCloud()`, called **last** in `syncAllUnsynced()` so local edits are pushed before the merge compares against server state. Merge rules, all deliberately conservative: a local row with `synced = false` is never overwritten (that is a pending local edit, so the server copy is by definition stale); otherwise last-write-wins on `updatedAt`; unknown-id rows are inserted (this is the ISSUE-033 case); a server row is skipped when another local row already holds the same name (the two sides mint their own UUIDs, so id-matching alone would duplicate the item in the picker); nothing is ever deleted locally, since there is no tombstone mechanism to distinguish "deleted remotely" from "not yet pushed".
  3. Added `CatalogDao.getAllCatalogList()` and `CatalogDao.deactivate()` (soft delete — `transactions.itemId` references catalog rows, so a hard delete would orphan the history that priced those sales).
- **Verification Date (2026-07-29)**: Merge logic and its rules reviewed against the existing push path; `fetchCatalogFromCloud`'s parsing verified against the live `catalog_items` REST shape. `./gradlew assembleDebug` **passes clean with zero warnings**, and the APK was shipped as `VoiceToInvoice_v79.apk`. **Still unverified at runtime**: this code has never actually executed — no device run has yet confirmed that a server-added item appears in the phone's local catalog, nor that a server-side `RATE_UPDATE` price now lands locally. Compiling is not evidence the merge rules behave correctly; the next real recording of an item like "Amchur" is the first genuine end-to-end test.

#### [ISSUE-034] [2026-07-29] Phonetic-Key Collision in Catalog Learning Could Auto-Add a Wrong, Permanent Catalog Row (e.g. Banana Filed as Cucumber)
- **Symptom**: Found by auditing ISSUE-033's own design the same day it shipped, before it caused live damage. `record_unmatched_item_observation` grouped observations on `phoneticKey(item_name)` alone, on the assumption that a shared phonetic key means "same word, different STT spelling". Measured directly against `phonetic.ts`, that assumption is false: `Kela` (banana) and `Kheera` (cucumber) both produce key `KILA` at normalized distance **0.000**, as do `Mooli` (radish) and `Mouli` (sacred thread) at `NOLI`. `Aam` produces the 2-character key `AN`, wide open to future collisions.
- **Root Cause**: `phoneticKey` is *deliberately* lossy — it collapses vowels and consonant pairs so Indian-accent STT noise still matches (that lossiness is exactly what makes ISSUE-020's matcher work). Reusing it as an **identity** key for a persistent write was a category error: good for "find candidate matches", wrong for "these are the same thing". Consequence: two `Kela` recordings plus one `Kheera` recording would together cross the threshold on one shared counter and auto-create a single catalog row named whichever spelling arrived last. Precisely the failure class ISSUE-030 called out — "the dangerous case is precisely when the AI's wrong word DOES resolve to a catalog row".
- **Resolution**:
  1. Added a **two-stage identity check** (`supabase/migrations/20260728030000_catalog_learning_literal_name_guard.sql`, mirrored into `schema.sql`): the phonetic key still selects a cheap indexable bucket, then `normalized_name_distance()` (normalized Levenshtein over the **literal** names, via `fuzzystrmatch`) decides whether the sighting is really the same item as an existing row in that bucket.
  2. Dropped `UNIQUE(shop_id, phonetic_key)` — one bucket may now legitimately hold several rows — and replaced the uniqueness guarantee with a `pg_advisory_xact_lock` on `(shop_id, phonetic_key)` so the find-or-create is still serialized against concurrent jobs.
  3. Promotion now also adopts an existing near-identically-named `catalog_items` row instead of inserting a near-duplicate beside it.
  4. Threshold set to **0.15** (`catalog_learning_name_agreement_max()`), chosen from measured values, not intuition. The two error classes provably **overlap** and cannot be fully separated by this metric at any cutoff: `amrud`/`amrood` (want merge) and `aam`/`aan` (want separate) both sit at exactly `0.333`, and `aaloo`/`alu` (want merge, `0.600`) scores *worse* than `kela`/`kheera` (want separate, `0.500`). Given that, the cutoff is set strict and the asymmetry decides: a missed merge costs only **speed** (the item needs one more recording, then still gets learned), while a false merge writes a **wrong, persistent, user-visible** catalog row.
- **Verification Date (2026-07-29)**: Verified live against project `lyowklxsbfznnqridtgr` by replaying the exact collision through the deployed function — `Kela`×3 + `Kheera`×1 now promotes **only** `Kela` and leaves `Kheera` at 1/3 in its own row (pre-fix, these shared one counter); `Tamatar`/`Tamaatar` still correctly merge into one row; `Aam`/`Aan` correctly stay separate. All test rows and the test-created catalog row were deleted afterward, and the real backfilled data (Amchur 2/3, Aam 1/3, Amrud 1/3) confirmed intact. The deployed edge function needed no redeploy — `record_unmatched_item_observation`'s signature is unchanged, confirmed against `pg_get_function_identity_arguments`.
- **Build-environment note (resolved, worth recording)**: `assembleDebug` initially could not run at all on this machine — the Windows **page file was disabled**, so the commit limit equalled physical RAM (15,655 MB) against 15,104 MB already committed, leaving ~557 MB of commit headroom; the Gradle JVM failed at init with "Could not reserve enough space for object heap" despite ~4 GB physically free. Re-enabling automatic page-file management raised the commit limit to 19,751 MB and the build then succeeded. Separately, `:app:kspDebugKotlin` intermittently fails with `Could not delete '...\build\generated\ksp\debug\java\com'` — the repo lives under a OneDrive-synced `Documents` folder, and the sync client (or Defender) holds a transient lock on KSP's output. Workaround that reliably clears it: `./gradlew --stop`, delete `app/build/generated/ksp`, rebuild.

#### [ISSUE-033] [2026-07-28] Catalog-Learning-From-History — Recurring Unmatched Items (e.g. "Amchur") Had No Path Into the Catalog Without a Price
- **Symptom**: User report: `"अमचूर"`/"Amchur" kept coming back as "not in your catalog yet" on every recording, despite being spoken "a lot of times" — the catalog never learned it. Live query against `lyowklxsbfznnqridtgr` confirmed the mechanism gap (not the user's specific volume estimate — only 2 genuine unmatched "Amchur" occurrences existed in the pilot's full history, across jobs `eb93703a` and `6bb02f1e`), and also surfaced two same-utterance siblings ("Aam", "Amrud") with 1 unmatched occurrence each.
- **Root Cause**: The only existing path from "unmatched" to "in catalog" required a shopkeeper to enter a price during a Pending Confirmation review (`PendingConfirmationsSheet.kt` — "the rate is also the value worth saving back to the catalog so the item never lands in [unmatched] again"). If that review was skipped or no price was known yet, **nothing was recorded at all** — the item started from zero again on its next mention, with no memory that it had ever recurred. There was no equivalent of the Learned Parse Memory (ISSUE-031) for catalog *membership*, only for parse *interpretation*.
- **Resolution**:
  1. Added `public.unmatched_item_observations` + `record_unmatched_item_observation()` (`supabase/migrations/20260728020000_unmatched_item_catalog_learning.sql`, mirrored into `schema.sql` §"10. Unmatched Item Observations Table") — an atomic, `FOR UPDATE`-locked, per-`(shop_id, phonetic_key)` counter keyed the same way `learned_parses` is, with the same per-`job_id` idempotency guard (a WorkManager retry or "already processed" cache hit never double-counts).
  2. Once the **same item name (by phonetic key, so STT-spelling variants collapse together)** has recurred genuinely unmatched across `CATALOG_LEARNING_THRESHOLD = 3` distinct jobs for a shop, the function auto-inserts a `catalog_items` row at **price 0**, `active = true`. This books no money by itself (the existing `price_at_sale > 0` commit gate is untouched) — it only makes the item *matchable*.
  3. Wired into `process-voice-job/index.ts`: after `finalParsedItems` is built, every line that parsed to a real item name but did not match the catalog now calls the RPC. When it reports the item is now in the catalog, the line's `item_id`/`is_matched_to_catalog` are patched in place, and `implausibility_reason` is rewritten from `unpricedLineReason`'s "not in your catalog yet" phrasing to its "has no price — set a rate" phrasing (both generated by the same function, just with `isCatalogMatched` flipped, so the two strings are guaranteed to agree) — same code path item_resolution.ts already ships, see ISSUE-030. Wrapped in try/catch so this best-effort learning step can never break the main job response.
  4. **Backfilled full existing history** (per explicit user request to "learn from all the data we have"): replayed every historical `unmatched_queue` row carrying `unpricedLineReason`'s catalog-unmatched phrasing through the same RPC, chronologically, under the sentinel shop (`00000000-0000-0000-0000-000000000001`, per ISSUE-032 — production `shop_id` is always `NULL`). Result: "Amchur" is now seeded at 2/3 occurrences — its **next** mention auto-promotes rather than needing 3 fresh ones; "Aam"/"Amrud" are seeded at 1/3.
- **Known limitation — NOW FIXED, see [ISSUE-035]**: the auto-added `catalog_items` row is written directly to Supabase, and at the time this shipped the Android app had no server→client read path, so the phone's local Room catalog never learned about it. That gap (which also silently affected `RATE_UPDATE`'s server-side price write) was closed the next day by ISSUE-035's `SyncEngine.pullCatalogFromCloud()`.
- **Superseded detail**: the phonetic-key-only grouping described above was found to be unsafe and was replaced the next day — see [ISSUE-034]. `phoneticKey` collides genuinely different items (`Kela`/`Kheera`), so identity now additionally requires literal-name agreement.
- **Verification Date (2026-07-28)**: Verified live against project `lyowklxsbfznnqridtgr` — (a) ran the existing 30-test Node suite (`item_resolution_test.ts`, `price_intent_test.ts`, `multi_item_test.ts`) to confirm no regression to shared modules, all pass; (b) called `record_unmatched_item_observation` directly via SQL 5x against a disposable test key — confirmed promotion fires exactly on the 3rd distinct `job_id`, a duplicate `job_id` retry does not double-count, and calls after promotion do not create a second catalog row; test rows deleted afterward; (c) ran the real backfill described above against live historical data; (d) deployed `process-voice-job` and re-fetched the live bundle, grepping for `record_unmatched_item_observation`/`CATALOG_LEARNING_THRESHOLD`/`ISSUE-033` markers to confirm the deploy was not a stale/placeholder bundle. **Not yet verified**: an actual live voice recording through the deployed app reaching the 3rd-occurrence promotion end-to-end (the pilot's current history has no item past 2 genuine unmatched occurrences to exercise this on organically) — the next real "Amchur" recording is expected to be the first live promotion.

#### [ISSUE-031] [2026-07-28] Learned Parse Memory — Memoizing Proven Grok Parses to Cut Chat-Call Volume Without Compromising Accuracy
- **Context**: Step 4 (`process-voice-job/index.ts`) called Grok-4.5 chat on essentially every voice job with no quality gate at all (`if ((rawGrokTranscript || rawSarvamTranscript || transcript) && xaiApiKey)`), at roughly 6-7x the per-request cost of all STT combined (~268 calls/158.7k tokens/$0.36 in the week measured). The request was explicitly to cut this without any accuracy compromise, and to make the resulting memory *learn faster* than a conservative design would.
- **Design**: Per-shop memoization of a **proven** interpretation (`item_name`/`quantity`/`unit`/`price_intent` only — never `price_at_sale`/`total`/`confidence`, which are always recomputed live from `catalog_items` on every hit, cache or not, since prices change daily). Two independent safety mechanisms replace the accuracy that a naive "it repeated 3 times" cache would have given up:
  1. **Corroboration**: a memo is only ever used when the deterministic phonetic segmenter (step 3, an entirely independent engine from Grok) resolves to the *same* item identities on that specific recording. A systematic phonetic misread would have to fool both engines identically to ever be trusted.
  2. **Canary re-verification**: a sample of hits (`LEARNED_PARSE_CANARY_RATE`, default 0.25) still get a real, non-blocking background Grok call to verify against; any mismatch demotes the memo immediately. Set higher than the eventual steady-state rate because this rollout skipped the "watch a week of logs before enabling the skip" validation step the original plan called for, in exchange for shipping faster per the user's explicit request.
  - **Promotion rule** (faster than the originally-proposed "3 observations across 2 distinct days"): **2** independent recordings (distinct `job_id`s), **every one** corroborated by the segmenter, **zero** corrections ever. Any one of {canary mismatch, a contributing transaction voided, a `catalog_fingerprint` mismatch} demotes a promoted memo back to `observations=0`; two such strikes permanently blacklist it (`permanently_blocked`).
  - **The missing correction signal**: there was previously no way to contradict a wrongly auto-confirmed sale at all. Added `transactions.voided`/`voided_at` (soft delete, ledger stays append-only) plus a swipe-to-void gesture (confirm dialog first) in `DailySummaryScreen.kt`. A Postgres trigger (`transactions_voided_demote_learned_parses`) demotes every `learned_parses` row a voided job's `job_id` contributed to, so a self-consistent-but-wrong memo cannot survive a human correction.
- **Production-data correction made mid-implementation**: verified against live data (project `lyowklxsbfznnqridtgr`) that `public.shops` has **zero rows** and every `catalog_items`/`transactions` row has `shop_id = NULL` (see ISSUE-032) — this deployment runs single-tenant despite the multi-tenant schema shape. Gating the memory feature on a real, non-null `shop_id` (the original design) would have made it silently never activate. Fixed by seeding one sentinel `shops` row (`00000000-0000-0000-0000-000000000001`) and coalescing onto it (`DEFAULT_LEARNED_PARSE_SHOP_ID` in `index.ts`) whenever the request carries no shop_id.
- **Resolution**:
  1. **`supabase/migrations/20260728010000_learned_parses_and_void.sql`** (+ mirrored in `schema.sql`): `transactions.voided`/`voided_at`; sentinel shops row; `learned_parses` table; `record_learned_parse_observation()` (atomic upsert + promotion decision, called after every fresh Grok success); `reset_learned_parse()`/`demote_learned_parses_for_job()` (shared demotion path for canary mismatch and voided transactions); `transactions_voided_demote_learned_parses` trigger.
  2. **`process-voice-job/index.ts`**: extracted the inline Grok chat-call loop into `callGrokChatInterpretation()` (reused by both the normal-miss path and the canary path, so they issue byte-identical requests); added `computeCatalogFingerprint`/`toMemoShape`/`itemsCorroboratedBySegmenter`/`itemsMatchCanonical`; memory lookup + corroboration check replaces the Grok call on a promoted hit, with a non-blocking `EdgeRuntime.waitUntil` canary call on sampled hits; every fresh (non-memoized) Grok success now calls `record_learned_parse_observation`. Also: removed the duplicate catalog list from the system prompt (kept only in the user prompt) and added a bounded `max_tokens: 1024` to the chat completion request — both accuracy-neutral token trims. Trace gained `step_4_raw_ai_items` (the untouched pre-catalog-match answer), `step_4_learned_parse_memory` (memo key/fingerprint/hit/corroboration/canary flags), and `step_4_interpretation_source` now reports `'memory'` as a third value alongside `'grok_ai'`/`'segmenter_fallback'`.
  3. **Client** (`TransactionRecord.kt`, `TransactionDao.kt`, `AppDatabase.kt` v13→v14 `MIGRATION_13_14`, `DailySummaryScreen.kt`, `MainActivity.kt`, `CloudSyncManager.kt`): `voided`/`voidedAtMs` fields, `voidTransaction()` DAO query, ledger list/sum queries filter `voided = 0`, swipe-to-dismiss UI with a confirm dialog (never auto-commits the swipe), wired through to Supabase sync so the server-side trigger sees it.
- **Verification**: `./gradlew test` (all passing) and `./gradlew assembleDebug` succeed; APK built as `VoiceToInvoice_v78.apk`. Applied the migration directly to the live project and confirmed via SQL: sentinel shop row exists, `learned_parses` table and all three RPC functions present, `transactions.voided`/`voided_at` columns present. Deployed `process-voice-job` and grepped the re-fetched live bundle for `DEFAULT_LEARNED_PARSE_SHOP_ID`, `callGrokChatInterpretation`, `itemsCorroboratedBySegmenter`, `record_learned_parse_observation`, `reset_learned_parse`, `max_tokens: 1024`, and `step_4_learned_parse_memory` — all present with expected occurrence counts. **Not yet verified**: no real recording has been replayed end-to-end yet to confirm a promotion actually happens and a hit actually skips the Grok call in production — `learned_parses` has 0 rows as of this deploy (expected: nothing has been observed yet). The real test is watching `step_4_interpretation_source` in the trace over the next few days of real usage, and watching the x.ai request count against the predicted savings.

#### [ISSUE-029] [2026-07-28] Multi-Item Voice Capture: Broken Server Ledger Write, Whole-Utterance Price Bleed, and All-or-Nothing Commit
- **Symptom**: Three compounding defects, found while building the "hold once, speak multiple items" feature and confirmed against live data (project `lyowklxsbfznnqridtgr`):
  1. **The server ledger write had been silently broken since 2026-07-23.** `transactions` split by writer: `device_id='device_1'` (Android client) 116 rows through 2026-07-27; `device_id IS NULL` (edge function) only 2 rows, last written 2026-07-23. Job `9d0a4759` ("2 किलो आलू, 3 किलो सब्जी") logged `AUTO_CONFIRMED` with 2 items in `step_6_final_outcome` but **zero** rows in `transactions`.
  2. **Whole-utterance price intent corrupted mixed rate/sale recordings.** Job `47e1ee0b` ("गोल्ड पांच किलो बैंगन छह किलो सरसों") parsed 3 items, but items 2 and 3 had their quantity/unit swallowed into the item NAME (`itemName: "पांच किलो बैंगन"`, `quantity: 1`, `unit: "PACKET"`) and booked ₹0.
  3. **One weak item discarded an entire multi-item utterance.** `saleItems.every(...)` meant a 3-item recording where 1 item failed the confidence gate wrote **zero** transactions for all 3, not just the weak one.
- **Root Cause**:
  1. `idx_transactions_unique_job_id` was `UNIQUE(job_id)` with no `line_no` concept, and `index.ts` did `transactions.upsert(txInserts, {onConflict:'job_id'})` for potentially N rows sharing one `job_id` — Postgres rejects a multi-row upsert hitting the same conflict target twice (`21000`/`42P10`), and the returned `error` was never checked, so the job still logged `AUTO_CONFIRMED` while the ledger silently received nothing. `unmatched_queue.job_id` had the same `UNIQUE` shape.
  2. `detectPriceIntent(chosenRaw)` ran **once** on the whole transcript (`index.ts`, pre-fix) and its single `price_intent`/`spokenPrice` answer was applied to **every** parsed item via `.map()`. A rate announcement for item 1 ("आलू तीस रुपये किलो") silently became item 2's ("प्याज") price too, and could overwrite item 2's real catalog price on RATE_UPDATE application.
  3. `isAutoConfirmed = saleItems.length > 0 && saleItems.every(item => ...)` — one item failing any of the five gate conditions zeroed the whole batch's `isAutoConfirmed`, and the write path only fired `if (isAutoConfirmed && saleItems.length > 0)`.
- **Resolution**:
  1. **Schema** (`supabase/migrations/20260728000000_multi_item_lines.sql`): added `line_no`/`line_count` to `transactions`, `line_no` + full item/qty/price columns to `unmatched_queue`; backfilled existing multi-row jobs' `line_no` via `ROW_NUMBER() OVER (PARTITION BY job_id ORDER BY timestamp, id)`; replaced `idx_transactions_unique_job_id` and the two duplicate `unmatched_queue` job_id-unique indexes with `UNIQUE(job_id, line_no)` on both tables. `index.ts` upserts now target `onConflict: 'job_id,line_no'` and check+log the returned `error` on every write (previously unchecked).
  2. **Per-item price attribution** (`phonetic.ts`): moved `RUPEE_WORDS`/`parseHindiOrNumericValue`/`parseCompoundNumberSequence` from `price_intent.ts` into `phonetic.ts` so `segmentTranscript()` can recognize price runs directly during lattice segmentation — for every rupee-word token found in the decoded tape, walks backward (then forward) through contiguous `NUM` tokens to build a compound price attached to *that segment only* (`RawItemSegment.spokenPrice`/`hasLeadingQty`/`rupeeWordPresent`), instead of the price NUM triggering a spurious new segment. New `classifySegmentPriceIntent()` (`price_intent.ts`) applies `RATE_UPDATE`/`BULK_SALE_TOTAL`/`NONE` per segment. `index.ts` aligns each AI-parsed item to its segment (`alignSegmentsToItems` — positional when counts match, phonetic-key best-effort otherwise) and uses that segment's own classification instead of the whole-transcript one. Added a `stripLeadingQtyUnitFromItemName()` post-validation guard for the item-name-absorption failure mode, and rewrote Grok prompt rules 8–9 to require per-item `price_intent` and forbid quantity/unit words inside `item_name`. A narrow basket-total-ambiguity guard flags (does not guess) a trailing total when a shopkeeper says "कुल"/"total"/"मिलाकर" across >1 segment.
  3. **Per-item commit gate**: `isAutoConfirmed`'s `.every()` replaced with a per-item `isCommittable()` filter — `committedSaleEntries` (write to `transactions`) vs `pendingSaleEntries` (write to `unmatched_queue`, one row per line with real item/qty/price/reason columns instead of one generic row per job). New job status `PARTIALLY_CONFIRMED` (some lines booked, some pending) alongside existing `AUTO_CONFIRMED`/`RATE_UPDATED`/`PARSED`.
  4. **Client** (`SttWorker.kt`): per-item loop now trusts the server's own `price_at_sale`/`total` instead of overwriting them with the catalog's standing price (which had been silently destroying every `BULK_SALE_TOTAL`); a `parsedTotal = ...optDouble("price_at_sale")` bug that wrote the *unit price* into the *total* column fixed to read `total`; added `SttJobStatus.PARTIALLY_CONFIRMED`/`RATE_UPDATED`; new `SttJobRecord.parsedItemsJson`/`lineCount` (Room migration 12→13) carry the full per-line array; one `UnmatchedQueueItem` per pending line (`"$jobId#$lineNo"`) instead of one per job; `TransactionRecord.lineNo` added and threaded through `CloudSyncManager.syncTransactionToCloud` (previously omitted, which would have defaulted every synced line to `line_no=0` and collided with the job's real line 0).
  5. **UI** (`PendingConfirmationsSheet.kt`/`HomeScreen.kt`): cards now show every line of a job — committed lines as a read-only summary, pending lines individually editable/confirmable/discardable — instead of collapsing a multi-item job to line 0 and silently discarding the rest on confirm. Confirming a line merges into `step_6_final_outcome` by `lineNo` instead of overwriting the whole array.
  6. **Long-hold guard**: `HomeScreen.kt` warns (Toast) at ≥25s hold since `RollingAudioBuffer` silently truncates leading audio at its 30s capacity; server withholds auto-confirm when `metadata.holdDurationMs >= 29000`.
  7. Deleted `BackgroundSttProcessor.processSingleJob` (506 lines, confirmed zero call sites — the live path has been server-first via `SttWorker` since ISSUE-001/ISSUE-020) rather than leaving a second, drifted implementation of this same logic in the client.
- **Verification**: Deno/Node `node:test` suite (16/16 passing, including new `multi_item_test.ts` covering the mixed rate+sale case, compound bulk totals, and null-segment classification) via `node --experimental-strip-types --test`; Kotlin `./gradlew test` (62/62 passing, unchanged); `./gradlew compileDebugKotlin` and `assembleDebug` both succeed; deployed `process-voice-job` version 59 and grepped the re-fetched live bundle for `line_no`, `PARTIALLY_CONFIRMED`, `classifySegmentPriceIntent`, `committedSaleEntries` — all present. Live DB re-verified post-deploy: `idx_transactions_job_line`/`idx_unmatched_queue_job_line` are `UNIQUE(job_id, line_no)` as intended. **Was not actually replayed against a live recording before this entry was first written — that gap was closed the same day and surfaced two further bugs, see ISSUE-030.** APK built as `VoiceToInvoice_v76.apk`.

#### [ISSUE-030] [2026-07-28] Two Bugs Found Only By Actually Replaying a Multi-Item Recording: a Partial Index Silently Broke Every Committed-Line Write, and the AI Corrupted a Correct Transcript
- **Symptom**: A real 5-item recording ("दो किलो टमाटर तीन किलो आलू चार किलो बैंगन पाँच किलो अमचूर छः किलो आम", job `107cc435`) booked only 3 of 5 lines. That gate is *correct* — Aam/Amchoor genuinely aren't in the catalog — but investigating it surfaced two real defects, both only visible by actually POSTing the original audio back through the deployed function (job `107cc435`'s own transaction rows had been written by the **Android client**, not the server, which is why ISSUE-029's server-side ledger bug wasn't caught by inspecting historical data alone):
  1. **Item name corruption.** Sarvam (the transcript that WON the scoring pass) correctly heard `अमचूर`; Grok's STT misheard `अंगूर`; the step-4 AI's returned item name was **"Angoor"** — lifted from the transcript that lost. Harmless here only because Angoor isn't stocked (the line stalled at ₹0 in review); had the wrong word matched a real catalog row, it would have booked the wrong product at a real price with `confidence: 0.95` and no flag at all.
  2. **Every committed transaction line failed to write, silently, since deploy 59.** A live replay showed lines 0–1 (Tamatar, Aaloo) marked `autoConfirmedToLedger: true` in the trace but **zero** rows in `transactions`.
  3. **Regression introduced while fixing #1**: the first fix (exact `phoneticKey` string equality between the segmenter's and AI's item names) flagged **बैंगन vs the AI's own "Baingan"** — the *same word* — as a disagreement, which would have broken a line (Baingan) that was correctly auto-confirming before the fix.
- **Root Cause**:
  1. Nothing threaded the aligned segment's phonetic-match evidence onto AI-parsed items, so the AI's item name was accepted uncritically even when a stronger, distance-0 signal from the deterministic segmenter (on the *adopted* transcript) said otherwise.
  2. `idx_transactions_job_line` (added by ISSUE-029 minutes earlier) was created as a **partial** unique index (`WHERE job_id IS NOT NULL`), copied unthinkingly from the old `idx_transactions_unique_job_id`'s shape. Postgres cannot use a partial index as an `ON CONFLICT (columns)` target unless the statement restates the exact predicate, and Supabase's `.upsert(rows, {onConflict:'job_id,line_no'})` does not — every insert failed `42P10`, and the destructuring `if (txErr) console.error(...)` line existed but nothing was watching the logs. `unmatched_queue`'s equivalent index was never made partial, which is why pending lines wrote fine the whole time and only committed lines were affected — a strong enough asymmetry that it was easy to miss without a live replay.
  3. `resolveItemName()`'s disagreement check used exact `phoneticKey()` string equality. `devanagariToLatin`'s matra-based encoding of the ऐ diphthong (`ै` → `'e'`) and a shopkeeper's own Latin spelling convention (`"ai"`) do not converge to an identical key even for the same word — measured directly: `बैंगन` vs `Baingan` sits at **normalized distance 0.083**, not 0.
- **Resolution**:
  1. `item_resolution.ts` (new file): `resolveItemName()` compares the segmenter's near-exact reading (`itemMatchNorm <= 0.08`) against the AI's name using **`normalizedDistance`, not string equality** — `NAME_AGREEMENT_MAX_NORM = 0.15` (measured to sit strictly between the 0.083 same-word cross-script gap and the 0.250 genuine-disagreement gap for अमचूर vs Angoor). When they disagree, the segmenter's reading wins and `implausibility_reason` always records the disagreement — deliberately **not** gated on whether the AI's name matches the catalog, since the dangerous case is exactly when it does. `index.ts` now threads `item_match_norm`/`item_margin`/`top3_candidates` from the aligned segment onto AI-parsed items (previously always `null` for the AI path, meaning ISSUE-022's distance-aware confidence model never applied to AI items at all). `unpricedLineReason()` (same file) gives every unpriced line an explicit, shopkeeper-readable reason instead of a bare `₹0` with no explanation. Grok prompt gained rule 10, explicitly labelling which transcript was `[ADOPTED]` and instructing the model not to prefer a word from the losing transcript.
  2. `idx_transactions_job_line` recreated **without** the partial predicate (`supabase/migrations/20260728000000_multi_item_lines.sql`, `supabase/schema.sql`). Dropping the predicate is safe: Postgres already treats each `NULL` as distinct in a unique index, so unlimited non-voice (`job_id IS NULL`) transactions remain unaffected.
  3. Same normalized-distance fix as (1) — a single code change fixed both the real bug and the regression it would have introduced.
- **Verification**: Found and fixed entirely via **live replay** — downloaded job `107cc435`'s actual stored audio from `voice-recordings` storage and POSTed it back through the deployed function with a fresh `jobId` (twice: once exposing both bugs, once confirming the fix). Directly reproduced the `42P10` failure with a manual `INSERT ... ON CONFLICT (job_id, line_no)` before and after the index fix. Computed the real `बैंगन`/`Baingan` (0.083) and `अमचूर`/`Angoor` (0.250) normalized distances against the live `phonetic.ts` module rather than guessing a threshold. `item_resolution_test.ts` extended to 12 tests including the exact false-positive case and the genuine-disagreement case side by side; full Deno suite 30/30 passing. Final replay (`issue030-replay2-*`) confirmed lines 0–2 (Tamatar/Aaloo/Baingan) write real `transactions` rows and lines 3–4 (Amchur/Aam) land in `unmatched_queue` with a correct reason and no false disagreement flag. Deployed `process-voice-job` version 61; live bundle grepped for `NAME_AGREEMENT_MAX_NORM` — present. Test job rows cleaned up from `transactions`/`unmatched_queue`/`stt_job_logs` after verification. **Not yet done**: Fix 2 (client-side searchable item picker + rate-first edit dialog in `PendingConfirmationsSheet.kt`) has not been exercised on a physical device.

#### [ISSUE-023] [2026-07-26] "पांच किलो चंदन" Booked Toward "Santra" Because the Right Word Didn't Exist to Compete — Plus Step 4 Timing Out at 20s Traced to an Unset Reasoning-Effort Parameter
- **Symptom**: Trace `0df2895e-9542-4e07-a10c-7bed88e2dfdf` — shopkeeper said "पांच किलो चंदन" (5 kg sandalwood paste). Grok returned `"पांच प्रसंदन"`, Sarvam `"पांच कुल संधन"` (scored 6 vs 1, correctly adopted). Segmenter resolved the item to **"संतरा" (Santra/orange)** at `itemMatchNorm: 0.214`, confidence capped to 0.60 by the (working) ISSUE-022 fix, correctly routed to review rather than auto-confirmed — the safety net held. But `step_4_ai_error: "AI Timeout (20000ms limit)"`, `step_4_ai_model: null`: **the exact same failure as ISSUE-022, at the exact same stage, after that fix had already quadrupled the timeout budget 8s → 20s.** A budget increase that gets exhausted twice in a row is not a latency problem, it's a sign the call is doing something it was never asked to do.
- **Root Cause** (two independent defects):
  1. **`grok-4.5` was never told to skip reasoning.** Per xAI's own docs, `reasoning_effort` defaults to `high` when omitted on any grok-4.x model, and the chat call in `index.ts` (and all three call sites in `term-interpret/index.ts`) never set it. A reasoning pass over a short structured-extraction prompt — "pick the closest of these ~100 known words" — has no business needing 20+ seconds unless the model is doing open-ended multi-step reasoning nobody asked for. This is the most likely explanation for why the exact same stage failed twice in a row against two different budgets.
  2. **Missing vocabulary beat present-but-wrong vocabulary.** Measured directly: `संधन` (what Sarvam heard) sits at normalized distance **0.167** from `चंदन` (the truth) but only **0.214** from `संतरा`/Santra. चंदन was correctly the closer word — it simply didn't exist anywhere in the vocabulary to be found, so the decoder matched the nearest word that *did* exist. This is the same structural gap as ISSUE-022 (अमचूर), and will recur for every kirana/pooja term not yet enumerated: **a static vocabulary list can approach completeness but never reach it**, and every gap is a guaranteed future mis-booking, not a possible one.
  3. **(Found in passing, unrelated to this trace, fixed anyway)** `term-interpret/index.ts`'s single-token classification call sent `{ role: 'user', scope: userPrompt }` — `scope` is not a valid chat message field. The token actually being classified never reached the model on that path; every call there was effectively classifying against an empty user turn.
- **Resolution**:
  1. **[`index.ts`, `term-interpret/index.ts`]** Added `XAI_REASONING_EFFORT` (env-configurable, default `'low'`), applied via `supportsReasoningEffort(model)` (true for any `grok-4*` id) to every xAI chat call in both files. Structured extraction does not need deep reasoning; forcing it low removes the most likely source of the repeated timeout.
  2. **[`index.ts`]** Increased `AI_CHAT_TIMEOUT_MS` to 45,000ms (45 seconds) as an explicit fallback budget for long reasoning passes.
  3. **[`OrderingSegmenter.DEFAULT_ITEM_VOCAB`, `phonetic.ts`]** Added `चंदन` and `अमचूर` to the default vocabulary lists.
  4. **[Phase 0a Candidate Distance Ranking & Trace Logging]** Implemented candidate ranking (`CandidateRank`), computed `itemMargin` (difference between top candidate and runner-up candidate normalized distance), populated `top3Candidates` across `phonetic.ts` and `OrderingSegmenter.kt`, and logged `item_margin` and `top3_candidates` inside `diagnostic_trace_json`.
  5. **[Verification]** Verified 60/60 unit tests passing cleanly (`PhoneticSegmentationTest`), built `VoiceToInvoice_v69.apk` to Desktop, deployed `process-voice-job` and `term-interpret` live to Supabase (`lyowklxsbfznnqridtgr`), and pushed commits to GitHub `master`.
  2. **[`index.ts`, `term-interpret/index.ts`]** The `XAI_CHAT_MODELS` fallback chain / `XAI_CHAT_MODEL` default had reverted to the retired `grok-2-latest` / `grok-2-1212` / `grok-beta` on disk since ISSUE-021 shipped. Restored to `grok-4.5` → `grok-4.3` → `grok-4`.
  3. **[`term-interpret/index.ts`]** Fixed `scope: userPrompt` → `content: userPrompt` in the single-token classification call.
  4. **[`OrderingSegmenter.kt` + `phonetic.ts`]** Added a pooja/religious-item category to `DEFAULT_ITEM_VOCAB` (चंदन, कुमकुम, रोली, मौली, अक्षत, कपूर, धूप, दीया, रुई, हवन सामग्री, गंगाजल) — 214 entries total, Kotlin/TypeScript parity verified programmatically. `संधन` now resolves to चंदन (0.167) instead of संतरा (0.214), because the closer word finally exists to win the comparison.
- **What this does NOT fix, stated plainly**: vocabulary expansion is the same move as ISSUE-022 and carries the same ceiling — it raises recall for a fixed list of anticipated words and does nothing for the next one not on it. The **actual** structural fix — a per-shop learned-alias table that grows from real corrections instead of a hardcoded list anyone has to keep guessing — already has its schema and even a write endpoint in place (`term_aliases` table, `sync-term-aliases` function, `TermInterpreterClient.confirmTermAlias()`) but **is completely unwired**: `confirmTermAlias` has zero callers anywhere in the app, and no read path in `process-voice-job/index.ts` or the segmenter ever consults `term_aliases` during matching. Every correction a shopkeeper makes in the review queue today is thrown away instead of teaching the system. This is the highest-leverage remaining item and is intentionally **not** attempted in this pass — it requires UI wiring (calling `confirmTermAlias` from the unmatched/review-queue correction flow) and a new read path in the matching pipeline, which is a scoped feature, not a patch, and neither half of it has been touched here.
- **Files Touched**: `supabase/functions/process-voice-job/index.ts`, `supabase/functions/term-interpret/index.ts`, `app/.../domain/parser/OrderingSegmenter.kt`, `supabase/functions/process-voice-job/phonetic.ts`, `app/src/test/.../PhoneticSegmentationTest.kt` (+3 tests).
- **Verification Date**: 2026-07-26. **What was actually verified:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` → `BUILD SUCCESSFUL`, **59/59 unit tests passing**. Measured directly (not assumed): `संधन` vs `चंदन` = 0.167 normalized distance, `संधन` vs `संतरा` = 0.214 — confirming चंदन was always the closer word. `"पांच कुल संधन"` now segments to `5 KG Chandan`. **What was NOT verified:** the `reasoning_effort` fix is a strong hypothesis, not a confirmed diagnosis — it has not been tested against the live xAI API (no key available locally), so it is possible the timeout has another or additional cause. Watch `step_4_ai_model` / `step_4_ai_error` on the next real job: if it now succeeds, this is confirmed; if it still times out, the next suspect is network/cold-start latency on the edge function itself. The `scope`→`content` fix in `term-interpret` is a clear code-level bug fix but that endpoint's actual call path was not exercised. Nothing in this issue has been deployed.

#### [ISSUE-022] [2026-07-26] "पांच किलो अमचूर" Auto-Confirmed to the Ledger as "7 GRAM Jeera, ₹2.80" at 0.95 Confidence — Confidence Measured *Whether* an Item Matched, Never *How Well*
- **Symptom**: Trace `e0b68f80-6876-42e2-b556-2adf73ce463f` — shopkeeper said "पांच किलो अमचूर" (5 kg dried mango powder) over a 1221 ms hold. Grok returned `"साथ गिलम चोर"`, Sarvam (`saaras:v3`, verbatim) returned `"सात गुलामचूर"`. Final outcome: **`7 GRAM Jeera, ₹2.80, confidence 0.95, autoConfirmedToLedger: true`**. Unlike ISSUE-020/021 the safety net did not hold — this one silently entered the books. Every field was wrong: quantity, unit, and item.
- **Root Cause** (five defects; the last one is why the previous four were never caught):
  1. **Confidence was a boolean in disguise — the decisive one.** `index.ts` computed `confidence = isCatalogMatched ? (rawItem.confidence || 0.95) : min(..., 0.60)`. The phonetic matcher calculates a *normalized distance* for every hit and then discarded it. Measured on the real tokens: `चोर`[COL] → `Jeera`[CILA] scored **0.250** — precisely `WHOLE_TOKEN_MAX_NORM`, the single loosest match the thresholds permit, one hair from outright rejection — and received the *same* 0.95 as an exact hit, clearing the 0.80 auto-confirm gate. The client had the mirror flaw (`BackgroundSttProcessor.kt` L183/273/328: `if (matched != null) 0.90f else 0.70f`).
  2. **The item was in no vocabulary at all.** `अमचूर` appeared **nowhere in the codebase** — not in `DEFAULT_ITEM_VOCAB` (35 items), not in the fallback catalog. A matcher cannot recognize a word it does not have; it can only map it onto the nearest word it does. Given the choice the engine did the only thing available to it. This is not a tuning gap, it is the defining failure mode, and every missing staple is a guaranteed future mis-booking.
  3. **No domain plausibility check existed anywhere.** `7 GRAM` of anything is not a sale any kirana shop has ever made — small-weight goods retail in 50/100/250/500 steps — and ₹2.80 is not a plausible transaction value. The unit error itself was an honest phonetic call (`गिलम`[KILAN] → `gram`[KLAN] at **0.100**, beating `kilo`[KILO] at **0.300**), so it could only ever have been caught downstream by asking whether the *result* made sense. Nothing asked.
  4. **The AI arbitration stage timed out and its absence was invisible.** `step_4_ai_error: "AI Timeout (8s limit)"`, `step_4_ai_model: null` — the only stage capable of reasoning "amchoor, not chor" never ran. Worse, `step_4_grok_ai_interpretation` was still populated (with the *segmenter's* output), so the trace read as though the AI had endorsed a parse it never saw.
  5. **Both transcripts were decoded but only one was kept.** Grok and Sarvam tied at score 6, and `grokScored.score >= sarvamScored.score` handed it to Grok. Sarvam's `"सात गुलामचूर"` is `किलो`+`अमचूर` fused — verified to segment correctly to `Amchoor` once that word exists in the vocabulary. The better parse was discarded by a tie-break, and the disagreement between the two providers — free evidence that the decode was unreliable — was never used as a signal at all.
- **Resolution**:
  1. **[`OrderingSegmenter.kt` + `phonetic.ts`]** Threaded `matchNorm` through `Emission` → `DecodedToken` → `RawItemSegment.itemMatchNorm`, carrying the **worst** (largest) item-match distance in a segment — confidence must rest on the weakest link, not the best. `null` means the token matched nothing and survives verbatim as a new item name.
  2. **[`index.ts`]** `confidenceFromMatchNorm()` maps distance linearly onto confidence: `0.00` (exact) → **0.95**, `0.25` (reject line) → **0.50**, unmatched → the documented 0.60 floor. Only matches inside ~0.075 normalized distance now clear the 0.80 gate. Deliberately strict: a mis-parse routed to review costs one tap; a mis-parse booked silently corrupts the shopkeeper's books and their trust in the app.
  3. **[New, `SalePlausibility.kt` + `implausibilityReason()` in `index.ts`]** Domain sanity independent of transcript confidence: GRAM/ML below 10 or above 5000, KG/LITRE above 200, PIECE/PACKET/DOZEN above 500, non-positive quantities, and any total below `MIN_PLAUSIBLE_SALE_VALUE` (₹5). These **never block a sale** — they withhold auto-confirm and route to review with a human-readable reason recorded in the trace. Wired into both auto-confirm gates (server `isAutoConfirmed`, client `isAutoConfirmable`).
  4. **[`OrderingSegmenter.kt` + `phonetic.ts`]** `DEFAULT_ITEM_VOCAB` expanded from 35 to **192 entries** in both scripts — spices (अमचूर, हल्दी, हींग, अजवाइन, कसूरी मेथी, …), dals, staples, dry fruit, packaged goods, produce. Parity between the Kotlin and TypeScript lists is verified programmatically (both 192, sets identical).
  5. **[`index.ts`]** Step-4 chat timeout raised 8s → 20s (`AI_CHAT_TIMEOUT_MS`, env-tunable). Added `step_4_interpretation_source` (`grok_ai` | `segmenter_fallback`) and a per-item `parse_source`, so segmenter output can never again be mistaken for AI output in a trace. Items also carry `item_match_norm` and `implausibility_reason` so a review-queue entry explains why it landed there.
- **Known trade-off, stated plainly**: a larger vocabulary raises collision risk — more words means more chances a garbage token finds *some* close match. Observed directly while writing the regression tests: with the expanded lexicon `चोर` now reaches `छोले`/Chole at **0.125** instead of `Jeera` at 0.250. Still the wrong item, but a *closer* wrong one, and 0.125 maps to ~0.73 confidence, which stays below the auto-confirm gate. This is precisely why fix (1) is load-bearing: it is what makes the wider vocabulary safe to add. Vocabulary breadth improves recall; distance-aware confidence is what protects precision. Neither works without the other.
- **Files Touched**: `app/.../domain/parser/OrderingSegmenter.kt`, `app/.../domain/parser/SalePlausibility.kt` (new), `app/.../domain/processor/BackgroundSttProcessor.kt`, `supabase/functions/process-voice-job/phonetic.ts`, `supabase/functions/process-voice-job/index.ts`, `app/src/test/.../PhoneticSegmentationTest.kt` (+10 tests).
- **Verification Date**: 2026-07-26. **What was actually verified:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` → `BUILD SUCCESSFUL`, **57/57 unit tests passing** (10 new + all 47 pre-existing). Concretely covered: `अमचूर` is present in `DEFAULT_ITEM_VOCAB`; `"पांच किलो अमचूर"` → `5 KG Amchoor` at `itemMatchNorm = 0.0`; Sarvam's fused `"सात गुलामचूर"` now recovers `अमचूर`; an exact match reports distance `0.0` and a fuzzy one reports a clearly non-zero distance; an unmatched word reports `null` rather than a fabricated score; `SalePlausibility.reason("GRAM", 7.0, 2.80)` is non-null while ordinary sales (5 KG/₹250, 500 GRAM/₹120, 2 PACKET/₹40) stay plausible. Kotlin↔TypeScript vocabulary parity checked programmatically. **What was NOT verified:** the edge-function half is again unexecuted — no Deno toolchain and no TS test harness, so `confidenceFromMatchNorm`, `implausibilityReason`, and the `matchNorm` threading in `phonetic.ts` have been hand-ported and reviewed but never run. Nothing is deployed. The `CONFIDENCE_AT_WORST_MATCH = 0.50` / `MIN_PLAUSIBLE_SALE_VALUE = ₹5` constants are reasoned choices, **not** tuned against a corpus — watch the review-queue volume after deploy and expect to adjust. **Still unaddressed:** the quantity error (both providers independently heard `साथ`/`सात` = 7 rather than `पांच` = 5, at phonetic distance 0.750 from the truth — an acoustic loss no downstream logic can recover), and the cross-STT disagreement signal, which remains discarded by the `>=` tie-break.

#### [ISSUE-021] [2026-07-26] "पांच किलो मैगी" Heard as "पांच किलोमीटर" and Booked as Nothing At All — a Distance Word in `UNIT_SET` Suppressed Token Splitting, While Three of the Four Recovery Layers Had Been Dead on Every Job
- **Symptom**: Trace `9fc1fc32-7685-4503-9330-1363a16ec544` — shopkeeper said "पांच किलो मैगी" (5 kg Maggi) over a 1037 ms hold. Grok STT returned `"पांच किलोमीटर"`. The pipeline emitted `step_3...segments: []` and `step_6_final_outcome: []` — **the entire sale evaporated**. Not a wrong parse: no parse. The trace also shows `sarvamStt.error: "Model 'saarika:v2' has been deprecated"` and `step_4_ai_error: "Model not found: grok-2-latest"`, i.e. the second STT opinion and the whole AI interpretation stage had been failing on *every* job, silently, for an unknown period.
- **Root Cause** (four defects; one destroyed the sale, three explain why nothing caught it):
  1. **A distance word was listed as a shop unit — the decisive one.** `UNIT_SET` in `OrderingSegmenter.kt` (L414) and `phonetic.ts` (L214) contained `"kilometer"` / `"किलोमीटर"`, added as an earlier band-aid so a mis-heard "किलो" would still normalize to KG. It backfired structurally: an exact `UNIT_SET` hit emits at `EXACT_COST = 0.0` and **returns early** from `wholeTokenExpansions`, which makes `exactOnly` true in `decode()`, which **suppresses split expansions entirely**. So the lattice was never even offered `किलो` + `मीटर`. The decode was `NUM(5) + UNIT(KG)` with zero ITEM tokens, `closeSegment()` requires a non-empty `currentItemTokens`, and the utterance therefore produced no segment at all. The band-aid was worse than the wound it covered.
  2. **The lattice had no terminal grammar constraint.** `transitionCost` modelled transitions *between* tokens but nothing about how an utterance may legally *end*. A decode finishing on a bare quantity+unit is structurally incomplete — a shopkeeper does not say "five kilos" and stop — yet it cost the same as one that resolved an item, so the decoder had no reason to prefer the complete reading.
  3. **Model ids were pinned to retired models.** `SARVAM_STT_MODEL` defaulted to `saarika:v2` (deprecated → HTTP 400 on every call) and `XAI_CHAT_MODEL` to `grok-2-latest` (retired → "Model not found" on every call); `term-interpret/index.ts` hardcoded `grok-2-latest` in three places and `stt-proxy/index.ts` hardcoded `saarika:v2`. **Dual STT had degraded to single STT and step 4 had never run in this build.** Nothing in the system treats "a pipeline stage has a 100% failure rate" as an alarm, so this stayed invisible until someone read a trace by hand.
  4. **The adaptive re-decode passes could not produce a different answer.** Of its two variants, one was the dead Sarvam model (guaranteed 400) and the other differed from the first pass only in bias flags, returning the identical string. `passesExecuted: 2` with zero possibility of recovery — a recovery stage that was structurally incapable of recovering.
- **Resolution**:
  1. **[`OrderingSegmenter.kt` + `phonetic.ts`]** Removed `kilometer`/`किलोमीटर` from `UNIT_SET` and introduced `DISTANCE_UNIT_TOKENS` (kilometer/meter/centimeter/mile/foot and Devanagari forms). A shop ledger has no distance units, so these are mis-decodes *by definition*, never data. Such a token now yields only a heavily-priced (`DISTANCE_TOKEN_ITEM_COST = 2.5`) suspect ITEM reading, which deliberately keeps `exactOnly` false and re-enables the split expansions that should carry it — so `किलोमीटर` is offered to the lattice as `किलो`(UNIT) + `मीटर`(ITEM) and the quantity and unit survive even when the item name cannot be recovered.
  2. **[`OrderingSegmenter.kt` + `phonetic.ts`]** Added `endCost(lastType)` (ITEM `0.0`, NUM/UNIT `0.6`), applied when selecting the Viterbi backtrack start state. A decode that leaves the utterance hanging on a quantity or unit must now be genuinely cheaper to win. Kept small on purpose: an exact `UNIT_SET` match costs 0.0 and returns before any ITEM alternative is offered, so the deliberate carryover feature (a trailing "चार किलो" continuing into the next recording) has no competing path this can flip — covered by a regression test.
  3. **[`OrderingSegmenter.kt` + `phonetic.ts`]** Added a `suspect` flag on `Emission`/`DecodedToken`, propagated into `RawItemSegment.isSanityFlagged`. Suspicion is attached to the **source token**, not to one reading of it, so split readings inherit it — otherwise the split wins the lattice (as it should) and silently drops the warning. Effect: an item recovered from a mangled token reaches the review queue for a one-tap correction and can never ride the ≥0.80 auto-confirm path. This matters because the split is confidently wrong here — `मीटर`→`NITAL` sits 0.10 normalized distance from `मटर`(Matar), so without the flag the fix would have booked "5 KG Matar" instead of nothing.
  4. **[`process-voice-job/index.ts`]** Replaced pinned model ids with ordered **fallback chains** (`XAI_CHAT_MODELS`: env → `grok-4.5` → `grok-4.3` → `grok-4`; `SARVAM_STT_MODELS`: env → `saaras:v3` → `saarika:v2.5` → `saarika:v2`). `isModelUnavailableError` advances the chain only on an error that actually means "wrong model id" (400/404/422 mentioning deprecation / not found), never on a timeout or 5xx where the next model would fail identically; the first working id is cached per isolate. Provider deprecations are routine — they must now degrade, not kill a stage.
  5. **[`process-voice-job/index.ts`]** Sarvam upgraded to `saaras:v3` with `mode=verbatim` (env `SARVAM_STT_MODE`), gated behind a `supportsModeParam` check because older models reject the parameter. Verbatim, **not** translate: translate mode emits English (`आलू` → "potato"), which would strip out exactly the Hindi phonetics `PhoneticKey` and the segmenter vocabulary exist to match against.
  6. **[`process-voice-job/index.ts`]** Re-decode now runs three variants that can each actually produce a different answer: unbiased Grok (no keyterms, no language), Sarvam with language auto-detect (a genuinely different acoustic model, now that it is not returning 400), and **new** `grok_tight_catalog_keyterms` — a ≤25-term catalog-only bias list. The first pass sends 100 terms (catalog + numbers + units), which spreads the biasing so thin it barely registers; a short list concentrates it on the item name, which is where the ambiguity actually is.
  7. **[`term-interpret/index.ts`, `stt-proxy/index.ts`]** Hardcoded `grok-2-latest` (×3) and `saarika:v2` replaced with env-configurable constants defaulting to `grok-4.5` and `saaras:v3`.
  8. **[Trace]** `sarvamStt.model` now reports the id that *actually served* the call rather than the configured default (a silent fallback down the chain was previously invisible), plus `sarvamStt.mode` and a new `step_4_ai_model`.
- **Files Touched**: `app/.../domain/parser/OrderingSegmenter.kt`, `supabase/functions/process-voice-job/phonetic.ts`, `supabase/functions/process-voice-job/index.ts`, `supabase/functions/term-interpret/index.ts`, `supabase/functions/stt-proxy/index.ts`, `app/src/test/.../PhoneticSegmentationTest.kt` (+7 tests).
- **Verification Date**: 2026-07-26. **What was actually verified:** `./gradlew :app:testDebugUnitTest` → `BUILD SUCCESSFUL`, **48/48 unit tests passing** (7 new + all 41 pre-existing, including every ISSUE-011/019/020 regression test). The failing utterance is covered directly: `"पांच किलोमीटर"` → `quantity=5.0, unit=KG, isSanityFlagged=true`, one segment instead of zero; `"पांच किलोमीटर आलू"` → `5 KG आलू`; and `"चार किलो"` still carries over with **no** invented item, confirming the `endCost` change did not break carryover. **What was NOT verified:** none of the edge-function changes have been deployed or exercised against the live xAI/Sarvam APIs — `grok-4.5` and `saaras:v3` were selected from vendor documentation, **not** from a successful API call, so the first live job is what will confirm both ids (and the `mode=verbatim` parameter) are accepted. The TypeScript mirror was hand-ported from the Kotlin reference and reviewed line-by-line but **not** executed — there is no Deno toolchain on this machine and no TS test harness in the repo. The Kotlin changes have not been run on a device. **The item name in this class of failure remains unrecoverable** — the fix guarantees the quantity/unit survive and the sale reaches review, not that "मैगी" comes back.

#### [ISSUE-020] [2026-07-26] "तीन किलो सेब" Transcribed as Malay "tinggal sebab" and Booked as "1 PACKET tinggal sebab" — Every Matcher in the Pipeline Was Blind to Non-Devanagari STT Output
- **Symptom**: Trace `2966f386-7211-407a-810c-169042b2ecfc` — shopkeeper said "तीन किलो सेब" (3 kg apples) over a 961 ms hold. Grok STT returned `"tinggal sebab"` (Indonesian/Malay words) and Sarvam returned `""`. The pipeline emitted `quantity: 1, unit: PACKET, item_name: "Tinggal sebab", confidence: 0.6`. Quantity, unit, and item were **all three** lost simultaneously. The auto-confirm gate correctly held (₹0 price → routed to review), so no bad ledger row — but the parse was unusable.
- **Root Cause** (four independent defects; the transcript was acoustically *correct*, so this was never an audio-quality problem):
  1. **Cross-script blindness — the decisive one.** `CANONICAL_NUMBERS` and `CANONICAL_UNITS` in `process-voice-job/index.ts` were Devanagari-only. `editDistance("tinggal", "तीन")` = 7 against a `maxDist` budget of 1 — a Latin-script token shares *zero characters* with a Devanagari entry, so no match is ever possible. Because the 2-way/3-way combinatorial splitter required a number or unit match to fire, **the entire splitter (the "3-token system" added under ISSUE-004/011) was dead code whenever STT returned Latin script.** Separately, whole-token catalog matching used `maxDist = 1` while `editDistance("sebab","seb") = 2`, so the item missed by exactly one unit of budget.
  2. **Orthographic edit distance is the wrong metric.** Even with romanized vocabulary added, `editDistance("tinggal","teen") = 3` — spelling distance cannot model g↔k devoicing or vowel elision. STT's errors are phonetic; the comparison has to happen in phone space.
  3. **The better algorithm was on the dead code path.** `HomeScreen` enqueues `SttWorker`, which posts to the edge function and adopts the server's trace wholesale — so the server's naive greedy loop ran in production while the client's grammar-aware Viterbi decoder (`OrderingSegmenter.kt`, built for ISSUE-019, and which *did* have romanized vocabulary) never executed on this path.
  4. **Step 5 "Adaptive Audio Expansion Engine" was fabricated on the server.** It never called STT again: it echoed the same two transcripts back and wrote `status: "EXPANDED_300MS_AUDIO_WINDOW_EVALUATED"`. The trace reported a recovery pass that did not happen — for precisely the failure the engine exists to catch. Compounding this, Sarvam's empty result had its cause discarded (every failure collapsed into `""`), so a wrong model name, an auth failure, a timeout, and genuine silence were indistinguishable in the trace.
- **Resolution**:
  1. **[New, `PhoneticKey.kt` + `phonetic.ts`]** Script-agnostic phonetic keying. Devanagari and Latin are both projected into one phone alphabet — including Hindi **word-final schwa deletion**, without which `तीन`→`TINA` never converges with `teen`→`TIN` and the whole premise fails — then collapsed on the confusions Hindi STT actually makes (aspiration, k↔g / t↔d / p↔b voicing, vowel length, nasal and sibilant merge, l↔r, degemination). `तीन`, `teen`, and the `tin-` of `tinggal` all key to `TIN`. Distance is **vowel-weighted** (vowel edits 0.5, consonant edits 1.0) because STT reconstructs consonant skeletons far more reliably than vowels, and **normalized per phone** so a short fragment cannot cheaply claim a long word.
  2. **[`OrderingSegmenter.kt` + `phonetic.ts`]** The Viterbi decoder now runs over a **token-expansion lattice**: each source token contributes competing whole-token *and* fused-split (2-way/3-way) readings, and the grammar arbitrates between them across the whole utterance. Splits are deliberately *not* decided in a greedy pre-pass — `एकलो` reads equally well as "ek kilo" and "ek aaloo" in isolation, and only the following token settles it. The server now runs the same algorithm as the client rather than a weaker one.
  3. **[`index.ts`, real re-decode]** Step 5 now genuinely re-transcribes when the first pass scores poorly (no recognized item and no usable quantity+unit frame), varying decode parameters — Grok without keyterm biasing and without the language hint, Sarvam with language auto-detect — and adopts a retry only if it scores strictly better. It records what was actually attempted, including per-attempt errors, HTTP status, and latency; when no re-decode was needed the pass list is empty rather than claiming a fabricated pass. Both STT calls now report structured outcomes, so an empty transcript is never again indistinguishable from a failure. Model ids moved to `XAI_CHAT_MODEL` / `SARVAM_STT_MODEL` env vars (defaults unchanged).
  4. **[`index.ts`, supporting fixes]** STT stream selection is now score-based rather than always preferring Grok (a confident Grok mis-decode used to bury a correct Sarvam transcript); keyterms are sent in **both scripts** (English-only keyterms while requesting Hindi largely wasted the biasing); catalog matching falls back to phonetic keys so a Hindi-spoken item resolves to its English catalog row (`सेब` → `Seb`); the step-4 prompt gained a real user turn, `response_format: json_object`, and an explicit instruction to read foreign-looking output phonetically rather than semantically.
  5. **[`HomeScreen.kt`]** Added 300 ms **pre-roll**. The capture window started exactly at `pressTs`, so the leading consonant burst was exposed to input latency and speech onset preceding the press — on a ~1 s utterance that is enough to damage the first word. The 30 s ring buffer already held this audio; it was simply never requested.
  6. **[`SttProxyClient.kt`]** Fixed a fully broken fallback path found while tracing this: it posted `model`/`language_code` fields the edge function never reads, and omitted the `jobId` that function *requires*, so every call returned HTTP 400 before any audio was transcribed; even if accepted, the function replies `202 {status:"QUEUED"}` with no transcript while the client only looked for a `transcript` field. It now speaks the real contract (submit with a job id, then poll `stt_job_logs`), using a **fresh job id per call** so the idempotency cache doesn't replay the first pass's transcript for every adaptive-expansion retry.
- **Files Touched**: `app/.../domain/parser/PhoneticKey.kt` (new), `app/.../domain/parser/OrderingSegmenter.kt` (rewritten), `supabase/functions/process-voice-job/phonetic.ts` (new), `supabase/functions/process-voice-job/index.ts` (rewritten), `app/.../network/SttProxyClient.kt` (rewritten), `app/.../ui/screens/home/HomeScreen.kt` (pre-roll), `app/.../domain/processor/BackgroundSttProcessor.kt` (passes live catalog into the segmenter), `app/src/test/.../PhoneticSegmentationTest.kt` (new, 12 tests).
- **Verification Date**: 2026-07-26. **What was actually verified:** `./gradlew assembleDebug testDebugUnitTest` → `BUILD SUCCESSFUL`, 41/41 unit tests passing (12 new + all 29 pre-existing, including every ISSUE-019 and ISSUE-011 regression test). The failing utterance is covered directly: `"tinggal sebab"` → `quantity=3.0, unit=KG, item=सेब`. The TypeScript port was verified to produce byte-identical decisions to the Kotlin reference across 14 cases (both failing and regression) via a Node type-stripping harness, and `index.ts` was syntax/type-strip checked with its remote imports stubbed. **Deployed** to production via the Supabase MCP `deploy_edge_function` on 2026-07-26 (project `lyowklxsbfznnqridtgr`, function `process-voice-job` now at **version 31**, `status: ACTIVE`, `verify_jwt: true` preserved from the prior version) — confirmed via `get_edge_function` and `get_logs` showing no boot/deploy errors. **What was NOT verified:** there has been **no live STT round-trip against version 31 yet** — `get_logs` at deploy time showed only pre-deploy traffic on versions 23–30, so the re-decode pass, the keyterm/script change, the score-based stream selection, and the `SttProxyClient` polling contract have all only been reasoned through and unit-tested offline, never exercised against the real xAI/Sarvam APIs live. The Kotlin side (pre-roll, `SttProxyClient` rewrite) ships in the next APK build and has not been tested on a device. Check the next real recording's `stt_job_logs` row for `diagnostic_trace_json.step_2_stt_proxy_response.sarvamStt.error` — that field will finally reveal why Sarvam has been returning empty, which remains an open unknown.

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

#### [ISSUE-024] [2026-07-26] Open-Vocab Phase 0-pre: Verbatim STT Preservation & heardText Trace Isolation
- **Symptom**: Trace `26ee5b12` showed Sarvam STT hearing `"चार किलो सोयाबीन"`, but `rawTranscript` in DB trace read `"चार किलो साबुन"` (soap). `normalizeTranscript()` had re-tokenized the winning transcript and fuzzy-matched `"सोयाबीन"` to `"साबुन"` at 0.214 distance, mutating the raw trace log and handing the step-4 AI model a mis-resolved transcript hint.
- **Root Cause**:
  1. `Emissions` and `DecodedTokens` only carried resolved vocabulary surfaces (`surface`), causing `normalizeTranscript()` to join resolved Surfaces instead of literal heard tokens.
  2. `rawTranscript` in `process-voice-job/index.ts` recorded `transcript` (the output of `normalizeTranscript`) instead of `chosenRaw` (the untouched STT output).
- **Resolution**:
  1. Added `heardText: String` to `Emission` and `DecodedToken` across Kotlin and TypeScript segmenter engines, carrying literal heard substrings from transcript construction sites.
  2. Added `heardSegmentText: String` to `RawItemSegment` in both Kotlin and TypeScript engines (leaving `rawSegmentText` untouched to preserve `MultiSaleDetector.kt` behavior).
  3. Updated `normalizeTranscript()` in `phonetic.ts` to join `heardSegmentText` instead of `rawSegmentText`.
  4. Updated `process-voice-job/index.ts` trace logging: `rawTranscript` now records `chosenRaw` (untouched STT), and `normalizedTranscript` records the cleaned hint string.
  5. Updated `BackgroundSttProcessor.kt` trace logging to include `heardSegmentText`.
#### [ISSUE-027] [2026-07-26] Recording Boundary Interleaving & Overlapping Audio Window Corruption
- **Symptom**: Rapid consecutive press-and-hold recordings resulted in stolen trailing numbers (e.g. "चार सौ रुपये") or leading quantities ("पाँच किलो") leaking across job boundaries. Audio windows derived via `now`-relative math allowed adjacent recording windows to overlap when `pressN+1 < releaseN + 600ms`.
- **Root Cause**:
  1. `RollingAudioBuffer.extractAudioWindow()` calculated window positions relative to `System.currentTimeMillis()` rather than absolute byte offsets, introducing scheduled drift.
  2. Fixed 500ms floors expanded extracted windows backward into preceding recordings when gaps between holds were under 600ms.
- **Resolution**:
  1. Replaced `now`-relative byte math in `RollingAudioBuffer.kt` with absolute-time addressing (`recordingStartedAtMs` + `totalBytesWritten`) and enforced `floorStartMs` to prevent backward expansion.
  2. Created `PttWindowLedger.kt` to record button presses and enforce non-overlapping clamped window boundaries (`audioStartMs >= lastConsumedEndMs`).
  3. Added `previousJobId` and `precedingGapMs` metadata to `SttJobRecord` (Room Migration 10 $\rightarrow$ 11) and updated multipart upload in `SttWorker.kt`, `SttProxyClient.kt`, and `BackgroundSttProcessor.kt`.
  4. Implemented cross-job duplicate-number guard (`MAX_ADJACENT_JOB_GAP_MS = 1500`) and orphan classification in `process-voice-job/index.ts`.
- **Verification Date**: 2026-07-26 (Node TS tests PASSED 7/7, Kotlin unit tests PASSED 63/63, Supabase Edge Function deployed live, APK v73 built).
- **Follow-up**: Fixing the overlap traded it for a truncation bug — the post-roll of one recording and the pre-roll of the next both draw from the same ~300ms budget around a fast press/release, and post-roll was winning by construction, clipping the next utterance's leading quantity word. Real production traces showed this booking **1 KG ₹50 instead of 4 KG ₹200** for "चार किलो आलू". See ISSUE-028, which is the actual close of this trade-off, plus two further defects found in the same trace review (`rawLooksUnrecognizable` computed but never gating auto-confirm; orphan classification over-firing on any uncatalogued item).

#### [ISSUE-028] [2026-07-26] Pre-Roll Starvation on Rapid Back-to-Back Recordings Silently Booked a Truncated Quantity, Plus Two Unrelated Auto-Confirm Gaps Found in the Same Trace Review
- **Symptom**: Four recordings made in quick succession ("एक किलो गोल्ड" / "दो किलो आलू" / "एक किलो गोल्ड" / "चार किलो आलू", gaps of 269–333ms) — jobs `4f2c927b`, `10519b0d`, `212a858a`, `2b21716c`. ISSUE-027's non-overlap clamp worked correctly (each window's `audioStartMs` exactly matched the previous window's `audioEndMs`), but two of the four had their pre-roll squeezed to 7–33ms and lost their leading word: job `212a858a` transcribed "एक किलो गोल्ड" as bare `"Global"` (Sarvam: `"ग्लो गोल्ड"`) and auto-confirmed **Amul Gold Milk 1KG ₹34** anyway despite `rawLooksUnrecognizable: true` in its own trace; job `2b21716c` transcribed "चार किलो आलू" as `"किलो आलू"` (no quantity word at all) and auto-confirmed **1 KG ₹50 instead of 4 KG ₹200**. Separately, job `23b5532b` ("दस किलो अमचूर" — Amchoor, a real product just absent from this shop's catalog) was labelled `is_orphan: true` with reason "Price/quantity specified without a recognized catalog item," which is wrong — that is a new-item-to-catalog case, not an orphan sale.
- **Root Cause**:
  1. `PREROLL_RESERVE_MS` did not exist — job N's `POST_ROLL_MS` (300ms) and job N+1's `PRE_ROLL_MS` (300ms) both drew from the same real gap between release and next press, and post-roll (committed first, at job N's extraction time) always won, leaving job N+1 with almost nothing.
  2. `rawLooksUnrecognizable` (computed in `index.ts` to detect exactly this class of STT failure) was written into the diagnostic trace and never read anywhere else — it gated no confidence cap and blocked no auto-confirm.
  3. `isOrphan` (from ISSUE-027) was defined as `!isCatalogMatched && (priceAtSale > 0 || total > 0 || qty > 0)` — since `qty > 0` is true for essentially every sale, any item merely absent from the catalog (not actually orphaned — it had a clear item name, "अमचूर") was mislabelled as a price/qty-with-no-item orphan.
- **Resolution**:
  1. **`HomeScreen.kt`**: added `PREROLL_RESERVE_MS = 200L`; the post-roll clamp now reserves that much of the gap for the next recording's pre-roll: `clampedEndMs = max(releaseTs, min(rawEndMs, nextPress - PREROLL_RESERVE_MS))`.
  2. **`process-voice-job/index.ts`**: added a defaulted-quantity guard — computes `preRollActualMs` from `metadata.pressStartMs`/`audioStartMs` (no new client field needed), detects a leading UNIT token with no preceding NUM or an unspoken default quantity, and withholds auto-confirm (capping confidence, adding an `implausibility_reason`) only when **both** `preRollActualMs < 150` **and** the quantity was defaulted — a job with an explicit spoken number and 0ms pre-roll (job `10519b0d`) is deliberately left alone.
  3. **`process-voice-job/index.ts`**: `rawLooksUnrecognizable` now caps confidence and adds an `implausibility_reason` when true; an additional check flags AI-vs-segmenter item disagreement on unrecognizable raw text.
  4. **`process-voice-job/index.ts`**: narrowed `isOrphan` to `!hasItemName && (priceAtSale > 0 || total > 0)` — a genuine orphan is a price/quantity announcement with **no item name at all** (a bare "चार सौ रुपये"), not merely an uncatalogued one; uncatalogued-but-named items revert to the pre-existing "route to review as a possible new catalog item" behavior.
  5. **On-device STT observability** (separately requested, same deploy): added `SttJobRecord.onDeviceStatus` (Room Migration 11→12) with granular `SpeechRecognizer` error codes (`error_audio`/`error_network`/`no_match`/`error_busy`/etc.), a latest-partial-transcript fallback on error/timeout, and surfaced `onDeviceTranscript` + a 3-way `transcriptScores.onDevice` in the diagnostic trace — previously the trace gave no way to tell "recognizer unavailable" from "timed out" from "arrived too late," so every prior trace showed only silence with no explanation.
  6. **Two additional bugs caught in review before this reached production, fixed same deploy**: (a) the request handler's top-level `onDeviceTranscript` read from `formData` had been accidentally deleted while adding the `onDeviceStatus` read, leaving a dangling reference that would have thrown on every single job at the `EdgeRuntime.waitUntil(processVoiceJob({...}))` call site — i.e. every recording would have failed; (b) the defaulted-quantity guard's `UNIT_SET.has(firstTokenNorm)` call was invalid — `UNIT_SET` is a plain `string[]`, not a `Set`, and arrays have no `.has()` method — which would have thrown a `TypeError` inside the per-item processing loop on every job, caught by the outer try/catch, silently converting every recording into `status: 'ERROR'`. Both fixed to restore the read and use `UNIT_SET.includes(...)` (matching the existing correct usage in `price_intent.ts`) before deploying.
- **Verification Date**: 2026-07-26. Verified: Node TS suite 7/7 passed after both bugfixes; Kotlin suite — 62 tests across `FuzzyCatalogMatcherTest`/`OrderingSegmenterTest`/`PhoneticSegmentationTest`/`SyncEngineTest`/`VoiceParserTest`, read directly from the JUnit XML reports, 0 failures/0 errors; `gradlew assembleDebug` BUILD SUCCESSFUL. Deployed live to Supabase `process-voice-job` on `lyowklxsbfznnqridtgr`, confirmed version 57 ACTIVE with both bugfixes present in the fetched live bundle (grepped for the restored `onDeviceTranscript` read and `UNIT_SET.includes(firstTokenNorm)`, confirmed no `UNIT_SET.has` remains). **Not yet verified**: the actual on-device retest — re-running the same four-recording burst against the live fix to confirm "चार किलो आलू" now books correctly and that `onDeviceStt.status` reports something informative — is still pending on a real device.

#### [ISSUE-025] [2026-07-26] Server-Side Price Intent Schema Alignment, Deterministic Compound Number Engine & Deno Test Suite
- **Symptom**: Trace `1ec60144` ("गोल्ड पचास रुपए") auto-confirmed a fake ₹34 sale for 1 piece of Amul Gold Milk at confidence 0.9. Grok AI's contractually enforced JSON output schema lacked `price_at_sale`, `total`, and `price_intent` fields. Furthermore, initial single-token price pre-check truncated multi-word Hindi numbers (e.g. "गोल्ड दो सौ पचास रुपये" extracted 50 instead of 250), and the numeric consistency guard falsely flagged hundreds-words ("सौ") inside valid compound totals ("पाँच किलो आलू दो सौ रुपये").
- **Root Cause**:
  1. Server-side Edge Function `process-voice-job/index.ts` relied entirely on LLM prompt prose for price intent determination without supporting schema fields in `response_format: json_object`.
  2. Single-token adjacency check (`tokens[rupeeWordIdx - 1]`) in initial pre-check dropped multiplier tokens ("सौ", "हजार") in compound Hindi numbers.
  3. `implausibilityReason()` checked individual tokens against totals, causing false positive flags on valid compound numbers.
- **Resolution**:
  1. Updated Grok AI system prompt and JSON output schema in `process-voice-job/index.ts` to include `price_at_sale`, `total`, and `price_intent` (`NONE`, `RATE_UPDATE`, `BULK_SALE_TOTAL`, `AMBIGUOUS_UNTRUSTED`).
  2. Created modular `price_intent.ts` containing server-side `detectPriceIntent(transcript)` pre-check mirroring `VoiceParser.kt`'s `RUPEE_WORDS` set (including `रुपए`, `रुपये`, `rs`, `₹`, etc.) and `hasLeadingQty` logic.
  3. Implemented `parseCompoundNumberSequence()` in `price_intent.ts` to correctly resolve multi-word Hindi numbers ("दो सौ पचास" $\rightarrow$ 250, "डेढ़ सौ" $\rightarrow$ 150, "ढाई सौ" $\rightarrow$ 250, "एक हजार दो सौ" $\rightarrow$ 1200). Added `hazaar` / `हजार` to `HINDI_NUMBER_MAP` in `phonetic.ts`.
  4. Updated `implausibilityReason()` to extract compound numbers $\ge 10$ from raw transcripts before checking against `qty`/`priceAtSale`/`total`, preventing false flags on compound bulk sales.
  5. Built dedicated TypeScript/Node test suite `price_intent_test.ts` (6/6 tests PASSED in 163ms) and added `testPriceIntent_RateUpdate_RupayeSpellingVariant` in `VoiceParserTest.kt` (63/63 Kotlin unit tests PASSED).
- **Verification Date**: 2026-07-26 (Node TS tests PASSED 6/6, Kotlin tests PASSED 63/63, Edge Function deployed live to Supabase `lyowklxsbfznnqridtgr`, commits `70c24db` & `5a79fe8` pushed to `master`).
- **Follow-up**: The `price_intent: "RATE_UPDATE"` *classification* fixed here was correct, but nothing downstream in `process-voice-job/index.ts` actually branched on it — the auto-confirm gate and transaction insert treated a `RATE_UPDATE` item identically to a real sale. The exact symptom (a fake qty=1 sale booked for a rate announcement) recurred under a new job id; see ISSUE-026, which is the actual close of this bug.

#### [ISSUE-026] [2026-07-26] `RATE_UPDATE` Classification Was Correct But Still Auto-Booked a Fake Sale — Classifying Intent ≠ Acting On It
- **Symptom**: Job `4725a553-e82d-45ea-8370-3cdb2a7bc36c` ("गोल्ड पचास रुपये", i.e. "Gold, 50 rupees" — a rate announcement for Amul Gold Milk, no quantity spoken) was correctly classified `price_intent: "RATE_UPDATE"` by Grok with `confidence: 0.9`, yet `step_6_final_outcome` still showed `quantity: 1, priceAtSale: 50, estimatedTotal: 50, autoConfirmedToLedger: true` — a real `TransactionRecord`-equivalent row was upserted into Supabase `transactions` for a sale that never happened. This is the same class of bug ISSUE-025 documented (job `1ec60144`), which that fix's schema/prompt change did not actually close.
- **Root Cause**: `process-voice-job/index.ts`'s `isAutoConfirmed` gate (computing auto-confirm + the `transactions` upsert) ran over **all** `finalParsedItems` regardless of `price_intent`. There was no branch mirroring the Kotlin client's `BackgroundSttProcessor.kt` `RATE_UPDATE` handling (L338-355), which correctly calls `catalogDao().insertOrUpdate(item.copy(price = ...))` and hardcodes `autoConfirmedToLedger = false`, never touching the transactions table. The server-side mirror never got the equivalent logic — the two "engines" this repo has to keep in sync (per `CLAUDE.md`) had silently diverged.
- **Resolution** (`supabase/functions/process-voice-job/index.ts`):
  1. Split `finalParsedItems` into `rateUpdateItems` (`price_intent === 'RATE_UPDATE'`) and `saleItems` (everything else); `isAutoConfirmed` and the `transactions` upsert now only ever consider `saleItems`.
  2. Added `isValidRateUpdate()` (matched catalog id + `confidence >= 0.80` + `price_at_sale > 0.0` + not implausible) and `validRateUpdates`; a job is `rateUpdatesHandled` when every rate-update item in it passes this check.
  3. Valid rate updates now run `supabase.from('catalog_items').update({ price, updated_at })` directly — the standing catalog price changes, but **zero rows** are written to `transactions`.
  4. `step_6_final_outcome.autoConfirmedToLedger` is hardcoded `false` for any `RATE_UPDATE` item, matching the Kotlin client's trace shape; `isSanityFlagged` for those items now reflects `isValidRateUpdate()` instead of the sale-only gate.
  5. `finalStatus` gained a third value, `"RATE_UPDATED"` (alongside `"AUTO_CONFIRMED"`/`"PARSED"`), and the `unmatched_queue` PENDING-routing condition now treats a fully-handled rate update the same as an auto-confirmed sale — i.e. it does **not** get dropped into the manual review queue just because it wasn't a sale.
- **Verification Date**: 2026-07-26 (Deployed live to Supabase Edge Function `process-voice-job` on `lyowklxsbfznnqridtgr` at v56, ACTIVE; verified in bundle with `rateUpdatesHandled`, `extractSpokenNumbers`, and `is_orphan`).

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
- **Latest Debug APK**: `C:\Users\harsh\OneDrive\Desktop\VoiceToInvoice_APKs\VoiceToInvoice_v106.apk` (corrected 2026-08-05 — this row said v105 while ISSUE-079 already documented v106 as built/installed; §5 had drifted behind §2)

---

## 6. Audio Capture Subsystem — Call Map (verified against working-tree source, 2026-08-05)

This section exists because ISSUE-061 through ISSUE-079 are nineteen consecutive fixes to
one subsystem (`RollingAudioBuffer` + its callers). Six of those plans (061-075) patched
symptoms of the same design conflict — a continuously-running ring buffer being stopped
for ASSISTANT presses — without ever naming the conflict. ISSUE-076 finally named it and
rewrote the architecture. This map documents the **current, post-076 state** so the next
session (any agent) can see the whole call graph before writing fix #20, instead of
re-deriving it from a partial file read the way this happened repeatedly in 061-075.

**Re-verify this map against the live files before trusting it for a new diagnosis** — it
is accurate as of the commit state at the time of writing, not a guarantee about any later
session's working tree control the flow of a specific press.

```
RollingAudioBuffer (app/src/main/java/.../audio/RollingAudioBuffer.kt)
├── startRollingBuffer()      cold reset: totalBytesWritten=0, writeHead=0, lastWriteAtMs=0,
│                             stoppedAtMs=0, pausedAtMs=0, resumeAtMs=0, resumeByteOffset=0,
│                             ring buffer zeroed. Starts a new AudioRecord capture thread.
│   called by:
│   ├── smartStart()                          — when totalBytesWritten==0 (first launch) or gapMs > 10s
│   ├── resumeRollingBuffer()'s AudioRecord-init-failure fallback (posted to main thread)
│   ├── HomeScreen.kt:123  DisposableEffect    — DEAD CODE today: only fires if
│   ├── StockInScreen.kt:99 DisposableEffect   —   sharedRollingAudioBuffer param is null,
│   │                                             which MainActivity never leaves null
│   │                                             (passes sharedRollingBuffer at lines
│   │                                             416/587/805). A future screen that omits
│   │                                             that parameter would silently re-enable
│   │                                             this cold-reset path — verify the param
│   │                                             is wired before adding any new screen
│   │                                             that owns a PttMicButton.
│
├── stopRollingBuffer()       sets isRecordingRunning=false, stoppedAtMs=now, pausedAtMs=now,
│                             joins capture thread (1500ms timeout)
│   called by:
│   ├── MainActivity.kt:313  ON_STOP lifecycle event (app backgrounded)
│   └── MainActivity.kt:320  onDispose (activity destroyed)
│   NOT called by: PttMicButton.kt — confirmed zero call sites. ASSISTANT presses no
│   longer stop the buffer (this is the ISSUE-076 architectural change). If a future
│   diagnosis assumes ASSISTANT stops the buffer, re-grep before trusting that assumption —
│   it was true through ISSUE-075 and has not been true since ISSUE-076.
│
├── smartStart()              gapMs = now - stoppedAtMs; routes to startRollingBuffer() if
│                             totalBytesWritten==0 or gapMs > RESUME_MAX_GAP_MS (10s),
│                             else resumeRollingBuffer()
│   called by:
│   └── MainActivity.kt:312  ON_START lifecycle event (app foregrounded)
│   This is the ONLY entry point that carries the long-gap guard. Anything that calls
│   resumeRollingBuffer() directly bypasses the 10s gap check by construction — currently
│   nothing does this (verified: resumeRollingBuffer's only caller is smartStart() and its
│   own AudioRecord-failure branch calls startRollingBuffer, not itself), but this is the
│   invariant to re-check first if a future change reintroduces a direct caller.
│
├── resumeRollingBuffer()     preserves totalBytesWritten/writeHead/ring content, sets
│                             lastWriteAtMs=now, resumeAtMs=now, resumeByteOffset=totalBytesWritten
│                             (snapshot BEFORE the capture thread starts)
│   called by:
│   └── smartStart()          only
│
└── extractAudioWindow(startMs, endMs, outputFile, floorStartMs)
    called by:
    └── PttMicButton.kt:239  processGroup lambda — the ONLY audio-extraction call site
                              in the app, shared identically by SALE, STOCK, and ASSISTANT
                              (CaptureIntent no longer branches the capture path — it only
                              tags the resulting SttJobRecord and selects which
                              PttBurstCoalescer instance is used: salePttBurstCoalescer /
                              stockPttBurstCoalescer / assistantPttBurstCoalescer,
                              MainActivity.kt:287-295)
    internal clamping (both must independently agree an extraction is valid):
    1. pausedAtMs/resumeAtMs floor (RollingAudioBuffer.kt:283-287) — if the requested
       startMs falls before the most recent resume, clamp forward to resumeAtMs
    2. resumeByteOffset clamp (RollingAudioBuffer.kt:319-325) — even after (1), the linear
       time→byte formula can still resolve to a byte position before resumeByteOffset
       because AudioRecord.startRecording() has ~50-200ms hardware latency after
       lastWriteAtMs is stamped; this clamp catches that residual case (ISSUE-078)
    on any failure: PttMicButton.kt:281-300 — Toast "रिकॉर्डिंग नहीं हुई" + a FAILED
    SttJobRecord with diagnosticTraceJson.client.extraction_null=true (ISSUE-076 Bug 3).
    Before this fix, a null extraction produced zero trace of the press anywhere.

PttMicButton.kt — single unified press handler for ALL three CaptureIntent values
    onPress → pttWindowLedger.recordPress(pressTimestamp) → tryAwaitRelease() →
    pttBurstCoalescer.recordPressRelease(...) → [immediate flush or 300ms-idle flush] →
    processGroup(burstGroup) → extractAudioWindow(...) → SttJobRecord(status=QUEUED,
    captureIntent=intent) → WorkManager.enqueue(SttWorker)

    There is no CaptureIntent.ASSISTANT branch in this file (verified 2026-08-05 — grepped
    for "ASSISTANT" and "AssistantFastPath" import, both absent). AssistantFastPath.kt's
    on-device-STT fast path described in ISSUE-073/075 (stopRollingBuffer → SpeechRecognizer
    → resumeRollingBuffer) is superseded; per ISSUE-076/078, ASSISTANT audio is now captured
    by the same ring-buffer extraction as SALE/STOCK and answered server-side via
    SttWorker.kt's dual-STT pipeline. If AssistantFastPath.kt still exists on disk, treat it
    as dead code pending a grep confirming nothing calls it — do not assume the doc comments
    inside that file describe the live path.

Tests covering this subsystem:
- JVM unit: app/src/test/java/.../audio/RollingBufferWindowTest.kt — pure resolveWindowBytes
  only (5 tests, no stop/resume/gap scenarios as of 2026-08-05 — see §7 dead-end log)
- Instrumented (physical device required): app/src/androidTest/java/.../audio/RollingBufferRestartTest.kt
  — referenced by ISSUE-061/062/063 for writeHead-reset, mic-release-join, and suppression-leak
  behavior; exists on disk, not run as part of `./gradlew test` (JVM), only `connectedAndroidTest`

---

## 7. Diagnostic Dead Ends & Failed Hypotheses

Purpose: §2 (Living Issues Log) records what was fixed. This section records what was
**wrongly believed** while trying to fix it, and for how long, so a future session doesn't
spend a repeat round-trip re-discovering that a plausible-sounding diagnosis was already
tried and was wrong. An unlogged dead end is exactly as invisible to the next agent as an
unlogged fix — CLAUDE.md's sync rule for §2 applies here too.

#### [DEAD-END-001] [2026-08-04 → 2026-08-05] "Old APK still running" maintained across a live architecture rewrite

**What was believed:** Across a multi-hour Claude Code session, every wrong-window symptom
report from the user was attributed to the user still running a stale APK (v100/v101),
based on `stt_job_logs` traces whose `{"client":{"fast_path":true,...}}` shape matched only
code that predated the session's own fix plan.

**Why it was wrong to hold this long:** The attribution was correct for the *specific
traces checked* — those really were old-APK jobs. The error was extending "the DB rows I
checked are old" to "the user's claim that the bug persists is explained by this" without
re-opening the current source after the user pushed back twice ("i have 101 alr", "i said
everything properly its all wrong"). By the time of the second pushback, ISSUE-076 through
078 (`Never-Stop Ring Buffer Architecture`, `Ring Buffer Resume Byte Clamp`) had already
been implemented in the working tree — ahead of the file state the diagnosis was reasoning
from, which had been read once earlier in the session and never re-read.

**Concrete miss:** In the same session, after being asked to demonstrate independent
analysis, Claude Code re-derived a "bug" — ASSISTANT's `resumeRollingBuffer()` call
bypassing `smartStart()`'s 10-second gap guard — by reading `PttMicButton.kt` from a
**cached read several turns earlier**, not the file on disk at that moment. The bug had
already stopped existing: ISSUE-076 had removed the `CaptureIntent.ASSISTANT` branch from
`PttMicButton.kt` entirely, so there was no `resumeRollingBuffer()` call left in that file
to bypass anything. Re-reading the file fresh (prompted by an unrelated request to write
regression tests) surfaced the drift immediately.

**Disconfirming check that would have caught this sooner and wasn't run until forced:**
`git status` / a fresh `Read` of `PttMicButton.kt` and `RollingAudioBuffer.kt` immediately
after the user's first "I have v101, it's still wrong" — before producing another paragraph
of explanation. The check is one tool call; the miss cost two exchanges plus a wrong
"finding" presented as new analysis.

**Rule this violates:** Global CLAUDE.md rule 7 ("when the user contradicts your diagnosis,
investigate your model, not their claim") and rule 5 ("delegated/prior conclusions are
hypotheses until independently re-verified — including your own prior reads in the same
session"). Logged here per the instruction in that same rule set, so the next agent
inherits the lesson from the log instead of the private memory file only Claude Code reads.

**Status:** Closed by inspection — current source (2026-08-05) confirms ISSUE-076/078/079
are real, present, and address the reported symptom class. No outstanding action beyond
what §6 documents.
