# Qualifier namespace + short-key margin — ISSUE-109 correction

**Author:** Claude Code · **Date:** 2026-08-09 · **Implementer:** Antigravity
**Corrects:** `Docs/brand_variant_unit_model_plan.md` §2.4, which was **wrong**.

> **Do not deploy the current working tree.** Live (`process-voice-job`) is verified NOT to
> contain `QUALIFIERS`/`composeIdentity` — checked by grepping the served bundle — and live is
> currently in a good state. The tree is not. Fix first, then deploy.

---

## 0. What went wrong

The previous plan's §2.4 said:

> "**Additionally** append every `QUALIFIERS` surface [to `ALL_ITEM_SURFACES`], so the
> segmenter's lattice can still recognise 'अमूल' as a token worth attaching to the item"

That instruction was implemented correctly and **is the defect**. `ALL_ITEM_SURFACES` feeds
`vocab.items` in `buildVocabulary`, and `vocab.items` is what `matchVocab` computes the ambiguity
margin over. Putting modifiers in there makes them compete as products:

```
segmentTranscript('पाँच किलो आलू')
  top3 = Aaloo (0.000) · हरा (0.167) · हरी (0.167)
  margin 0.167 × keyLen 4 = 0.67 < 1.0  →  AMBIGUOUS, isSanityFlagged
```

A clean five-kilo-potatoes parse flagged for review — the exact ISSUE-107 defect, reintroduced by
my own instruction. **Verified** by running the segmenter directly.

Removing qualifiers from the item vocabulary (already done in the tree) fixes that case but
creates a worse one — unrecognised modifier tokens get fuzzy-matched to whatever product is
nearest:

| Input | Segmented as | Wanted |
|---|---|---|
| `दो पैकेट अमूल गोल्ड` | **`Angoor Gold`** — अमूल → अंगूर (grapes) | `Amul Gold Milk` |
| `एक पैकेट हरी नींबू` | **`Aaloo नींबू`** — हरी → आलू | `Green Nimbu` |

Both **verified** by running the segmenter. Inventing a product is worse than flagging one.

**The real requirement:** a qualifier must be *recognisable* to the lattice (so it is never
fuzzy-matched to a product) while being *invisible* to the item ambiguity margin (so it never
makes a real product look ambiguous). Those are two different vocabularies, not one.

---

## 1. Scope

**In scope:** a separate qualifier vocabulary in the segmenter, the margin computation, the
short-key margin gate, and one missing lexicon surface.

**Out of scope — already done and verified, do not touch:** `ITEM_LEXICON` contents,
`composeIdentity`, `canonicalOf` precedence, the `base_unit` migrations, the Room v27 migration,
`no_price_for_spoken_unit`. Those parts of ISSUE-109 are correct.

**Do not change** `MIN_MARGIN_PHONE_EDITS` from `1.0` (§4 adds an escape hatch beside it, it does
not move it).

---

## 2. Step 1 — qualifiers get their own vocabulary

### 2.1 `supabase/functions/process-voice-job/lexicon.ts`

`ALL_ITEM_SURFACES` must stay items-only — it already is, leave it. Add beside it:

```ts
/**
 * Qualifier surfaces, flat. These go into their OWN segmenter vocabulary, never into
 * ALL_ITEM_SURFACES: a modifier in the item vocabulary competes as a product and collapses the
 * ambiguity margin of real products ("हरा" sits 0.167 from "आलू"). But it must still be
 * recognisable, or the lattice fuzzy-matches it to the nearest product instead ("अमूल" -> अंगूर).
 * See ISSUE-109.
 */
export const ALL_QUALIFIER_SURFACES: string[] =
  QUALIFIERS.flatMap(q => [q.canonical, ...q.surfaces])
```

### 2.2 `phonetic.ts` — a fourth vocabulary

1. `SegmenterVocabulary` gains `qualifiers: VocabEntry[]`.
2. `buildVocabulary` populates it. Canonical is namespaced so a qualifier can never collide with
   an item canonical:
   ```ts
   qualifiers: ALL_QUALIFIER_SURFACES.map(q => ({
     key: phoneticKey(q), surface: q, canonical: `qual:${canonicalQualifierOf(q)}`
   })),
   ```
   Export `canonicalQualifierOf(surface): string` from `lexicon.ts` (lookup in
   `QUALIFIER_BY_SURFACE`, returns the qualifier's canonical). Two surfaces of one qualifier
   (`हरा`/`हरी`) must share a canonical, exactly as items do.
3. `Emission` gains `isQualifier?: boolean`.
4. In `wholeTokenExpansions`, **before** the item match at the current `matchVocab(key, vocab.items, …)`
   call, try the qualifier vocabulary at a tight threshold:
   ```ts
   const q = matchVocab(key, vocab.qualifiers, WHOLE_TOKEN_MAX_NORM, { rawFragment: raw })
   if (q) {
     out.push({
       emissions: [{
         type: 'ITEM', cost: ITEM_MATCHED_BASE_COST + q.normalized,
         surface: raw, heardText: raw, matchNorm: q.normalized, isQualifier: true,
       }],
       emissionCost: ITEM_MATCHED_BASE_COST + q.normalized,
     })
   }
   ```
   Emit as `type: 'ITEM'` so it still joins the item run in the lattice and lands in
   `currentItemTokens` — that is what keeps "अमूल घी" one phrase. Do **not** `return` here; let the
   item reading also be produced so the lattice can still pick it when a word is genuinely both.

### 2.3 Margin must ignore qualifiers

In the segment accumulation loop in `segmentTranscript` (the code that maintains
`worstItemNorm`, `bestItemMargin` and `segmentTop3` as ITEM emissions are consumed, around
`phonetic.ts:1000-1055`): when an emission has `isQualifier === true`, append its surface to
`currentItemTokens` as today, but **skip** updating `worstItemNorm`, `bestItemMargin` and
`segmentTop3`.

Add the comment:
```
// A brand or variety word is part of the item PHRASE but is not a competing product. Letting
// its match statistics into the ambiguity margin is what flagged "पाँच किलो आलू" as AMBIGUOUS
// (हरा/हरी sit 0.167 from आलू). ISSUE-109.
```

If **every** ITEM emission in a segment was a qualifier (someone said only "अमूल"), leave
`worstItemNorm` null so `resolutionKind` stays `UNKNOWN` — a bare brand is not a sale.

### 2.4 `OrderingSegmenter.kt` — mirror

Same four changes: `SegmenterVocabulary.qualifiers`, `VocabEntry` already has `canonical`,
`Emission.isQualifier`, the qualifier probe in the whole-token expansion, and the margin skip.
Use `ItemLexicon.ALL_QUALIFIER_SURFACES` and `ItemLexicon.canonicalQualifierOf`.

---

## 3. Step 2 — one missing lexicon surface

`composeIdentity('amul milk')` returns `null` today because `milk` resolves to no base item — the
lexicon has `Doodh` with surface `दूध` only. Add to the `Doodh` entry in **both** files:

```
'दूध', 'doodh', 'milk', 'dudh'
```

This was surfaced by a test case (`amul milk` → `Amul Milk`) that the previous run **added on its
own** — it is not in the plan's §5.1 table. Keep the case; it found a real gap. Note it under
Deviations rather than reporting "None".

---

## 4. Step 3 — short-key items can never clear the margin gate

**Pre-existing, not caused by this workstream, and live today.** `phonetic.ts` computes:

```ts
isAmbiguousByMargin = bestItemMargin * itemKeyLength < MIN_MARGIN_PHONE_EDITS   // 1.0
```

For a 2-phone key this demands `margin ≥ 0.5`, which short real products cannot reach.
**Verified** against the live lexicon:

```
segmentTranscript('एक किलो घी')
  key 'घी' = KI (2 phones)
  top3 = घी (0.000) · गोभी (0.375) · मैगी (0.375)
  0.375 × 2 = 0.75 < 1.0  →  AMBIGUOUS → confidence capped 0.55 → review queue
```

So "एक किलो घी" is routed to review on the **currently deployed** build, and so is any other
2-phone item (`आम` → `AN`, per the existing note in `item_resolution.ts:29`). This is the same
class ISSUE-103 addressed for 6-phone keys and did not finish.

**Fix:** an absolute-margin escape beside the existing rule, not a replacement for it.

```ts
/** A runner-up 0.30 normalized away is a clear win regardless of key length. Short real
 *  products (घी -> KI, आम -> AN, 2 phones) can never satisfy margin*keyLen >= 1.0, which
 *  demands margin >= 0.5 — unreachable against a genuine neighbour like गोभी at 0.375. Without
 *  this, every 2-phone item in the catalog is permanently review-only. ISSUE-109. */
const CLEAR_WIN_ABS_MARGIN = 0.30

const isAmbiguousByMargin =
  bestItemMargin !== null && itemKeyLength > 0 &&
  bestItemMargin < CLEAR_WIN_ABS_MARGIN &&
  (bestItemMargin * itemKeyLength) < MIN_MARGIN_PHONE_EDITS
```

Mirror the constant and the condition into `OrderingSegmenter.kt` (`MIN_MARGIN_PHONE_EDITS` lives
at `OrderingSegmenter.kt:536`).

`0.30` is chosen to sit below `घी`'s real 0.375 margin and above the 0.167 near-collisions that
must keep flagging. §5.3 is the check that it does not loosen anything it should not.

---

## 5. Verify — all of these, before reporting done

### 5.1 Segmenter behaviour

Add to `phonetic_test.ts`. Every line is an assertion, not an illustration:

| Transcript | `itemTokens` | `resolutionKind` |
|---|---|---|
| `पाँच किलो आलू` | `["Aaloo"]` | `MATCH` |
| `छः किलो अदरक` | `["अदरक"]` | `MATCH` |
| `एक किलो घी` | `["घी"]` | `MATCH` |
| `दो पैकेट अमूल गोल्ड` | phrase containing `अमूल`, **must not contain** `Angoor` | `MATCH` |
| `एक पैकेट हरी नींबू` | phrase containing `हरी`, **must not contain** `Aaloo` | `MATCH` |

### 5.2 Identity

`canonicalOf` on the joined `itemTokens` from 5.1 must give: `Aaloo`, `Adrak`, `Ghee`,
`Amul Gold Milk`, `Green Nimbu`. Plus `amul milk` → `Amul Milk`.

### 5.3 Nothing loosened

`phonetic_test.ts:10` and `:40` expect `AMBIGUOUS` and **must still pass**. If either flips to
`MATCH`, `CLEAR_WIN_ABS_MARGIN` is too high — stop and report the number, do not edit the
assertion.

### 5.4 Full suite

```
cd supabase/functions/process-voice-job
node --experimental-strip-types --test lexicon_test.ts phonetic_test.ts item_resolution_test.ts
```
All must pass. Report the counts. The last run reported "Deviations: None" while 2 of 39 were
failing — run this and paste the tail before writing that section.

### 5.5 Mirror parity

`ItemLexiconTest.kt` must cover every §5.2 case with identical expected strings.

Do **not** deploy and do **not** run gradle.

## 6. Deviations

Required. If a step names something that does not exist, stop on it, finish the independent
steps, and quote the plan line against the code — as was done correctly for the earlier
`canonical_insert_guard` v1 plan.
