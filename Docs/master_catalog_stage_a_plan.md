# Master Catalog — Stage A: T1 vocabulary generation tooling

**Author:** Claude Code · **Date:** 2026-08-11 · **Implementer:** Antigravity
**Parent plan:** `Docs/master_catalog_plan.md` §9 Stage A (+ Stage B, folded in — see §4)
**Scope:** standalone offline tooling only. **No app code, no edge function, no schema change,
no deploy.** This stage produces a committed JSON artifact and nothing else.

---

## 0. What this is and isn't

This generates the raw candidate list for the T1 base-item vocabulary (`master_catalog_plan.md`
§3) — kirana, general, and dairy verticals only, per that plan's §11.1 recommendation to measure
before expanding further. It does **not** wire anything into the app. Stages C onward in the
parent plan (schema, matching cascade, imports) are separate, later plans, not part of this one.

**Do not** touch `OrderingSegmenter.kt`, `phonetic.ts`, `DEFAULT_ITEM_VOCAB`,
`FuzzyCatalogMatcher.kt`, or `lexicon.ts`/`ItemLexicon.kt` in this pass. This stage only produces
files under `tools/catalog/` and `Docs/data/`.

---

## 1. Precondition (manual, not part of the implementation)

The script reads `XAI_API_KEY` from the environment. It is **not** available locally the way it
is in the edge function (there it's a Supabase secret). Before running this tool, set it for the
current shell session only — never commit it, never write it to a file:

```powershell
$env:XAI_API_KEY = "<the same key already configured in the process-voice-job edge function's secrets>"
```

If `XAI_API_KEY` is unset, the script must exit with a clear error naming this precondition, not
a generic fetch failure.

---

## 2. File: `Docs/data/category_spine.json`

Create with exactly this content — the seed category list for Stage A's scope (kirana/general/
dairy only). This is deliberately a starting list per `master_catalog_plan.md` §11.2 ("needs
review by someone with shop-floor knowledge") — Stage A's job is to produce candidates for
review, not a final vocabulary.

```json
{
  "vertical": "kirana",
  "categories": [
    "atta_flour", "rice", "dal_pulses", "sugar_jaggery", "salt", "cooking_oil", "ghee_butter",
    "milk", "curd_paneer", "spices_whole", "spices_powder", "tea_coffee", "biscuits_cookies",
    "namkeen_snacks", "noodles_pasta", "bread_bakery", "chocolate_candy", "cold_drinks_juice",
    "water_bottled", "detergent_soap", "dishwash", "toiletries_soap", "shampoo_haircare",
    "toothpaste_oral", "sanitary_baby", "matches_candles", "mosquito_repellent", "pickle_papad",
    "honey_jam_spreads", "dry_fruits_nuts", "besan_sooji", "vermicelli_sevaiya", "ketchup_sauces",
    "instant_food", "ice_cream_frozen", "eggs", "vegetables_fresh", "fruits_fresh",
    "stationery_basic", "batteries_bulbs", "agarbatti_pooja", "paan_tobacco", "cattle_feed"
  ]
}
```

---

## 3. File: `tools/catalog/generate-t1-vocab.ts`

Deno script (matches the existing edge-function toolchain, no new runtime dependency). Run with:

```
deno run --allow-net --allow-read --allow-write tools/catalog/generate-t1-vocab.ts
```

### 3.1 Types

```typescript
interface CategorySpine {
  vertical: string
  categories: string[]
}

interface GeneratedEntry {
  canonical: string
  vertical: string
  category_key: string
  default_unit: 'KG' | 'GRAM' | 'LITRE' | 'ML' | 'PACKET' | 'PIECE' | 'DOZEN' | 'BOX'
  aliases: {
    devanagari: string[]
    hinglish: string[]
    english: string[]
  }
  size_modifiers_apply: boolean
}

interface RejectedEntry {
  canonical: string
  category_key: string
  reason: string   // 'fewer_than_2_aliases' | 'cross_category_collision' | 'off_list dup'
}
```

### 3.2 Grok call, one request per category

Model: `'grok-4.20-0309-non-reasoning'` — matches
`supabase/functions/process-voice-job/index.ts`'s `XAI_CHAT_MODELS[0]`, already the cheapest
model proven adequate for structured extraction in this codebase (see that file's comment on
ISSUE-117). Endpoint: `https://api.x.ai/v1/chat/completions`. `response_format: { type:
'json_object' }`. Max 8 concurrent requests (`Promise.all` in batches of 8).

Prompt template (fill `{category}` from the spine, `{vertical}` = `"kirana"`):

```
You are listing real products Indian kirana (small neighbourhood grocery) shops sell in the
category "{category}". Vertical: {vertical}.

For each DISTINCT product a shopkeeper would actually stock (not every SKU size — one entry per
product, e.g. one entry for "Good Day", not one per pack size), return an object with:
- canonical: the standard display name (English, brand name if it's a branded product; a plain
  generic name like "Chawal" for unbranded commodities)
- category_key: "{category}"
- default_unit: the unit this shop most commonly sells it in — one of
  KG, GRAM, LITRE, ML, PACKET, PIECE, DOZEN, BOX
- aliases.devanagari: how this is written in Hindi/Devanagari script, including common spelling
  variants. Empty array if there is no natural Devanagari form (e.g. an English-only brand name
  nobody writes in Devanagari).
- aliases.hinglish: how an Indian shopkeeper would SAY this in a voice note using Latin script —
  phonetic spellings, not the formal English name. Include common short forms.
- aliases.english: the formal English/brand name(s), if different from canonical.
- size_modifiers_apply: true if this product commonly comes in multiple sizes a shopkeeper would
  call "chhota"/"bada" (small/big) rather than requiring an exact size every time.

Only include real products actually sold in Indian kirana shops. Do not invent brand names. If
you are not confident a brand exists in the Indian market, omit it rather than guess.

Return JSON: { "entries": [ ...objects as described above... ] }

Return 15-40 entries for this category, covering the products an actual shopkeeper in this
category would name if asked to list everything they stock.
```

### 3.3 Post-processing, in order

1. **Reject entries with `aliases.devanagari.length + aliases.hinglish.length + aliases.english.length < 2`.**
   Push to `RejectedEntry[]` with reason `'fewer_than_2_aliases'`.
2. **Dedupe exact `canonical` string collisions** across categories (keep first occurrence, log
   the rest to a `duplicates` array in the output — informational only, not a rejection).
3. **Phonetic collision gate.** For every surviving alias (all three arrays, all entries), compute
   its phonetic key using the **existing** algorithm — do not reimplement it. Import from
   `supabase/functions/process-voice-job/phonetic.ts`'s exported key function (read that file
   first to find the exact export name and signature; if it is not exported, add a named export
   for it in that file as the only allowed change to an existing file in this stage, and mirror
   nothing else). Group all aliases (this run's candidates) by phonetic key.
   - If a phonetic key group's aliases all trace back to entries within the **same**
     `category_key` → keep all, no action.
   - If a phonetic key group spans **different** `category_key` values → reject **all** entries
     in that group, reason `'cross_category_collision'`, write the full group (key, categories
     involved, canonicals involved) to `tools/catalog/collisions.tsv` (tab-separated, header row:
     `phonetic_key\tcategory_a\tcanonical_a\tcategory_b\tcanonical_b`).
4. **OFF cross-check (informational tag only, never a rejection).** Fetch
   `https://in.openfoodfacts.org/category/{category-slug}.json` is not reliable enough to map
   1:1 to the category spine's keys, so instead: for each surviving entry, do a single fuzzy
   substring check of `canonical` (lowercased) against
   `https://in.openfoodfacts.org/cgi/search.pl?search_terms={canonical}&json=1&page_size=1` — if
   any product is returned, set `off_verified: true` on the entry; otherwise `false`. This field
   is informational (feeds `lexicon_entries.verified` in a later stage) and must never cause a
   rejection — this vertical is only ~40% food, so a `false` here is expected and normal for most
   entries, not a signal of a bad entry.

### 3.4 Output

Write `Docs/data/master_catalog_t1_seed_kirana.json`:

```json
{
  "generated_at": "<ISO 8601 timestamp>",
  "model": "grok-4.20-0309-non-reasoning",
  "vertical": "kirana",
  "entry_count": <int>,
  "rejected_count": <int>,
  "entries": [ ...GeneratedEntry, each with an added off_verified boolean... ],
  "rejected": [ ...RejectedEntry... ]
}
```

Deterministic key ordering (`JSON.stringify` with a fixed key order, not insertion order) so
regeneration produces a reviewable diff, not a full-file rewrite each time.

Print a summary to stdout: total entries generated, total rejected (broken down by reason),
total cross-category collisions written to `collisions.tsv`, total `off_verified: true`.

---

## 4. Stage B, folded in: nothing to implement yet

`master_catalog_plan.md` Stage B (extend `QUALIFIERS` in `lexicon.ts`/`ItemLexicon.kt` to
~150–300 commodity brands) is **not part of this implementation pass**. It depends on this
stage's output existing first (the generated list is where candidate commodity brands come from).
Once `master_catalog_t1_seed_kirana.json` exists, Claude Code will review it, hand-pick the
commodity-brand subset, and write Stage B as its own small, fully-specified plan. Do not
pre-emptively edit `QUALIFIERS` in this pass.

---

## 5. Test

Add `tools/catalog/generate-t1-vocab.test.ts` (Deno test) covering only the pure functions (no
network): the phonetic-collision grouping logic (§3.3 step 3) given a fixed fake candidate list
with one deliberate cross-category collision and one deliberate same-category collision — assert
the cross-category one is rejected and logged, the same-category one survives. Do not attempt to
test the Grok/OFF network calls; those are exercised by actually running the script.

---

## 6. What "done" looks like

- `tools/catalog/generate-t1-vocab.ts` exists and runs standalone via the `deno run` command in
  §3.
- `Docs/data/category_spine.json` exists with exactly the content in §2.
- Running the script (with `XAI_API_KEY` set) produces `Docs/data/master_catalog_t1_seed_kirana.json`
  and, if any collisions were found, `tools/catalog/collisions.tsv`.
- `tools/catalog/generate-t1-vocab.test.ts` passes via `deno test tools/catalog/`.
- **Do not run the script yourself as part of this implementation pass** — it costs real API
  calls against a live key and the user has not reviewed the category spine yet. Implement and
  test the pure logic only; leave running it to a follow-up step Claude Code will do explicitly.

## Deviations

State clearly if `phonetic.ts`'s key function is not exported and you had to add an export, or
if any other step named a file/symbol that didn't exist as described.
