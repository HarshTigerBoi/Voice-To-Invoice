# Master Catalog Expansion — plan v2

**Status:** plan, nothing implemented
**Drafted:** 2026-08-11 (v2 supersedes v1 of the same date)
**Goal:** cover everything sold in Indian shops — kirana through hardware, "chhota Good Day"
through "Asian Paints balti" — while the app adapts its vocabulary, units and prices to each
individual shopkeeper.

---

## 0. Two constraints that shape every decision

### 0.1 Density destroys matching

`FuzzyCatalogMatcher` flags AMBIGUOUS when the top-2 similarity gap is under `0.15`
(`FuzzyCatalogMatcher.kt:183`), dropping confidence to `0.40` — below the auto-confirm gate.
That guard is correct and **density-sensitive**: more near-identical names in the candidate pool
means smaller margins everywhere.

A flat 500k list makes every utterance ambiguous. So the plan's central mechanism is **shrinking
the candidate pool at match time** — by vertical (§2), then by category, then by brand (§5).
Global size is unbounded; the pool any single utterance is scored against stays in the low
thousands. Every section below serves that.

### 0.2 Scraped data cannot supply spoken forms

A crawl yields `"Britannia Good Day Cashew Cookies 200g"`. The shopkeeper says `"chhota good day"`.
Retail sites carry no Devanagari, no Hinglish, and no size-modifier vocabulary.

**So acquisition splits in two, and both halves are mandatory:**

| Need | Source |
|---|---|
| Canonical names, pack sizes, MRP, barcodes | Bulk crawl (§7) |
| Spoken forms, Hindi/Hinglish aliases, size modifiers | LLM generation (§7) + learning loop (§6) |

Neither substitutes for the other.

### 0.3 Verified ground truth (queried 2026-08-11, project `lyowklxsbfznnqridtgr`)

| Fact | Value |
|---|---|
| `catalog_items` rows in prod | 131 (78 on shop `2f992a33…`, 53 legacy `shop_id IS NULL`) |
| Distinct `item_name` in `transactions` | 50, across 234 rows |
| Top spoken items | `Aaloo` 81, `Baingan` 18, `Aam` 14, `Amul Gold Milk` 10 |
| `DEFAULT_ITEM_VOCAB` | 220 entries, duplicated in `OrderingSegmenter.kt` + `phonetic.ts` |
| `shops` columns | `id, user_id, name, **vertical**, language, tier, created_at` |
| `shops.vertical` values live | `vegetable` × 2 — **the column exists and is unused** |
| `ItemUnit` seeds | 13, all kirana-shaped (`AppDatabase.kt:1003–1014`) |
| `LearningKind` | `ITEM_ALIAS` wired; **`UNIT_MEANING`, `DEFAULT_PRICE`, `PHRASE_INTENT`, `CUSTOMER_ALIAS` declared, no writers** |
| `category`/`brand`/`parentId` on `CatalogItem` | none — catalog is flat strings |

**Two of the three hard problems in this plan already have schema.** `shops.vertical` is the
partition key (§2); `LearningKind.UNIT_MEANING` / `DEFAULT_PRICE` are the size-modifier and
price-learning stores (§5.2, §6). This is mostly wiring, not new construction.

---

## 1. Legal position (settled — recorded so it is not relitigated)

Product names and brand lists are **facts**. Compilations of facts attract no copyright in India:
*Eastern Book Company v. D.B. Modak* (2008) rejected "sweat of the brow" in favour of a modicum-of-
creativity standard, and India has **no EU-style sui generis database right**. Referring to a brand
by name to identify the actual product is nominative use — what every inventory system does.

The narrow residue is that automated crawling may breach a site's **terms of service** — a contract
matter, not copyright or criminal. Shop owner has weighed this and decided to proceed. Engineering
consequences only, handled in §7.3: be polite, cache aggressively, expect churn.

---

## 2. Vertical partitioning — how "everything" stays safe

`shops.vertical` already exists. Use it as the lexicon partition key.

```
Global lexicon:        ~500k entries across all verticals
Shop's active pool:    only its vertical's partitions
```

A kirana shop never scores against Asian Paints SKUs; a hardware shop never scores against
biscuits. **This is what makes unbounded global coverage compatible with §0.1.**

Proposed vertical set (extends the current single `vegetable` value):

`vegetable` · `kirana` · `general` · `dairy` · `pharmacy` · `hardware` · `paint` ·
`electrical` · `plumbing` · `stationery` · `auto_parts` · `cloth` · `sweets` · `cosmetics`

**Multi-vertical shops are the common case**, not the exception — a village general store sells
both atta and paint brushes. So:

- `shops.vertical` stays as the **primary** for defaults and onboarding.
- Add `shop_verticals (shop_id, vertical, weight)` for the actual active set.
- A vertical auto-activates at low weight when the shop first sells anything from it, so coverage
  grows by use rather than by a settings screen the shopkeeper never opens.
- Partition weight feeds §5's scoring: a low-weight vertical's entries need a wider margin to win.

---

## 3. Four tiers

| Tier | Example | Size | Source | Priced? | Scope |
|---|---|---|---|---|---|
| **T0 Vertical** | kirana, hardware | ~14 | Hand | — | global |
| **T1 Generic** | biscuit, paint, balti, cement | ~4,000 | Grok + review | No | global |
| **T2 Brand** | Good Day, Asian Paints, Ambuja | ~50–150k | Crawl + Grok aliases | No | global |
| **T3 Variant** | 200g / 500g / 20L balti / 50kg bag | unbounded | **Crawl MRP + per-shop learning** | **Yes** | **per-shop** |

**T3 is the tier that answers "chhota Good Day."** It is bulk-*seeded* with pack sizes and MRP from
the crawl, but the shop's **stocked** variants and their prices are per-shop and learned (§5.2, §6).
Bulk-importing T3 as global priced rows is what would pollute `catalog_items` with zero-stock,
wrong-price entries and break `ReorderAdvisor`, `AlertEngine`, and stock reports — all of which
assume a row here means *this shop sells this*.

---

## 4. Schema

### 4.1 `lexicon_entries` (from open-vocabulary-architecture.md §5 — designed, not implemented)

```sql
ALTER TABLE public.lexicon_entries
    ADD COLUMN tier          SMALLINT NOT NULL DEFAULT 2,  -- 1 generic, 2 brand, 3 variant
    ADD COLUMN vertical      TEXT     NULL,                -- partition key; NULL = all verticals
    ADD COLUMN category_key  TEXT     NULL,                -- 'biscuit', 'paint'
    ADD COLUMN parent_id     UUID     NULL REFERENCES public.lexicon_entries(id) ON DELETE CASCADE,
    ADD COLUMN default_unit  TEXT     NOT NULL DEFAULT 'PIECE',
    ADD COLUMN pack_size     NUMERIC  NULL,                -- 200 (with pack_unit 'GRAM')
    ADD COLUMN pack_unit     TEXT     NULL,
    ADD COLUMN mrp           NUMERIC  NULL,                -- sanity band only, never the price
    ADD COLUMN barcode       TEXT     NULL,
    ADD COLUMN source_ref    TEXT     NULL,                -- provenance: 'off:<code>', 'crawl:<site>'
    ADD COLUMN verified      BOOLEAN  NOT NULL DEFAULT false;

CREATE INDEX idx_lex_vertical ON public.lexicon_entries (vertical, category_key) WHERE NOT revoked;
CREATE INDEX idx_lex_parent   ON public.lexicon_entries (parent_id)              WHERE NOT revoked;
CREATE INDEX idx_lex_barcode  ON public.lexicon_entries (barcode)                WHERE barcode IS NOT NULL;
```

### 4.2 `ItemUnit` — currently 13 kirana units, seeded as code

`AppDatabase.kt:1003–1014` hardcodes KG/GRAM/LITRE/ML/PACKET/PIECE/DOZEN/PAO/AADHA/SAWA/DHAI/BOX.
Nothing there can express a cement bag, a wire coil, or a paint balti.

**Add** (with `vertical` scoping so a kirana shop is not offered SQFT):

| Unit | Base | Factor | Vertical |
|---|---|---|---|
| `BALTI` | LITRE | *shop-set* | paint, hardware |
| `BAG` | KG | *shop-set* (cement 50) | hardware |
| `TIN` | LITRE | *shop-set* | paint |
| `DRUM` | LITRE | *shop-set* | paint, chemical |
| `METRE` / `FOOT` | METRE | 1.0 / 0.3048 | electrical, cloth |
| `SQFT` | SQFT | 1.0 | hardware, tiles |
| `COIL` / `BUNDLE` / `ROLL` | PIECE | *shop-set* | electrical, plumbing |
| `STRIP` | PIECE | *shop-set* | pharmacy |
| `QUINTAL` | KG | 100.0 | kirana, agri |
| `PAIR` / `SET` / `JODI` | PIECE | 2.0 / *shop-set* / 2.0 | general, cloth |

***shop-set* is the important column.** A balti is 20 L at one shop and 10 L at another; a peti is
20 kg here and 25 kg there. These **must not** be global constants — they are exactly what
`LearningKind.UNIT_MEANING` was declared for. Seed a national default, override per shop on first
correction, store in `shop_learning`.

Migrate `ItemUnit` seeding from `AppDatabase` code to lexicon-delivered data, per invariant I5
(no vocabulary change should need a deploy).

### 4.3 `CatalogItem` — add variant linkage only

```kotlin
val lexiconEntryId: String? = null,  // resolved T2/T3 entry this shop item corresponds to
val packSize: Double? = null,        // 200.0
val packUnit: String? = null,        // "GRAM"
val sizeRank: Int? = null,           // 0 = smallest stocked of its brand; drives chhota/bada
```

`sizeRank` is materialized per (shop, parent brand) whenever the shop's stocked variant set
changes. §5.2 reads it. Keep price/stock semantics exactly as they are.

---

## 5. Matching — pool shrinking, then size modifiers

### 5.1 The cascade

Implement in `OrderingSegmenter.kt` **and** `phonetic.ts` — mirrored logic, both sides change.

```
resolveItem(tokens, shop):

  0. strip + retain SIZE MODIFIER tokens          → §5.2
  1. shop catalog          exact/fuzzy            → wins outright (unchanged)
  2. shop_learning ITEM_ALIAS                     → wins outright (already wired)
  3. T1 generic, vertical-scoped                  → ~300 candidates, not 4,000
  4. T2 brand, SCOPED to parent from step 3       → ~200 candidates, not 150,000
  5. T3 variant, SCOPED to brand from step 4      → ~6 candidates
  6. no T1 hit → T2 global within vertical, REQUIRE 2× margin
```

Steps 3–5 are load-bearing. `Good Day` competes against biscuit brands, never against 150k
products. **This is what keeps the `0.15` margin guard meaningful at any global size.**

### 5.2 Size modifiers — "chhota Good Day"

The insight that drives the design: **chhota/bada are relative and shop-specific.** "Bada Good Day"
means 200 g at a shop stocking 100/200, and 500 g at a shop stocking 200/500. A national table of
`chhota = 100g` is wrong at most shops. **It cannot be bulk-imported. It must be learned per shop.**

Modifier vocabulary (generated, per §7, in all three scripts):

| Class | Tokens |
|---|---|
| SMALL | chhota, chota, छोटा, chhoti, nanha, small, mini |
| LARGE | bada, बड़ा, badi, jumbo, large, big, family pack |
| MID | medium, beech ka, बीच का, regular |
| ABSOLUTE | 200 gram, ₹5 wala, paanch rupaye wala, half kg |

Resolution, after step 4 has resolved the brand:

```
variants = shop's stocked CatalogItems WHERE lexiconEntryId.parent = brand,
           ordered by packSize → sizeRank

ABSOLUTE modifier  → direct packSize match. Unambiguous. Done.
n == 0 variants    → brand resolves, variant UNKNOWN, ask price (§6). Creates the variant.
n == 1             → modifier ignored, resolve to it. ("chhota good day" with one size = that size)
n == 2             → SMALL→rank 0, LARGE→rank 1
n >= 3             → SMALL→rank 0, LARGE→rank n-1, MID→middle
```

**Then persist the binding.** Write `shop_learning` kind `UNIT_MEANING`, key
`phoneticKey(modifier + brand)`, value = resolved `catalogItemId`. On the next utterance step 2
hits it directly — no ordinal inference, and a shopkeeper correction overrides the inference
permanently via the existing `decay`/`confirm` mechanics in `ShopLearningRepository`.

This is precisely the case `ShopLearning`'s own docstring anticipated ("peti = 20kg at this shop").

**Ordinal inference is a bootstrap, not the answer.** It gets the first utterance usually-right;
the learned binding makes it always-right thereafter. Never auto-confirm a purely ordinal
inference above the gate — route to review with the variant chips pre-ranked.

---

## 6. Price learning — "ask until we're sure"

Wire `LearningKind.DEFAULT_PRICE`, keyed on `phoneticKey(catalogItemId)`, reusing the existing
`confidence` field (0..1, rises on confirmation, falls on contradiction).

| State | Condition | Behaviour |
|---|---|---|
| **UNKNOWN** | no observation | **Ask every time.** Book qty+item, price pending. |
| **PROVISIONAL** | 1–2 consistent | Pre-fill, require a tap to confirm |
| **CONFIRMED** | ≥3 consistent, confidence ≥ 0.8 | Auto-fill silently |
| **CONTESTED** | variance > 15% across last 5 | Back to asking; do not guess |
| **STALE** | >90 days unused, or MRP moved >10% | Ask once, then re-confirm |

Rules:

1. **A sale is never blocked on price.** UNKNOWN books qty + item with price null and surfaces in
   review. Losing the sale record to get a price is the wrong trade — voice capture is the product.
2. **MRP is a sanity band, never the price.** Kirana sells below MRP, above it, and loose. Use it
   only to flag entry errors: outside 0.5×–2.0× MRP → confirm. That is the whole value of crawled
   MRP, and it is worth having.
3. **Price attaches to T3 variant, not T2 brand.** "Good Day" has no price; "Good Day 200g" does.
   This is why §5.2's variant resolution must run before the price lookup.
4. **Bulk prices are never imported as shop prices.** Crawled MRP lands in `lexicon_entries.mrp`
   and nowhere else.

---

## 7. Acquisition

Offline tooling under `tools/catalog/`, committing versioned artifacts to the repo.
**Not edge functions — never deploy these.**

### 7.1 Channel A — bulk crawl (canonical names, pack sizes, MRP, barcodes)

| Vertical | Sources |
|---|---|
| kirana / general / dairy | BigBasket, JioMart, Blinkit, DMart, Zepto |
| hardware / paint / electrical / plumbing | Moglix, IndiaMART, Amazon Business, Asian Paints & Berger site catalogs |
| pharmacy | 1mg, PharmEasy, Netmeds |
| stationery / cosmetics | Amazon.in, Nykaa, Flipkart |

Category-listing pages give name + pack + MRP + image + category path in one pass; the category
path is a **free T1/T2 hierarchy signal** — take it, it is better than inferring the tree later.

### 7.2 Channel B — Open Food Facts bulk (barcode ↔ product, validation)

`openfoodfacts-products.jsonl.gz`, ~0.9 GB compressed / ~9 GB uncompressed (verified at
`world.openfoodfacts.org/data`), ODbL. India subset: **22,380 food** + **1,718 beauty** (verified
at `in.openfoodfacts.org`, `in.openbeautyfacts.org`).

Too small to be primary and food-skewed, but it is the only source with **barcodes**, which
unlocks §7.5 — and it cross-checks crawl output for free.

### 7.3 Channel C — spoken-form generation (**mandatory, no substitute**)

No crawl yields Devanagari or Hinglish. For every T1 and T2 entry, generate via
`grok-4.20-0309-non-reasoning` (already the step-4 default, `process-voice-job/index.ts:59`),
`response_format: json_object`:

```json
{
  "canonical": "Good Day",
  "vertical": "kirana", "category_key": "biscuit", "tier": 2,
  "aliases": {
    "devanagari": ["गुड डे", "गुड्डे"],
    "hinglish":   ["good day", "gud de", "gudde"],
    "english":    ["Good Day Biscuit"]
  },
  "size_modifiers_apply": true
}
```

**Reject any entry with fewer than 2 aliases.** A canonical name with no spoken forms adds density
(§0.1) while contributing zero recognition — strictly negative value.

Batch by category, 8 concurrent. Cost scales with T2 size; run T1 (~4k) first and measure before
committing to 150k.

### 7.4 Crawl engineering

- **Cache raw responses to disk, keyed by URL + fetch date.** Parse from cache, never re-fetch to
  re-parse. Makes parser iteration free and crawl volume minimal.
- One parser module per source, versioned, with a golden HTML fixture per site in
  `tools/catalog/fixtures/`. Sites change markup; fixtures make the break loud and local.
- Serial per host with a delay, honour `robots.txt` crawl-delay, real UA with contact. Politeness
  is also what avoids the blocks that would actually stall this.
- Ceiling on pages per host per run; resumable checkpoints.
- **Never crawl from an edge function or CI.** Local tool, committed artifact.

### 7.5 Channel D — barcode scan at stock-in (highest quality per unit effort)

A scanned barcode is an **exact** SKU identity — no phonetics, no ambiguity, no margin problem.
It resolves brand *and* variant *and* pack size in one action, and it is the natural moment to
capture price, feeding §6 directly.

`lexicon_entries.barcode` + OFF's mapping makes this cheap for food. For the rest, an unknown
barcode scanned at stock-in becomes a shop-local entry that §7.6 later promotes.

**This deserves its own decision.** It sidesteps the entire §5 cascade for packaged goods, which is
most of T2/T3. Scoping it is out of scope here — flagged as a strong candidate for the highest
value-per-effort work in this document.

### 7.6 Channel E — the learning loop (best data, arrives slowest)

Cross-shop promotion (open-vocabulary-architecture.md §4.7) grows the lexicon from words shops
actually said, with real spoken forms and real per-shop bindings. Everything above is scaffolding
to make the first weeks tolerable until this dominates.

---

## 8. Collision gate — non-negotiable

Every candidate alias goes through `PhoneticKey`, grouped by key, **before** import.

| Collision | Action |
|---|---|
| Within a category (`Parle-G` / `Parle Gold`) | Keep both — §5.1 step 5 and review chips disambiguate |
| **Across categories** (`साबुन` soap / `सोयाबीन` soyabean) | **Hard fail. Import neither.** Log to `tools/catalog/collisions.tsv` |
| Across verticals (`balti` paint / `balti` utensil) | Allow — §2 partitioning separates them |

The `सोयाबीन`→`साबुन` mis-resolution at norm 0.214 (open-vocabulary-architecture.md §1.5) is this
exact failure **already observed in production at 220 words**. At 150k it is not a risk, it is a
certainty. This gate is what makes the difference between "more coverage" and "more wrong bookings."

---

## 9. Staging — what ships when

| Stage | Ships | Gated on |
|---|---|---|
| **A** | Crawl + generation tooling; T1 (~4k) artifact committed | nothing — **start now** |
| **B** | `shop_verticals` + vertical seeding + expanded `ItemUnit` | A |
| **C** | `lexicon_entries` + tier/vertical columns + `GET /lexicon` + Room cache | open-vocab Phase 1 |
| **D** | §5.1 cascade (vertical → category → brand → variant) | C |
| **E** | T2 bulk import, **one vertical at a time**, kirana first | **D — hard gate** |
| **F** | §5.2 size modifiers + `UNIT_MEANING` writes | D + `CatalogItem.sizeRank` |
| **G** | §6 price learning + `DEFAULT_PRICE` writes | F |
| **H** | §7.5 barcode scan at stock-in | C |

**E must not precede D**, and E ships **per vertical**, never all at once — one vertical is a
measurable experiment (§10), all at once is an unattributable regression.

F and G are the "adjusts to the shopkeeper" payload and are mostly wiring declared-but-unwritten
enum values. **H may deserve to jump the queue** — it only needs C.

---

## 10. Verification

Build success proves nothing (CLAUDE.md). Verify by effect.

1. **Capture the margin baseline before generating anything.** Run the current 220 words through
   `FuzzyCatalogMatcher` against the 50 real `item_name` values from `transactions`; record the
   top-2 margin distribution. Without this number, no later stage can be shown to have helped —
   and it is unrecoverable after the fact.
2. **Re-run after every stage.** Median margin must not fall. If it does, the §5 pool scoping is
   not working — stop, do not proceed to the next vertical.
3. **Review-burden metric**, 7 days before/after each stage: `PARSED`-not-auto-confirmed as a
   fraction of total. A rise after E means scoping is leaking.
4. **Live check per stage**: query `stt_job_logs` for a job created *after* install, quote the row.
   No row = not verified; say so plainly.
5. **Goldens**: `chhota good day`, `bada good day`, `good day 200 gram`, `asian paints balti`,
   `ek bori cement` → regression corpus (open-vocabulary-architecture.md §7).
6. **Price-state test**: same item sold 4× at one price must transition UNKNOWN → PROVISIONAL →
   CONFIRMED and stop asking. Assert on the state, not on the UI.

---

## 11. Open questions

1. **T2 target size.** 150k is a guess. Recommend crawling kirana only, measuring §10.1 against it,
   then deciding — rather than committing to a number now.
2. **Vertical list.** 14 in §2, inferred from "Good Day to Asian Paints balti." Needs review by
   someone with shop-floor knowledge; everything inherits scope from it.
3. **T1 `default_unit` correctness.** Grok will guess KG vs PCS vs LITRE. Wrong defaults are silent
   per-item ledger errors. Recommend hand-reviewing all ~4,000; T2/T3 inherit from parent.
4. **Multi-vertical onboarding.** Does the shopkeeper pick verticals, or do they auto-activate on
   first sale (§2)? Auto-activation is better UX and worse for margins in week one. Undecided.
5. **Barcode priority (§7.5).** Strong candidate to jump ahead of E/F/G. Needs a scoping pass.
6. **Regeneration cadence.** Brands change. Decide after v1 lands.
