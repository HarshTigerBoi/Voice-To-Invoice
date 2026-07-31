# Voice Assistant Framework — Customer Identity & Conversational Ledger

> Status: **exploration, nothing committed.** 2026-07-29. Companion to
> [`multi_intent_capture_plan.md`](multi_intent_capture_plan.md), which covers button architecture.
> This document covers: customer identity ("the five Ramesh problem"), voice addressing,
> the question-answering engine, multilingual reply, and the hands-free conversational loop.

---

## 0. The reframe

**The shopkeeper does not have an identity problem. The database has an identity problem.**

When रमेश is standing at the counter, the shopkeeper knows exactly which रमेश that is — he can
see his face. Ambiguity exists *only inside our data model*. Every good design below follows from
this: **never make the shopkeeper resolve ambiguity in the abstract — only ever in the presence of
the actual person, and only when we genuinely can't figure it out ourselves.**

Corollary: **90% of customer names in a single shop are unique.** The numbering system must be
invisible for those. If saying "सुनीता" just works, numbers should never surface. Numbers are for
*collisions* and for *query convenience* — not a tax on every transaction.

---

## 1. Customer numbering — design variants

The number is a **machine handle**. The name (+ qualifier) is the **human handle**. Both exist;
neither is mandatory.

| # | Variant | Detail | Assessment |
|---|---|---|---|
| N1 | **Sequential khata number** | #1 = first person who ever took udhaar. Never reused. | ✅ **This is literally the बही-खाता page number** — a metaphor every Indian shopkeeper already has in their head. Culturally native, not invented. Strongly recommended |
| N2 | Two-digit fixed (01–99) | Always spoken as two digits | Marginal gain; caps at 99 |
| N3 | Number + colour | "तेईस, लाल" | Redundancy helps STT, but doubles what must be remembered |
| N4 | Number + animal/object token | "तेईस — शेर" | Memorable, non-literate-friendly, robust STT (common words). Collides past ~12 tokens unless paired with the number |
| N5 | Last-4 of phone number | No assignment step at all; already unique; the customer knows their own | ✅ Zero-admin. But only works if you have their number |
| N6 | Renumber by frequency | Top debtors get low numbers | ❌ **No** — renumbering destroys the memory you just built |
| N7 | Per-category ranges | 1–99 daily, 100+ occasional | Over-engineering |

### Making the number stick without memorisation

- **S1. Speak it back, every single time.** "रमेश जी, नंबर तेईस, तीन सौ चालीस बाकी." Repetition
  builds the association for free. Costs nothing.
- **S2. Physical card.** Hand the customer a card: *"आपका खाता नंबर 23."* ✅ **Strong** — a physical
  artifact, zero memory burden on the shopkeeper, and it makes the customer feel properly
  accounted for (which is exactly the trust dynamic udhaar runs on).
- **S3. WhatsApp their number to them** when you have their phone: *"आपका खाता नंबर 23 है."*
  Now the *customer* can say it: "मैं तेईस नंबर हूँ."
- **S4. Big numeral next to the face** on the credit list. Digits are readable by nearly every
  semi-literate shopkeeper even when words aren't.
- **S5. Never require it.** Name works, number works, "आखिरी वाला" works. Accept all forms.

---

## 2. Collision handling — every option

Triggered *only* when a spoken name matches ≥2 known customers.

| # | Strategy | How it plays out | Assessment |
|---|---|---|---|
| C1 | **Face-picker popup** | "कौन सा रमेश?" + 2–3 photos, one tap | ✅ Resolution by *face*, not by name — matches how the shopkeeper actually thinks |
| C2 | **Capture the qualifier they already use** | Shopkeepers already say "रमेश दूधवाला", "मोटा रमेश", "रमेश जो सामने रहता है". On first collision, ask once: "रमेश... और?" → "दूधवाला". Store `रमेश दूधवाला` as the name | ✅ **Best idea here.** Don't invent an identity system — *capture the one they already have.* Permanently fixes the collision instead of re-asking |
| C3 | **Recency/frequency auto-resolve** | One Ramesh bought yesterday and owes ₹340; the other hasn't appeared in 3 months. Pick the live one, say it aloud: "रमेश दूधवाला, है ना?" | ✅ **Resolves most collisions with zero interaction.** Confidence-weighted default + easy spoken correction |
| C4 | **Number qualifier in speech** | "तेईस नंबर रमेश" | Works, but requires recall |
| C5 | **Voice disambiguation, no popup** | App *asks aloud* "दूधवाला या सात नंबर?" and auto-opens the mic for the answer | ✅ The hands-free-goal version of C1 |
| C6 | **Deferred** | Book as `CREDIT-UNASSIGNED`, resolve at closing time with faces | ✅ Right answer during a rush; recall degrades over hours |
| C7 | **Always-new** | Every utterance creates a new customer; merge later | ❌ Creates the five-Ramesh mess it's meant to solve |
| C8 | **Amount-shape prior** | This Ramesh always buys ~₹200 of vegetables; that one buys sacks of rice | Weak alone; useful as a tiebreak signal inside C3 |

**Recommended stack: C3 first (silent), C2 on genuine ambiguity (permanent fix), C1/C5 as the
fallback, C6 when the shop is slammed.**

### The new-customer popup

Their proposal: after recording, a popup offers photo / phone / number. Options for how intrusive:

- **P-A. Blocking modal** — ❌ interrupts the sale flow at exactly the wrong moment.
- **P-B. Non-blocking card** ✅ — *"नया ग्राहक — नंबर 24"*, auto-dismisses, with optional
  `[फोटो]` `[फ़ोन]` buttons. **Zero required interaction.** The number is assigned regardless;
  enrichment is opt-in and can happen any time later.
- **P-C. Voice-only** — app says "नया ग्राहक, नंबर चौबीस" and moves on. Enrichment deferred to the
  credit screen.
- **P-D. End-of-day enrichment sweep** — "आज 3 नए ग्राहक — फोटो जोड़ें?"

**Photo caveat worth pilot-testing:** photographing customers for a khata is not an established
norm in Indian retail. It may read as fine (like a bank KYC) or as intrusive. Validate before
building it as the primary identity mechanism — face-*picking* from photos the shopkeeper
opted to take is safe; requiring photos is not.

---

## 3. How does the app know a name was spoken at all?

This is the actual technical crux of "say a name → it becomes udhaar."

| # | Signal | Example | Strength |
|---|---|---|---|
| D1 | Known-customer phonetic match | matches the existing customer list | Strong for repeats, useless for new |
| D2 | **Honorific detection** | जी, भाई, दादा, चाचा, अंकल, बहन, दीदी, साहब | ✅ **Very strong in Hindi** — "रमेश जी" is unmistakably a person |
| D3 | **Case-marker detection** | "रमेश **को**", "रमेश **ने**", "रमेश **के लिए**" | ✅ Dative/ergative marking = a recipient. Real grammatical signal, and they say it naturally |
| D4 | Position heuristic | leading token before the quantity that isn't item/unit/number | Medium — STT garbage looks identical |
| D5 | LLM NER | Grok extracts the person entity | Strong, but network-dependent |
| D6 | Negative evidence | not in catalog, not a number, not a unit ⇒ probably a name | Weak alone; fine as a tiebreak |

### What the credit button is *actually* for

Not intent signalling. **It removes the need to *detect* that a name was spoken.** On the credit
mic, the leading unknown token is a name *by construction* — D1–D6 collapse into "it's the name."

That's a much better argument for the button than "the shopkeeper shouldn't have to say उधार,"
and it means the button and the say-a-name path are complementary, not alternatives:

- **On the SALE mic:** D2+D3 detect a name → auto-route to credit. (Bonus path, best-effort.)
- **On the CREDIT mic:** the name is assumed. (Guaranteed path, for when it matters.)

---

## 4. Addressing a customer in a question

Accept **every** form, always. Never require one:

| Form | Example |
|---|---|
| Number | "पाँच नंबर को कितना देना है?" / "पाँच नंबर ने क्या क्या लिया?" |
| Bare number | "पाँच का कितना बाकी है?" |
| Name | "रमेश दूधवाला का कितना बाकी?" |
| Name + number | "रमेश तेईस नंबर" |
| Relative | "आखिरी वाला", "जो अभी गया", "कल वाला" |
| Superlative | "सबसे ज्यादा उधार किसका?" — then follow-ups inherit that customer |

**Parsing "पाँच" as customer-5 vs quantity-5:** on the ASK mic there is no quantity slot, so the
ambiguity largely disappears. The `नंबर` marker, and the verb frame (देना / बाकी / लिया), settle
the rest.

**Follow-up context is essential.** After "पाँच नंबर को कितना देना है?", the questions
"और कल?" / "क्या क्या लिया?" / "मैसेज भेजो" must all inherit customer #5. That means the ASK
engine needs a **short-lived conversation state** (last entity, last metric, last time-range),
expiring after ~2 minutes or on the next SALE press.

---

## 5. The question-answering engine — six architectures

| # | Architecture | How | Pros | Cons |
|---|---|---|---|---|
| Q1 | **Local template matcher** | Hand-coded patterns → parameterised Room queries | Instant, offline, free, fully deterministic | Brittle; can't handle anything unanticipated |
| Q2 | **LLM → structured intent JSON** | Grok gets transcript + schema description, returns `{metric, entity, filter, range}`; **the app** executes a safe parameterised query | ✅ Flexible *and* safe — the model never touches the DB. Auditable | Needs network; bounded by the intent vocabulary you define |
| Q3 | **LLM → SQL directly** | Text-to-SQL over a read-only view | Handles genuinely arbitrary questions | Hallucinated columns, unbounded cost, needs a hard read-only sandbox |
| Q4 | **Nightly digest + LLM over the digest** | Precompute a compact JSON shop-state blob (top items, burn rates, credit list, P&L); answer questions over that small blob inline | ✅ Very cheap, one call, tiny context, no query generation at all | Can't drill into arbitrary detail; stale within the day |
| Q5 | **Tiered hybrid** | Q1 → Q4 → Q2 → Q3 escape hatch | ✅ Fast and offline for the common case, capable for the tail | Most moving parts |
| Q6 | **Agentic tool-calling** | Grok with real tools: `get_stock`, `get_sales`, `get_credit`, `get_burn_rate`, `draft_whatsapp`. Multi-step reasoning | ✅ **This is the assistant you're actually describing.** "कल क्या मंगाऊँ?" needs burn-rate + stock + supplier in one chain | Slowest, priciest, network-only, needs strict tool-level permissions |

**Recommended: Q5 as the skeleton, growing into Q6.** Ship Q1 (top ~15 questions, instant and
offline — this covers the daily rhythm), keep Q2 as the general fallback, and add Q6 tools one at
a time for the advisory questions. **Critically: the tool layer for Q6 is the same query layer
Q1/Q2 use** — build the queries once as a clean `LedgerQueries` surface and every tier reuses it.

### Hard rule for all tiers
If confidence is low, **say so**: *"समझ नहीं आया, फिर से बोलिए."* A confidently wrong number
destroys trust far faster than an admitted miss — and in a ledger app, trust is the entire product.

---

## 6. Language in, same language out

- **L1. Detect from STT output** (Sarvam returns a language code; Grok can be asked). Pass the
  detected language into the response prompt: *reply in the same language and register.*
- **L2. Android `TextToSpeech`** — free, offline, supports hi/bn/ta/te/mr/gu-IN. Quality is
  mediocre and **code-mixed Hinglish is its worst case** (a Devanagari voice mangles English words
  and vice-versa).
- **L3. Sarvam TTS** ✅ — **you already use Sarvam for STT.** Purpose-built for Indic languages,
  handles code-mixing, same vendor and key. This is the obvious primary, with L2 as the offline
  fallback.
- **L4. Pre-recorded phrase bank + slot injection** — record a human saying the ~40 sentence
  frames once; splice in numbers via TTS. Best quality, fully offline, zero per-use cost, but
  rigid and one recording set per language.
- **L5. Numbers spoken in the local convention** — "तीन सौ चालीस", not "three hundred forty".
  Sounds trivial; it's the difference between sounding native and sounding like a robot.

**Recommended: L3 primary → L2 offline fallback → L4 for the handful of highest-frequency
phrases (the closing briefing, the confirmation line) where quality matters most.**

---

## 7. The hands-free conversational loop

Your stated goal — *"they don't have to touch the phone except pressing that button"* — needs one
mechanic above all others:

### Auto-open mic after every app question
Whenever the app asks something ("कौन सा रमेश?", "सही है?", "कितने का?"), it **immediately reopens
the mic** and listens for the answer. No second press. That single behaviour converts every
popup in this document into a spoken exchange.

Consequences to design for:
- **Barge-in** — the shopkeeper will answer before the TTS finishes. Listen *during* playback and
  cut the audio off. Without this it feels slow and people will stop using it.
- **Timeout + silent give-up** — if nobody answers in ~4s, fall back to the visual card. Never hang.
- **Echo cancellation** — the mic must not hear our own TTS. `AudioManager` voice-comm mode, or
  simply don't listen through the speaker's own output window.
- **Turn budget** — cap at 2 clarification turns, then hand off to the visual card. An assistant
  that interrogates you is worse than one that gives up.
- **A universal "रहने दो" / cancel word** at any point in the loop.

### Voice confirmation of writes
"रमेश जी को पाँच किलो आलू, सौ रुपये, उधार — सही है?" → हाँ/नहीं. This is how a write path becomes
hands-free without becoming dangerous. Pair it with confidence: auto-confirm silently above the
existing 0.80 threshold, *ask aloud* below it.

---

## 8. Actions by voice (beyond questions)

- **WhatsApp — `wa.me` deep link** ✅ recommended. Opens WhatsApp with the Hindi message pre-typed;
  **the shopkeeper taps send.** Safe by construction, needs no API approval, works today.
  Programmatic sending is not possible on consumer WhatsApp anyway.
- **WhatsApp Business Cloud API** — genuinely automatic sending, but needs business verification,
  pre-approved message templates, and per-message cost. A real option once there are paying shops.
- **SMS fallback** — `SmsManager` can send directly, but SMS is dead for this audience.
- **Voice-dictated free text appended to the reminder**: "और लिख देना कि कल तक दे दें" → appended
  to the generated message before it opens. This is the feature that makes it feel like a
  secretary rather than a form.
- **Call** — "रमेश को फ़ोन लगाओ" → `ACTION_DIAL` (pre-filled dialler, user presses call).
- **Price update by voice** — already exists as `RATE_UPDATE` in the price-intent path; wire the
  ASK mic's command detection into it rather than reimplementing.
- **Mark credit paid** — "रमेश का उधार चुका दिया" → propose + confirm aloud, never silent.

**Policy for every action: the assistant *proposes and confirms*, it never silently executes.**
Reads can be instant; writes and outbound messages get a confirmation turn.

---

## 9. The framework to build toward

One pipeline, N intents, pluggable — replacing the current sale-only path:

```
Audio
  → STT (existing 3-way race: Grok / Sarvam / on-device)
  → Intent Router      (button hint + honorific/case-marker/keyword signals)
  → Slot Filler        (per-intent: qty·unit·item | customer | metric·range)
  → EntityResolver     ← THE NEW SHARED PIECE
  → Executor           (WriteExecutor | ReadExecutor (read-only by type) | ActionExecutor)
  → Responder          (Hindi/Hinglish/… sentence → Sarvam TTS)
  → Clarifier loop     (ambiguous ⇒ ask aloud + auto-open mic, max 2 turns)
```

**`EntityResolver` is the real architectural contribution here.** Today `PhoneticKey.kt` resolves
*items* only. Generalise it to `EntityResolver<T>` — candidates, phonetic keys, **recency and
frequency priors**, and a collision policy — and the exact same component then resolves:

- **items** (existing behaviour, unchanged)
- **customers** (§1–3 — the five-Ramesh problem *is* an entity-resolution problem)
- **suppliers** (same problem, already in the schema)

One component, three payoffs, and the per-shop learning it accumulates is precisely the moat.

Second shared piece: **`LedgerQueries`** — one clean, parameterised, read-only query surface.
Q1 templates call it, Q2 intent-JSON calls it, Q6 tool definitions wrap it. Write the queries once.

---

## 10. The advisory tier ("कल क्या मंगाऊँ?")

Beyond retrieval — this is inference, and it's the highest-value thing on the list:

| Input | Status |
|---|---|
| Burn rate per item (7 / 14 / 28-day) | easy, data exists |
| Current stock on hand | ✅ exists |
| Day-of-week seasonality (Sunday spikes) | easy once there's ~6 weeks of data |
| **Festival calendar** (Diwali, Navratri, Karva Chauth…) | ⚠️ huge demand driver in Indian retail; needs a calendar table, worth it |
| Weather (rain kills footfall) | optional, free APIs exist |
| Per-item spoilage rate | needs the waste feature first |
| Margin per item | needs the costing decision (recommend weighted average) |

Ship **v1 = burn rate vs stock on hand** ("टमाटर कल खत्म हो जाएगा"), layer seasonality and
festivals later. Delivered as the 5am **spoken mandi list**, this is inventory-as-byproduct
stopping being a pitch and becoming a daily-used feature.

---

## 11. Sequencing

1. **`EntityResolver<T>` generalisation** — unblocks customers *and* improves item matching. Pure win, no UI.
2. **Customer numbering + C3 recency auto-resolve + C2 qualifier capture** — fixes five-Ramesh.
3. **Sarvam TTS + spoken confirmations** — the literacy unlock; every later feature depends on it.
4. **`LedgerQueries` + Q1 local templates (top ~15 questions)** — instant, offline, covers the daily rhythm.
5. **Auto-open-mic clarifier loop** — turns everything above hands-free.
6. **Q2 LLM intent-JSON fallback** — the long tail.
7. **WhatsApp draft-and-confirm.**
8. **Q6 agentic tools + advisory tier + closing briefing.**

Steps 1–3 are foundations that every other item on both documents depends on. Nothing after step 3
is wasted work regardless of which button layout you eventually pick.
