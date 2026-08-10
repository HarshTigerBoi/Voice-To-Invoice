# Brand / Variant / Unit Model — compositional item identity

**Author:** Claude Code · **Date:** 2026-08-09 · **Implementer:** Antigravity
**Issue:** ISSUE-109 · **Supersedes** the "one item = one row" premise in
`Docs/item_lexicon_canonicalization_plan.md` §D.

---

## 0. The rule this encodes

Stated by the shopkeeper, 2026-08-09:

> "we need to merge all ghee ghee and hindi ghee … they are all same … but saras ghee is
> different than amul ghee thats how it is"

> "if said 1 kilo nimbu then its different and 10 nimbu is different and 1 packet nimbu is
> different … and there can be different brands too so they can say 1 packet green nimbu …
> in market there are so many types of sugar brands like madhur sugar and tata sugar and even
> brands have sub brands"

Three separate axes, currently collapsed into one `catalog_items.name` string:

| Axis | Same or different? | Example |
|---|---|---|
| **Script / transliteration** | **SAME item** — must merge | `घी` = `ghee` = `gi` |
| **Brand / variety qualifier** | **DIFFERENT items** — must not merge | `Saras Ghee` ≠ `Amul Ghee` ≠ `Ghee` |
| **Unit of sale** | SAME item, **different price line** — must not merge, must not conflict | Nimbu ₹5/PIECE *and* ₹100/PACKET |

The current model gets axis 1 right (after ISSUE-107) and axes 2 and 3 **wrong**:

- Axis 2 — **verified live bug, now patched but not systematically fixed.** `canonicalOf('घी')`
  returned `'Desi Ghee'`, so "घी" priced at ₹650/KG instead of ₹1200/KG. Same class found in
  `गोल्ड`→`Amul Gold Milk`, `आटा`→`Atta (Aashirvaad)`, `चीनी`→`Sugar (Madhur)`. All four were
  hand-split on 2026-08-09; the *mechanism* that allows a generic word to claim a brand is
  still there, and every future alias added by hand can reintroduce it.
- Axis 3 — `Docs/item_lexicon_canonicalization_plan.md` §D2 treats two rows with the same
  identity and different units as a **duplicate to be merged**. That is wrong: Nimbu ₹5/PIECE and
  Nimbu ₹100/PACKET are both real. The merge migration currently spares them only because it also
  refuses on price mismatch — an accident, not the rule.

**Verified DB facts this plan is built on** (project `lyowklxsbfznnqridtgr`, 2026-08-09):

- `item_units` has `base_unit` + `multiplier`: `KG,GRAM,PAO,AADHA,DHAI,SAWA → KG`;
  `PIECE,DOZEN → PIECE`; `PACKET → PACKET`; `LITRE,ML → LITRE`; `BOX → BOX`.
  So ₹/GRAM and ₹/KG are convertible; ₹/PIECE and ₹/PACKET are **not**, absent a pack size.
- The shop genuinely prices some items per GRAM (`Garam Masala` ₹0.6/GRAM, `Haldi Powder`
  ₹0.25/GRAM, `Jeera` ₹0.4/GRAM) — the conversion path is load-bearing, not theoretical.
- `phonetic.ts:1052` already emits `itemTokens: [name]` with multi-token item text joined, and
  41 of 359 lexicon surfaces are already multi-word. Qualifier phrases survive segmentation today.

---

## 1. Design

### 1.1 Identity is composed, not enumerated

Replace "every spelling is listed against a canonical" with
**`identity = [brand] + [variety] + base`**, where each part is resolved from its own vocabulary.

```
canonicalOf("अमूल घी")   → base Ghee, brand Amul    → "Amul Ghee"
canonicalOf("सरस घी")    → base Ghee, brand Saras   → "Saras Ghee"
canonicalOf("घी")        → base Ghee, no qualifier  → "Ghee"
canonicalOf("ghee")      → base Ghee, no qualifier  → "Ghee"      ← merges with घी
canonicalOf("हरी नींबू") → base Nimbu, variety Green → "Green Nimbu"
canonicalOf("tata sugar")→ base Sugar, brand Tata   → "Tata Sugar" ← brand never seen before
```

The last line is the point: a brand nobody enumerated still gets its own identity instead of
silently inheriting whichever brand happens to own the generic word in the alias table.

**Explicit entries always win.** `ITEM_LEXICON` stays, and is consulted first. Composition only
fires when no explicit entry matches. This is the safety property that makes the change
non-breaking — e.g. `हरी मिर्च` is an explicit surface of `Mirch` today and must **stay** `Mirch`
(green chilli is the shop's default "Mirchi"), not become `Green Mirch`.

### 1.2 A sellable row is (shop, identity, base_unit)

Not `(shop, identity)`. Nimbu@PIECE and Nimbu@PACKET are two legitimate rows. Nimbu@KG and
Nimbu@GRAM are **one** row (same base unit, convertible by multiplier).

### 1.3 Never invent a cross-base price

If the shop prices Nimbu per PIECE and a customer says "एक किलो नींबू", there is no honest
conversion — that needs a pack size the system does not have. Route to review with a specific
reason. **Do not** fall back to the PIECE price, and do not fall back to ₹0.

---

## 2. Phase 1 — compositional identity

### 2.1 `supabase/functions/process-voice-job/lexicon.ts`

Add above `ITEM_LEXICON`:

```ts
export type QualifierKind = 'BRAND' | 'VARIETY'

export interface QualifierEntry {
  /** Canonical display form, used to build the composed identity. */
  canonical: string
  kind: QualifierKind
  surfaces: string[]
}

/**
 * Words that MODIFY an item rather than name one. "अमूल घी" is not a spelling of "घी" — it is a
 * different sellable product at a different price. Before this table existed, a generic word
 * could be listed as a surface of a branded canonical ('चीनी' -> 'Sugar (Madhur)'), which priced
 * loose sugar at Madhur's rate. See ISSUE-109.
 */
export const QUALIFIERS: QualifierEntry[] = [
  // Brands
  { canonical: 'Amul',       kind: 'BRAND', surfaces: ['अमूल', 'amul'] },
  { canonical: 'Saras',      kind: 'BRAND', surfaces: ['सरस', 'saras'] },
  { canonical: 'Madhur',     kind: 'BRAND', surfaces: ['मधुर', 'madhur'] },
  { canonical: 'Tata',       kind: 'BRAND', surfaces: ['टाटा', 'tata'] },
  { canonical: 'Aashirvaad', kind: 'BRAND', surfaces: ['आशीर्वाद', 'aashirvaad'] },
  { canonical: 'Fortune',    kind: 'BRAND', surfaces: ['फॉर्च्यून', 'fortune'] },
  { canonical: 'Mother Dairy', kind: 'BRAND', surfaces: ['मदर डेयरी', 'mother dairy'] },
  { canonical: 'Nestle',     kind: 'BRAND', surfaces: ['नेस्ले', 'nestle'] },
  { canonical: 'Britannia',  kind: 'BRAND', surfaces: ['ब्रिटानिया', 'britannia'] },
  { canonical: 'Parle',      kind: 'BRAND', surfaces: ['पारले', 'parle'] },

  // Varieties
  { canonical: 'Desi',   kind: 'VARIETY', surfaces: ['देसी', 'desi'] },
  { canonical: 'Green',  kind: 'VARIETY', surfaces: ['हरा', 'हरी', 'हरे', 'green'] },
  { canonical: 'Red',    kind: 'VARIETY', surfaces: ['लाल', 'red'] },
  { canonical: 'Black',  kind: 'VARIETY', surfaces: ['काला', 'काली', 'black'] },
  { canonical: 'White',  kind: 'VARIETY', surfaces: ['सफेद', 'सफ़ेद', 'white'] },
  { canonical: 'Fresh',  kind: 'VARIETY', surfaces: ['ताज़ा', 'ताजा', 'fresh'] },
  { canonical: 'Full Cream', kind: 'VARIETY', surfaces: ['फुल क्रीम', 'full cream'] },
  { canonical: 'Toned',  kind: 'VARIETY', surfaces: ['टोंड', 'toned'] },
]
```

**Do not** add `ताज़ा`/`taaza` as a BRAND — it is already an explicit surface of
`Amul Taaza Milk` and explicit entries win, so that name is unaffected. It is listed as a VARIETY
so that an unseen combination like `ताज़ा दूध` composes to `Fresh Doodh` rather than being dropped.

### 2.2 Rewrite `canonicalOf` in `lexicon.ts`

Replace the existing body. Order is load-bearing:

```ts
/**
 * Canonical identity for any spoken/typed item phrase.
 *
 * Resolution order — explicit first, compositional second, literal fold last:
 *   1. Whole-phrase hit in SURFACE_TO_CANONICAL  -> that canonical. Preserves every existing
 *      name, including multi-word ones like 'हरी मिर्च' -> 'Mirch' and display names carrying
 *      a parenthetical brand ('Sugar (Madhur)').
 *   2. Compositional: strip known qualifiers, resolve the remainder as a base item, recompose
 *      as '<Brand> <Variety> <Base>'. This is what gives an unenumerated brand its own
 *      identity ('tata sugar' -> 'Tata Sugar') instead of letting it inherit whichever brand
 *      owns the generic word.
 *   3. Neither -> the phrase's own folded form, so a shop's private item still groups with
 *      itself across case and whitespace.
 * ISSUE-109.
 */
export function canonicalOf(name: string): string {
  const trimmed = (name ?? '').trim()
  if (!trimmed) return ''

  const explicit = canonicalize(trimmed)
  if (explicit) return explicit

  const composed = composeIdentity(trimmed)
  if (composed) return composed

  return trimmed.toLowerCase().replace(/\s+/g, ' ')
}

/** Returns null when the remainder after stripping qualifiers is not a known base item. */
export function composeIdentity(phrase: string): string | null {
  const tokens = phrase.trim().toLowerCase().split(/\s+/).filter(Boolean)
  if (tokens.length < 2) return null

  const brands: string[] = []
  const varieties: string[] = []
  const rest: string[] = []

  let i = 0
  while (i < tokens.length) {
    // Longest-match first so two-word qualifiers ('full cream', 'mother dairy') win.
    let matched = false
    for (let span = Math.min(3, tokens.length - i); span >= 1 && !matched; span--) {
      const probe = tokens.slice(i, i + span).join(' ')
      const q = QUALIFIER_BY_SURFACE.get(probe)
      if (q) {
        if (q.kind === 'BRAND') { if (!brands.includes(q.canonical)) brands.push(q.canonical) }
        else { if (!varieties.includes(q.canonical)) varieties.push(q.canonical) }
        i += span
        matched = true
      }
    }
    if (!matched) { rest.push(tokens[i]); i++ }
  }

  if (rest.length === 0) return null                 // all qualifier, no product
  if (brands.length === 0 && varieties.length === 0) return null  // nothing was stripped

  const base = canonicalize(rest.join(' '))
  if (!base) return null                             // remainder is not a known item

  return [...brands, ...varieties, base].join(' ')
}
```

Add the lookup map next to `SURFACE_TO_CANONICAL`:

```ts
const QUALIFIER_BY_SURFACE: Map<string, QualifierEntry> = (() => {
  const m = new Map<string, QualifierEntry>()
  for (const q of QUALIFIERS) {
    m.set(q.canonical.trim().toLowerCase(), q)
    for (const s of q.surfaces) m.set(s.trim().toLowerCase(), q)
  }
  return m
})()
```

**Qualifier surfaces must not also be item surfaces.** Add a module-load assertion that throws
if any `QUALIFIERS` surface collides with a key in `SURFACE_TO_CANONICAL`; a collision means a
word is being treated as both a product and a modifier, which is exactly the ISSUE-107/109 defect
class. Fail loudly at deploy rather than silently mispricing.

### 2.3 `app/src/main/java/com/voicetoinvoice/app/domain/lexicon/ItemLexicon.kt`

Mirror §2.1 and §2.2 exactly — same `QUALIFIERS` list, same canonical strings, same resolution
order, same collision assertion (throw in an `init {}` block). The two files must stay identical
in identity output for every input; §5.1 is the test that enforces it.

### 2.4 Keep `DEFAULT_ITEM_VOCAB` a superset

`ALL_ITEM_SURFACES` must continue to include every item surface. **Additionally** append every
`QUALIFIERS` surface, so the segmenter's lattice can still recognise "अमूल" as a token worth
attaching to the item rather than discarding it as noise. Do not remove anything.

---

## 3. Phase 2 — sellable row = (shop, identity, base_unit)

### 3.1 Migration `supabase/migrations/20260809000300_catalog_base_unit.sql`

```sql
ALTER TABLE catalog_items ADD COLUMN IF NOT EXISTS base_unit text;

UPDATE catalog_items c
   SET base_unit = COALESCE(u.base_unit, c.unit_id)
  FROM item_units u
 WHERE u.id = c.unit_id AND c.base_unit IS DISTINCT FROM COALESCE(u.base_unit, c.unit_id);

UPDATE catalog_items SET base_unit = unit_id WHERE base_unit IS NULL;

CREATE INDEX IF NOT EXISTS idx_catalog_items_identity_unit
    ON catalog_items (shop_id, canonical_key, base_unit) WHERE active;
```

Keep it an index, **not** a unique constraint, until §5.3 confirms zero collisions on live data.

### 3.2 Correct the merge rule

`supabase/migrations/20260809000100_merge_duplicate_catalog_items.sql` has already run and merged
nothing. Supersede it with `20260809000400_merge_same_identity_same_unit.sql`, identical in
structure but grouped by `(shop_id, canonical_key, base_unit)` instead of
`(shop_id, canonical_key)`.

Keep all existing safety properties verbatim: survivor = greatest `updated_at` then greatest
`price`; skip and `RAISE NOTICE 'CANONICAL_MERGE_CONFLICT …'` when price differs; repoint all six
FKs (`transactions.item_id`, `stock_in.item_id`, `stock_ledger.item_id`, `stock_batches.item_id`,
`unmatched_queue.resolved_item_id`, `unmatched_item_observations.promoted_catalog_item_id`); set
`active = false` on the loser and **never `DELETE`**.

Two rows differing only in `unit_id` *within the same base_unit* (e.g. ₹0.6/GRAM vs ₹600/KG) are a
genuine duplicate — but their `price` values will differ, so they will be reported as conflicts,
not merged. That is correct: converting them needs a human to confirm the multiplier applied.

### 3.3 Promotion RPC

`record_unmatched_item_observation` (7-arg version, live) currently adopts an existing row by
`canonical_key` alone. Add `p_base_unit text DEFAULT NULL` and require **both** to match:

```sql
IF p_canonical_key IS NOT NULL AND p_canonical_key <> '' THEN
    SELECT id INTO v_new_item_id
        FROM public.catalog_items
        WHERE shop_id = p_shop_id
          AND active = true
          AND canonical_key = p_canonical_key
          AND (p_base_unit IS NULL OR base_unit = p_base_unit)
        ORDER BY price DESC
        LIMIT 1;
END IF;
```

and stamp `base_unit` on the INSERT. Ship as `20260809000500_promotion_guard_base_unit.sql`,
mirrored into `supabase/schema.sql`.

**Trap, already hit once on this workstream:** `CREATE OR REPLACE FUNCTION` with a changed
parameter list creates a **second overload** rather than replacing. After applying, run
`SELECT pg_get_function_identity_arguments(oid) FROM pg_proc WHERE proname =
'record_unmatched_item_observation'` and `DROP FUNCTION` every signature except the newest.

`index.ts` must pass `p_base_unit` alongside the existing `p_canonical_key` at the RPC call
(~L2081), derived from the parsed line's unit via `item_units.base_unit`.

### 3.4 Room mirror

- `CatalogItem.kt`: add `val baseUnit: String = ""`, add `Index("baseUnit")`.
- `AppDatabase.kt`: bump `version = 27`, add `MIGRATION_26_27` with try/catch'd
  `ALTER TABLE catalog_items ADD COLUMN baseUnit TEXT NOT NULL DEFAULT ''` plus the index, and
  register it in `addMigrations(...)`.
- Backfill `baseUnit` in the existing `onOpen` callback from the seeded `item_units` table.
- `CatalogDao.dedupeCatalogItems` and `getActiveByCanonicalKey` must key on
  `(canonicalKey, baseUnit)`.

---

## 4. Phase 3 — resolution and the honest miss

In `supabase/functions/process-voice-job/index.ts`, at the catalog fast path (~L695-720) and in
the main matching path:

1. Resolve the line's identity with `canonicalOf(rawName)`.
2. Resolve the spoken unit to its base via `item_units.base_unit`.
3. Select the catalog row with matching `(shop_id, canonical_key, base_unit)`.
4. If the row's `unit_id` differs from the spoken unit but shares a base, convert:
   `price_for_spoken_unit = row.price * (spoken.multiplier / row_unit.multiplier)`.
5. **If the identity matches but no row exists for that base_unit**, do not price the line.
   Return a new decline reason `no_price_for_spoken_unit` and route to the review queue with the
   identity and the missing unit named, so the shopkeeper can add that price line in one tap.
   Do **not** fall back to a different base unit's price, and do **not** book at ₹0.

Add `identityResolution` to the diagnostic trace under `step_4_…`, carrying
`{ spokenSurface, identity, brands, varieties, spokenUnit, baseUnit, priceRowUnit, converted }`
so a mispricing can be read off the trace instead of reproduced.

---

## 5. Verify

### 5.1 New test file `supabase/functions/process-voice-job/lexicon_test.ts`

Assert exactly these, by identity string:

| Input | Expected identity |
|---|---|
| `घी` | `Ghee` |
| `ghee` | `Ghee` |
| `gi` | `Ghee` |
| `अमूल घी` | `Amul Ghee` |
| `सरस घी` | `Saras Ghee` |
| `desi ghee` | `Desi Ghee` |
| `चीनी` | `Sugar` |
| `sugar` | `Sugar` |
| `tata sugar` | `Tata Sugar` |
| `madhur sugar` | `Sugar (Madhur)` (explicit entry wins) |
| `आटा` | `Atta` |
| `aashirvaad atta` | `Atta (Aashirvaad)` (explicit entry wins) |
| `हरी मिर्च` | `Mirch` (explicit entry wins — must NOT become `Green Mirch`) |
| `हरी नींबू` | `Green Nimbu` |
| `अदरक` | `Adrak` |
| `Adrak` | `Adrak` |
| `गोल्ड` | `Gold` |
| `amul gold` | `Amul Gold Milk` (explicit entry wins) |

Plus: no `QUALIFIERS` surface collides with any item surface.

### 5.2 Mirror-parity test

Add a JVM test asserting `ItemLexicon.canonicalOf` returns the identical string for every input
in the §5.1 table. The two implementations diverging silently is the failure mode this whole
lexicon exists to prevent.

### 5.3 Live data checks — run before adding any unique constraint

```sql
-- Must be empty: same identity + same base unit + same price = a true duplicate
SELECT shop_id, canonical_key, base_unit, count(*)
  FROM catalog_items WHERE active
 GROUP BY 1,2,3 HAVING count(*) > 1;

-- Expect Nimbu (PIECE + PACKET) and Chaas (PACKET + KG) to appear here and be LEFT ALONE
SELECT shop_id, canonical_key, array_agg(base_unit), array_agg(price)
  FROM catalog_items WHERE active
 GROUP BY 1,2 HAVING count(DISTINCT base_unit) > 1;
```

### 5.4 Regression

`node --experimental-strip-types --test phonetic_test.ts item_resolution_test.ts lexicon_test.ts`
— all must pass, including the two existing assertions that expect `AMBIGUOUS`. If either flips
to `MATCH`, stop and report; do not edit the assertion.

### 5.5 By effect

After deploy and install, record **"एक किलो घी"** and **"दो पैकेट अमूल गोल्ड"**, then:

```sql
SELECT job_id, raw_transcript, parsed_item_name, parsed_qty, parsed_total,
       diagnostic_trace_json::json #>> '{step_6_final_outcome,0,confidence}' AS conf
  FROM stt_job_logs ORDER BY created_at DESC LIMIT 5;
```

Pass = घी prices at **₹1200/KG** (not ₹650) and अमूल गोल्ड resolves to `Amul Gold Milk` at
₹70/PACKET (not the ₹72 `Gold` row). Quote the rows. No post-deploy row = verification did not
happen; say so plainly.

---

## 6. Open — needs the shopkeeper, not the implementer

1. **नींबू ₹100/PACKET vs Nimbu ₹5/PIECE** — both legitimate under this model, but is a PACKET
   of nimbu a known count (e.g. 20 pieces)? If yes, a `pack_size` column would let "1 kilo nimbu"
   convert instead of routing to review. Not in scope until answered.
2. **छाछ ₹32/KG vs Chaas ₹15/PACKET** — confirmed both correct by the shopkeeper
   ("packet is of 15 and per kg price is 32"). Left as two rows deliberately.
3. The `QUALIFIERS` brand list in §2.1 is a starting set. It should eventually be learned
   per-shop from `unmatched_item_observations` rather than hand-maintained — that is the
   per-shop learning moat, and is a separate plan.

## 7. Bug class statement

This **eliminates the class** "a generic word inherits a specific brand's price", because brand
and variety are resolved from their own vocabulary and composed into the identity rather than
being enumerated as surfaces of a branded canonical. A newly-encountered brand now gets its own
identity by construction instead of by someone remembering to add it.

It **does not** address unit conversion across incompatible bases (PIECE ↔ PACKET ↔ KG); that
needs per-item pack sizes, and §4.5 deliberately routes those to review rather than guessing.

## 8. Audit log

Add `ISSUE-109` to `Docs/audit.md` under 🟢 RESOLVED ISSUES with **Symptom** (घी priced at Desi
Ghee's ₹650 instead of ₹1200; `चीनी`→`Sugar (Madhur)`, `आटा`→`Atta (Aashirvaad)`,
`गोल्ड`→`Amul Gold Milk` in the same class), **Root Cause** (generic surfaces enumerated under
branded canonicals; identity had no brand/variety axis; sellable row keyed without unit),
**Resolution** (numbered, naming every file above), and a **Verification Date** stating plainly
what was confirmed live versus left unverified. Cross-reference ISSUE-107 and ISSUE-108.
