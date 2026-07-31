# Voice Assistant & Customer Ledger — Implementation Plan

> **Audience: whichever agent implements this (Claude Code / Antigravity).** Self-contained.
> Written 2026-07-29. Verified against source on that date.
>
> Companion docs (option spaces and rationale, not decisions):
> [`multi_intent_capture_plan.md`](multi_intent_capture_plan.md) ·
> [`voice_assistant_framework.md`](voice_assistant_framework.md) ·
> [`customer_ledger_design.md`](customer_ledger_design.md)
>
> **Ground truth verified 2026-07-29:** there is no customer entity —
> `CreditRecord.customerName` is a free-text string, `CreditDao` has no per-customer query.
> `PhoneticKey` already exposes `of()` / `distance()` / `normalizedDistance()` and is directly
> reusable for names. `AppDatabase` is at **version 8**, manual migrations only.
> `MainActivity` uses a hand-rolled `enum class Screen` + `when` block, no ViewModel layer.

---

## 0. Design principles — every decision below derives from these

1. **Discoverability over elegance.** A feature the shopkeeper doesn't know exists is worth zero.
   Every capability gets a **visible, permanent affordance**. Hidden gestures and wake words are
   *accelerators layered on top of visible controls* — never the only way to reach anything.
2. **Never block the next recording.** Any dialog, picker, or confirmation must yield instantly
   when a mic is pressed. Rush hour is the design constraint.
3. **Never hard-filter to zero.** Spoken text ranks candidates; it never eliminates them.
   An empty picker is a bug.
4. **Propose, don't execute.** Reads answer instantly. Writes and outbound messages get a
   confirmation turn.
5. **Admit uncertainty.** "समझ नहीं आया, फिर से बोलिए" beats a confident wrong number. In a ledger
   app, trust is the product.
6. **One pipeline.** Buttons and the assistant differ only in whether intent is *given* or
   *inferred*. Never two parsing paths for the same thing.

---

## 1. Target architecture

```
                    ┌──────────────── intent GIVEN ─────────────┐
                    │  नकद mic → SALE                            │
   Audio ──► STT ───┤  उधार mic → CREDIT_SALE                    ├──► Slot Filler
   (existing        │  माल mic  → STOCK_IN                       │
    3-way race)     │  खराब mic → WASTE                          │
                    └────────────────────────────────────────────┘
                    ┌──────────── intent INFERRED ───────────────┐
                    │  Assistant button / wake word              │
                    │      → IntentRouter classifies             │
                    └────────────────────────────────────────────┘
                                        │
                                        ▼
                             EntityResolver<T>
                        (items · customers · suppliers)
                                        │
              ┌─────────────────────────┼─────────────────────────┐
              ▼                         ▼                         ▼
        WriteExecutor            ReadExecutor              ActionExecutor
     (transactions,          (LedgerQueries —          (WhatsApp draft, dial,
      credits, stock)         read-only by type)        price update, mark paid)
              └─────────────────────────┼─────────────────────────┘
                                        ▼
                                   Responder
                            (sentence → Sarvam TTS)
                                        │
                                   Clarifier loop
                        (ambiguous ⇒ speak question + reopen mic,
                         max 2 turns, then fall back to visual card)
```

**Key property:** `ReadExecutor` is constructed with a read-only query surface and has no DAO
write access *in the type system*. A question can never book a sale — enforced structurally,
not by a confidence threshold.

---

## 2. Surface map

### Bottom tab bar (3 tabs)
| Tab | Label | Mics on screen |
|---|---|---|
| 1 | **बेचो** ₹ | **नकद** (large, green) · **उधार** (medium, amber) |
| 2 | **माल** + | **माल आया** (large, blue) · **खराब** (medium, red) |
| 3 | **हिसाब** 📊 | none — existing summary, customer list, credit list |

App always opens on **बेचो**. Auto-return to बेचो after 60s idle on tabs 2–3.

### Assistant — visible everywhere (principle #1)
- A **permanent floating button** (bottom-right, above the tab bar) on *every* screen.
  This is the discoverable affordance. It is never hidden, never behind a menu.
- **Wake word** ("बिल वाले" / "Hello Bill") is an **accelerator for the same thing** — for when
  the phone is across the counter. Off by default; introduced by the app after ~1 week of use
  ("अब आप 'बिल वाले' बोलकर भी बुला सकते हैं").
- The assistant handles **every** intent, not just questions:
  - `"बिल वाले, पाँच किलो आलू"` → SALE
  - `"रमेश बिजली वाला, दस किलो आलू"` → CREDIT_SALE, customer resolved
  - `"आज कितना कमाया?"` → READ
  - `"रमेश को मैसेज भेजो"` → ACTION

### Discoverability mechanisms (build these, they are not optional polish)
- **Assistant idle-state hint carousel** — when opened with no speech, it *speaks and shows*
  three rotating example commands drawn from the shop's own data:
  *"आप पूछ सकते हैं — आज कितना कमाया?"*
- **Customer cards** (never a bare name list) — the card *is* the feature advertisement.
- **First-run voice tour**: one spoken sentence per mic, triggered on first visit to each tab.
- **Post-success nudges**: after the 5th udhaar sale → *"अब आप 'तीन नंबर' बोलकर भी चुन सकते हैं."*
  Teach the accelerator only after the basic path is habitual.

---

## 3. Phase 0 — Data foundation *(no UX risk; everything depends on it)*

### 3.1 New entity
`app/src/main/java/com/voicetoinvoice/app/data/local/entity/CustomerRecord.kt`

```kotlin
@Entity(
    tableName = "customers",
    indices = [Index("phoneticKey"), Index("code", unique = true), Index("phone")]
)
data class CustomerRecord(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val shopId: String = "default_shop",
    val code: Int,                     // 1,2,3… NEVER reused after delete/merge
    val name: String,
    val keyword: String? = null,       // "दोसे वाले", "बिजली वाला", "डॉक्टर"
    val phone: String? = null,
    val photoPath: String? = null,
    val phoneticKey: String,           // PhoneticKey.of(name)
    val keywordPhoneticKey: String? = null,
    val lastSeenMs: Long = 0L,         // recency prior
    val txnCount: Int = 0,             // frequency prior
    val mergedIntoId: String? = null,  // tombstone; excluded from all active queries
    val createdAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)
```

### 3.2 Entity changes
- `CreditRecord` += `customerId: String?` *(keep `customerName` — backfill safety, never drop)*
- `TransactionRecord` += `customerId: String?`

### 3.3 Migration
`AppDatabase` **8 → 9**. Follow the existing pattern exactly: bump `version`, add
`MIGRATION_8_9` with try/catch'd `ALTER TABLE` / `CREATE TABLE`, register in `addMigrations(...)`.
No auto-migrations.

**Backfill (inside the migration or a one-shot `onOpen` task):**
1. `SELECT DISTINCT customerName FROM credits WHERE customerName IS NOT NULL AND customerName != ''`
2. Create one `CustomerRecord` per distinct name, codes assigned in first-seen (`MIN(updatedAt)`) order.
3. `UPDATE credits SET customerId = ...` matching on name.
4. Log the backfill count into the diagnostic trace so it is verifiable after the fact.

### 3.4 `CustomerDao`
```
getActiveCustomers(): Flow<List<CustomerRecord>>        -- mergedIntoId IS NULL
getByCode(code: Int): CustomerRecord?
getByPhoneSuffix(suffix: String): List<CustomerRecord>
getByPhoneticKey(key: String): List<CustomerRecord>
getLedgerFor(customerId): Flow<List<TransactionRecord>>
getOutstandingFor(customerId): Double
nextFreeCode(): Int                                     -- MAX(code)+1 over ALL rows incl. tombstones
insert / update / mergeInto(sourceId, targetId)
findLikelyDuplicates(): List<Pair<CustomerRecord, CustomerRecord>>
```

`mergeInto` must, in one transaction: reassign `credits.customerId` and
`transactions.customerId`, sum `txnCount`, keep the **lower** code, set `mergedIntoId` on the
source, mark both unsynced.

### 3.5 Cloud
`supabase/migrations/` — new `customers` table, RLS-enabled, mirroring the entity.
`SyncEngine` gains a customers sweep (identical `synced` pattern to every other entity).
`CloudSyncManager` gains `pushCustomer`.

**Acceptance:** existing credits survive upgrade with correct `customerId`; codes are unique and
gapless-from-1; merge reassigns every record and never reuses a code.

---

## 4. Phase 1 — `EntityResolver<T>`

`app/src/main/java/com/voicetoinvoice/app/domain/resolver/EntityResolver.kt`

Generalise the item-matching logic already implemented around `PhoneticKey`. One component,
three consumers: **items, customers, suppliers.**

```kotlin
interface Resolvable {
    val resolverId: String
    val primaryPhoneticKey: String
    val altPhoneticKeys: List<String>   // keyword, aliases
    val exactTokens: List<String>       // code as digits, phone, phone suffix
    val lastSeenMs: Long
    val useCount: Int
}

data class ResolutionResult<T>(
    val candidates: List<Scored<T>>,    // ALWAYS non-empty when the pool is non-empty
    val decision: Decision               // AUTO_ASSIGN | ASK | NONE_AVAILABLE
)
```

Scoring:
```
score = w1·phoneticName + w2·phoneticKeyword + w3·exactCode
      + w4·exactPhone   + w5·recency(lastSeenMs) + w6·log1p(useCount)

AUTO_ASSIGN  iff  top1 >= THRESHOLD  &&  (top1 - top2) >= MARGIN
otherwise    ASK  (ranked list, never truncated to zero — principle #3)
```

Start with `THRESHOLD = 0.80` (consistent with the existing auto-confirm bar) and
`MARGIN = 0.15`; record both in `Docs/audit.md` §1 as source-of-truth constants.

**Acceptance:** unit tests in `app/src/test/.../resolver/` covering — exact name; misheard name
(रामेश→रमेश); three same-name customers; name+keyword unique; code only; phone only; no speech at
all (recency ordering); empty pool. **A zero-length candidate list with a non-empty pool must fail
the test suite.**

---

## 5. Phase 2 — Customer UI

`ui/screens/customer/` — new package.

- **`CustomerCard.kt`** — the बही-खाता page: photo (or initial circle) · name · `#003` ·
  keyword · phone · outstanding · recent entries. One component reused in the list, the picker,
  and the detail screen.
- **`CustomerListScreen.kt`** — cards, sorted by outstanding then recency. Duplicate-suspect
  banner at top when `findLikelyDuplicates()` is non-empty.
- **`CustomerEditScreen.kt`** — create/edit. On create: **speak the name back**
  (*"नया ग्राहक — रामेश — सही है?"*) and show similar existing customers **above** the form
  (*"क्या यह इनमें से है?"*) with faces. Make the duplicate visible at the moment one is created.
- **`CustomerMergeDialog.kt`** — "ये दोनों एक ही हैं", side-by-side, explicit confirm.

Photo capture is **optional and shopkeeper-initiated** (see §12 risk R4).

---

## 6. Phase 3 — Tabs + non-blocking udhaar capture

### 6.1 Navigation
Replace the `enum class Screen` + `when` in `MainActivity.kt` with a `Scaffold(bottomBar = …)`
holding three tabs; existing screens (catalog, suppliers, prices, logs, settings) move behind a
**हिसाब**-tab overflow. Keep the hand-rolled navigation pattern — do **not** introduce
Navigation Compose in this phase.

### 6.2 Sell tab
Extract the existing PTT gesture block (`HomeScreen.kt` ~217–398 — the coalescer/ledger/WorkManager
logic) into a reusable **`PttMicButton`** composable parameterised by
`(intent: CaptureIntent, size, color, label, tone)`. **Do not duplicate that logic per mic** — it
carries the burst coalescer, window ledger, and expedited-work wiring, and divergence between
copies would be a severe bug source.

`SttJobRecord` += `captureIntent: CaptureIntent` (`SALE | CREDIT_SALE | STOCK_IN | WASTE | ASSISTANT`),
forwarded through `SttProxyClient` to `process-voice-job`, and written into
`diagnosticTraceJson.step_1`.

### 6.3 The udhaar flow — exact sequence
1. Release **उधार** mic → existing pipeline parses items.
2. **Book immediately**: transactions with `paymentMode = CREDIT`, `customerId = null`,
   plus a `CreditRecord`. *The sale is never held hostage to identification.*
3. `EntityResolver` ranks customers using any spoken name/keyword/code/phone.
4. `AUTO_ASSIGN` → set `customerId`, speak *"रमेश दोसे वाले, नंबर तीन — तीन सौ चालीस बाकी"*. No UI.
5. `ASK` → **non-blocking overlay** of ranked `CustomerCard`s + `+` (new) + `⋯` (none of these);
   app speaks *"कौन सा?"* and **auto-reopens the mic**.
6. Spoken reply narrows by name / keyword / code / phone; unique narrowing assigns immediately.
7. **If any mic is pressed while the overlay is open → overlay collapses instantly** into a
   pending badge *"1 उधार — किसका?"*. Recording proceeds. (Principle #2.)
8. Unassigned credits resolve from the badge, the हिसाब tab, or an end-of-day sweep.

### 6.4 Stock tab
Same `PttMicButton`, intents `STOCK_IN` / `WASTE`. `WASTE` writes a negative stock adjustment —
**never** a ₹0 sale, which would corrupt every profit and sales figure the assistant reports.

**Acceptance:** with the picker open, pressing a mic starts recording within one frame and loses
no audio; an unassigned credit is never silently dropped.

---

## 7. Phase 4 — Voice output & the conversational loop

`domain/voice/` — new package.

- **`SpeechOutput.kt`** — Sarvam TTS primary (same vendor/key as the existing STT path), Android
  `TextToSpeech` offline fallback. Proxy Sarvam TTS through a **new edge function** so the key
  stays server-side, consistent with existing secret handling.
- **`ResponseComposer.kt`** — templated sentences per language; numbers rendered in the local
  convention ("तीन सौ चालीस", not "three hundred forty"). Language detected from the winning STT
  transcript and echoed back in the same language.
- **`ConversationController.kt`** — the clarifier loop:
  - auto-reopen mic after any app question
  - **barge-in**: listen during playback, cut TTS the moment speech is detected
  - 4s timeout → fall back to the visual card, never hang
  - echo cancellation (`AudioManager` voice-communication mode)
  - **max 2 clarification turns**, then visual card
  - universal cancel word ("रहने दो")

**Acceptance:** barge-in cuts TTS within ~200ms; the loop never exceeds 2 questions; TTS is never
transcribed as user speech.

---

## 8. Phase 5 — Assistant button + `IntentRouter`

- **`ui/components/AssistantButton.kt`** — permanent FAB, present on every screen.
- **`domain/router/IntentRouter.kt`** — classifies assistant utterances into
  `SALE | CREDIT_SALE | STOCK_IN | WASTE | READ | ACTION`.
  - Local signals first: honorifics (जी/भाई/चाचा/दादा), case markers (को/ने/के लिए),
    question words (कितना/कब/कौन/क्या), catalog hits, imperative verbs (भेजो/लगाओ/कर दो).
  - Grok fallback for the ambiguous remainder.
  - **Below confidence → ask, never guess** ("बिक्री या सवाल?").
- Assistant idle state shows + speaks three rotating example commands from the shop's own data.

**Acceptance:** "बिल वाले, पाँच किलो आलू" books a sale; "रमेश बिजली वाला दस किलो आलू" books a
credit sale to the right customer; "आज कितना कमाया" never writes anything.

---

## 9. Phase 6 — `LedgerQueries` + the read tier

`domain/query/LedgerQueries.kt` — one parameterised, **read-only** surface. Every read tier wraps
it: local templates now, LLM intent-JSON later, agentic tools after that. Write the queries once.

Ship these (all backed by data that already exists):

| Query | Note |
|---|---|
| stock on hand (item / all) | `getStockLevels()` |
| price of item | |
| sales total by range | day / yesterday / week / month / last N hours |
| sales by item by range | |
| top & bottom sellers | |
| when was item last sold | |
| outstanding total | |
| outstanding by customer | by code, name, keyword, or phone |
| oldest / largest credit | |
| customer ledger | what they bought, when |
| supplier & cost of last stock-in | |
| period comparison | this week vs last |
| **burn rate + days-of-cover** | new; powers "क्या खत्म होने वाला है?" |
| **dead stock** | new; no sales in N days |
| **profit** | ⚠️ blocked on §12 R1 — pick weighted-average costing first |

`domain/query/QuestionTemplates.kt` — ~15 local patterns → `LedgerQueries` calls. Instant, offline,
deterministic. Grok intent-JSON fallback for the tail (the model returns
`{metric, entity, filter, range}`; **the app** executes the query — the model never touches the DB).

**Conversation state:** last entity / metric / range, expiring after 2 minutes or on the next
capture press, so "और कल?" and "क्या क्या लिया?" inherit context.

---

## 10. Phase 7 — Wake word *(last; everything works without it)*

Only after the visible button is shipped and used — principle #1.

**Library options:**
| Option | License | Notes |
|---|---|---|
| **openWakeWord** (ONNX Runtime Android) | Apache 2.0 | ✅ Recommended — free, custom words trainable, no per-seat cost |
| Porcupine (Picovoice) | commercial | Best accuracy, paid for production |
| sherpa-onnx KWS | Apache 2.0 | Good, heavier integration |
| Vosk | Apache 2.0 | Full ASR; overkill and battery-hungry for KWS |

**Architecture — two-stage, mandatory for battery:**
`RollingAudioBuffer` (already continuously recording) → cheap VAD gate → keyword model **only when
speech is present**. Target < 3%/hour on a low-end device; measure on real hardware, not an emulator.

Wake word: **"बिल वाले"** (3 syllables, distinctive, low false-trigger risk; short words like
"बिल" alone will fire constantly in a shop where people literally ask for bills).

**Guards:** confirmation tone on wake · easy cancel · sensitivity setting · auto-disable after
N false triggers with a spoken explanation.

**Compliance — do not skip:** always-on mic requires **prominent disclosure** under Play Store
policy plus a persistent foreground-service notification. `AppForegroundService` and mic permission
already exist, so the incremental delta is disclosure + settings toggle (**default off**). Extend
`play_console_declaration.md` in the same style as the existing UPI-listener justification.

---

## 11. Phase 8 — Actions & advisory

- **WhatsApp**: compose the message, open `wa.me` deep link pre-filled — **the shopkeeper taps
  send.** Programmatic sending is impossible on consumer WhatsApp anyway, and this satisfies
  principle #4. Voice-dictated additions appended before opening.
- **Call**: `ACTION_DIAL` (pre-filled dialler, user presses call).
- **Mark credit paid** / **price update**: propose aloud + confirm. Route price updates into the
  existing `RATE_UPDATE` path — do not reimplement.
- **Advisory v1**: burn rate vs stock → *"टमाटर कल खत्म हो जाएगा."*
- **Closing briefing**: unprompted at shutter-down —
  *"आज ₹4,300 की बिक्री, ₹800 उधार, टमाटर खत्म होने वाला है."*
- **Mandi list**: spoken purchase list from burn rate. This is inventory-as-byproduct becoming a
  daily-used feature rather than a claim.

---

## 12. Risks & decisions required

| | Risk / decision | Resolution |
|---|---|---|
| **R1** | **Costing method blocks every profit question** | Decide before Phase 6. Recommend **weighted average** — vegetables are fungible and it's barely harder than latest-cost |
| **R2** | Server mirror drift — `process-voice-job` re-implements parsing independently | Every new `captureIntent` needs the matching server branch. Verify with `get_edge_function` + grep after each deploy (this repo has a history of silent placeholder deploys) |
| **R3** | Wake-word battery drain on low-end devices | Two-stage VAD gate; measure on real hardware; ship default-off |
| **R4** | Photographing customers is not an established Indian retail norm | Optional and shopkeeper-initiated only. **Pilot with 2–3 real shops before depending on it** for identity |
| **R5** | Duplicate customers degrade the ledger permanently | Merge tool is Phase 2, not "later". Passive duplicate detector alongside it |
| **R6** | `PttMicButton` extraction touches the most delicate code in the app (burst coalescer + window ledger) | Extract *once*, parameterised. Never copy-paste per mic. Regression-test burst coalescing after |
| **R7** | Tab restructure could break existing screen wiring | `MainActivity` owns all DAOs and passes callbacks down; keep that pattern, change only the container |

---

## 13. Sequencing

| Phase | Contents | Risk | Blocks |
|---|---|---|---|
| **0** | `CustomerRecord`, migration 8→9, backfill, `CustomerDao`, cloud mirror | none (data only) | everything |
| **1** | `EntityResolver<T>` + tests | none | 2, 3 |
| **2** | Customer cards, create/edit, **merge**, duplicate detector | low | 3 |
| **3** | Tabs, `PttMicButton` extraction, `captureIntent`, non-blocking udhaar picker | **high** (R6) | 5 |
| **4** | Sarvam TTS, `ResponseComposer`, `ConversationController` | medium | 5, 6 |
| **5** | Assistant button + `IntentRouter` | medium | 6 |
| **6** | `LedgerQueries`, 15 local templates, Grok intent-JSON fallback | low | 8 |
| **7** | Wake word + compliance | medium (R3) | — |
| **8** | Actions, advisory, closing briefing, mandi list | low | — |

**Phases 0–2 are pure data-layer with no UX risk and are the correct starting point.**
Phase 3 is where the rush-hour behaviour is won or lost. Phase 7 is deliberately last: the app
must be fully usable, and its features fully discoverable, without ever saying "बिल वाले".

---

## 14. Working agreements for the implementing agent

- Log every behaviour-affecting fix in `Docs/audit.md` §2 as a sequential `ISSUE-NNN` before
  ending a session; reference the number in the commit message.
- Update `Docs/audit.md` §1 when changing a source-of-truth constant
  (`THRESHOLD`, `MARGIN`, DB version, model names).
- Room changes: bump `version`, add `MIGRATION_N_N+1` with try/catch'd DDL, register in
  `addMigrations(...)`. No auto-migrations.
- After any `assembleDebug`, copy the APK to
  `C:\Users\harsh\OneDrive\Desktop\VoiceToInvoice_APKs\VoiceToInvoice_v<N+1>.apk`
  (`ls` the folder first — the version number drifts).
- Edge-function changes deploy immediately once verified, then re-fetch the live bundle and grep
  for expected markers to confirm the deploy actually carried the change.
