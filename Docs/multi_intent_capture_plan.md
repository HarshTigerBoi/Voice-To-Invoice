# Multi-Intent Voice Capture — Design Option Space

> Status: **exploration, nothing committed.** Written 2026-07-29. Grounded against the actual source
> (`HomeScreen.kt`, `data/local/entity/*`, `domain/parser/*`) — not against the docs, which drift.
> Purpose: enumerate every viable design for "more than one mic button" before we build anything.

---

## 0. The framing that unlocks this

The instinct is to list `sale / stock-in / udhaar / waste` as four peer things needing four buttons.
They are **not peers**. Two independent axes are being collapsed into one question:

**Axis 1 — Read vs Write.** Recording an event vs asking a question.
**Axis 2 — Which event.** Sale / stock-in / waste.
**Axis 3 — Payment attribute.** Cash / UPI / Udhaar. *This is not an event at all.*

Once separated:

| What physically happened | Is it an event? | Where it belongs |
|---|---|---|
| Goods go **out** to a customer | yes | SALE |
| Goods come **in** from a supplier | yes | STOCK |
| Goods **gone**, no customer (खराब/सड़ा) | yes | STOCK, negative |
| Money owed instead of paid now | **no — attribute of a sale** | `PaymentMode.CREDIT` |
| Money comes in later (repayment) | yes, but money-only | credit settlement |
| "How much did I make today?" | **no — a read** | ASK |

`TransactionRecord` already has `PaymentMode.CREDIT`, and `CreditRecord.linkedTransactionId`
already points back at the sale. **The udhaar plumbing exists.** The only missing datum is
*which customer* — and a proper noun is the single worst payload to hand to Hindi STT.

### The one split that is genuinely non-negotiable

**A question must never be able to book a sale.** If reads and writes share one mic, a single
misheard "कितना आलू है" becomes a sale of आलू. A separate ASK button makes the read path
*structurally incapable* of writing — no classifier to get wrong, no confidence threshold to tune.
That is a stronger argument than any UX argument on this page.

---

## 1. Button architecture — every option

### A1. One mic, intent inferred from speech (status quo + classifier)
Shopkeeper says "उधार" / "माल आया" / "कितना" and we classify.
- **Pro:** zero UI change, zero muscle-memory change, most "natural".
- **Con:** the intent keyword becomes the weakest link in an already-noisy Hindi STT chain.
  Forces the shopkeeper to *memorise vocabulary* — that is the literacy burden moved, not removed.
- **Fatal con:** no structural barrier between read and write.
- **Verdict:** viable as a *bonus* path layered on buttons. Not as the primary mechanism.

### A2. Two buttons — SALE + ASK
Split reads from writes; stock/waste stay on the existing manual screens.
- **Pro:** the safety property, at minimum cost. Sale path untouched.
- **Con:** stock-in stays typed, so the inventory moat stays weak.

### A3. Three buttons — SALE + STOCK + ASK  ← *baseline recommendation*
STOCK covers both goods-in and waste (both are "inventory changed, no customer").
- **Pro:** complete coverage, each button = one direction of physical goods.
- **Con:** three targets to distinguish without reading. Solvable (see §4).

### A4. Four buttons — SALE + STOCK + UDHAAR + ASK
UDHAAR mic pre-sets `paymentMode = CREDIT`.
- **Pro:** intent is unambiguous, no post-sale tap.
- **Con:** still needs a name, so it doesn't actually remove the hard part. And it makes a
  *payment attribute* look like a peer of *physical events*, which is conceptually wrong and
  will confuse the mental model. Four targets is past the comfortable limit.
- **Verdict:** not recommended, but cheap to add later if pilot shops ask for it.

### A5. One mic + mode chips above it
Three coloured chips over a single mic; tapping one changes the mic's colour/meaning.
- **Pro:** one motor target — the thumb always goes to the same place. Mode is *visible*.
- **Fatal con if sticky:** shopkeeper leaves it on STOCK and books 20 sales as stock-in.
- **Mitigation:** auto-revert to SALE after every single recording. But then it's
  "tap chip, then hold mic" = two actions, worse than three buttons.
- **Verdict:** only if user testing shows three separate targets confuse people.

### A6. Directional drag from one mic (WhatsApp slide-to-lock style)
Press and hold in place = SALE; drag left = STOCK; drag up = ASK.
- **Pro:** one button visually. Beautiful, zero clutter, very fast once learned.
- **Con:** undiscoverable for a first-time illiterate user; drag-while-holding is a hard motor
  skill for older hands, and wet/dirty fingers on a cheap capacitive screen make drags unreliable.
- **Verdict:** excellent *power-user accelerator* layered on top of visible buttons. Never the only way.

### A7. Volume-key push-to-talk  ← *high-value, underrated*
Bind volume-up (via the existing `AppForegroundService`) to the SALE mic, screen off, phone in pocket.
- **Pro:** the real shop constraint is *hands* — wet, holding a bag, weighing vegetables. A
  physical key needs no aim, no unlock, no looking. This may beat every on-screen option for the
  90% case.
- **Con:** Android background key capture is fiddly and OEM-dependent (Xiaomi/Vivo/Oppo kill
  services aggressively — exactly the phones this market uses). Needs per-OEM testing.
- **Verdict:** prototype it early; it could reorder every other priority.

### A8. Wake word ("सुनो" / "दुकान")
- **Pro:** truly hands-free.
- **Con:** battery, privacy optics, Play Store scrutiny, and false triggers in a loud market.
- **Note:** architecturally *closer than it looks* — `RollingAudioBuffer` is already continuously
  recording. An on-device keyword spotter over that buffer is a small addition mechanically.
  The cost is policy and battery, not engineering.
- **Verdict:** park it. Revisit if volume-key fails on OEM testing.

### A9. Counter-dock / kiosk layout
When the phone is charging and horizontal, switch to three enormous full-height tiles.
- **Verdict:** cheap to add later, genuinely nice, not a priority.

---

## 2. Udhaar customer identity — every option

The hard part isn't the ledger, it's **"which Ramesh"**. Without a stable identity you get five
customers named रमेश / रमेशजी / रामेश / Ramesh / रमेस within a week, and the credit book is useless.

| # | Option | How it works | Pro | Con |
|---|---|---|---|---|
| U1 | **Face-chips on confirm card** | Photo + name of ~8 regulars on the existing confirmation card; tap one → sale flips to CREDIT | Zero reading, zero STT risk, one tap | Needs photos captured once; caps at ~8 visible |
| U2 | **Voice name + phonetic match** | Speak the name, match against known customers **reusing the existing `PhoneticKey.kt`** built for items | Machinery already exists; fully hands-free | Proper nouns are STT's worst case; needs a merge/dedupe UI |
| U3 | **Contact picker** | Pull from phone contacts — gets name *and* number in one shot | Number is needed for WhatsApp anyway | `READ_CONTACTS` needs a Play Store justification (we already maintain `play_console_declaration.md`) |
| U4 | **"Same as last" one-tap** | Most udhaar is to the person standing right there, often repeat within minutes | Single tap, no list | Wrong ~30% of the time; needs easy undo |
| U5 | **Phone number as identity** | Customer speaks their 10 digits | Digits STT *far* better than names; unlocks WhatsApp directly | Customer must cooperate; 10 digits is slow at a busy counter |
| U6 | **Number-code per customer** | Each regular gets a number: "उधार नंबर तीन" | Digits are robust | Requires memorisation — a literacy burden in disguise |
| U7 | **Colour + animal tokens** | Each regular gets a memorable token: "लाल शेर", "हरा हाथी" | Fully non-literate, memorable, robust STT (common words) | Novel = needs onboarding; caps at ~12 before collisions |
| U8 | **Photo-at-sale** | Snap the customer's face on udhaar; identity *is* the photo, no name at all | Matches how shopkeepers actually remember people — by face | Privacy/consent implications; needs a clear in-app notice |
| U9 | **Deferred attribution** ← *strong* | Book as `CREDIT-UNASSIGNED` instantly; at closing time the app asks "आज के 6 उधार किसके थे?" with face-chips | **Removes all friction at the exact moment the shop is busiest** | Recall degrades over hours; needs a good end-of-day flow |
| U10 | **UPI back-link** | The existing `UpiNotificationListenerService` already catches incoming amounts — match them to open credits | Reuses shipped infrastructure | Only helps settlement, not creation |

**Recommended combination: U1 + U9 + U2-as-bonus.** Face-chips when there's time, one-tap
"उधार, बाद में बताऊँगा" when there isn't, spoken-name matching honoured when STT nails it.
U7 and U8 are the genuinely novel bets worth pilot-testing.

---

## 3. The ASK mic — question taxonomy and data readiness

Completely different pipeline from the sale path:
`speech → metric + filter + time-range → Room query → Hindi sentence → spoken aloud`

| Question family | Example | Backing data | Status |
|---|---|---|---|
| Stock level | "आलू कितना बचा?" | `catalogDao().getStockLevels()` | ✅ ready |
| Price lookup | "आलू का भाव?" | `catalog_items.price` | ✅ ready |
| Revenue by period | "आज / कल / इस हफ्ते कितनी बिक्री?" | `getTodayTotalSales()` + range bounds | ✅ ready |
| Revenue by hour | "पिछले एक घंटे में?" | transactions timestamp filter | ✅ trivial |
| Item-level sales | "आज कितना आलू बिका?" | transactions by item | ✅ ready |
| When was X sold | "टमाटर कब बिका था?" | transactions by item + time | ✅ ready |
| Top / bottom sellers | "सबसे ज्यादा क्या बिका?" | group by item | ✅ ready |
| **Profit** | "आज कितना कमाया?" | join `stock_in.costPrice` to sales | ⚠️ needs new query **+ a costing decision** |
| Credit outstanding | "कुल कितना उधार बाकी है?" | credits sum | ✅ ready |
| Credit by customer | "सबसे ज्यादा उधार किसका?" + detail | credits grouped | ✅ ready |
| Credit aging | "सबसे पुराना उधार?" | `credits.updatedAt` | ✅ ready |
| Trend comparison | "पिछले हफ्ते से ज्यादा या कम?" | two range queries | ✅ ready |
| **Reorder forecast** | "क्या खत्म होने वाला है?" | stock level ÷ 7-day burn rate | ⚠️ new, easy, high value |
| **Dead stock** | "क्या नहीं बिक रहा?" | no sales in N days | ⚠️ new, easy |
| Supplier lookup | "टमाटर किससे लिया, कितने का?" | `stock_in` + `suppliers` | ✅ ready |
| Waste | "इस हफ्ते कितना खराब हुआ?" | — | ❌ needs the waste feature first |

### Costing decision (blocks all profit questions)
Pick one before building: **latest cost price** (simplest, slightly wrong), **weighted average**
(right for fungible vegetables), or **FIFO** (most correct, most state). Recommendation:
**weighted average** — vegetables are fungible and it's barely harder than latest-cost.

### Commands will arrive on the ASK mic — decide the policy now
Shopkeepers will inevitably say "रमेश को WhatsApp करो", "रमेश का उधार चुका दिया",
"आलू का भाव 30 कर दो" into the ASK button. Options:
- **P1 (safe):** ASK answers only. Commands get "यह बटन सिर्फ बताता है" — annoying but honest.
- **P2 (recommended):** ASK *proposes*, never executes. It speaks back what it would do and shows
  one confirm button. Preserves the read-only guarantee where it matters (silent writes) while
  not being useless.
- **P3:** route recognised commands into the write pipeline with the normal confirm card.

Note WhatsApp is naturally safe: a `wa.me` deep link opens WhatsApp with the Hindi message
pre-typed and **the shopkeeper taps send** — we never send on their behalf.

### Answer channel
- **Hindi TTS spoken aloud** (`android.speech.tts`, `hi-IN`) — mandatory; this is the actual
  literacy unlock, and it's needed on *all three* buttons, not just ASK.
- **Huge-numeral card** — most semi-literate shopkeepers read digits fluently even when they
  can't read words. `₹4,320` in 72sp is legible to nearly everyone.
- **Repeat button (🔁)** — a market is loud; the answer will be missed constantly.
- **Sparkline/bars for trends** — a picture of "this week vs last" beats any sentence.
- **Follow-up context** — "और कल?" should inherit the previous metric. Session memory of the last
  query. Big UX win, moderate work.
- **Hedged confidence** — when the match is medium-confidence, speak "लगभग" rather than a bare
  number. And when unsure what was asked: **"समझ नहीं आया, फिर से बोलिए."** A confidently wrong
  number destroys trust far faster than an admitted miss.

### Offline behaviour
Stock, price, today's sales, credit — all local Room, must answer **offline and instantly**.
Recommendation: **local template matcher for the top ~12 question shapes, Grok fallback for the
long tail.** Offline-first is the app's whole premise; an ASK button that needs signal is a
demo, not a product.

---

## 4. Distinguishing buttons without reading

Five simultaneous non-textual channels — redundancy is the point:

1. **Size** — SALE 180dp, others 90dp. Size *is* frequency; 90% of presses all day are sales.
2. **Position** — SALE centre (unmoved, protects existing muscle memory), STOCK bottom-left,
   ASK bottom-right (dominant thumb, used all day; stock-in is a morning/delivery activity).
3. **Colour** — green / orange / blue.
4. **Icon** — mic / sack-of-goods / question-mark-in-speech-bubble.
5. **Distinct haptic + distinct start tone per button** — confirms *before* speaking which mode
   you're in. This is the one that actually prevents wrong-button errors, because it's the only
   feedback that arrives while the finger is still down.

Plus a Hindi TTS confirmation on release: *"आलू दो किलो, साठ रुपये, दर्ज हो गया."*

### Wrong-button recovery ← *kills the main objection to multiple buttons*
Every recording keeps its audio and transcript regardless of which button fired it. A
**"गलत बटन"** undo re-routes the *same audio* through a different intent — no re-speaking.
With this, a mis-press costs one tap instead of a corrupted ledger entry, and the whole
multi-button risk argument mostly evaporates.

---

## 5. Plumbing required (any option that adds a button)

1. `SttJobRecord` gains `captureIntent: CaptureIntent` (`SALE | STOCK | WASTE | ASK`).
2. `AppDatabase` 8 → 9, manual `MIGRATION_8_9` with try/catch'd `ALTER TABLE`, registered in
   `addMigrations(...)` — per the existing pattern, no auto-migrations.
3. `SttProxyClient` forwards the intent; `process-voice-job` branches **after** the 3-way STT race
   (the race and adaptive re-decode are intent-agnostic and should stay shared — only the
   interpretation prompt and destination table differ).
4. New destination paths: `stock_in` rows, negative stock adjustments, and a **read-only** ASK
   handler with no DAO write access at all (enforce it in the type system, not by convention).
5. `diagnosticTraceJson` gains the intent at `step_1` so the logs screen stays useful.

---

## 6. Ideas beyond the original brief

- **Closing-time spoken briefing.** At shutter-down the app *speaks*, unprompted:
  *"आज ₹4,300 की बिक्री, ₹800 उधार, टमाटर कल खत्म हो जाएगा."* Proactive, not asked. Strongest
  retention story and the best 30-second grant demo in the whole product.
- **"क्या मंगाना है?" — the mandi list.** Burn-rate-derived purchase list, spoken aloud while the
  shopkeeper stands at the wholesale market at 5am. This is *inventory-as-byproduct actually
  realised* — the moat stops being a claim and becomes a daily-used feature.
- **Volume-key PTT** (§A7) — potentially the highest-leverage ergonomic change available.
- **Wrong-button audio re-route** (§4).
- **Deferred udhaar attribution** (§U9).
- **Colour/animal customer tokens** (§U7) and **face-as-identity** (§U8).
- **Confidence-hedged speech** (§3) — "लगभग ₹4,000" when we're not sure.

---

## 7. Sequencing options

| Plan | Contents | Argument |
|---|---|---|
| **S1 — ASK first** | SALE + ASK, TTS, top-12 local templates | Read-only ⇒ cannot corrupt the ledger. Zero risk to the working sale path. Best demo. |
| **S2 — STOCK first** | SALE + STOCK(+waste) | Feeds the inventory moat, but it writes, so it carries real risk and needs the full parse/confirm/plausibility chain |
| **S3 — All three** | Full layout, one migration | One retraining event instead of two; largest diff to verify |
| **S4 — Ergonomics spike first** | Prototype volume-key PTT before committing to any layout | If A7 works, it changes what the on-screen layout should even be |

**Recommendation: S1, with a timeboxed S4 spike alongside it.** TTS built for ASK is immediately
reusable by SALE and STOCK, so S1 is not throwaway work — it's the shared foundation.
