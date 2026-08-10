# Master Catalog Expansion — plan v3

**Status:** plan, nothing implemented
**Drafted:** 2026-08-11 (v3 supersedes v2 — v2 conflicted with already-shipped ISSUE-109 work
discovered mid-session; see §0.4)
**Goal:** cover everything sold in Indian shops — kirana through hardware, "chhota Good Day"
through "Asian Paints balti" — while the app adapts its vocabulary, units and prices to each
individual shopkeeper.

---

## 0. Four constraints that shape every decision

### 0.1 Density destroys matching

`FuzzyCatalogMatcher` flags AMBIGUOUS when the top-2 similarity gap is under `0.15`
(`FuzzyCatalogMatcher.kt:183`), dropping confidence to `0.40` — below the auto-confirm gate.
That guard is correct and **density-sensitive**: more near-identical names in the candidate pool
means smaller margins everywhere. This applies to the fuzzy-matched **base item vocabulary**
(§3 T1) — global size is unbounded, but the pool any single utterance is scored against must stay
in the low thousands, via vertical + category scoping.

### 0.2 Scraped data cannot supply spoken forms

A crawl yields `"Britannia Good Day Cashew Cookies 200g"`. The shopkeeper says `"chhota good
day"`. Retail sites carry no Devanagari, no Hinglish, no size-modifier vocabulary. Acquisition
splits into a bulk-crawl half (names, pack sizes, MRP, barcodes) and an LLM-generation half
(spoken forms) — neither substitutes for the other (§7).

### 0.3 Verified ground truth (queried 2026-08-11, project `lyowklxsbfznnqridtgr`)

| Fact | Value |
|---|---|
| `catalog_items` rows in prod | 131 (78 on shop `2f992a33…`, 53 legacy `shop_id IS NULL`) |
| Distinct `item_name` in `transactions` | 50, across 234 rows |
| `shops` columns | `id, user_id, name, **vertical**, language, tier, created_at` |
| `shops.vertical` values live | `vegetable` × 2 — column exists, unused beyond that |
| `ItemUnit` seeds | 13, all kirana-shaped (`AppDatabase.kt` seed block) |
| `LearningKind` | `ITEM_ALIAS` wired; `UNIT_MEANING`, `DEFAULT_PRICE` declared, no writers |

### 0.4 Reconciliation with ISSUE-109 — read this before §3

**v2 of this plan proposed a `lexicon_entries` table with a `parent_id` brand tree — a flat set
of fuzzy-matched brand candidates scoped under a resolved generic.** Mid-session, a much larger
body of uncommitted work surfaced in the working tree (now committed, see git log) that already
solves the commodity-brand half of that problem, differently and better.

**What already exists, verified live (`Docs/audit.md` ISSUE-107/108/109, closed 2026-08-09):**

- `supabase/functions/process-voice-job/lexicon.ts` + mirrored
  `app/.../domain/lexicon/ItemLexicon.kt`: a `QUALIFIERS` list (`BRAND`/`VARIETY` kind, currently
  10 brands + 8 varieties) and `composeIdentity()`. Given `"tata sugar"`, it **strips the
  qualifier token via exact-surface lookup** (`QUALIFIER_BY_SURFACE`, a `Map`, not fuzzy
  matching), resolves the remainder (`"sugar"`) against the existing small base-item vocabulary,
  and recomposes as `"Tata Sugar"` — an identity for a brand that was never individually
  enumerated.
- `catalog_items.canonical_key` + `catalog_items.base_unit`: a sellable row is
  `(shop_id, canonical_key, base_unit)`. Migrations `20260809000300`–`500` and Room `MIGRATION_26_27`
  already ship this.
- `record_unmatched_item_observation` (7-arg) already stamps `canonical_key` on insert and looks
  up existing rows by `(canonical_key, base_unit)` before creating a duplicate.

**Why this changes the design, not just the naming:** exact-surface qualifier lookup has **no
margin problem at any scale** — it's a hash map, not a distance computation. §0.1's density
concern doesn't apply to brand qualifiers on true generic commodities (milk, ghee, sugar, atta,
oil, namak) at all, because they're never fuzzy-matched against each other. Building a parallel
fuzzy brand-tree for this case would duplicate a solved problem and reintroduce the very margin
risk ISSUE-109 eliminated.

**What ISSUE-109 does NOT solve, and where this plan still has real work:**

1. **Named branded products are not brand+generic compositions.** "Good Day" is not
   Parle(brand) + "Day"(generic) — there's no generic to strip a qualifier from. It's a standalone
   product, exactly like today's `ITEM_LEXICON`/`FuzzyCatalogMatcher.indicAliasMap` entries. This
   is a **base-item vocabulary scale problem** (§3 T1), not a qualifier problem, and §0.1's margin
   risk fully applies here — this is most of what "Asian Paints balti" and "thousands of
   products" actually means.
2. **`QUALIFIERS` is a 10-brand hand list.** Extending it to the ~150–300 real commodity brands
   that pair with generics (not thousands — most brands don't pair with a bare commodity word) is
   still real, still needed, and still cheap because the mechanism is already built (§3 T2).
3. **Pack-size variants are a new axis ISSUE-109 didn't need to touch, and they collide with its
   dedupe key as currently defined.** §4.3 below is a required fix, not optional.
4. **`shops.vertical` partitioning doesn't exist yet.** Needed for §0.1 pool-shrinking at T1
   scale regardless of the qualifier mechanism.

---

## 1. Legal position (settled — recorded so it is not relitigated)

Product names and brand lists are **facts**. Compilations of facts attract no copyright in India:
*Eastern Book Company v. D.B. Modak* (2008) rejected "sweat of the brow," and India has no
EU-style sui generis database right. Referring to a brand by name to identify the actual product
is nominative use. The narrow residue is that automated crawling may breach a site's **terms of
service** — a contract matter, not copyright or criminal — handled as an engineering nuisance in
§7.3 (politeness, caching, expect churn), not a blocker.

---

## 2. Vertical partitioning

`shops.vertical` exists, unused beyond one hand-set value. Use it as the T1 pool-scoping key.

```
Global T1 vocabulary:  tens of thousands of entries across all verticals
Shop's active pool:    only its vertical's partitions
```

A kirana shop never scores against Asian Paints SKUs; a hardware shop never scores against
biscuits. This is what makes unbounded global T1 coverage compatible with §0.1.

Proposed vertical set: `vegetable · kirana · general · dairy · pharmacy · hardware · paint ·
electrical · plumbing · stationery · auto_parts · cloth · sweets · cosmetics`

Multi-vertical shops are the common case (a general store sells atta and paint brushes):

- `shops.vertical` stays as the primary, for defaults and onboarding.
- Add `shop_verticals (shop_id, vertical, weight)` for the actual active set.
- A vertical auto-activates at low weight the first time a shop sells anything from it, so
  coverage grows by use, not by a settings screen nobody opens.
- Weight feeds T1 scoring: a low-weight vertical's entries need a wider margin to win, so a rare
  cross-vertical sale doesn't false-positive against the shop's dominant vertical.

---

## 3. Tiers, revised against §0.4

| Tier | What | Example | Size | Mechanism |
|---|---|---|---|---|
| **T0 Vertical** | partition key | kirana, hardware | ~14 | `shops.vertical` (§2) |
| **T1 Base item** | standalone product or generic commodity | Chawal, Good Day, Asian Paints Tractor Emulsion, Cement | ~15–20k | Fuzzy/phonetic, vertical+category scoped (§0.1 applies — this is the part that needs pool-shrinking) |
| **T2 Commodity qualifier** | brand/variety modifying a T1 generic | Amul + Ghee, Tata + Sugar | ~150–300 brands × existing varieties | `QUALIFIERS` + `composeIdentity()` — **already built, exact-surface, extend the list only** |
| **T3 Variant** | pack size of a T1 or T2 identity | Good Day 200g, 20L balti, 50kg cement bag | unbounded | Crawl-seeded MRP/pack size; **shop's stocked variant + price is per-shop and learned** (§5.2, §6) |

**T1 absorbs what v2 called "T2 brand"** for named products — Good Day, Parle-G, Surf Excel,
Maggi, Asian Paints Tractor Emulsion are base items in the same fuzzy-matched pool as Chawal and
Ghee, not children of a brand node. **T2 is now exactly ISSUE-109's mechanism**, scoped to true
generic+brand commodity pairs only.

**T3 is never bulk-imported as priced/stocked rows** — same reasoning as v2: bulk MRP seeds
`lexicon_entries.mrp` as a sanity band; the shop's own stock-in supplies its actual price and
which variants it carries. Bulk-importing T3 as global rows would pollute `catalog_items` with
zero-stock, wrong-price entries that `ReorderAdvisor`/`AlertEngine`/stock reports all assume mean
"this shop sells this."

---

## 4. Schema

### 4.1 `lexicon_entries` — T1 only (from open-vocabulary-architecture.md §5, designed, not built)

No `parent_id` brand tree. Brand composition is handled by `QUALIFIERS`, not this table.

```sql
ALTER TABLE public.lexicon_entries
    ADD COLUMN vertical      TEXT     NULL,   -- partition key; NULL = all verticals
    ADD COLUMN category_key  TEXT     NULL,   -- 'biscuit', 'paint', 'cement'
    ADD COLUMN default_unit  TEXT     NOT NULL DEFAULT 'PIECE',
    ADD COLUMN pack_size     NUMERIC  NULL,    -- optional, for entries that ARE a specific pack
    ADD COLUMN pack_unit     TEXT     NULL,
    ADD COLUMN mrp           NUMERIC  NULL,    -- sanity band only, never the price
    ADD COLUMN barcode       TEXT     NULL,
    ADD COLUMN source_ref    TEXT     NULL,    -- 'off:<code>', 'crawl:<site>', 'generated'
    ADD COLUMN verified      BOOLEAN  NOT NULL DEFAULT false;

CREATE INDEX idx_lex_vertical ON public.lexicon_entries (vertical, category_key) WHERE NOT revoked;
CREATE INDEX idx_lex_barcode  ON public.lexicon_entries (barcode) WHERE barcode IS NOT NULL;
```

### 4.2 `QUALIFIERS` extension — T2, no new table

Extend the existing list in `lexicon.ts` + `ItemLexicon.kt` from 10 brands to the target set,
sourced from §7 generation, filtered to brands that genuinely pair with a bare generic (Tata,
Madhur, Aashirvaad, Fortune — not Parle, Britannia, which name standalone products, not
commodities). **Keep the module-load collision assertion** (`brand_variant_unit_model_plan.md`
§2.2) — a candidate brand whose surface collides with an existing T1 item surface must fail loudly
at deploy, not silently misroute.

### 4.3 `CatalogItem` / `catalog_items` — pack size must join the identity key (required fix)

**This is the one place v2 would have introduced a real bug if shipped as written.** The existing
dedupe/promotion grouping is `(shop_id, canonical_key, base_unit)` (ISSUE-108/109). "Good Day
200g" and "Good Day 500g" share the same `canonical_key` ("Good Day Biscuit") and the same
`base_unit` (`PIECE`) — under the current grouping they are indistinguishable, and the merge
migration (`20260809000400_merge_same_identity_same_unit.sql`) would treat two legitimately
different, correctly-priced variants as duplicates of each other.

Fix, as its own migration (does not touch ISSUE-109's shipped logic, extends it):

```sql
-- supabase/migrations/<next>_catalog_pack_size_identity.sql
ALTER TABLE catalog_items ADD COLUMN IF NOT EXISTS pack_size NUMERIC NULL;
ALTER TABLE catalog_items ADD COLUMN IF NOT EXISTS pack_unit TEXT NULL;

DROP INDEX IF EXISTS idx_catalog_items_identity_unit;
CREATE INDEX idx_catalog_items_identity_unit
    ON catalog_items (shop_id, canonical_key, base_unit, COALESCE(pack_size, -1))
    WHERE active;
```

`record_unmatched_item_observation` and the merge migration's `GROUP BY` both need `pack_size`
added to their grouping tuple, treating `NULL` (no pack — sold loose/by base unit) as its own
distinct group via `COALESCE(pack_size, -1)`. Mirror to Room (`CatalogItem.packSize: Double? =
null`, `packUnit: String? = null`), bump `AppDatabase` version, `MIGRATION_N_N+1`.

`sizeRank: Int? = null` (v2's field, unchanged) — materialized per (shop, canonical_key,
base_unit) whenever the shop's stocked pack-size set changes, ordered by `pack_size`. Feeds §5.2.

---

## 5. Matching

### 5.1 The cascade

Implement in `OrderingSegmenter.kt` **and** `phonetic.ts` — mirrored, both sides change.

```
resolveItem(tokens, shop):

  1. shop catalog / shop_learning ITEM_ALIAS      → wins outright (unchanged, already wired)
  2. strip QUALIFIER tokens (exact-surface)        → §4.2, deterministic, no margin cost
  3. T1 base item, vertical + category scoped      → ~300–500 candidates, not 15–20k
  4. qualifiers stripped in step 2 recompose        → "<Brand> <Base>" per composeIdentity()
     with the T1 base resolved in step 3
  5. size-modifier tokens (chhota/bada/absolute)    → §5.2, resolved against shop's stocked
                                                       pack_size variants of the step 3/4 identity
  6. no T1 hit                                       → T2 global within vertical, require 2x margin
```

Step 3 is what keeps `FuzzyCatalogMatcher`'s `0.15` margin guard meaningful at 15–20k entries —
vertical + category scoping, not brand-tree scoping (that job now belongs entirely to step 2's
deterministic strip, which has no margin problem to manage).

### 5.2 Size modifiers — "chhota Good Day" (unchanged from v2, still correct)

Chhota/bada are relative and shop-specific — "bada Good Day" is 200g at a shop stocking 100/200,
500g at a shop stocking 200/500. Cannot be bulk-imported; must be learned per shop.

Modifier vocabulary (generated per §7, mirrored in both engines):

| Class | Tokens |
|---|---|
| SMALL | chhota, chota, छोटा, chhoti, nanha, small, mini |
| LARGE | bada, बड़ा, badi, jumbo, large, big, family pack |
| MID | medium, beech ka, बीच का, regular |
| ABSOLUTE | 200 gram, ₹5 wala, paanch rupaye wala, half kg |

Resolution, after step 3/4 resolves `(canonical_key, base_unit)`:

```
variants = shop's stocked CatalogItems WHERE canonical_key = X AND base_unit = Y,
           ordered by pack_size → sizeRank

ABSOLUTE modifier  → direct pack_size match. Unambiguous. Done.
n == 0 variants    → identity resolves, pack size UNKNOWN, ask price (§6). Creates the variant.
n == 1             → modifier ignored, resolve to it.
n == 2             → SMALL→rank 0, LARGE→rank 1
n >= 3             → SMALL→rank 0, LARGE→rank n-1, MID→middle
```

Persist the binding: `shop_learning` kind `UNIT_MEANING`, key `phoneticKey(modifier + canonical_key)`,
value = resolved `catalogItemId`. Next utterance hits it directly via step 1. A shopkeeper
correction overrides it permanently via `ShopLearningRepository`'s existing confirm/decay.

Never auto-confirm a purely ordinal inference above the gate — route to review with the variant
chips pre-ranked.

---

## 6. Price learning — "ask until we're sure" (unchanged from v2, still correct)

Wire `LearningKind.DEFAULT_PRICE`, keyed on `phoneticKey(catalogItemId)`, reusing the existing
`confidence` field (0..1).

| State | Condition | Behaviour |
|---|---|---|
| **UNKNOWN** | no observation | **Ask every time.** Book qty+item, price pending. |
| **PROVISIONAL** | 1–2 consistent | Pre-fill, require a tap to confirm |
| **CONFIRMED** | ≥3 consistent, confidence ≥ 0.8 | Auto-fill silently |
| **CONTESTED** | variance > 15% across last 5 | Back to asking; do not guess |
| **STALE** | >90 days unused, or MRP moved >10% | Ask once, then re-confirm |

1. **A sale is never blocked on price.** UNKNOWN books qty + item with price null, surfaces in
   review. Losing the sale record to get a price is the wrong trade.
2. **MRP is a sanity band, never the price.** Kirana sells below, above, or loose. Flag only
   outside 0.5×–2.0× MRP.
3. **Price attaches to the `(canonical_key, base_unit, pack_size)` row**, not the bare identity —
   "Good Day" has no price; "Good Day 200g" does. This is why §5.2 must resolve before price
   lookup, unchanged from v2, now stated against the corrected key from §4.3.
4. **Bulk prices are never imported as shop prices.** Crawled MRP lands in `lexicon_entries.mrp`
   and nowhere else.

---

## 7. Acquisition (unchanged from v2, still correct — verified 2026-08-11)

Offline tooling under `tools/catalog/`, committing versioned artifacts to the repo. Not edge
functions — never deploy these.

### 7.1 Channel A — bulk crawl (canonical names, pack sizes, MRP, barcodes)

| Vertical | Sources |
|---|---|
| kirana / general / dairy | BigBasket, JioMart, Blinkit, DMart, Zepto |
| hardware / paint / electrical / plumbing | Moglix, IndiaMART, Amazon Business, Asian Paints & Berger site catalogs |
| pharmacy | 1mg, PharmEasy, Netmeds |
| stationery / cosmetics | Amazon.in, Nykaa, Flipkart |

Category-listing pages give name + pack + MRP + image + category path in one pass — the category
path is a free T1 category signal, take it rather than inferring the tree later.

### 7.2 Channel B — Open Food Facts bulk (barcode ↔ product, validation)

`openfoodfacts-products.jsonl.gz`, ~0.9 GB compressed / ~9 GB uncompressed (verified,
`world.openfoodfacts.org/data`), ODbL. India: **22,380 food** + **1,718 beauty** (verified,
`in.openfoodfacts.org`, `in.openbeautyfacts.org`). Too small and food-skewed to be primary, but
the only source with barcodes (§7.5) and a free hallucination check on generated brand names.

### 7.3 Channel C — spoken-form generation (mandatory, no substitute)

No crawl yields Devanagari or Hinglish. For every T1 entry, generate via
`grok-4.20-0309-non-reasoning` (already the step-4 default, `process-voice-job/index.ts`),
`response_format: json_object`:

```json
{
  "canonical": "Good Day",
  "vertical": "kirana", "category_key": "biscuit",
  "aliases": {
    "devanagari": ["गुड डे", "गुड्डे"],
    "hinglish":   ["good day", "gud de", "gudde"],
    "english":    ["Good Day Biscuit"]
  },
  "size_modifiers_apply": true
}
```

Reject entries with fewer than 2 aliases — a canonical name with no spoken forms adds density
(§0.1) while contributing zero recognition. Batch by category, 8 concurrent. Run T1 first (~15k)
and measure before deciding whether T2 needs anything beyond the 150–300 commodity brands.

### 7.4 Crawl engineering

- Cache raw responses to disk, keyed by URL + fetch date. Parse from cache, never re-fetch to
  re-parse.
- One versioned parser per source, golden HTML fixture per site in `tools/catalog/fixtures/`.
- Serial per host with delay, honour `robots.txt` crawl-delay, real UA with contact.
- Ceiling on pages per host per run; resumable checkpoints.
- Never crawl from an edge function or CI. Local tool, committed artifact.

### 7.5 Channel D — barcode scan at stock-in

A scanned barcode is exact SKU identity — no phonetics, no margin problem. Resolves brand,
variant, and pack size in one action, at the moment the shop is already handling the item and
knows its price — feeds §6 directly. `lexicon_entries.barcode` (§4.1) + OFF's mapping makes this
cheap for food; an unknown barcode scanned at stock-in becomes a shop-local entry that §7.6 later
promotes. **Only needs Stage C (§9) — no dependency on T2 or the pack-size fix.** Candidate to
run ahead of the rest of this plan; scoping it is a separate decision.

### 7.6 Channel E — the learning loop

Cross-shop promotion (open-vocabulary-architecture.md §4.7) grows the lexicon from words shops
actually said. Everything above is scaffolding until this dominates.

---

## 8. Collision gate — non-negotiable (unchanged from v2)

Every candidate T1 alias goes through `PhoneticKey`, grouped by key, before import.

| Collision | Action |
|---|---|
| Within a category (`Parle-G` / `Parle Gold`) | Keep both — review chips disambiguate |
| **Across categories** (`साबुन` soap / `सोयाबीन` soyabean) | **Hard fail. Import neither.** Log to `tools/catalog/collisions.tsv` |
| Across verticals (`balti` paint / `balti` utensil) | Allow — §2 partitioning separates them |
| **T2 candidate vs any T1 surface** | **Hard fail** — this is the module-load assertion in §4.2, extended to cover generated candidates before they're added to `QUALIFIERS` |

The `सोयाबीन`→`साबुन` mis-resolution at norm 0.214 (open-vocabulary-architecture.md §1.5) is this
exact failure already observed in production at 220 words. At 15–20k it is a certainty without
this gate.

---

## 9. Staging

| Stage | Ships | Gated on |
|---|---|---|
| **A** | Crawl + generation tooling; T1 (~15k) artifact committed | nothing — start now |
| **B** | `QUALIFIERS` extended to ~150–300 commodity brands | A — **no schema change, cheap, ship early** |
| **C** | `shop_verticals` + vertical seeding + expanded `ItemUnit` | A |
| **D** | `lexicon_entries` T1 columns + `GET /lexicon` + Room cache | open-vocab Phase 1 |
| **E** | §5.1 cascade (qualifier strip → T1 vertical/category match) | C + D |
| **F** | T1 bulk import, **one vertical at a time**, kirana first | **E — hard gate** |
| **G** | §4.3 pack-size identity-key fix | **before H — hard gate, corrects a live merge/dedupe bug in ISSUE-108/109's shipped grouping** |
| **H** | §5.2 size modifiers + `UNIT_MEANING` writes | F + G |
| **I** | §6 price learning + `DEFAULT_PRICE` writes | H |
| **J** | §7.5 barcode scan at stock-in | D only — can jump ahead of E–I |

**F must not precede E**, and F ships per vertical, never all at once. **G must ship before H** —
shipping size-modifier resolution against the current unfixed dedupe key would have the merge
migration silently collapse "Good Day 200g" into "Good Day 500g" the next time it runs.

---

## 10. Verification

Build success proves nothing (CLAUDE.md). Verify by effect.

1. **Margin baseline before generating anything.** Run the current base-item vocabulary through
   `FuzzyCatalogMatcher` against the 50 real `item_name` values from `transactions`; record the
   top-2 margin distribution. Unrecoverable after the fact.
2. **Re-run after Stage F**, per vertical. Median margin must not fall; if it does, stop before
   the next vertical.
3. **Dedupe correctness test for Stage G, before it ships**: seed two `catalog_items` rows for the
   same shop with identical `canonical_key`/`base_unit`/different `pack_size`, run the corrected
   merge migration, assert both survive. Run the *old* migration against the same fixture first
   and confirm it *would have* incorrectly merged them — that's the regression this stage exists
   to prevent.
4. **Review-burden metric**, 7 days before/after each stage: `PARSED`-not-auto-confirmed as a
   fraction of total.
5. **Live check per stage**: query `stt_job_logs` for a job created after install, quote the row.
6. **Goldens**: `chhota good day`, `bada good day`, `good day 200 gram`, `asian paints balti`,
   `tata sugar` (T2 composition), `ek bori cement` → regression corpus.

---

## 11. Open questions

1. **T1 target size.** ~15–20k is inferred from vertical breadth, not measured. Crawl kirana
   first, measure §10.1, then decide before committing to a number for the rest.
2. **Vertical list.** 14 in §2 — needs review by someone with shop-floor knowledge.
3. **T1 `default_unit` correctness.** Grok will guess KG vs PCS vs LITRE; wrong defaults are
   silent per-item ledger errors. Recommend hand-reviewing generated defaults for the top ~2,000
   by expected sales volume before Stage F.
4. **Multi-vertical onboarding.** Shopkeeper picks verticals vs. auto-activation on first sale
   (§2). Auto-activation is better UX, worse for margins in week one. Undecided.
5. **QUALIFIERS target list.** ~150–300 is a guess at "brands that genuinely pair with a bare
   commodity." Needs the same review-by-someone-with-shop-floor-knowledge as vertical list.
6. **Regeneration cadence.** Brands change. Decide after v1 lands.
