# Open-Vocabulary Architecture — the "never again" design

**Status:** design proposal, nothing implemented
**Author:** drafted 2026-07-26, after ISSUE-020 → ISSUE-023
**Purpose:** replace vocabulary-expansion whack-a-mole with a system that converges on its own

---

## 0. The ceiling — read this first

You asked for a design where **"no matter what word is said, it does it correctly."**

That is not achievable, and any design that claims it is lying to you. Here is the proof, from your own traces:

- Trace `e0b68f80`: you said "paanch kilo amchoor". **Both** STT engines independently returned `साथ`/`सात` (7), not `पांच` (5). Two models, trained on different data, heard the same wrong number. The audio genuinely sounded like "saat". No amount of downstream logic recovers a signal that was destroyed at the microphone.
- Trace `9fc1fc32`: "kilo maggie" → "किलोमीटर". `मीटर` and `मैगी` differ by a consonant. The information is gone by the time you hold a string.

The first time a shop says a genuinely new word in a noisy room, some percentage of the time the system will not know what it was. That is physics, not a bug.

**What IS achievable, and is what you actually want:**

> **Correct, or explicitly "I don't know" — never confidently wrong. And never wrong twice for the same word.**

The current system fails *both* halves. It is confidently wrong (0.95 confidence on a 0.250 match), and it is wrong *repeatedly* for the same word because nothing learns. Fix those two, and the error rate for any given shop decays toward zero with use instead of staying flat forever.

That is the difference between a system you maintain and a system that maintains itself. The rest of this document is how to build the second one.

---

## 1. Why the current design caps out

Four structural properties, none of which is fixable by adding more words.

### 1.1 The vocabulary is source code

`DEFAULT_ITEM_VOCAB` is a literal `listOf(...)` in `OrderingSegmenter.kt`, duplicated in `phonetic.ts`. Adding one word = code change + edge function deploy + APK build + Play Store release.

India's kirana/pooja/masala vocabulary is effectively unbounded, regionally variable, and includes brand names invented weekly. You have expanded this list three times (35 → 192 → 214). **You will keep running out.** The list is not too short; the list being a list is the problem.

### 1.2 Matching is forced-choice

`matchVocab()` returns the best candidate under a threshold. There is no "none of these" outcome. So an unknown word does not fail — it gets **coerced onto the nearest known word**, confidently.

This is the direct cause of every recent issue:

| Trace | Said | Heard | Coerced to | Distance |
|---|---|---|---|---|
| `e0b68f80` | अमचूर | चोर | **Jeera** | 0.250 |
| `0df2895e` | चंदन | संधन | **संतरा** (orange) | 0.214 |
| `481ac42a` | चंदन | चंदन ✅ | **Chana Dal** | 0.214 |

Look at the last row. STT was **perfect**. Both engines returned `चंदन`. The matcher still got it wrong, because `चंदन` was not in the lexicon and `Chana Dal` was 0.214 away, which is under the 0.25 threshold. **A correct transcript was destroyed by forced-choice matching.** No STT improvement can fix that.

### 1.3 Nothing learns

`term_aliases` table exists. `sync-term-aliases` exists. `TermInterpreterClient.confirmTermAlias()` exists. **All three are unwired** — `confirmTermAlias` has zero callers, and no read path consults `term_aliases` during matching.

Every correction a shopkeeper makes today is thrown in the bin. The system has made the same class of mistake four times across four issues because it is structurally incapable of remembering.

### 1.4 Evidence is collected and then discarded

- Two STT providers run. When they **agree** (trace `481ac42a`: both said `चंदन`) that agreement is powerful evidence — and is used for nothing. Provider selection is `grokScored.score >= sarvamScored.score`, an arbitrary tie-break.
- The matcher computes a distance for *every* candidate, then keeps only the winner. **The runner-up's distance is the single most informative number in the whole pipeline** (§4.2) and it is dropped on the floor.
- The shop's own transaction history says exactly which items it actually sells. Unused.

---

## 2. Target invariants

Write these as tests. If any can be violated, the design is wrong.

| # | Invariant |
|---|---|
| **I1** | Every non-empty utterance ends as a transaction **or** a review row. Never silence, never an empty segment list. |
| **I2** | No item is auto-confirmed unless the winning candidate beats the runner-up by a **margin**, not merely a threshold. |
| **I3** | "Unknown" is a first-class outcome that preserves quantity + unit, never a coercion to the nearest word. |
| **I4** | Every shopkeeper correction writes a durable alias. The same phonetic key never resolves wrongly **twice** in the same shop. |
| **I5** | Adding a word to the recognizable vocabulary requires **zero code changes and zero deploys**. |
| **I6** | A word learned in one shop can benefit all shops, after independent confirmation from N distinct shops. |
| **I7** | Every historical failing trace is a replayable regression test in CI. |

**I2 and I4 are the load-bearing ones.** I2 stops confident wrongness. I4 stops repetition. Everything else is support.

---

## 3. Architecture

```
                    ┌───────────────────────────────────────────┐
                    │  L0  ALIAS TABLE (learned, per-shop)      │  exact hit → done, conf 0.98
                    └───────────────────────────────────────────┘
                                     ↓ miss
                    ┌───────────────────────────────────────────┐
                    │  L1  LEXICON (data, not code)             │  score ALL candidates
                    │      shop catalog ∪ global ∪ learned      │  keep top-3 + margin
                    └───────────────────────────────────────────┘
                                     ↓
                    ┌───────────────────────────────────────────┐
                    │  L2  OPEN-SET DECISION                    │  MATCH | AMBIGUOUS | UNKNOWN
                    │      margin test + absolute test          │
                    └───────────────────────────────────────────┘
                          ↓ MATCH        ↓ AMBIGUOUS      ↓ UNKNOWN
                    ┌──────────┐  ┌──────────────┐  ┌──────────────┐
                    │ evidence │  │ LLM adjudi-  │  │ new-item     │
                    │ fusion   │  │ cates (async)│  │ candidate    │
                    └──────────┘  └──────────────┘  └──────────────┘
                                     ↓
                    ┌───────────────────────────────────────────┐
                    │  L3  GATE: auto-confirm or review          │
                    └───────────────────────────────────────────┘
                                     ↓ review + correction
                    ┌───────────────────────────────────────────┐
                    │  L4  LEARNING LOOP → writes back to L0/L1 │
                    └───────────────────────────────────────────┘
```

The loop from L4 back to L0 is what makes it converge. Everything above L4 is a one-shot guess; L4 is what makes the guess permanent knowledge.

---

## 4. Layer specifications

### 4.1 L0 — Alias table (learned memory)

**Lookup, in order. First hit wins, stop.**

```
key = phoneticKey(token)

1. shop-local alias   WHERE shop_id = me     AND phonetic_key = key   → conf 0.98
2. global alias       WHERE shop_id IS NULL  AND phonetic_key = key
                        AND distinct_shop_count >= 3                  → conf 0.92
3. fall through to L1
```

A shop-local alias is the shopkeeper's own confirmed answer. Treat it as ground truth — cost 0, no fuzzy matching, no second-guessing. This is what guarantees **I4**.

> **Design note:** key on `phonetic_key`, **not** raw text. STT returns a different surface string every time (`चंदन`, `चनदन`, `chandan`, `Chandan`); they all key to `CANTAN`. Keying on raw text would mean learning the same correction dozens of times.

### 4.2 L1 + L2 — Scoring and the open-set decision

**This is the core of the design. Read carefully.**

The bug in the current matcher is that it asks *"is the best candidate close enough?"* It should ask *"is the best candidate close enough **AND** clearly better than everything else?"*

```
function resolveItem(token, ctx) -> Resolution:

    key = phoneticKey(token)

    # --- L1: score EVERY candidate, keep the ranking ---
    scored = []
    for entry in lexicon(ctx.shop_id):          # catalog ∪ global ∪ learned
        d     = normalizedPhoneticDistance(key, entry.phonetic_key)
        prior = shopPrior(entry.item_id, ctx)    # §4.4, in [0, 1]
        scored.append({
            entry,
            distance: d,
            score:    d - W_PRIOR * prior        # lower is better
        })

    scored.sortBy(score)
    best   = scored[0]
    second = scored[1]   # may be null if lexicon has one entry

    # --- L2: open-set decision ---
    margin = second ? (second.score - best.score) : INFINITY

    if best.distance > TAU_ABS:                  # nothing is even close
        return UNKNOWN(reason: "no candidate within threshold",
                       heardKey: key, top3: scored[0:3])

    if margin < TAU_MARGIN:                      # everything is equally close
        return AMBIGUOUS(reason: "winner not separated from runner-up",
                         heardKey: key, top3: scored[0:3])

    return MATCH(item: best.entry, distance: best.distance, margin: margin,
                 alternatives: scored[1:3])
```

#### Why the margin test is the thing that future-proofs you

A token that is 0.21 from *one* word and 0.45 from everything else is a **plausible match**.
A token that is 0.21 from *five different words* is **not a match at all** — it is a word you do not have, sitting in the middle of your vocabulary space.

Absolute distance cannot tell these apart. Margin can.

**This is why the margin test survives vocabulary gaps.** In trace `481ac42a`, `चंदन` was absent and `Chana Dal` sat at 0.214. The margin test asks: *how much better is Chana Dal than the next candidate?* If several words cluster around 0.2–0.25 — which is exactly what happens when the true word is missing — the margin collapses and the result becomes AMBIGUOUS → review, instead of a confident wrong booking.

You get "I don't know this word" behaviour **without having to know the word**. That is the property you asked for.

> ⚠️ **You must measure this, not trust it.** I have not measured runner-up distances for your traces — the current code discards them. **First implementation step (§8, Phase 0a) is to log `top3` for every job for a few days and look at the real distribution.** Set `TAU_MARGIN` from that data. Starting guess: `TAU_ABS = 0.25` (unchanged), `TAU_MARGIN = 0.08`. Both must be tunable without a deploy (§4.7).

### 4.3 Evidence fusion — use what you already collect

Confidence should be **multiplicative** across independent signals, so any one weak signal drags the result down. That is the correct shape for a gate protecting a financial ledger.

```
confidence = base(best.distance)          # 0.50 … 0.95   (you already have this)
           × marginFactor(margin)         # 0.60 … 1.00
           × agreementFactor(providers)   # 0.85 … 1.10   ← currently unused
           × priorFactor(shopFrequency)   # 0.90 … 1.05   ← currently unused
           × sourceFactor(resolution)     # ALIAS 1.0, LEXICON 1.0, LLM_ADJUDICATED 0.95
clamp to [0, 0.99]
```

**`agreementFactor` is free accuracy you are throwing away today:**

| Situation | Factor | Rationale |
|---|---|---|
| Both providers → same phonetic key | **1.10** | Two independent models agreeing is strong evidence |
| Providers disagree, one is clearly better-scoring | **0.95** | Normal |
| Providers disagree, scores are tied | **0.85** | Currently resolved by an arbitrary `>=` tie-break |
| Only one provider returned | **1.00** | Neutral |

In trace `481ac42a` both engines returned `चंदन`. That deserved a confidence boost and got nothing.

### 4.4 Shop priors — the shop's history is a language model

A shop that has sold Chandan 200 times and Chana Dal never should bias toward Chandan. You have this data sitting in `transactions`.

```sql
CREATE MATERIALIZED VIEW shop_item_frequency AS
SELECT shop_id, item_id, COUNT(*) AS n,
       COUNT(*)::float / SUM(COUNT(*)) OVER (PARTITION BY shop_id) AS share
FROM transactions
WHERE created_at > now() - interval '90 days'
GROUP BY shop_id, item_id;
-- REFRESH MATERIALIZED VIEW CONCURRENTLY nightly
```

```
shopPrior(item_id, ctx) = log1p(n) / log1p(maxN_for_shop)      # → [0, 1]
```

Keep `W_PRIOR` **small** (start `0.03`). This is a tiebreaker, not a driver — otherwise a shop can never sell anything new, which is a worse failure than the one you are fixing. Never let the prior alone push a candidate past the margin test.

### 4.5 UNKNOWN is an outcome, not a failure (invariant I3)

When L2 returns UNKNOWN or AMBIGUOUS:

1. **Keep quantity and unit.** They are almost always correct — in every failing trace you have shown me, the quantity/unit frame survived. Only the item name was lost.
2. Create the review row with:
   - `heard_phonetic_key` — the key, not just the surface text
   - `heard_surface` — what STT actually returned, both providers
   - `top3_candidates` — with distances, for one-tap resolution
   - `audio_clip_ref` — pointer to the stored audio segment
3. Never invent a catalog item, never coerce to the nearest word, never auto-confirm.

**Storing the audio clip is important and cheap.** When the shopkeeper corrects the row you now hold an `(audio, correct_label)` pair. Even if you never train a model, that corpus is how you calibrate thresholds (§9) and how you replay regressions (§7). It costs a few KB per unknown.

### 4.6 L4 — The learning loop (invariant I4)

This is the piece that already has schema and is completely unwired.

```
User taps a correction in the review queue
  ↓
POST /learn-alias { shop_id, phonetic_key, heard_surface, canonical_item_id, job_id }
  ↓
UPSERT lexicon_aliases (shop_id, phonetic_key) → canonical_item_id, confirm_count += 1
  ↓
INSERT alias_confirmations (alias_id, shop_id)
  ↓
bump lexicon_version for that shop
  ↓
Client pulls delta on next sync → Room cache updated
  ↓
Next utterance with that key hits L0 exactly. Never wrong again.
```

**Two-tap contract in the UI.** The review row shows the top-3 candidates as tappable chips plus a "New item" action:

```
┌──────────────────────────────────────────┐
│  5 KG  •  heard: "चंदन"                  │
│  ─────────────────────────────────────   │
│  Which item?                             │
│   [ Chana Dal ]  [ Chandan ]  [ Chini ]  │
│   [ + New item: "चंदन" ]                 │
└──────────────────────────────────────────┘
```

Tapping any chip resolves the sale **and** teaches the system, in one gesture. This is the entire UX cost of the design — one tap, once per new word, ever.

**"New item" is the escape hatch that makes vocabulary unbounded.** It creates a `catalog_item` + `lexicon_entry` keyed on the phonetic key. The shopkeeper never types — they say the word, tap "new item", and it is learned. The lexicon grows from *speech*, not from data entry.

### 4.7 Cross-shop promotion (invariant I6)

You already have `distinct_shop_count` and `promote_verified_term_aliases()` in the migration. Wire them:

```sql
-- promote a shop-local alias to global once N independent shops agree
UPDATE lexicon_aliases SET scope = 'global', verified = true
WHERE scope = 'shop'
  AND distinct_shop_count >= 3
  AND confirm_count >= 5;
```

Shop A teaching "chandan" means shop B never has to. **This is the network effect that makes the system genuinely future-proof** — the more shops use it, the fewer unknowns any new shop encounters. Your 214-word list becomes a living lexicon that grows without you touching code.

Guard against poisoning: require confirmations from **distinct** shops (the join table already enforces this via its composite PK), and never auto-promote an alias that any shop has explicitly *reverted*.

### 4.8 LLM adjudication — only for the hard cases

Do not put the LLM on the critical path. Put it on the AMBIGUOUS bucket only.

```
if resolution is AMBIGUOUS:
    enqueue async adjudication:
        prompt: heard surfaces (both providers) + top-5 candidates + shop's frequent items
        ask:    "which of these, or NONE?"
        model:  grok-4.5, reasoning_effort='low', response_format=json_object
    if it answers with a candidate → upgrade the review row's default selection
    if it answers NONE            → leave as new-item candidate
    never auto-confirm on LLM alone (sourceFactor 0.95, and gate still applies)
```

**Why this shape:** you only pay latency when you are genuinely unsure, it can never block a recording, and if it times out (as it has in every trace so far) you lose *nothing* — the review row already exists with its top-3. Compare to today, where a step-4 timeout silently hands the decision to the segmenter alone.

---

## 5. Data model

```sql
-- ---------------------------------------------------------------
-- The lexicon: the vocabulary as DATA. Invariant I5.
-- ---------------------------------------------------------------
CREATE TABLE public.lexicon_entries (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id            UUID NULL REFERENCES public.shops(id) ON DELETE CASCADE,
                       -- NULL = global entry, visible to every shop
    phonetic_key       TEXT NOT NULL,
    surface_form       TEXT NOT NULL,          -- display name, e.g. 'चंदन'
    script             TEXT,                   -- 'deva' | 'latin'
    canonical_item_id  UUID NULL REFERENCES public.catalog_items(id) ON DELETE CASCADE,
    source             TEXT NOT NULL,          -- 'seed'|'catalog'|'learned'|'promoted'
    confirm_count      INT  NOT NULL DEFAULT 0,
    distinct_shop_count INT NOT NULL DEFAULT 0,
    verified           BOOLEAN NOT NULL DEFAULT false,
    revoked            BOOLEAN NOT NULL DEFAULT false,
    version            BIGINT NOT NULL,        -- for client delta sync
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_lex_key        ON public.lexicon_entries (phonetic_key)
                                   WHERE NOT revoked;
CREATE INDEX idx_lex_shop_key   ON public.lexicon_entries (shop_id, phonetic_key)
                                   WHERE NOT revoked;
CREATE INDEX idx_lex_version    ON public.lexicon_entries (shop_id, version);
CREATE UNIQUE INDEX uq_lex_scope_key
    ON public.lexicon_entries (COALESCE(shop_id, '00000000-0000-0000-0000-000000000000'::uuid),
                               phonetic_key);

-- ---------------------------------------------------------------
-- Unknown-word capture. Feeds review UI, threshold calibration,
-- and the regression corpus.
-- ---------------------------------------------------------------
CREATE TABLE public.unknown_tokens (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id           UUID REFERENCES public.shops(id) ON DELETE CASCADE,
    job_id            UUID,                   -- joins to stt_job_logs
    heard_phonetic_key TEXT NOT NULL,
    heard_surfaces    JSONB NOT NULL,         -- {"grok":"चंदन","sarvam":"चंदन"}
    top_candidates    JSONB NOT NULL,         -- [{item_id,name,distance,margin},...]
    resolution        TEXT,                   -- 'UNKNOWN' | 'AMBIGUOUS'
    quantity          NUMERIC,
    unit              TEXT,
    audio_clip_url    TEXT,
    resolved_item_id  UUID NULL,              -- set when the user corrects it
    resolved_at       TIMESTAMPTZ NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------
-- Runtime-tunable thresholds. Invariant I5 applies to these too:
-- calibration must never require a deploy.
-- ---------------------------------------------------------------
CREATE TABLE public.matching_config (
    key        TEXT PRIMARY KEY,
    value      NUMERIC NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO public.matching_config (key, value) VALUES
    ('TAU_ABS',            0.25),
    ('TAU_MARGIN',         0.08),
    ('W_PRIOR',            0.03),
    ('AUTO_CONFIRM_GATE',  0.80),
    ('GLOBAL_PROMOTE_SHOPS', 3);
```

Reuse the existing `term_aliases` / `term_alias_confirmations` tables for the alias half rather than creating parallel ones — add `phonetic_key` and `shop_id` columns to `term_aliases` and backfill. It already has `confirm_count`, `distinct_shop_count`, `verified`, and a promotion function.

**Client side (Room):** mirror `lexicon_entries` in a `lexicon_cache` table, pull deltas via `GET /lexicon?shop_id=X&since_version=N`, keep the vocabulary in memory as a phonetic-key-indexed map. Bump `AppDatabase` version with a manual migration, per the existing pattern.

---

## 6. What the segmenter must expose

The lattice in `OrderingSegmenter.kt` / `phonetic.ts` currently returns only the winner. It must return the ranking.

```kotlin
data class ItemResolution(
    val kind: ResolutionKind,          // MATCH | AMBIGUOUS | UNKNOWN
    val item: LexiconEntry?,
    val distance: Double?,
    val margin: Double?,
    val alternatives: List<Candidate>, // top 3, always populated
    val heardKey: String,
    val source: ResolutionSource       // ALIAS | GLOBAL_ALIAS | LEXICON | LLM
)
```

`RawItemSegment.itemMatchNorm` (added in ISSUE-022) becomes `itemResolution: ItemResolution`. That is a mechanical refactor — the plumbing for a single number already exists, it just needs to carry a struct instead.

**Keep both engines in sync.** The Kotlin/TS duplication is a standing hazard (you have hit it twice). At minimum, add a CI check that diffs the constants and vocabulary between the two files — the parity check I ran ad-hoc should be a permanent test.

---

## 7. Regression corpus (invariant I7)

You have no protection against re-breaking a past fix. The `kilometer`-in-`UNIT_SET` bug in ISSUE-021 *was* someone's earlier fix for ISSUE-011.

**Two harnesses:**

1. **Text-level (fast, runs on every commit).** A JSON golden file:
   ```json
   [{"trace":"481ac42a","transcripts":{"grok":"पांच लो चंदन","sarvam":"पांचलो चंदन"},
     "expect":{"qty":5,"unit":"KG","item":"Chandan","autoConfirm":false}},
    {"trace":"9fc1fc32","transcripts":{"grok":"पांच किलोमीटर"},
     "expect":{"qty":5,"unit":"KG","item":null,"flagged":true}}]
   ```
   Every resolved issue adds a row. Runs in milliseconds under `./gradlew test`.

2. **Audio-level (nightly).** Replay stored `audio_clip_url`s through the real STT + pipeline. Catches provider drift — a model update silently changing behaviour, which is exactly what bit you in ISSUE-021.

**Make adding a golden row part of closing an issue**, the same way `Docs/audit.md` already is.

---

## 8. Implementation phases

Ordered by **(impact ÷ effort)**. Phase 0 is most of the value.

### Phase 0a — Instrument first (½ day) ⚠️ do this before anything else
- Log `top3` candidates + distances + margin for every item resolution into the diagnostic trace.
- Change nothing else. Ship it. Collect a few days of real data.
- **You cannot pick `TAU_MARGIN` without this.** Every threshold in this doc is a guess until you have the distribution.

### Phase 0b — Margin test + UNKNOWN outcome (2–3 days) ← **biggest single win**
- Implement §4.2 in both engines. No schema change, no new tables.
- Add `AMBIGUOUS`/`UNKNOWN` outcomes; preserve qty+unit (I3).
- Gate auto-confirm on margin (I2).
- **This alone would have caught all three recent traces**, including the one where STT was perfect.

### Phase 1 — Lexicon as data (3–5 days)
- `lexicon_entries` table + seed from current `DEFAULT_ITEM_VOCAB` + all shops' catalogs.
- `GET /lexicon` delta endpoint; Room cache; client sync on startup.
- Segmenters read the cache instead of the hardcoded list. **Delete the literal lists.** (I5)

### Phase 2 — Learning loop (3–5 days) ← **the one that makes it converge**
- `unknown_tokens` capture + review-queue UI with top-3 chips and "New item" (§4.6).
- `POST /learn-alias`; wire the long-orphaned `confirmTermAlias()`.
- L0 alias lookup at match time. (I4)

### Phase 3 — Evidence fusion (2 days)
- `agreementFactor` from cross-provider phonetic-key comparison.
- Replace the `>=` tie-break with genuine both-transcript decoding: decode both, pick the better **parse**, not the better transcript score.

### Phase 4 — Shop priors (2 days)
- `shop_item_frequency` materialized view + `W_PRIOR` in scoring. Keep the weight small.

### Phase 5 — Cross-shop promotion (1–2 days)
- Wire `promote_verified_term_aliases()` on a nightly cron. (I6)

### Phase 6 — Regression corpus (2–3 days)
- Both harnesses (§7) + CI gate + Kotlin/TS parity check. (I7)

### Phase 7 — LLM adjudication (2 days)
- Async, AMBIGUOUS-only (§4.8).

### Phase 8 — Long term, only with data
- Learn the phonetic confusion matrix from accumulated `(audio, correction)` pairs instead of hand-tuning `PhoneticKey`'s collapse rules. Needs a few thousand corrections. Do not start before you have them.

**If you only do two phases: 0b and 2.** Margin stops confident wrongness; the learning loop stops repetition. Everything else is optimization.

---

## 9. Metrics — how you know it is working

Instrument these before Phase 0b so you have a baseline.

| Metric | Definition | Target |
|---|---|---|
| **Silent error rate** | auto-confirmed transactions later edited/deleted by the user | **→ 0.** The only metric that truly matters. |
| **Repeat error rate** | same `phonetic_key` resolved wrongly twice in the same shop | **→ 0** after Phase 2. If not, the loop is broken. |
| **Unknown rate per shop** | UNKNOWN ÷ total utterances, by shop age | must **decay** with shop age. Flat = not learning. |
| **Review burden** | fraction routed to review | should fall after Phase 2. If it rises and stays high, `TAU_MARGIN` is too aggressive. |
| **Time-to-first-correct** | utterances before a new word resolves correctly | target **1** (one correction, then permanent). |

**The decay curves are the proof.** A system that is merely accurate shows flat lines. A system that *learns* shows unknown-rate and review-burden decaying per shop with use. That decay is the deliverable you are asking for.

### Threshold calibration procedure

Once `unknown_tokens` has ~200 resolved rows:

1. For each, you have `(margin, was_the_top_candidate_correct)`.
2. Plot precision vs `TAU_MARGIN`.
3. Choose the smallest `TAU_MARGIN` where precision-above-threshold ≥ 0.98.
4. Write it to `matching_config`. No deploy. Re-run monthly.

This replaces hand-tuning constants — which is how `WHOLE_TOKEN_MAX_NORM = 0.25` came to allow the "चोर → Jeera" match in the first place.

---

## 10. What this still will not fix

Stated plainly so you are not surprised later.

1. **Audio-destroyed information.** "paanch" heard as "saat" by both engines is unrecoverable. Margin/priors do not help — the system will be *confidently* wrong about the quantity because both providers agree. **Mitigation:** quantity is the highest-value field; consider a confirmation glance for high-value sales (§4.3 could add a `valueFactor` that lowers confidence as ₹ rises).
2. **First encounter with a truly novel word** costs one tap. That is the floor, and it is the right floor.
3. **Homophones.** If two catalog items key identically, phonetics cannot separate them — only shop priors and context can. Detect this at lexicon-build time and warn.
4. **Kotlin/TS duplication remains** a standing hazard until one side becomes authoritative. The CI parity check (Phase 6) contains the risk; it does not remove it.

---

## 11. The one-paragraph summary

Stop trying to know every word in advance. Make the vocabulary **data that syncs** instead of code that ships (I5). Make the matcher able to say **"I don't know"** by testing the *margin* between the best and second-best candidate rather than an absolute threshold — this catches unknown words **without needing to know them** (I2, I3). Turn every correction into a **permanent, phonetically-keyed alias** so no word is ever wrong twice (I4), and promote aliases confirmed by multiple shops to a **shared global lexicon** so the system gets smarter as more shops use it (I6). Use the evidence you already collect and discard — cross-provider agreement, runner-up distance, the shop's own sales history. The result is not a system that is never wrong; it is a system whose error rate **decays toward zero on its own**, which is the only version of "never again" that actually exists.
