# ISSUE-130 — "भाव डालें" writes the rate to the wrong unit's price line

**Status**: diagnosed against live data, fix planned, NOT implemented.
**Symptom owner**: pilot shop `780d830d-bc71-4a3e-b0df-7f53a67d1dec`, item `Gobhi`.

---

## 1. The evidence (verified, live DB — 2026-08-12)

Five Gobhi jobs inside six minutes on 2026-08-11:

| time (UTC) | transcript | qty | unit | total | status |
|---|---|---|---|---|---|
| 18:29:22 | दस किलो गोभी | 10 | KG | 0 | CONFIRMED |
| 18:32:31 | एक किलो गोभी | 1 | KG | 25 | CONFIRMED |
| 18:32:55 | पाँच किलो गोभी | 5 | KG | 125 | CONFIRMED |
| 18:33:46 | पाँच किलो गोभी | 5 | KG | 125 | CONFIRMED |
| 18:34:24 | सात किलो गोभी | 7 | KG | 0 | **PARSED** (re-asks) |

Every row is `CONFIRMED`, never `AUTO_CONFIRMED` — all four went through the review
queue and the shopkeeper re-typed ₹25 each time. The ₹125 totals came from the dialog's
`qty × rate`, not from the catalog.

**The catalog row those rate entries actually wrote to:**

```
name: Gobhi | unit_id: PIECE | price: 25 | concept: cauliflower
updated_at: 2026-08-11 18:32:46+00      ← 15 s after the first ₹25 confirm
```

**Positive control** — the items that *do* auto-confirm all carry a KG row, all still at
seed `updated_at 2026-08-11 15:25:14` (never touched by a rate entry):

| name | unit_id | price | auto-confirms? |
|---|---|---|---|
| Aaloo | KG | 30 | yes — 15 KG → ₹450 |
| Baingan | KG | 40 | yes — 74 KG → ₹2960 |
| Basmati Rice | KG | 90 | yes — 17 KG → ₹1530 |
| Chaas (Buttermilk) | PACKET | 15 | yes |
| **Gobhi** | **PIECE** | **25** | **no — spoken in KG** |

**Server trace for the failing job `d9c7c975-b615-4d1e-900f-50747ab3c126`:**

```json
"identityResolution": {
  "spokenSurface":"Gobhi","identity":"Gobhi","spokenUnit":"KG",
  "baseUnit":"KG","priceRowUnit":null,"converted":false
}
"implausibility_reason": "phonetic match is ambiguous (margin below threshold)
  | no_price_for_spoken_unit (item 'Gobhi' has no price line for unit KG)
  | 'Gobhi' is not in your catalog yet — set a rate to book it"
```

`priceRowUnit: null` is the whole bug in one field.

## 2. Root cause

**The write side is keyed on name; the read side is keyed on (identity, base unit).**

Read side — `supabase/functions/process-voice-job/index.ts:804-825`:

```ts
let hits = dbCatalogItems.filter(ci => {
  const ciCanonical = ci.canonical_key || canonicalOf(ci.name)
  const ciBase      = ci.base_unit || baseUnitOf(ci.unit_id)
  return ciCanonical === lineIdentity && ciBase === lineBaseUnit   // ← unit-aware
})
…
if (hits.length === 0) {
  const identityHits = dbCatalogItems.filter(ci => (ci.canonical_key || canonicalOf(ci.name)) === lineIdentity)
  if (identityHits.length > 0) return no('no_price_for_spoken_unit')   // ← lands here, forever
  return no('item_not_in_catalog')
}
```

`ItemLexicon.baseUnitOf` (`app/.../domain/lexicon/ItemLexicon.kt:273-282`) maps
`KG/GRAM/PAO/AADHA/DHAI/SAWA → KG` and `PIECE/DOZEN → PIECE`. `PIECE ≠ KG`, so the
Gobhi row is filtered out, `identityHits` still finds it by name, and the server
returns `no_price_for_spoken_unit` on every single KG utterance.

Write side — `app/src/main/java/com/voicetoinvoice/app/ui/screens/home/HomeScreen.kt:478-492`:

```kotlin
val byId     = resolvedItemId?.let { id -> catalog.find { it.id == id } }
val byName   = byId ?: catalog.find { it.name.equals(line.itemName, ignoreCase = true) }  // ← NO unit test
val isNewItem = byName == null
val item     = byName ?: CatalogItem(name = line.itemName, unitId = line.unit, price = rate)
…
if (isNewItem) {
    db.catalogDao().insertOrUpdate(item)
} else if (!isStockIntent && rate > 0.0 && rate != item.price) {
    db.catalogDao().updatePrice(item.id, rate)          // ← overwrites the PIECE row's price
}
```

`updatePrice` (`CatalogDao.kt:51-52`) is `UPDATE catalog_items SET price = :newPrice …
WHERE id = :id` — it changes the price and **never the unit**. So ₹25/kg is stamped onto
the per-piece line, the KG line is never created, and the loop is closed.

The irony: `CatalogDao.insertOrUpdate` (`CatalogDao.kt:28-31`) is already correctly
unit-aware — it resolves via `getActiveByCanonicalKey(key, baseUnit)`. Only the caller's
own name-only pre-check stops that code from ever running.

## 3. Fix

### 3.1 `HomeScreen.kt:478-492` — make resolution unit-aware (the instance)

Replace the `byName` lookup and the insert/update branch with:

```kotlin
val spokenBase = com.voicetoinvoice.app.domain.lexicon.ItemLexicon.baseUnitOf(line.unit)
fun CatalogItem.baseOf() =
    if (baseUnit.isNotBlank()) baseUnit
    else com.voicetoinvoice.app.domain.lexicon.ItemLexicon.baseUnitOf(unitId)

// An explicit pick only counts when it is a price line for the unit that was spoken.
val byId   = resolvedItemId?.let { id -> catalog.find { it.id == id && it.baseOf() == spokenBase } }
val byName = byId ?: catalog.find {
    it.name.equals(line.itemName, ignoreCase = true) && it.baseOf() == spokenBase
}
val isNewItem = byName == null

// A sibling row under a different unit is the template for the new price line: it
// already carries the canonicalKey that identity resolution depends on.
val sibling = catalog.find { it.name.equals(line.itemName, ignoreCase = true) }
val item = byName ?: CatalogItem(
    name         = sibling?.name ?: line.itemName,
    unitId       = line.unit,
    price        = rate,
    canonicalKey = sibling?.canonicalKey ?: "",
    baseUnit     = spokenBase
)
```

Field names verified against `CatalogItem.kt:61-64` — `canonicalKey: String = ""` and
`baseUnit: String = ""`, both non-null with `""` defaults. **There is no `concept` field
on the Kotlin entity** (see §3.5); do not add one in this change.

Leave the `insertOrUpdate` / `updatePrice` branch below **unchanged** — with a
unit-aware `isNewItem` it now does the right thing on both paths, and
`insertOrUpdate`'s `(canonicalKey, baseUnit)` keying prevents the duplicate rows the
ISSUE-030 comment at lines 471-475 warns about.

### 3.2 `PendingConfirmationsSheet.kt:131-134` — stop hiding the unit dimension

```kotlin
fun distinctCatalogByName(catalog: List<CatalogItem>): List<CatalogItem> =
    catalog.groupBy { it.name.trim().lowercase() }
        .mapNotNull { (_, rows) -> rows.maxByOrNull { it.updatedAt } }
        .sortedBy { it.name.lowercase() }
```

This collapses `Gobhi (KG)` and `Gobhi (PIECE)` into one entry, so the picker cannot
express "the per-kg one". Change the grouping key to name **+ base unit**, and render
the unit and price in the suggestion row so the two are distinguishable:

```kotlin
catalog.groupBy { it.name.trim().lowercase() to
    (if (it.baseUnit.isNotBlank()) it.baseUnit else ItemLexicon.baseUnitOf(it.unitId)) }
```

Then in the suggestion list (~`PendingConfirmationsSheet.kt:598-620`) show
`"${it.name} — ₹${trimNumber(it.price)}/${it.unitId}"` instead of the bare name.

### 3.3 `SttWorker.kt:431-432` and `:481-482` — the same defect on the auto-commit path

```kotlin
val matchedCatalog = catalog.find { it.id == itemId }
    ?: catalog.find { it.name.equals(itemName, ignoreCase = true) }   // ← no unit test
```

Two sites, both name-only. **This one is worse than a re-ask**: it can resolve a KG
utterance to a PIECE row and book `7 × ₹25/piece` as if it were a per-kg price — a
silently wrong amount in the ledger. Apply the same `baseOf() == spokenBase` filter to
both. If no unit-matching row exists, `matchedCatalog` must stay null so the line routes
to review rather than pricing off the wrong line.

### 3.4 Out of scope for this plan, but same bug class — do not fix here, report it

`VoiceCommandHandlers.kt:75-76` and `:118-119` repeat the identical name-only
`catalog.find` pattern. **Not cleared by evidence** — I have not traced whether those
handlers can reach a pricing decision. Name them in the Deviations section; they need
their own diagnosis before anyone touches them.

### 3.5 Surfaced while planning — `concept` is server-side only (do NOT fix here)

`catalog_items.concept` exists in Postgres (ISSUE-126, migration
`20260811000000_add_catalog_concept.sql`, backfilled for all 53 pilot SKUs) but **there
is no matching field on the Kotlin `CatalogItem` entity** (`CatalogItem.kt:12-65`).
Consequence: any catalog row the client creates and syncs up lands with `concept = NULL`,
including the new `Gobhi / KG` row this plan produces. The `PIECE` sibling keeps
`concept: cauliflower`; the new KG row will not have it.

That does **not** block this fix — the concept layer treats NULL as "behaves exactly as
before" by design (ISSUE-126 resolution note 2) — but it means the review-queue path
quietly creates concept-less rows, so the concept layer degrades for exactly the items a
shopkeeper adds by voice. **Verified**: the field is absent from the entity. **Not
verified**: whether `CloudSyncManager`'s catalog payload or a server-side backfill
compensates. Flag it for its own issue; do not widen this change to cover it.

## 4. Instance vs class

This plan fixes **the class for the confirm/commit paths** (§3.1 + §3.3 remove every
name-only price resolution in the review-queue and auto-commit writers), and explicitly
does **not** clear `VoiceCommandHandlers` (§3.4). The underlying invariant worth stating
in code: *a price is only ever read from, or written to, a catalog row whose base unit
equals the spoken line's base unit.*

## 5. Data repair — must happen on the device

Sync is one-directional local→cloud with no pull path (`CLAUDE.md` §Architecture), so
**editing `catalog_items` in Supabase will not reach the phone** and will be overwritten
on the next sweep. The live Gobhi row is now wrong in two ways: it lost its seeded
₹30/piece price, and it still has no KG line.

Two options, in order of preference:

1. **Do nothing in code.** After §3.1 ships, the next "सात किलो गोभी" → ₹25 entry
   creates the `Gobhi / KG / 25` row correctly and the re-asking stops by itself. The
   stale `PIECE @ 25` row remains and should be corrected by hand in the Catalog screen.
2. If a clean per-piece price matters immediately, edit the PIECE row back to ₹30 in the
   Catalog screen on the phone.

**No migration.** A `MIGRATION_N_N+1` that invents a KG row for one pilot shop's Gobhi
would ship pilot-specific data to every install.

## 6. Verification — by effect, not by build

`./gradlew.bat test` and `assembleDebug` prove nothing here. The acceptance test is:

1. Install the build (`tools/vti-ship.ps1`, md5-verified).
2. Say **"सात किलो गोभी"**. It routes to review (expected — no KG row yet).
3. Tap भाव डालें, unit `KG`, rate `25`, Save & Confirm.
4. Confirm the new row exists locally, then after sync:
   ```sql
   SELECT name, unit_id, price, concept, updated_at FROM catalog_items
   WHERE shop_id = '780d830d-bc71-4a3e-b0df-7f53a67d1dec' AND name ILIKE '%gobhi%'
   ORDER BY unit_id;
   ```
   **Expected: two rows** — `KG / 25` (new, `concept: cauliflower` carried over) and
   `PIECE / 25` (untouched).
5. Say **"पाँच किलो गोभी"** again. Then:
   ```sql
   SELECT job_id, status, parsed_qty, parsed_unit, parsed_total, created_at
   FROM stt_job_logs WHERE raw_transcript LIKE '%गोभी%'
   ORDER BY created_at DESC LIMIT 3;
   ```
   **Pass = `AUTO_CONFIRMED`, `parsed_total: 125`, and no `no_price_for_spoken_unit` in
   the trace's `implausibility_reason`.** `CONFIRMED` means it still went through review —
   that is a failure, not a pass. Quote the row.

If step 5 produces no new job row, the verification did not happen — say so.

## 7. Mirrored-logic check

**Client only.** `index.ts:804-825` is already correct — it is the component that
*detected* the bug. No edge-function change, no deploy. Do not "fix" the server by
relaxing the unit filter: that filter is what stops a per-piece price being charged for
a kilogram.
