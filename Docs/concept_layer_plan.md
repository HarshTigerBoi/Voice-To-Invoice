# Concept Layer — base-commodity identity for item resolution

**Status**: Stage 1 implementation (server + schema). Kotlin mirror is Stage 2.
**Issue**: ISSUE-126. Supersedes the alias-list approach for cross-language item identity.

## Problem

The system has no representation of *what an item is*. It has four disconnected
name-spaces — `DEFAULT_ITEM_VOCAB`, the shop's `catalog_items`, `term_aliases`, and
`ITEM_LEXICON` — all of which are **surface-form** lists. Nothing states that "rice",
"chawal", "चावल" and "Basmati Rice" denote the same commodity, so:

- Surfaces must be enumerated by hand, and any missed spelling is a silent failure.
- Phonetic matching cannot bridge translations. `phoneticKey("rice")` = `LICI`,
  `phoneticKey("चावल")` = `CAVAL`. They share no phones and never will.

### Evidence — two live jobs, same commodity, opposite outcomes

Job `ea68312e` ("17 kg Rice"): segmenter's best phonetic candidate was **Elaichi**
(`itemMatchNorm` 0.1667 — `LICI` vs `ILAICI` is a two-vowel insertion, and vowel edits
are discounted to 0.5 in `phoneticDistance`). 0.1667 > `SEGMENTER_OVERRIDE_MAX_NORM`
(0.08), so the override did NOT fire, the AI's "Basmati Rice" survived, and the line
booked ₹1530 auto-confirmed. **Correct by luck** — the deterministic layer was one
threshold away from booking cardamom.

Job `735469d9` ("सत्रह किलो चावल"): segmenter matched `चावल` at `itemMatchNorm` **0.0**
(it is a surface of `ITEM_LEXICON` canonical `Chawal`, lexicon.ts:133). 0.0 ≤ 0.08, so
the override fired and replaced the AI's "Basmati Rice" with "चावल" — a vocabulary word
with **no `catalog_items` row**. Result: `matchedCatalogId: null`, `priceAtSale: 0`,
confidence 0.55, routed to `unmatched_queue`.

In both jobs `step_4_raw_ai_items` was `"Basmati Rice"`. The AI was right twice; the
deterministic override discarded it once.

### Why the override cannot simply be inverted

`item_resolution.ts:81-84` documents the reverse failure the override exists to prevent
(ISSUE-030): shopkeeper said "अमचूर", Grok misheard "अंगूर". A rule of "prefer whichever
name matches the catalog" books grapes in that case. The distinguishing fact is
semantic, not phonetic:

- `चावल` and `Basmati Rice` are the **same** commodity → prefer the sellable SKU.
- `अमचूर` and `अंगूर` are **different** commodities → keep the segmenter, flag the line.

Deciding between those requires knowing what each name *is*. That is the concept layer.

## Design

Two levels, replacing one:

- **Concept** — language-independent base commodity. `rice`, `milk`, `dal`, `biscuit`.
- **SKU** — the priced, sellable `catalog_items` row. `Basmati Rice @ ₹90/KG`.

Resolution mirrors what a shopkeeper does:

| Concept resolves to | Behaviour |
| :-- | :-- |
| exactly 1 SKU in this shop | use it |
| more than 1 SKU | **ambiguous** — route to review with the candidate list. Never guess. |
| 0 SKUs | not stocked — existing unmatched path |

Qualifiers (`QUALIFIERS` in lexicon.ts) narrow within a concept: concept `dal` + variety
`chana` → Chana Dal. This preserves ISSUE-109 (a brand-qualified item is a distinct
sellable product at a distinct price) while fixing the generic-word case.

### Where concepts come from — not a hand-maintained list

1. **SKU → concept**: assigned **once per catalog item**, by the LLM, at add time.
   Never runs on the speech path. A one-time backfill covers the existing rows.
2. **Spoken word → concept**: the LLM's own world knowledge, already demonstrated
   working in both traces above. `ITEM_LEXICON` degrades to an offline/fast-path cache
   rather than the source of truth.

This is what removes the "we will always miss a variation" failure mode: surfaces stop
being enumerated at all.

### Why not embeddings

Considered and rejected for this codebase, on two grounds:

1. **Near-identical SKUs at different prices.** This shop stocks Amul Gold Milk (₹34),
   Amul Taaza Milk (₹27) and Saras Milk (₹30). Their names are semantically almost
   identical, so cosine similarity ranks them within noise of each other while the
   price difference is real money. A discrete concept id either matches or does not;
   ambiguity becomes a question instead of a confident wrong answer.
2. **Offline-first.** The Kotlin client parses on-device with no network
   (`OrderingSegmenter`, `MultiSaleDetector`). An embedding index or API breaks that.

Phonetic matching (Metaphone family) was also considered and does not address the
problem: it is already implemented in `phonetic.ts` in a Devanagari-aware form, and
translation pairs share no phones by construction.

## Concept assignment for the 53 live SKUs

Shop `780d830d-bc71-4a3e-b0df-7f53a67d1dec`. Ambiguous concepts in **bold**.

| Concept | SKUs |
| :-- | :-- |
| **milk** | Amul Gold Milk, Amul Taaza Milk, Saras Milk |
| **biscuit** | Parle-G Biscuit, Good Day Biscuit, Bourbon Biscuit, Hide & Seek Biscuit |
| **dal** | Chana Dal, Moong Dal, Toor Dal |
| **oil** | Fortune Refined Oil, Mustard Oil |
| rice | Basmati Rice |
| sugar | Sugar (Madhur) |
| ghee | Desi Ghee |
| atta | Atta (Aashirvaad) |
| potato / onion / tomato / brinjal / okra / carrot / peas / spinach / cucumber / garlic / ginger / coriander / chilli / cauliflower / bittergourd / bottlegourd / lemon / broccoli / dragonfruit | one each |
| paneer / butter / curd / buttermilk / egg / bread / rusk / poha / salt / tea / coffee / noodles / turmeric / cumin / garam-masala / red-chilli-powder / soft-drink / energy-drink | one each |

41 of 53 are unambiguous, so the generic word resolves cleanly for the large majority.

### Live bug this surfaces

`db-setup/index.ts:141-144` seeds `दूध`/`doodh`/`milk`/`dudh` → **`Amul Gold Milk`**.
That silently picks one of three milk SKUs spanning ₹27–₹34. Under this design those
rows must become concept `milk` and route to the ambiguity question instead of
resolving to a specific brand. Same class of defect as ISSUE-109.

## Stage 1 — implementation steps (this commit)

1. **Migration** `supabase/migrations/20260811000000_add_catalog_concept.sql`
   - `ALTER TABLE public.catalog_items ADD COLUMN IF NOT EXISTS concept TEXT`
   - `CREATE INDEX IF NOT EXISTS idx_catalog_items_shop_concept ON public.catalog_items (shop_id, concept)`
   - Nullable and additive: existing reads are unaffected, no destructive step.
2. **Concept vocabulary** `supabase/functions/process-voice-job/concepts.ts`
   - `CONCEPTS`: concept id → surfaces across Devanagari / Hinglish / English, used as
     the deterministic fallback when the LLM is unavailable.
   - `conceptOf(name)`: SKU display name → concept id, for backfill and add-time use.
   - `resolveConceptToSkus(concept, qualifiers, catalog)`: the 1 / >1 / 0 decision.
3. **Backfill** of the 53 rows via `conceptOf`.
4. **Resolution + override** in `process-voice-job/index.ts`
   - `resolveItemName` gains a concept comparison: when the segmenter name and the AI
     name share a concept, prefer the one that resolves to a priced SKU; when they do
     not, keep the existing segmenter-wins-and-flag behaviour (ISSUE-030 intact).
   - Ambiguous concept → `implausibility_reason` naming the candidates, no auto-confirm.
5. **Prompt** — ask Grok for `concept` alongside `item_name`.

## Stage 2 — deferred, not in this commit

- Kotlin mirror (`ItemLexicon.kt`, `OrderingSegmenter`) for the offline path. Until this
  lands, the client fallback keeps its current surface-list behaviour; the server is
  authoritative whenever the device is online, which is the normal path.
- LLM concept assignment for newly added catalog items (Stage 1 backfills existing rows
  and `conceptOf` covers known vocabulary; a genuinely novel SKU gets `NULL` and behaves
  exactly as today until assigned).
- Review-queue UI for choosing between ambiguous candidates. Stage 1 routes to review
  with the candidates named in the reason string; picking one is still the existing
  manual flow.

## Verification

Per CLAUDE.md: verify by effect, not by build. After deploy, re-record both utterances
and query `stt_job_logs` for rows created after the deploy:

```sql
SELECT job_id, status, raw_transcript, parsed_item_name, parsed_qty, parsed_total,
       length(diagnostic_trace_json) AS trace_len, created_at
FROM stt_job_logs ORDER BY created_at DESC LIMIT 5;
```

Expected: "सत्रह किलो चावल" → `parsed_item_name` = `Basmati Rice`, priced ₹90/KG,
auto-confirmed. "पांच किलो दूध" → ambiguous, routed to review naming all three milk
SKUs, NOT silently booked as Amul Gold.
