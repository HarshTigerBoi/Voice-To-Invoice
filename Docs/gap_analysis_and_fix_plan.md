# Gap Analysis & Fix Plan — Voice Assistant / Customer Ledger

> Audit performed 2026-07-30 against working tree (uncommitted). Verified by reading source and
> reference-counting every new component, not by reading the implementation plan.
> Plan being audited: [`implementation_plan_voice_assistant.md`](implementation_plan_voice_assistant.md)

---

## 0. Headline

**The code was written. It was not connected.**

Phase 0 (data layer) is genuinely done and correct. Phases 1 and 4–7 exist as compiling source
files that **nothing references** — ten components with zero call sites outside their own file.
The app therefore behaves as if only the tab bar and two mics were built.

Reference count of every new component (`grep -rn <name> app/src/main/java -l`, excluding its own file):

| Component | Referenced by | Live? |
|---|---|---|
| `CustomerRecord`, `CustomerDao`, migration v16 | `AppDatabase`, `SyncEngine`, `CloudSyncManager` | ✅ |
| `PttMicButton` | `HomeScreen`, `AssistantFloatingButton` | ✅ |
| `CustomerListScreen`, `CustomerEditScreen` | `MainActivity` | ✅ |
| `CustomerCard`, `CustomerMergeDialog` | customer screens | ✅ |
| **`EntityResolver`** | *(tests only)* | ❌ dead |
| **`UdhaarPickerOverlay`** | — | ❌ dead |
| **`AssistantFloatingButton`** | — | ❌ dead |
| **`IntentRouter`** | — | ❌ dead |
| **`QuestionTemplates`** | — | ❌ dead |
| **`LedgerQueries`** | `QuestionTemplates` *(itself dead)* | ❌ dead |
| **`ActionExecutor`** | — | ❌ dead |
| **`ConversationController`** | — | ❌ dead |
| **`SpeechOutput`** | `ConversationController` *(itself dead)* | ❌ dead |
| **`ResponseComposer`** | `QuestionTemplates` *(itself dead)* | ❌ dead |
| **`WakeWordController`** | — | ❌ dead |

This is good news: the remaining work is mostly **wiring**, not authoring.

---

## 1. Findings by severity

### 🔴 S1 — "उधार बेचो" books an ordinary **CASH** sale *(data integrity)*

The chain dead-ends after one hop:

1. `HomeScreen.kt:245` — `PttMicButton(intent = CaptureIntent.CREDIT_SALE)` ✅
2. Intent is stamped onto `SttJobRecord.captureIntent` ✅
3. **`SttWorker.kt:337` hardcodes `paymentMode = PaymentMode.CASH`** and never reads
   `captureIntent` ❌
4. `SttProxyClient` never forwards the intent — `process-voice-job/index.ts` contains **zero**
   occurrences of `capture_intent` ❌
5. No `CreditRecord` is created from voice. The only `CreditRecord(...)` construction in the whole
   app is `MainActivity.kt:245`, the **manual** entry path ❌
6. No code path anywhere assigns `customerId` ❌

**This is exactly the reported symptom:** a credit sale was recorded, and हिसाब shows nothing,
because it was silently written to the ledger as a cash sale with no customer and no credit row.

### 🔴 S2 — No customer is ever linked to anything
`EntityResolver` and `UdhaarPickerOverlay` are both dead. There is no ranking, no picker, no
"कौन सा?", no auto-assign. `CustomerRecord.lastSeenMs` / `txnCount` are never updated, so the
recency/frequency priors can never accumulate even once resolution is wired.

### 🟠 S3 — No customer detail screen exists
`MainActivity.kt:363` — `onCustomerClick = { customer -> currentScreen = Screen.UDHAAR }`.
Tapping a customer opens the **generic** udhaar screen, discarding the customer entirely.
The बही-खाता card (name · #code · keyword · phone · outstanding · entries) was specified in
Phase 2 and never built as a screen. **This is the reported "customer profile doesn't show it".**

### 🟠 S4 — Summary is unreachable from the tab bar
Tab 3 hardcodes `onClick = { currentScreen = Screen.CUSTOMER_LIST }` (`MainActivity.kt:160`).
`Screen.SUMMARY` is *selected-state* aware (line 159) but has no navigation path from the tab.
Its only entry point is an `OutlinedButton` in `HomeScreen` — which S5 covers with a banner.

### 🟠 S5 — The review bar covers the quick-action row
`HomeScreen.kt:288` draws `PendingConfirmationsBar` with
`Modifier.align(Alignment.TopCenter).padding(top = 16.dp)` inside a `Box`, directly on top of the
`Column` whose first child is the Udhaar / Suppliers / Prices / Summary / Logs `Row` (lines
185–213). The overlap is unconditional whenever pending count > 0 — and it currently reads
"23 sales ready to confirm", so it is always on.

### 🟠 S6 — Stock tab has no mic at all
`StockInScreen.kt` contains **zero** mic/PTT references. It is still the pre-existing manual
form (Catalog Item / Quantity / Cost / Supplier / Save). Neither **माल आया** nor **खराब** exists,
so waste capture has no entry point anywhere in the app.

### 🟠 S7 — Assistant has no button, and the wake word is inert
`AssistantFloatingButton` has zero call sites, so there is no assistant affordance on any screen —
violating design principle #1 (discoverability). `WakeWordController` is likewise unreferenced and
never started, so **"बिल वाले" cannot wake anything.** No `SpeechOutput` call site exists either,
so the app currently has **no TTS at all** — every spoken response in the plan is absent.

### 🟡 S8 — Stray cut-off bar at the top of every screen
All three screenshots show a partially-clipped row reading "बेचो ₹ / माल + / हिसाब" above the
title. There is only one `NavigationBar` in the source (`MainActivity.kt:143`, inside `bottomBar`),
and it renders correctly at the bottom. Likely cause is **nested `Scaffold`s** — `MainActivity`
wraps everything in a `Scaffold`, and `HomeScreen` declares its own (it consumes a `padding`
parameter at `HomeScreen.kt:178`) — combined with `enableEdgeToEdge()` at `MainActivity.kt:50`
double-applying window insets. **Needs on-device confirmation before fixing; do not guess.**

### 🟡 S9 — `CLAUDE.md` is stale and misled the plan
`CLAUDE.md` states `AppDatabase` is at **version 8**. It is actually at **version 16**
(`AppDatabase.kt:29`). The implementation plan consequently specified a "8→9" migration; the
implementer correctly did 15→16 instead. Same drift risk applies to the APK version counter.

### 🟡 S10 — Repo hygiene
Untracked JVM crash artifacts at repo root: `hs_err_pid24832.log`, `hs_err_pid25368.log`,
`replay_pid24832.log`, `replay_pid25368.log`. Add to `.gitignore` and delete.

---

## 2. What *is* correctly done — do not redo

- `CustomerRecord` entity with `code`, `keyword`, `phone`, `photoPath`, `phoneticKey`,
  `lastSeenMs`, `txnCount`, `mergedIntoId` — matches spec.
- `MIGRATION_15_16`: creates `customers`, adds `customerId` to `credits` and `transactions`,
  **and backfills** distinct `customerName` values (`AppDatabase.kt:310`). Correct.
- `CustomerDao` with `getLedgerFor`, `getOutstandingFor`, merge queries, duplicate detection.
- Cloud mirror: `supabase/migrations/20260730000000_create_customers.sql`, `SyncEngine` sweep,
  `CloudSyncManager` pushes `customer_id` on both transactions and credits.
- `PttMicButton` was extracted **once** and parameterised — risk R6 from the plan was respected.
- `EntityResolverTest.kt` exists.

---

## 3. Fix plan

Ordered so that **data integrity is restored before any feature is added**. Fixes 1–3 are the
minimum to make the app honest about what it recorded.

### Fix 1 — 🔴 Make उधार actually mean credit *(do first, alone, verify before continuing)*
1. `SttWorker` reads `job.captureIntent` and derives
   `paymentMode = if (intent == CREDIT_SALE) PaymentMode.CREDIT else PaymentMode.CASH`
   — replace the hardcode at `SttWorker.kt:337`.
2. On `CREDIT_SALE`, also insert a `CreditRecord` linked via `linkedTransactionId`, with
   `customerId = null` initially (booking must never wait for identification — principle #2).
3. `SttProxyClient` forwards `capture_intent` in the upload payload.
4. `process-voice-job/index.ts` reads `capture_intent`, and its server-side auto-confirm writes
   `payment_mode = 'CREDIT'` plus a `credits` row. **Both client and server mirror this logic** —
   per `CLAUDE.md`, changing one without the other is the recurring failure mode in this repo.
5. Write `captureIntent` into `diagnosticTraceJson.step_1` so the logs screen can prove it.
6. **Backfill/repair:** existing mis-booked credit sales are indistinguishable from cash sales in
   the ledger. Identify them via `stt_jobs.captureIntent = CREDIT_SALE` joined to their
   transactions, and offer a one-time repair sweep. Anything recorded before `captureIntent`
   existed is unrecoverable — say so plainly rather than silently guessing.

**Verify:** record an उधार sale → `transactions.paymentMode = CREDIT`, a `credits` row exists,
हिसाब shows it.

### Fix 2 — 🔴 Wire customer resolution + the non-blocking picker
1. After a `CREDIT_SALE` job resolves, call `EntityResolver` over active customers using any
   spoken name/keyword/code/phone.
2. `AUTO_ASSIGN` → set `customerId` on transaction and credit; bump `lastSeenMs` / `txnCount`.
3. Otherwise → show `UdhaarPickerOverlay`, ranked, **never empty** (principle #3), with `+` and `⋯`.
4. **Collapse the overlay to a pending badge the instant any mic is pressed** (principle #2).
5. Unassigned credits must be reachable and resolvable from the हिसाब tab.

**Verify:** the EntityResolverTest cases hold end-to-end, and an empty candidate list with a
non-empty customer pool is impossible.

### Fix 3 — 🟠 Build `CustomerDetailScreen` and route to it
New `ui/screens/customer/CustomerDetailScreen.kt` — the बही-खाता page: header
(photo · name · `#003` · keyword · phone) · outstanding · dated entry list from
`CustomerDao.getLedgerFor()`. Add `Screen.CUSTOMER_DETAIL` carrying the customer id, and fix
`MainActivity.kt:363` to navigate there instead of `Screen.UDHAAR`.

### Fix 4 — 🟠 Make हिसाब reach both customers and summary
Recommended: a segmented control at the top of the हिसाब tab — **ग्राहक | सारांश** — keeping three
tabs. (Alternative: a 4th tab, at the cost of a more crowded bar.) Either way `Screen.SUMMARY`
must be reachable without depending on the HomeScreen button row.

### Fix 5 — 🟠 Stop the review bar covering the quick actions
The banner is an overlay on a `Box`; the quick-action `Row` is the first child of the `Column`
underneath. Options:
- **(a) Recommended** — make the banner a normal first child of the `Column` (in-flow, pushes
  content down) rather than a `TopCenter` overlay.
- (b) Keep it floating but add top padding to the `Column` equal to the banner height when
  `pendingCount > 0`.
- (c) Move the quick actions into the हिसाब tab entirely — they are secondary navigation and
  arguably do not belong on the capture screen at all. **This also fixes Fix 4 for free.**

### Fix 6 — 🟠 Give the stock tab its mics
Add two `PttMicButton`s to `StockInScreen`: **माल आया** (`STOCK_IN`) and **खराब** (`WASTE`),
mirroring the sell screen's layout so the pattern is learnable. Keep the manual form below as the
fallback. `WASTE` writes a **negative stock adjustment — never a ₹0 sale**, which would corrupt
every revenue and profit figure the assistant later reports.
Requires `SttWorker` + `process-voice-job` branches for both intents.

### Fix 7 — 🟠 Wire the assistant (button first, wake word later)
1. Render `AssistantFloatingButton` in `MainActivity`'s `Scaffold` so it is present on **every**
   screen — this is the discoverability affordance and it must ship before any wake word.
2. Wire `SpeechOutput` (Sarvam TTS via a new edge function so the key stays server-side; Android
   `TextToSpeech` offline fallback) — nothing speaks today.
3. Wire `ConversationController` for auto-reopen-mic, barge-in, 4s timeout, 2-turn cap.
4. Wire `IntentRouter` → `QuestionTemplates` → `LedgerQueries` for the read path, and confirm
   `ReadExecutor` has no write access.
5. Idle-state hint carousel: speak + show three example commands from the shop's own data.

### Fix 8 — 🟡 Wake word (last)
`WakeWordController` exists but is never started. Ship only after Fix 7, **default off**, with
two-stage VAD gating, measured battery on real hardware, and the Play Store prominent-disclosure
update in `play_console_declaration.md`.

### Fix 9 — 🟡 Housekeeping
- Update `CLAUDE.md`: DB version 8 → 16; re-check the APK version counter against the folder.
- `.gitignore` + delete `hs_err_pid*.log`, `replay_pid*.log`.
- Log S1 as a new `ISSUE-NNN` in `Docs/audit.md` §2 (next sequential number after ISSUE-035) —
  it is a genuine behaviour-affecting bug, not a missing feature.

---

## 4. Recommended sequencing

| | Fix | Why here |
|---|---|---|
| 1 | **Fix 1** — credit ⇒ CREDIT | Data integrity. Every hour it runs, more sales are mis-booked |
| 2 | **Fix 3** — customer detail screen | Makes Fix 1's output visible and verifiable |
| 3 | **Fix 5(c)** — move quick actions to हिसाब | Unblocks the UI and delivers Fix 4 at the same time |
| 4 | **Fix 2** — resolver + picker | The feature proper, now that the ledger is trustworthy |
| 5 | **Fix 6** — stock/waste mics | Completes capture coverage |
| 6 | **Fix 7** — assistant button + TTS | Largest new surface; needs everything above working |
| 7 | **Fix 8** — wake word | Explicitly last, per the original plan |
| 8 | **Fix 9** — housekeeping | Continuous |

**Do Fix 1 by itself and verify on-device before starting anything else.** It is the only item
that is actively corrupting data, and bundling it with UI work would make the verification
ambiguous.

## 5. Open question for the user

S8 (stray bar at screen top) needs one on-device check to distinguish nested-`Scaffold` inset
double-application from something else. Worth confirming before touching the layout.
