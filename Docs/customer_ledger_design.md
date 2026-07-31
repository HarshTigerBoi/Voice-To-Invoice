# Customer Ledger & Tabbed Capture — Design Spec

> 2026-07-29. This is the **concrete spec** for the design the user proposed, with fixes.
> The two companion docs are option spaces, not decisions:
> [`multi_intent_capture_plan.md`](multi_intent_capture_plan.md) (button architecture),
> [`voice_assistant_framework.md`](voice_assistant_framework.md) (identity theory, QA engine).
> Verified against source 2026-07-29: **no customer entity exists today** —
> `CreditRecord.customerName` is a free-text string and `CreditDao` has no per-customer query.

---

## 1. Verdict on the proposed design

**It's good. Adopt it.** Three parts of it are better than what the option-space docs proposed:

1. **The बही-खाता card metaphor** (name on top, entries below) is culturally native and should
   drive the whole customer screen, not just the picker.
2. **"Special keyword" as a first-class field** (दोसे वाले / डॉक्टर / गाँव) — the earlier docs had
   this only as a collision patch. Making it a permanent field for *every* customer is better:
   it's how shopkeepers already think, and it becomes a spoken selector.
3. **Tabs instead of scattered mics.** A tab is a *place*, not a hidden mode — it's visible,
   persistent, and has its own layout. This sidesteps the sticky-mode failure that made the
   "mode chips" option (A5) unsafe. Cognitive load is lower than 4 mics on one screen.

**Three things will break it if unaddressed** — §3 P1, P3, P6 below. All three are fixable and
none change the shape of the design.

---

## 2. The design as specified

### Navigation — bottom tab bar
| Tab | Icon | Contents |
|---|---|---|
| **बेचो** (Sell) | ₹ | Two mics: **नकद** (normal sale) + **उधार** (credit sale) |
| **माल** (Stock) | + | Two mics: **माल आया** (stock-in) + **खराब** (waste) |
| **हिसाब** (Summary/Ask) | 📊 | Existing summary + the **पूछो** ASK mic |

Open on **Sell** always; auto-return to Sell after ~60s idle on another tab.

### Customer card (the diary page)
```
┌──────────────────────────────┐
│  [photo]   रमेश        #003  │
│            दोसे वाले          │
│            98XXXXXX21        │
├──────────────────────────────┤
│  बाकी: ₹340                  │
│  12 जुल — 5 किलो आलू  ₹100   │
│  08 जुल — 2 किलो प्याज ₹80   │
└──────────────────────────────┘
```
Fields: `code` (001…), `name`, `keyword`, `phone`, `photo` — all optional except code + name.

### Udhaar capture flow
1. Press **उधार** mic → speak items, optionally a name/keyword/number/phone.
2. On release → **sale is booked immediately** as `CREDIT`, customer unassigned.
3. Resolver ranks customers by every spoken signal + recency/frequency.
4. **Exactly one confident match → assign silently, speak it back, no popup.** (The common case.)
5. Otherwise → picker overlay, ranked, with `+` (new) and `⋯` (none of these).
6. App says **"कौन सा?"** and **the mic auto-reopens** — answer by name, keyword, number, or phone.
7. Any spoken attribute narrows the list; a unique narrowing assigns immediately.

### Selectors that must all work
name · keyword ("दोसे वाले") · code ("तीन नंबर" / "नंबर तीन" / "तीन") · phone digits ·
name+keyword · relative ("आखिरी वाला", "जो अभी गया")

---

## 3. Problems to fix — ranked by severity

### P1 — 🔴 The picker must never block the next recording
**The single biggest risk.** Rush hour, three customers deep: he speaks an udhaar sale and a modal
demands a choice before he can record the next one. He will abandon the feature that day.

**Fix:** the sale is booked *first*, as `CREDIT` with `customerId = null`. The picker is a
**non-blocking overlay**. If he presses any mic again, the picker collapses into a pending badge
(*"1 उधार — किसका?"*), and unassigned credits resolve later — from the badge, or in an end-of-day
sweep. Deferred attribution becomes a graceful degradation, not a separate mode.

### P2 — 🔴 Never hard-filter to zero
"रमेश" misheard as "रामेश" → filter returns 0 matches → empty popup + a `+` button → he taps it →
**a duplicate Ramesh is born.** This is precisely the five-Ramesh generator.

**Fix:** the spoken name is a **ranking signal, never a hard filter**. Always show the full list,
best guesses on top, using `PhoneticKey.normalizedDistance()`. An empty result set must be
impossible whenever ≥1 customer exists.

### P3 — 🔴 Duplicate-merge is mandatory
Duplicates will happen regardless. Without a merge, the ledger degrades permanently and
irreversibly.

**Fix:** a "ये दोनों एक ही हैं" merge in the customer list — reassigns all credits/transactions,
keeps the lower code, tombstones the other via `mergedIntoId`. Plus a passive duplicate detector
that surfaces likely pairs (same phonetic key, no keyword) for one-tap merging.

### P4 — 🟠 Speak the name back when creating a customer
He can't read the prefilled name, so bad STT gets saved silently.

**Fix:** on `+`, speak *"नया ग्राहक — रामेश — सही है?"* and show existing similar customers
prominently above the form: *"क्या यह इनमें से है?"* with faces. Make the duplicate visible at
exactly the moment one would be created.

### P5 — 🟠 No name spoken at all is the *expected* path, not an error
Since the button already means udhaar, he'll often just say "पाँच किलो आलू". The picker then
shows everyone ranked by recency. That's normal behaviour — design for it, don't treat it as a
failure case.

### P6 — 🟠 Codes must never be reused
After delete/merge, a code is retired forever. Reuse silently reassigns history.

### P7 — 🟡 "Last person who bought" needs no AI
It's `ORDER BY timestamp DESC LIMIT 1` on credit sales. The genuinely AI-needing references are
things like *"जो पिछले हफ्ते टमाटर ले गया था"*. Don't over-scope the easy one.

### P8 — 🟡 Honorifics on the *normal* sale mic
If he says "रमेश जी को" on the नकद button, don't silently book cash — ask *"उधार?"*. The dedicated
button makes name-detection a safety net rather than a load-bearing mechanism.

### P9 — 🟡 Photo capture at the counter needs pilot validation
Photographing customers for a khata is not an established norm in Indian retail. Optional and
shopkeeper-initiated is safe; required is not. Test with 2–3 real shops before depending on it.

### P10 — 🟡 Everything conversational depends on TTS
"कौन सा?", the name read-back, the confirmation — all of it. Sarvam TTS is the unblocking
dependency for this entire flow.

---

## 4. Schema — the actual blocking work

```kotlin
@Entity(tableName = "customers")
data class CustomerRecord(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val shopId: String = "default_shop",
    val code: Int,                    // 1, 2, 3… never reused
    val name: String,
    val keyword: String? = null,      // "दोसे वाले", "डॉक्टर"
    val phone: String? = null,
    val photoPath: String? = null,
    val phoneticKey: String,          // PhoneticKey.of(name), indexed
    val lastSeenMs: Long = 0L,        // recency prior
    val txnCount: Int = 0,            // frequency prior
    val mergedIntoId: String? = null, // tombstone after merge
    val createdAt: Long = System.currentTimeMillis(),
    val synced: Boolean = false
)
```

- `CreditRecord` gains `customerId: String?` (keep `customerName` for backfill safety).
- `TransactionRecord` gains `customerId: String?`.
- `AppDatabase` **8 → 9**, manual `MIGRATION_8_9` with try/catch'd `ALTER TABLE`, registered in
  `addMigrations(...)` — per the existing pattern, no auto-migrations.
- **Backfill:** distinct `credits.customerName` values → `CustomerRecord` rows, codes assigned in
  first-seen order, existing credits relinked.
- `CustomerDao`: ranked lookup, by-code, by-phone, per-customer ledger, merge, next-free-code.
- Supabase mirror: `customers` table + `SyncEngine` sweep (same `synced` pattern as every other entity).

---

## 5. Resolution algorithm

```
score(customer) =
      w1 · phoneticNameMatch      (PhoneticKey.normalizedDistance, existing)
    + w2 · keywordMatch           (exact/phonetic on "दोसे वाले")
    + w3 · codeMatch              (exact — strongest signal when present)
    + w4 · phoneDigitMatch        (exact)
    + w5 · recencyPrior           (decay on lastSeenMs)
    + w6 · frequencyPrior         (log txnCount)

if top1 > THRESHOLD and top1 - top2 > MARGIN  → assign silently, speak it back
else                                          → ranked picker + "कौन सा?" + mic reopen
```

Same shape as the existing item-matching path — which is why it should be built as the shared
`EntityResolver<T>` (see the framework doc §9) and then serve items, customers *and* suppliers.

---

## 6. Open question: where does ASK live?

The proposed 3-tab layout drops the ASK mic. Options:

| | Option | Assessment |
|---|---|---|
| **A** | ASK mic on the **हिसाब/Summary** tab | ✅ **Recommended.** Semantically exact — you go there to find things out. Keeps it at 3 tabs |
| B | 4th tab | Cleaner separation, but 4 tabs and Summary becomes redundant |
| C | Floating ASK button on every screen | Always available, but clutters the capture screens |

---

## 7. Build order

1. **`CustomerRecord` + migration 8→9 + backfill + `CustomerDao`** — nothing works without it.
2. **`EntityResolver<T>`** — generalise `PhoneticKey` usage; serves customers *and* improves items.
3. **Customer card UI + create/edit + merge** (P3) — including the duplicate detector.
4. **Tab bar restructure**; Sell tab with नकद + उधार.
5. **Non-blocking picker** (P1) with ranked list, `+`, `⋯`, pending badge.
6. **Sarvam TTS** → "कौन सा?", name read-back, confirmations.
7. **Auto-open-mic** on the picker; spoken selectors (name/keyword/code/phone).
8. Stock tab (माल आया + खराब); ASK mic on हिसाब.

Steps 1–3 are pure data-layer and carry no UX risk. Step 5 is where the rush-hour behaviour is
won or lost.
