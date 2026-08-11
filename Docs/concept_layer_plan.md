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

## Status as of this handoff

Stage 1 and Stage 1b are **implemented, unit-tested, committed, and pushed** to
`chore/ship-pipeline`. Nothing left in this section is design work — it is deploy +
verify only. Relevant commits, in order:

- `039a773` — unrelated (icon/nav test, ignore)
- `16d77f0` — unrelated (ISSUE-125 signing fix, ignore)
- `0149679` — Stage 1: `concepts.ts`, migration `20260811000000_add_catalog_concept.sql`
  (already applied live), catalog backfill (already applied live), `resolveItemName`
  concept arbitration in `item_resolution.ts`
- `0c0f7c2` — Stage 1b: ambiguity gate wired into `index.ts`

`app/**` is untouched by any of this. **Do not rebuild or reinstall the APK for this
plan** — it is a server-only change (Supabase edge function). The next voice recording
on the phone hits the updated function automatically once deployed; no `/ship` run
needed.

## Remaining work — execute this exactly

### Step 1 — sync the branch

```powershell
git fetch origin chore/ship-pipeline
git checkout chore/ship-pipeline
git pull origin chore/ship-pipeline
git log --oneline -1
```

Confirm HEAD is `0c0f7c2` or later. If it is not, stop — the deploy in step 2 would
ship stale code.

### Step 2 — deploy

```powershell
npx supabase functions deploy process-voice-job --project-ref lyowklxsbfznnqridtgr
```

If this fails with an auth error, run `npx supabase login` first (opens a browser) —
this requires a human at the keyboard, do not attempt to work around it.

### Step 3 — confirm the deploy is not stale or partial

CLAUDE.md is explicit that this project has a history of incomplete deploys going live
silently. Do not skip this step.

```powershell
npx supabase functions download process-voice-job --project-ref lyowklxsbfznnqridtgr --project-id lyowklxsbfznnqridtgr
```

(or use the Supabase MCP `get_edge_function` tool if available in your session)

Grep the fetched bundle for every one of these markers — all four must be present:

```
ISSUE-126
resolveConceptToSkus
ambiguousConceptReason
conceptOfSpoken(rawName) ?? conceptOfSku(rawName)
```

If any marker is missing, the deploy did not actually ship the current code. Re-run
step 2 and re-check before proceeding — do not proceed to step 4 on a bundle you have
not confirmed.

### Step 4 — verify by effect, not by build

Per CLAUDE.md's diagnosis discipline, "BUILD SUCCESSFUL" / "deploy succeeded" proves
nothing. Record these two utterances into the app on the test phone (existing install,
no reinstall needed):

1. **"सत्रह किलो चावल"** (17 kg rice)
2. **"पांच किलो दूध"** (5 kg milk)

Then query, using the Supabase MCP `execute_sql` tool against project
`lyowklxsbfznnqridtgr`:

```sql
SELECT job_id, status, raw_transcript, parsed_item_name, parsed_qty, parsed_total,
       diagnostic_trace_json, created_at
FROM stt_job_logs
WHERE created_at > now() - interval '30 minutes'
ORDER BY created_at DESC LIMIT 10;
```

**Expected for "सत्रह किलो चावल"**: `parsed_item_name` = `Basmati Rice`,
`parsed_total` = 1530 (17 × ₹90), status `AUTO_CONFIRMED`. In
`diagnostic_trace_json.step_6_final_outcome[0]`: `matchedCatalogId` non-null,
`itemName` = `"Basmati Rice"`.

**Expected for "पांच किलो दूध"**: status is **NOT** `AUTO_CONFIRMED` (`PARSED` or
similar). `diagnostic_trace_json` contains an `implausibility_reason` matching
`'milk' matches 3 items in your catalog — pick one: Amul Gold Milk ₹34, Amul Taaza
Milk ₹27, Saras Milk ₹30` (exact SKU list/prices from `ambiguousConceptReason` in
`concepts.ts`). `parsed_total` must be `0` — it must NOT show ₹34 or any other milk
price, which would mean it silently picked one.

If either expectation is not met, this is not verified — say so plainly per CLAUDE.md's
verification-honesty rule, quote the actual trace, and do not mark it resolved.

### Step 5 — update Docs/audit.md

In the `[ISSUE-126]` entry (search for that string — there are two related entries,
Stage 1 and Stage 1b, both need updating):

1. Change the **Status** line from `EDGE FUNCTION NOT DEPLOYED, NEVER EXERCISED ON A
   LIVE RECORDING` to `DEPLOYED <today's date>, VERIFIED <today's date>`.
2. Add a bullet under **Verification Date** quoting the two actual `job_id`s from step
   4 and the exact `implausibility_reason` string returned for the milk job — not a
   paraphrase, the literal string from the query result.
3. If step 4's expectations were NOT met, do not edit the Status line — instead add a
   new dated entry describing exactly what the trace showed instead, per CLAUDE.md's
   "state plainly what you verified vs. what's still unverified."

Commit this doc update on `chore/ship-pipeline` with a message referencing ISSUE-126,
per CLAUDE.md's rule that a commit and its audit entry must never diverge.

## Deviations

If anything here doesn't match what you find in the code or the live project (a
constant, a file path, a query result shape), stop and say so — quoting this doc's line
and what you actually see — rather than silently adjusting and proceeding. Per
CLAUDE.md: "Ambiguity → stop and ask... Silent deviation is worse than pausing."
