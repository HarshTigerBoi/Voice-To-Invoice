# Item Lexicon & Canonicalization Plan

**Author:** Claude Code · **Date:** 2026-08-09 · **Implementer:** Antigravity
**Issue:** ISSUE-107 (see §7 for the audit.md entry to write)

---

## 0. Why

Job `8d6a2aa6-4637-4b8f-8d94-9ee398f1479a` ("छः किलो अदरक", 6 KG Adrak, ₹720) was correct in
every field and still went to the review queue at confidence 0.55.

Cause, **verified** against source and the live DB:

1. `DEFAULT_ITEM_VOCAB` (`phonetic.ts:434`, mirrored `OrderingSegmenter.kt:694`) lists every item
   twice — Devanagari and Latin — e.g. `'अदरक', 'Adrak'`. They are one item but two surfaces with
   two different phonetic keys (`ATALAK` / `ATLAK`, the schwa difference already documented at
   `index.ts:700`).
2. `matchVocab` (`phonetic.ts:649`, `OrderingSegmenter.kt:224`) dedupes candidates by
   `entry.key` — the **phonetic key** — so both surfaces survive as separate candidates.
   Winner `अदरक` distance 0.0, runner-up `Adrak` distance 0.0833.
3. `margin = second - best = 0.0833`. `phonetic.ts:1095`:
   `isAmbiguousByMargin = margin * itemKeyLength < MIN_MARGIN_PHONE_EDITS` → `0.0833 * 6 = 0.5 < 1.0`
   → `resolutionKind = 'AMBIGUOUS'`, `isSanityFlagged = true`.
4. `index.ts:1890` appends `implausibility_reason`, which caps confidence at
   `IMPLAUSIBLE_CONFIDENCE_CAP = 0.55` (`index.ts:128`) — below the 0.80 auto-confirm gate.

**The item competed with itself.** This fires for every Devanagari-spoken item whose Latin twin is
in the vocab, i.e. most of the produce list. It also fires between unit surfaces
(`किलो` / `kilo` / `kg` all canonicalise to `KG` but are three competing vocab entries today).

Verified, live DB (project `lyowklxsbfznnqridtgr`):
- `term_aliases` has **no** row for `अदरक` or `Adrak`. The duplicate is entirely in hardcoded vocab.
- The server fetches catalog with `.eq('shop_id', resolvedShopId)` (`index.ts:1060`) — 69 rows,
  matching the trace's `catalogItemsFetched: 69`. Global `shop_id IS NULL` seed rows are not in play.
- There are **no** exact `(shop_id, name)` duplicates. There **are** cross-script duplicates inside
  shop `2f992a33-fa26-4be2-9006-3e6eafd41e2c` — see §5.

Four copies of a canonicalisation table already exist and have drifted apart:
`FuzzyCatalogMatcher.indicAliasMap` (~60 entries, canonical = display name) and three verbatim
14-entry `when` blocks at `AppDatabase.kt:798`, `CatalogDao.kt:126`, `SyncEngine.kt:87` (canonical =
lowercase slug, and `मिर्च → "mirchi"` where the alias map says `"Mirch"`).

**Goal of this plan:** one canonical identity per item, defined in exactly one place per language,
consumed by the segmenter, the matcher, the catalog, and the sync path. One catalog row per item
per shop.

---

## 1. Scope

| In scope | Out of scope |
|---|---|
| New lexicon module (TS + Kotlin, mirrored) | Any change to `MIN_MARGIN_PHONE_EDITS` (1.0 stays) |
| `VocabEntry.canonical` + candidate grouping by canonical | Any change to `IMPLAUSIBLE_CONFIDENCE_CAP` (0.55 stays) |
| Replacing the 4 drifted alias tables with the lexicon | Any change to the 0.80 auto-confirm gate |
| Catalog dedupe: server SQL migration + Room migration | Grok prompt / model changes |
| Insert-time canonicalisation so dupes stop reappearing | The learned-parse-memory subsystem |

**Do not** change confidence constants. The bug is that a false ambiguity is being *detected*; the
thresholds that react to it are correct and are load-bearing for real ambiguities (आलू vs लौकी).

---

## 2. Part A — the lexicon (single source of truth)

### A1. New file: `supabase/functions/process-voice-job/lexicon.ts`

```ts
/**
 * ONE canonical identity per item, with every spelling that maps to it.
 *
 * This file is the single source of truth for item identity. Before it existed the same
 * mapping lived in four places that had drifted apart (FuzzyCatalogMatcher.indicAliasMap and
 * three verbatim `when` blocks in AppDatabase/CatalogDao/SyncEngine), and DEFAULT_ITEM_VOCAB
 * listed each item's Devanagari and Latin spellings as if they were two different products —
 * which is what made "छः किलो अदरक" ambiguous against itself and sent a perfect parse to the
 * review queue at 0.55. See ISSUE-107.
 *
 * MIRRORED: app/src/main/java/com/voicetoinvoice/app/domain/lexicon/ItemLexicon.kt.
 * Any edit here must be made there in the same commit, with identical canonical strings.
 */
export interface LexiconEntry {
  /** The display name. MUST equal the catalog_items.name the shop actually uses. */
  canonical: string
  /** Every spoken/typed spelling that means `canonical`. Case-insensitive at lookup. */
  surfaces: string[]
}

export const ITEM_LEXICON: LexiconEntry[] = [ /* see A3 */ ]
```

Plus these exports, in this file:

```ts
/** surface (lowercased, trimmed) -> canonical. Built once at module load. */
const SURFACE_TO_CANONICAL: Map<string, string> = (() => {
  const m = new Map<string, string>()
  for (const e of ITEM_LEXICON) {
    m.set(e.canonical.trim().toLowerCase(), e.canonical)
    for (const s of e.surfaces) m.set(s.trim().toLowerCase(), e.canonical)
  }
  return m
})()

/** Canonical name for a known surface, else null. */
export function canonicalize(surface: string): string | null {
  if (!surface) return null
  return SURFACE_TO_CANONICAL.get(surface.trim().toLowerCase()) ?? null
}

/**
 * Canonical identity for ANY name. Unknown names canonicalise to their own trimmed,
 * case-folded form, so a shop's private item still groups with itself rather than
 * splitting on whitespace/case. Never returns empty for non-empty input.
 */
export function canonicalOf(name: string): string {
  const trimmed = (name ?? '').trim()
  if (!trimmed) return ''
  return canonicalize(trimmed) ?? trimmed.toLowerCase().replace(/\s+/g, ' ')
}

/** Every surface in the lexicon, flat. Replaces the hand-maintained DEFAULT_ITEM_VOCAB. */
export const ALL_ITEM_SURFACES: string[] =
  ITEM_LEXICON.flatMap(e => [e.canonical, ...e.surfaces])
```

### A2. `phonetic.ts` keeps exporting `DEFAULT_ITEM_VOCAB`

Do **not** delete `DEFAULT_ITEM_VOCAB` — `item_resolution.ts`, `item_resolution_test.ts:186`,
`index.ts:11/538/576/1150/1312/1745` and the Kotlin mirror all import it. Redefine it as derived:

In `phonetic.ts`, replace the literal array at line 434 with:

```ts
import { ALL_ITEM_SURFACES } from './lexicon.ts'

/** Derived from ITEM_LEXICON — do not hand-edit. Add items in lexicon.ts. */
export const DEFAULT_ITEM_VOCAB: string[] = ALL_ITEM_SURFACES
```

The *contents* must be a superset of today's 220-word list so no existing test regresses:
`item_resolution_test.ts:186` asserts every `DEFAULT_ITEM_VOCAB` word scores 1.00 against itself.

### A3. Lexicon contents

Build `ITEM_LEXICON` by merging, in this precedence order:

1. **`FuzzyCatalogMatcher.indicAliasMap`** (`FuzzyCatalogMatcher.kt:65-107`). Its **values are the
   canonical names** — they are the real catalog display names (`"Curd (Dahi)"`,
   `"Amul Gold Milk"`, `"Desi Ghee"`, `"Atta (Aashirvaad)"`). Group its keys under each value.
2. **The Devanagari/Latin pairs in today's `DEFAULT_ITEM_VOCAB`** (`phonetic.ts:434-…`). Each
   consecutive `'देवनागरी', 'Latin'` pair is ONE entry: `canonical` = the Latin form,
   `surfaces` = `[the Devanagari form]` — **unless** rule 1 already assigned that item a canonical,
   in which case add both spellings as surfaces of the rule-1 canonical.
3. Every remaining single word in `DEFAULT_ITEM_VOCAB` with no pair becomes its own entry with an
   empty `surfaces` list.

**Named conflicts — resolve exactly this way, do not improvise:**

| Surface | Today's conflicting canonicals | Use |
|---|---|---|
| `मिर्च`, `mirch`, `mirchi`, `chilli`, `chili`, `हरी मिर्च` | `"Mirch"` (alias map) vs `"mirchi"` (the 3 `when` blocks) | **`"Mirch"`** |
| `घी`, `ghee`, `gi`, `desi ghee` | `"Ghee"` (vocab) vs `"Desi Ghee"` (alias map) | **`"Desi Ghee"`** |
| `दही`, `dahi`, `curd` | `"Dahi"` (vocab) vs `"Curd (Dahi)"` (alias map) | **`"Curd (Dahi)"`** |
| `छाछ`, `chaas`, `buttermilk`, `मट्ठा` | `"Chaach"` (vocab) vs `"Chaas (Buttermilk)"` (alias map) | **`"Chaas (Buttermilk)"`** |
| `अंडे`, `अंडा`, `anda`, `egg`, `eggs` | `"Anda"` (vocab) vs `"Eggs"` (alias map) | **`"Eggs"`** |
| `मक्खन`, `butter`, `बटर`, `amul butter` | `"Butter"` both | `"Butter"` |

The losing spelling in each row must still appear in `surfaces` — it is a real thing people say and
must keep matching.

`अदरक` / `adrak` / `ginger` → canonical **`"Adrak"`** (this is the case that motivated the plan;
the shop's catalog row is literally named `Adrak`).

### A4. New file: `app/src/main/java/com/voicetoinvoice/app/domain/lexicon/ItemLexicon.kt`

Byte-for-byte the same identities as A3.

```kotlin
package com.voicetoinvoice.app.domain.lexicon

/**
 * MIRROR of supabase/functions/process-voice-job/lexicon.ts. Keep identical. See ISSUE-107.
 */
data class LexiconEntry(val canonical: String, val surfaces: List<String> = emptyList())

object ItemLexicon {
    val ENTRIES: List<LexiconEntry> = listOf( /* identical to ITEM_LEXICON */ )

    private val surfaceToCanonical: Map<String, String> = buildMap {
        for (e in ENTRIES) {
            put(e.canonical.trim().lowercase(), e.canonical)
            for (s in e.surfaces) put(s.trim().lowercase(), e.canonical)
        }
    }

    fun canonicalize(surface: String?): String? =
        surface?.trim()?.lowercase()?.let { surfaceToCanonical[it] }

    fun canonicalOf(name: String?): String {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        return canonicalize(trimmed) ?: trimmed.lowercase().replace(Regex("\\s+"), " ")
    }

    val ALL_SURFACES: List<String> = ENTRIES.flatMap { listOf(it.canonical) + it.surfaces }
}
```

Then in `OrderingSegmenter.kt`, replace the literal list at line 694 with:

```kotlin
/** Derived from ItemLexicon — do not hand-edit. Add items in ItemLexicon.kt. */
val DEFAULT_ITEM_VOCAB: List<String> = ItemLexicon.ALL_SURFACES
```

---

## 3. Part B — group candidates by canonical, not by phonetic key

**This is the actual bug fix.** Everything else is hygiene.

### B1. `phonetic.ts`

1. Add a field to `VocabEntry` (line 541):
   ```ts
   interface VocabEntry {
     key: string
     surface: string
     /** Identity this surface belongs to. Two surfaces of one item share it, so the
      *  ambiguity margin measures distance to the next DIFFERENT item. ISSUE-107. */
     canonical: string
     numericValue?: number
     canonicalUnit?: string
   }
   ```

2. Populate it in `buildVocabulary` (line 554). Numbers and units get synthetic canonicals so the
   same grouping fixes `किलो`/`kilo`/`kg` competing with each other:
   ```ts
   numbers: Object.entries(HINDI_NUMBER_MAP)
     .filter(([w]) => w.length >= 2)
     .map(([w, v]) => ({ key: phoneticKey(w), surface: w, canonical: `num:${v}`, numericValue: v })),
   units: UNIT_SET.map(u => ({ key: phoneticKey(u), surface: u, canonical: `unit:${normalizeUnit(u) ?? u}`, canonicalUnit: u })),
   items: Array.from(new Set([...DEFAULT_ITEM_VOCAB, ...catalogNames].filter(n => n && n.trim())))
     .map(n => ({ key: phoneticKey(n), surface: n, canonical: canonicalOf(n) })),
   ```
   (Import `canonicalOf` from `./lexicon.ts`. If `normalizeUnit` is not importable at that point,
   use `canonical: \`unit:${u}\`` — units are already canonical strings in `UNIT_SET`.)

3. In `matchVocab` (line 649), change the dedupe key from `entry.key` to `entry.canonical`.
   Exactly three lines change — 660, 662, 668 — plus the map comment:
   ```ts
   // Keyed by CANONICAL identity, not phonetic key: "अदरक" (ATALAK) and "Adrak" (ATLAK) are
   // one item with two keys, and keying by key made them two candidates one edit apart, which
   // read as an ambiguous match and capped a correct parse at 0.55. ISSUE-107.
   const candidateMap = new Map<string, VocabHit>()
   for (const entry of vocab) {
     if (!entry.key) continue
     ...
     const existing = candidateMap.get(entry.canonical)
     if (!existing || norm < existing.normalized) {
       candidateMap.set(entry.canonical, { entry, normalized: norm })
     } else if (norm === existing.normalized) {
       ...
       if (newLitDist < existingLitDist) {
         candidateMap.set(entry.canonical, { entry, normalized: norm })
       }
     }
   }
   ```
   Leave the sort, the `maxNorm` cut, the `margin = second - best` computation and the `top3`
   construction untouched. They now operate on one candidate per item, which is the whole point.

4. The alias fast-path at line 636-647 already returns `margin: 1.0`. Leave it.

### B2. `OrderingSegmenter.kt` — the same three changes

1. `VocabEntry` (line 79): add `val canonical: String`.
2. `SegmenterVocabulary` (line 504-517): `numbers` → `canonical = "num:$value"`,
   `units` → `canonical = "unit:$unit"`, `items` → `canonical = ItemLexicon.canonicalOf(name)`.
3. `matchVocab` (line 224-240): key `candidateMap` by `entry.canonical` instead of `entry.key`.

Carry the same comment. `MIN_MARGIN_PHONE_EDITS` at line 536 stays `1.0`.

### B3. Expected effect on the motivating job

Candidates for `अदरक` become: `Adrak` (0.0, canonical group wins with the Devanagari surface),
then `पालक` at 0.25. `margin = 0.25`, `0.25 × 6 = 1.5 ≥ 1.0` → `resolutionKind = 'MATCH'`,
`isSanityFlagged = false`, no `implausibility_reason`, confidence stays at Grok's 0.95, and the
line auto-confirms. The fast path at `index.ts:685` also stops bailing with
`segment_not_matched`, which removes the Grok round-trip (≈4.1 s of that job's 5.9 s).

---

## 4. Part C — delete the three drifted copies

Replace the local `fun normalizeItemName` / `fun normalizeName` `when` blocks with
`ItemLexicon.canonicalOf(...)`:

| File | Symbol | Action |
|---|---|---|
| `app/.../data/local/AppDatabase.kt:798` | local `fun normalizeItemName` inside `MIGRATION_24_25` | **Leave it.** A migration must stay frozen at the behaviour it shipped with; changing it rewrites history for devices that already ran it. Add a comment: `// Frozen: MIGRATION_24_25 shipped with this table. New code uses ItemLexicon.canonicalOf. ISSUE-107.` |
| `app/.../data/local/dao/CatalogDao.kt:126` | local `fun normalizeItemName` inside `dedupeCatalogItems` | Delete the local function; call `ItemLexicon.canonicalOf(raw)` at every call site in that function. |
| `app/.../data/sync/SyncEngine.kt:87` | local `fun normalizeName` inside `pullCatalogFromCloud` | Delete; call `ItemLexicon.canonicalOf(raw)`. |
| `app/.../domain/matcher/FuzzyCatalogMatcher.kt:65` | `private val indicAliasMap` | Replace the literal map with `private val indicAliasMap: Map<String, String> get() = ItemLexicon.surfaceMapForMatcher()` — add `fun surfaceMapForMatcher(): Map<String, String>` to `ItemLexicon` returning `surfaceToCanonical`. Keep the property name so the ~10 call sites below line 109 are untouched. |

Note the behaviour change this creates and accept it: `CatalogDao`/`SyncEngine` previously
canonicalised to lowercase slugs (`"aaloo"`), and now canonicalise to display names (`"Aaloo"`).
Both are only ever used as grouping keys inside those functions — **verify** by reading each call
site that the value is not written to a DB column. If any call site *does* persist it, stop and
report that step rather than changing what is stored.

---

## 5. Part D — one row per item, in the data

### D1. Server: `supabase/migrations/20260809000000_canonical_catalog_dedupe.sql`

Add the identity column and populate it for the cases SQL can settle on its own:

```sql
ALTER TABLE catalog_items ADD COLUMN IF NOT EXISTS canonical_key text;

-- Baseline: an item's own folded name. The app/edge function overwrite this with the
-- lexicon canonical on every write (see D3), which is what collapses cross-script pairs.
UPDATE catalog_items
   SET canonical_key = lower(regexp_replace(btrim(name), '\s+', ' ', 'g'))
 WHERE canonical_key IS NULL;

CREATE INDEX IF NOT EXISTS idx_catalog_items_canonical
    ON catalog_items (shop_id, canonical_key) WHERE active;
```

Do **not** add a UNIQUE constraint in this migration. Uniqueness is enforced after the merge pass
in D2 lands and is verified clean; a unique index applied first will fail the migration.

### D2. Server: merge duplicates, never delete

Write `supabase/migrations/20260809000100_merge_duplicate_catalog_items.sql` implementing this
rule, as a `DO $$ … $$` block:

For each `(shop_id, canonical_key)` group with more than one `active` row:

- **Survivor** = the row with the greatest `updated_at`; ties broken by greatest `price`.
- **Only merge automatically when `unit_id` matches the survivor AND `price` matches exactly.**
- Repoint every FK to the survivor's `id` — all six, none may be skipped:
  `transactions.item_id`, `stock_in.item_id`, `stock_ledger.item_id`, `stock_batches.item_id`,
  `unmatched_queue.resolved_item_id`, `unmatched_item_observations.promoted_catalog_item_id`.
- Then `UPDATE catalog_items SET active = false, updated_at = now() WHERE id = <loser>`.
  **Never `DELETE`** — a wrong merge must be reversible.
- Leave `stock_ledger` rows alone after repointing; the survivor's `stock_qty` is *not* summed here
  (the ledger is the source of truth and the client rebuilds from it).

**Groups where `unit_id` or `price` differ must be left alone and reported, not merged.** Verified
today in shop `2f992a33-fa26-4be2-9006-3e6eafd41e2c`, these four conflict and are a shopkeeper
decision, not a migration's:

| Devanagari row | Latin row | Conflict |
|---|---|---|
| `घी` ₹1200/KG | `Desi Ghee` ₹650/KG | price 1200 vs 650 |
| `नींबू` ₹100/PACKET | `Nimbu` ₹5/PIECE | unit **and** price |
| `छाछ` ₹32/KG | `Chaas (Buttermilk)` ₹15/PACKET | unit **and** price |
| ` गोल्ड` ₹72/PACKET (note leading space) | `Amul Gold Milk` ₹70/PACKET | price 72 vs 70 |

Emit these via `RAISE NOTICE` in the form
`CANONICAL_MERGE_CONFLICT shop=<id> canonical=<key> ids=<a,b> prices=<x,y> units=<u,v>` so they
are greppable in the migration output. Do not guess a winner.

### D3. Write-time canonicalisation, so duplicates stop reappearing

- **Edge function** — in `index.ts`, wherever a new `catalog_items` row is inserted (search for
  `.from('catalog_items').insert` and the catalog-promotion path used by `unmatched_queue`
  resolution), set `canonical_key: canonicalOf(name)` and, before inserting, re-query for an
  existing active row with that `(shop_id, canonical_key)`; if one exists, **use it instead of
  inserting**.
- **Edge function catalog match** — at `index.ts:697-707`, the fast path filters
  `dbCatalogItems` by phonetic key and bails with `ambiguous_catalog_match` when
  `hits.length !== 1`. Before that check, collapse `hits` by `canonicalOf(ci.name)`; if all hits
  share one canonical, keep the single highest-priced active row and proceed. Two rows of the same
  item must not read as ambiguity.
- **Room** — in `CatalogDao`, the insert/upsert path for new items must call
  `ItemLexicon.canonicalOf(name)` and reuse an existing row with the same canonical before
  creating one.

### D4. Room migration 25 → 26

- `AppDatabase.kt:36`: `version = 26, // ISSUE-107: canonicalKey on catalog_items`
- `CatalogItem.kt`: add
  ```kotlin
  /** Lexicon identity for this item. Two spellings of one product share it. ISSUE-107. */
  val canonicalKey: String = ""
  ```
  and add `Index("canonicalKey")` to the entity's `indices`.
- New `MIGRATION_25_26` following the file's existing try/catch'd `ALTER TABLE` idiom:
  ```kotlin
  private val MIGRATION_25_26 = object : Migration(25, 26) {
      override fun migrate(db: SupportSQLiteDatabase) {
          try { db.execSQL("ALTER TABLE catalog_items ADD COLUMN canonicalKey TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
          try { db.execSQL("CREATE INDEX IF NOT EXISTS index_catalog_items_canonicalKey ON catalog_items(canonicalKey)") } catch (_: Exception) {}
      }
  }
  ```
  Backfill in Kotlin, not SQL (the lexicon is not available to SQLite): in the same `onOpen`
  callback that already purges bad STT rows, if any active `catalog_items` row has
  `canonicalKey = ''`, set it via `ItemLexicon.canonicalOf(name)` and then run the existing
  `CatalogDao.dedupeCatalogItems(...)` once.
- Register it: append `MIGRATION_25_26` to the `.addMigrations(...)` list at `AppDatabase.kt:879`.
- `CatalogDao.dedupeCatalogItems` must key its grouping on `canonicalKey` and apply the same
  "only merge when unit and price agree" rule as D2. Losers are deactivated, never deleted.

---

## 6. Verification — by effect, not by build

Do **not** report this done because it compiles.

1. **Unit tests (JVM):** `./gradlew.bat test --tests "com.voicetoinvoice.app.*Segmenter*"` and any
   `VoiceParserTest`. Add one new test in the existing segmenter test file:
   `"छः किलो अदरक" → segments[0].resolutionKind == MATCH, isSanityFlagged == false`.
2. **Deno tests:** run `phonetic_test.ts` and `item_resolution_test.ts`. `phonetic_test.ts:10` and
   `:40` assert `AMBIGUOUS` for genuinely ambiguous inputs — those **must still pass**. If either
   now returns `MATCH`, stop: the grouping is collapsing items that are actually different, and
   that is a worse bug than the one being fixed. Report it rather than editing the assertion.
3. **Deploy:** `npx supabase functions deploy process-voice-job --project-ref lyowklxsbfznnqridtgr`,
   then re-fetch the live bundle and grep it for the string `canonicalOf` to confirm the deploy
   actually carried the change.
4. **Live proof:** record "छः किलो अदरक" on the phone after installing, then:
   ```sql
   SELECT job_id, raw_transcript, parsed_item_name, parsed_qty,
          diagnostic_trace_json::json #>> '{step_6_final_outcome,0,confidence}' AS conf,
          diagnostic_trace_json::json #>> '{step_6_final_outcome,0,autoConfirmedToLedger}' AS booked
     FROM stt_job_logs ORDER BY created_at DESC LIMIT 5;
   ```
   Pass = `conf` ≥ 0.80 and `booked` = `true` on a row created **after** the deploy. Quote the row.
   No such row = verification did not happen; say so plainly.
5. **Dedupe proof:**
   ```sql
   SELECT shop_id, canonical_key, count(*) FROM catalog_items
    WHERE active GROUP BY 1,2 HAVING count(*) > 1;
   ```
   Every remaining group must be one of the four §D2 conflicts, and nothing else.

---

## 7. `Docs/audit.md`

Add under 🟢 RESOLVED ISSUES as **ISSUE-107**, dated 2026-08-09, in the existing format:
**Symptom** (job `8d6a2aa6-4637-4b8f-8d94-9ee398f1479a`, correct 6 KG Adrak parse queued at 0.55),
**Root Cause** (numbered 1-4 as in §0), **Resolution** (numbered, naming
`lexicon.ts`, `ItemLexicon.kt`, `phonetic.ts`, `OrderingSegmenter.kt`, the two SQL migrations and
`MIGRATION_25_26`), **Verification Date** stating exactly what was checked live vs. left unverified.

Update §1 "Ground-Truth Source-Code Verified Constants" to record that `DEFAULT_ITEM_VOCAB` is now
**derived** from `ITEM_LEXICON` / `ItemLexicon.ENTRIES` and must not be hand-edited.

---

## 8. Open questions — do not guess

1. **`आदा नीला` ₹56/PACKET** exists in the shop's catalog (verified). It looks like an STT artefact
   ("अदरक नीला"?), not a product. It is **not** in scope here — do not delete it, do not add it to
   the lexicon. Mention it in Deviations.
2. If any `CatalogDao`/`SyncEngine` call site of the old normalizers **persists** the normalized
   value to a column rather than using it as an in-memory grouping key, stop on that step (§4) and
   report it — switching lowercase slugs to display names would then be a data change, not a
   refactor, and that is not what this plan authorises.

## 9. Bug class statement

This eliminates the **class** "one item competing with its own alternate spelling" for items,
numbers and units alike, because identity is now a property of the vocabulary entry rather than an
accident of the phonetic key. It does **not** address ambiguity between genuinely different items
with near-identical phonetics (आम / हाँ, ISSUE-103) — that check is intentionally preserved.
