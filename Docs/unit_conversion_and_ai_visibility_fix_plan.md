# Segmenter Overconfidence + Unit-Conversion Total Bug + AI-Model Visibility — Fix Plan

**Date:** 2026-08-08
**Author:** Claude Code (plan only — see CLAUDE.md, not implementing)
**Implementer:** Antigravity — execute word by word.
**Trigger:** live job `379a522f-95d0-4c1d-a01e-dcb5a2485f58` — shopkeeper said "100 किलो आलू", STT
misheard it entirely, segmenter produced `16 GRAM Aaloo`, pipeline computed `total = ₹800`
(`16 × ₹50`, Aaloo's per-**KG** catalog price applied directly to a **gram** quantity with no
conversion). Confirmed by the user, then verified against source.

This plan has two parts, in priority order:

- **§A — how the segmenter reached "16 GRAM Aaloo" out of `"सूल गलवालू"` at all**, and why it
  reported that reading as a confident, clean `MATCH` instead of flagging it. This is the deeper
  bug — the arithmetic bug in §B only matters *because* the segmenter handed it a fabricated
  quantity/unit pair with no warning attached.
- **§B — the gram↔kg total-computation bug** (originally the whole scope of this plan, before
  the question of how "16 gram" was reached in the first place got raised).
- **§C — AI-model visibility in the diagnostic log**, unrelated to A/B, bundled here because it
  came up in the same conversation.

---

## §A — Root cause: the segmenter can manufacture a confident match out of one garbled token

### A.1 What actually happened, traced through the real algorithm (verified by execution)

I compiled `phonetic.ts` as-is (`tsc`, no edits) and ran the exact functions this job used against
the exact strings from its trace — not a description of the algorithm, the algorithm itself.

**The transcript was two tokens: `"सूल"` and `"गलवालू"`.**

**Token 1, `"सूल"`, whole-token match against the number vocabulary:**

```
phoneticKey('सूल')  = 'SOL'
phoneticKey('सोलह') = 'SOLA'   (सोलह = "solah" = sixteen)
distance = 0.5, normalized = 0.125
```

`0.125` is comfortably inside `WHOLE_TOKEN_MAX_NORM = 0.25` — this is a **legitimate, tight**
phonetic near-match. On its own this part of the read is not the problem: सूल→सोलह(16) is
exactly the kind of recoverable STT slip (dropped trailing vowel) the segmenter exists to fix.
This is where quantity `16` came from.

**Token 2, `"गलवालू"`, does not cleanly match anything as a whole token:**

```
phoneticKey('गलवालू') = 'KALAVALO'
vs 'gram'/'ग्राम' → normalized 0.4375   (> 0.25, REJECTED as a whole-token unit)
vs 'Aaloo'/'आलू'  → normalized 0.5000   (> 0.25, REJECTED as a whole-token item)
```

Correctly rejected both ways — `"गलवालू"` is not a clean match for anything. This is where it
*should* have stopped: fall through to `ITEM_BASELINE_COST = 1.2`, i.e. "keep it as an
unrecognized item name," which is exactly the safe, honest outcome for garbage input.

**But the segmenter also tries every substring split of the token** (`splitExpansions`,
`phonetic.ts:717`), and one of them wins:

```
split @ index 5 of 'KALAVALO':  p1 = "KALAV"   p2 = "ALO"
  "KALAV" vs 'gram'  → normalized 0.300   (exactly AT the split ceiling — the loosest split allows)
  "ALO"   vs 'Aaloo' → normalized 0.000   (EXACT — "आलू"/"Aaloo" collapse to the identical key)
  split cost = 0.300 + 0.000 + 2×SPLIT_PENALTY(0.10) = 0.50
```

`0.50 < 1.2` (the unrecognized-item fallback), so the Viterbi lattice picks the split. **One
exact-but-coincidental item match (`"ALO"` happening to equal `"Aaloo"`'s key) is enough by itself
to drag a unit reading that only barely squeaked under the split ceiling along with it**, because
the item's cost of `0.000` so thoroughly dominates the combined cost that the borderline unit
piece almost doesn't matter to the total.

### A.2 Why this got reported as a clean `MATCH`, not flagged

This is the actual bug — not that the split happened (fuzzy splitting garbled tokens is the
segmenter's whole design), but that **nothing downstream can tell "this UNIT came from a
borderline split" apart from "this UNIT was cleanly, independently spoken."**

Verified by reading `phonetic.ts` directly:

- `em()`, the emission-builder used by `splitExpansions` (`phonetic.ts:726-732`):
  `matchNorm: type === 'ITEM' ? hit.normalized : undefined` — **`matchNorm` is only ever
  recorded for `ITEM` emissions.** NUM and UNIT emissions carry a `cost` used internally for
  Viterbi path selection, but that cost is discarded once the path is chosen.
- `wholeTokenExpansions` (`phonetic.ts:662-715`) does the same: the `n` (NUM) and `u` (UNIT)
  push objects never set `matchNorm` either.
- In `segmentTranscript`'s main loop (`phonetic.ts:1012-1036`): the `NUM` branch
  (`currentQty = dt.numericValue`) and `UNIT` branch (`currentUnit = normalizeUnit(...)`) read
  nothing about match quality. Only the `else` (ITEM) branch feeds `worstItemNorm`/
  `bestItemMargin` (`phonetic.ts:1024-1029`).
- `resolutionKind` (`closeSegment`, `phonetic.ts:958-959`) is computed **purely from
  `worstItemNorm`/`bestItemMargin`** — the item's own match quality, nothing else:
  `worstItemNorm === null ? 'UNKNOWN' : (bestItemMargin < 0.08 ? 'AMBIGUOUS' : 'MATCH')`.
- `isSanityFlagged` (`phonetic.ts:966`) is `ambiguousDoubleQty || suspectReading ||
  (resKind !== 'MATCH')` — `suspectReading` only fires for a hardcoded list
  (`DISTANCE_UNIT_TOKENS`, e.g. "किलोमीटर"), not generically for "a unit was recovered from a
  borderline split."

Net effect: because `"ALO"` matched `"Aaloo"` at norm **0**, `worstItemNorm = 0`,
`resolutionKind = 'MATCH'`, `isSanityFlagged = false` — **the segment reports as the cleanest
possible read**, with zero trace of the fact that its unit came from splitting one garbled token
at a position that only barely cleared the loosest threshold the algorithm allows. A shopkeeper
(or reviewer) looking at `itemMatchNorm: 0, resolutionKind: "MATCH"` has no way to know the unit
is fabricated.

**Verified identically on the Kotlin client** — `OrderingSegmenter.kt:326`:
`matchNorm = if (type == TokenType.ITEM) hit.normalized else null` — the exact same
item-only restriction, confirmed by direct comparison, not assumed from the "mirrors" doc comment.
This is a genuine two-file bug, not a Deno-only one.

### A.3 Why this matters more after yesterday's changes

`buildFastPathFrom` (both the original and the STT-race-shortcut path added yesterday) gates on
`seg.resolutionKind !== 'MATCH'` and `seg.itemMatchNorm !== 0` to decide whether a segment is safe
to book without AI review. **Both of those conditions are satisfied by this exact segment.** This
job was *not* auto-confirmed only because of an entirely unrelated check
(`rawLooksUnrecognizable`, the transcript-level "does anything here match known vocabulary"
gibberish detector) — a lucky save, not a designed one. A transcript that passes the
gibberish-level check (i.e., contains *some* genuinely recognizable words elsewhere) but still
contains one force-split garbled token would sail straight through the fast path and the
`confidence ≥ 0.80` auto-confirm gate with a fabricated quantity/unit and no flag at all.

### A.4 The fix: track match quality for NUM/UNIT emissions too, not just ITEM

**Both files — this is parsing logic, the CLAUDE.md mirror rule applies.**

**`supabase/functions/process-voice-job/phonetic.ts`:**

1. In `em()` (`splitExpansions`, ~line 726), stop restricting `matchNorm` to `ITEM`:
   ```ts
   // BEFORE
   matchNorm: type === 'ITEM' ? hit.normalized : undefined,
   // AFTER
   matchNorm: hit.normalized,
   ```
   Also add a `fromSplit: true` field to every emission `splitExpansions` produces (all three of
   its push sites, ~lines 741/743/748/761) — this is the signal that distinguishes "one token
   forced into two roles" from "two tokens independently, cleanly spoken."

2. In `wholeTokenExpansions` (~lines 690, 693), add `matchNorm: n.normalized` / `matchNorm:
   u.normalized` to the NUM/UNIT push objects (currently absent entirely) so a whole-token
   near-miss is measured on the same footing.

3. In `segmentTranscript`'s main loop (`phonetic.ts:1012-1022`), track worst-case NUM/UNIT norm
   the same way `worstItemNorm` already works, plus whether either came from a split:
   ```ts
   // add alongside worstItemNorm / bestItemMargin declarations:
   let worstNonItemNorm: number | null = null
   let anyFromSplit = false
   ```
   Inside the `NUM`/`UNIT` branches, before the existing assignment:
   ```ts
   if (dt.matchNorm !== undefined && dt.matchNorm !== null) {
     worstNonItemNorm = worstNonItemNorm === null ? dt.matchNorm : Math.max(worstNonItemNorm, dt.matchNorm)
   }
   if (dt.fromSplit) anyFromSplit = true
   ```
   (`DecodedToken` needs a `fromSplit?: boolean` field threaded through from `Emission` — check
   `decode()`'s `decoded.push(...)` at ~line 870 and add it there too.)

4. In `closeSegment` (`phonetic.ts:958-966`), tighten `resKind`/`isSanityFlagged` to also
   distrust a borderline split-derived NUM/UNIT even when the item itself is exact:
   ```ts
   // NEW constant near SPLIT_PART_MAX_NORM:
   const SPLIT_UNIT_TRUST_NORM = 0.15  // tighter than SPLIT_PART_MAX_NORM (0.30) -- a split
     // piece that only just cleared the loose ceiling is not "confirmed", it's "not yet rejected"

   const nonItemUntrustworthy = anyFromSplit && worstNonItemNorm !== null && worstNonItemNorm > SPLIT_UNIT_TRUST_NORM
   const resKind: 'MATCH' | 'AMBIGUOUS' | 'UNKNOWN' = worstItemNorm === null ? 'UNKNOWN' :
                   (nonItemUntrustworthy || (bestItemMargin !== null && bestItemMargin < 0.08)) ? 'AMBIGUOUS' : 'MATCH'
   ```
   `isSanityFlagged` needs no separate edit — it already derives from `resKind !== 'MATCH'`.

**`app/src/main/java/com/voicetoinvoice/app/domain/parser/OrderingSegmenter.kt`:** the identical
change, mirrored line for line — `matchNorm = if (type == TokenType.ITEM) hit.normalized else
null` (line 326) becomes unconditional, `fromSplit` threaded the same way, `worstItemNorm`
tracking (line 725 region) gains the `worstNonItemNorm`/`anyFromSplit` companions, and the
`ResolutionKind` computation (line 735 region) gains the same tightening.

### A.5 Verification for §A

1. Feed `"सूल गलवालू"` through `segmentTranscript` (a unit test, not a live job) and confirm the
   resulting segment now has `resolutionKind: "AMBIGUOUS"` (or `isSanityFlagged: true`), not
   `"MATCH"`.
2. Confirm this does **not** regress genuinely clean two-token reads — e.g. `"पाँच किलो आलू"`
   (clean NUM+UNIT+ITEM, no split needed) must still resolve to `resolutionKind: "MATCH"`,
   `isSanityFlagged: false`. Add this as a paired test case specifically so the tightening can't
   silently start flagging legitimate orders.
3. Re-run this exact job's transcript (`"सूल गलवालू"`) against the deployed fix and confirm
   `step_4_fast_path.skipReason` now reads something like `segment_not_matched` /
   `inexact_phonetic_match` rather than the segment being fast-path-eligible in the first place —
   i.e. confirm the fix closes the gap even without `rawLooksUnrecognizable`'s unrelated save.

### A.6 Bug instance vs bug class

This is a **class** fix, not an instance patch: it corrects the confidence model for every
split-derived NUM/UNIT, not just this one word. It does not, and cannot, fix STT accuracy itself —
`"सूल गलवालू"` will still be a wrong transcript after this ships. What changes is that a wrong
transcript can no longer masquerade as a confident, clean parse; it will be flagged and routed to
review instead of silently priced.

### A.7 Open question

`SPLIT_UNIT_TRUST_NORM = 0.15` (§A.4) is a **reasoned default, not a measured one** — I do not
have a labeled corpus of "should have been trusted" vs "should have been flagged" split-derived
units to tune it against. Ship it, then watch `parse_inspections` (if that table exists — see the
earlier latency plan) and this job's outcome class specifically for a week before treating the
threshold as final.

---

## §B — The gram↔kg total-computation bug (original scope)

---

## §B.0 — What's actually true here (verified, not inferred)

Two independent problems, one trace.

**0.1 — The STT mishearing is not this plan's subject.** Both providers failed on the actual
audio: Grok returned `""`, Sarvam returned `"सूल गलवालू"` — neither resembles "100 किलो आलू". This
job's transcript is simply wrong at the source. Nothing in this plan fixes STT accuracy; that is a
model/audio-quality problem, not a logic bug. **Verified** from the job's `step_2_stt_proxy_response`.

**0.2 — The unit-conversion bug is real, confirmed by reading the code, and is not new.**
[index.ts:1750](../supabase/functions/process-voice-job/index.ts) —

```ts
priceAtSale = spokenPrice > 0 ? spokenPrice : (matched ? matched.price : 0.0)
total = qty * priceAtSale
```

`matched.price` is whatever the catalog row's `price` column holds, denominated in that row's own
`unit_id`. `qty` is the segment's raw number in whatever unit was spoken. **Nothing between these
two lines converts one into the other's scale.** Untouched by yesterday's STT-race/fast-path
diff — this bug predates that work entirely.

**0.3 — This specific job did NOT book wrong money, but not because of a correctness guarantee.**
Status stayed `PARSED` (not `AUTO_CONFIRMED`) because `implausibility_reason` was set to
`"transcript text looks unrecognized / corrupt"` — the unrelated gibberish-detection check
(`rawLooksUnrecognizable`, index.ts). The GRAM-specific sanity check in
[price_intent.ts:204-206](../supabase/functions/process-voice-job/price_intent.ts) —
`if (u === 'GRAM' ... ) { if (qty < 10) return ... }` — did **not** fire, because 16 ≥ 10. If the
transcript had been clean (e.g. a correctly-recognized "500 gram aloo"), nothing would have
blocked it, and it would have auto-confirmed at `500 × 50 = ₹25,000` instead of the correct
`0.5 × 50 = ₹25`. **This is the load-bearing finding**: the bug is currently latent, not inert.

**0.4 — Porting the Kotlin client's conversion function as-is would introduce the SAME bug in the
opposite direction.** I checked
[VoiceParser.kt:252-263](../app/src/main/java/com/voicetoinvoice/app/domain/parser/VoiceParser.kt)
(`calculateTotalForUnits`) expecting it to be the correct reference to mirror. It isn't:

```kotlin
private fun calculateTotalForUnits(qty: Double, unit: String, unitPrice: Double, defaultCatalogUnit: String): Double {
    val qtyInStandardUnit = when (unit) {
        "GRAM", "ML" -> qty * 0.001
        ...
        else -> qty
    }
    return qtyInStandardUnit * unitPrice
}
```

`defaultCatalogUnit` is accepted as a parameter and **never used in the function body**. The
conversion is unconditional: GRAM is always treated as a sub-unit of a KG-priced item. **Verified
against live catalog data** — 8 active rows are `unit_id = 'GRAM'` with sub-rupee prices
(`Haldi Powder` ₹0.25, `Jeera` ₹0.4, `Lal Mirch Powder` ₹0.3, `Garam Masala` ₹0.6 — each priced
**per gram already**, not per kg). For those items, "50 gram haldi" under the Kotlin formula
computes `50 × 0.001 × 0.25 = ₹0.0125` instead of the correct `50 × 0.25 = ₹12.50` — wrong by
1000x in the *other* direction. **Do not copy this function verbatim into the server.** Both
pipelines need the same corrected logic, not a client-to-server port of the existing one.

---

## §B.1 — Correct design: conversion keyed off the catalog row's own unit, not a static table

The only source of truth for "what scale is this price in" is `matched.unit_id` — the catalog
row actually resolved to. Compare the **spoken unit** against it and convert only when they're the
same physical dimension (both weight, or both volume) but different scale.

### 1.1 New shared helper (add to both sides, same semantics)

**Server** — add to `supabase/functions/process-voice-job/index.ts`, near `confidenceFromMatchNorm`
(around line 137):

```ts
/** Grams-per-unit / ml-per-unit for the two dimensions this catalog uses. Anything not
 *  listed (PACKET/PIECE/DOZEN) is a count, not a weight/volume, and is never converted. */
const WEIGHT_BASE_UNITS: Record<string, number> = { GRAM: 1, KG: 1000 }
const VOLUME_BASE_UNITS: Record<string, number> = { ML: 1, LITRE: 1000 }

/**
 * Returns the multiplier to apply to a spoken quantity so that `qty * factor * catalogPrice`
 * is correct regardless of which of GRAM/KG (or ML/LITRE) the shopkeeper said versus which
 * one the catalog row is priced in. Same-unit and cross-dimension (e.g. spoken PACKET
 * against a KG-priced row) both return 1 -- the latter is a real mismatch this function
 * cannot resolve, and is left to implausibilityReason's total-magnitude check (§B.2) rather
 * than silently guessing.
 */
function unitConversionFactor(spokenUnit: string, catalogUnit: string): number {
  const s = (spokenUnit || '').toUpperCase()
  const c = (catalogUnit || '').toUpperCase()
  if (s === c) return 1
  if (s in WEIGHT_BASE_UNITS && c in WEIGHT_BASE_UNITS) return WEIGHT_BASE_UNITS[s] / WEIGHT_BASE_UNITS[c]
  if (s in VOLUME_BASE_UNITS && c in VOLUME_BASE_UNITS) return VOLUME_BASE_UNITS[s] / VOLUME_BASE_UNITS[c]
  return 1
}
```

**Client** — replace `calculateTotalForUnits` in
`app/src/main/java/com/voicetoinvoice/app/domain/parser/VoiceParser.kt:252-263` with the
catalog-aware version:

```kotlin
private fun calculateTotalForUnits(qty: Double, unit: String, unitPrice: Double, defaultCatalogUnit: String): Double {
    val weightBase = mapOf("GRAM" to 1.0, "KG" to 1000.0)
    val volumeBase = mapOf("ML" to 1.0, "LITRE" to 1000.0)
    val s = unit.uppercase()
    val c = defaultCatalogUnit.uppercase()
    val factor = when {
        s == c -> 1.0
        weightBase.containsKey(s) && weightBase.containsKey(c) -> weightBase.getValue(s) / weightBase.getValue(c)
        volumeBase.containsKey(s) && volumeBase.containsKey(c) -> volumeBase.getValue(s) / volumeBase.getValue(c)
        else -> 1.0
    }
    return qty * factor * unitPrice
}
```

This is the fix for §B.0.4 too — `defaultCatalogUnit` was already threaded through as a parameter
into the old function and simply never read; wiring it in is the entire client-side change.
**Confirm the call site already passes the real catalog unit** (not a default/guess) before
treating this as done — read the caller of `calculateTotalForUnits` and verify.

### 1.2 Wire it into the total computation

**Server**, at [index.ts:1748-1750](../supabase/functions/process-voice-job/index.ts):

```ts
// BEFORE
} else {
  priceAtSale = spokenPrice > 0 ? spokenPrice : (matched ? matched.price : 0.0)
  total = qty * priceAtSale
}
```

```ts
// AFTER
} else {
  if (spokenPrice > 0) {
    // A spoken price is already in the shopkeeper's own words for THIS quantity/unit --
    // never rescale it against the catalog's denomination.
    priceAtSale = spokenPrice
    total = qty * priceAtSale
  } else if (matched) {
    const convFactor = unitConversionFactor(unit, matched.unit_id)
    priceAtSale = matched.price
    total = qty * convFactor * priceAtSale
  } else {
    priceAtSale = 0.0
    total = 0.0
  }
}
```

**Placement note:** `unit` (declared at index.ts:1754, `const unit = rawItem.unit || (matched ?
matched.unit_id : "PACKET")`) is currently declared **after** this block. Move that one `const
unit = ...` line to **immediately before** this `if/else` chain (i.e. ahead of where `intent` is
branched, around line 1738) so `unit` exists when `unitConversionFactor` needs it. This is the
same class of ordering mistake that caused yesterday's outage — **verify by reading the moved
code once more before deploying**, don't assume the move is inert just because it's "just a
`const`".

Also note the `BULK_SALE_TOTAL` branch (`total = spokenPrice; priceAtSale = total / qty`) is
correct as-is and untouched — a spoken bulk total is already in real rupees for the real spoken
quantity, there is nothing to convert.

**Client** — the fix is entirely inside `calculateTotalForUnits` (§B.1.1); no other call-site change
needed once the function itself is correct, provided the caller already supplies the true catalog
unit as `defaultCatalogUnit`. Verify that at the call site before considering this done.

---

## §B.2 — Defensive net: a total-magnitude sanity check (belt, not suspenders)

§B.1 fixes the known cause. It should not be the *only* thing standing between a parsing bug and the
ledger — that was exactly this repo's ISSUE-022 lesson (recall vs precision). Add an upper-bound
check next to the existing lower-bound one in
[price_intent.ts:213-215](../supabase/functions/process-voice-job/price_intent.ts)
(`implausibilityReason`):

```ts
// existing lower-bound check, unchanged:
if (mode === 'SALE' && total > 0 && total < MIN_PLAUSIBLE_SALE_VALUE) {
  return `sale value ₹${total.toFixed(2)} is below the ₹${MIN_PLAUSIBLE_SALE_VALUE} auto-confirm floor`
}

// NEW upper bound -- a per-kirana-sale ceiling, not a per-shop one. Deliberately generous
// (silver/ghee/dry-fruit legitimately price in the hundreds per kg) so this only catches
// the class of error this bug produces -- 100x-1000x order-of-magnitude mistakes -- not a
// large but real sale.
const MAX_PLAUSIBLE_SALE_VALUE = mode === 'STOCK' ? 500000 : 50000
if (total > MAX_PLAUSIBLE_SALE_VALUE) {
  return `sale value ₹${total.toFixed(2)} exceeds the ₹${MAX_PLAUSIBLE_SALE_VALUE} auto-confirm ceiling`
}
```

**Open question, do not guess:** ₹50,000 as the SALE ceiling and ₹500,000 for STOCK are my
estimates from the catalog's own price range (चांदी/silver legitimately prices at ₹412,000/**kg**,
verified live — so the STOCK ceiling must clear that). Confirm both numbers make sense for this
shop's actual scale of business before shipping; get it wrong high and this net does nothing, get
it wrong low and a legitimate large sale gets bounced to manual review.

---

## §C — AI-model visibility in the diagnostic log UI

**Confirmed real, separate from §B.1-2.** The server trace already records which Grok chat model
served a job (`step_4_ai_model`, e.g. `"grok-4.5"` — see `aiModelUsed` in index.ts, populated from
`callGrokChatInterpretation`'s `result.model`). The client never displays it.

[DiagnosticLogsScreen.kt:218-232](../app/src/main/java/com/voicetoinvoice/app/ui/screens/logs/DiagnosticLogsScreen.kt):

```kotlin
// BEFORE
val pathBadge: Pair<String, Color>? = remember(log.diagnosticTraceJson) {
    runCatching {
        if (log.diagnosticTraceJson.isBlank()) return@runCatching null
        val root = JSONObject(log.diagnosticTraceJson)
        val fp = root.optJSONObject("step_4_fast_path")
        val src = root.optString("step_4_interpretation_source", "")
        when {
            fp?.optBoolean("used") == true -> "⚡ FAST" to Color(0xFF00796B)
            src == "memory" -> "🧠 MEMO" to Color(0xFF5E35B1)
            src == "segmenter_fallback" -> "📐 RULES" to Color(0xFF616161)
            src == "grok_ai" || src == "forced_ai_fallback" -> "🤖 AI" to Color(0xFF8D6E63)
            else -> null
        }
    }.getOrNull()
}
```

```kotlin
// AFTER
val pathBadge: Pair<String, Color>? = remember(log.diagnosticTraceJson) {
    runCatching {
        if (log.diagnosticTraceJson.isBlank()) return@runCatching null
        val root = JSONObject(log.diagnosticTraceJson)
        val fp = root.optJSONObject("step_4_fast_path")
        val src = root.optString("step_4_interpretation_source", "")
        val aiModel = root.optString("step_4_ai_model", "").takeIf { it.isNotBlank() && it != "null" && it != "fast_path" }
        when {
            fp?.optBoolean("used") == true -> "⚡ FAST" to Color(0xFF00796B)
            src == "memory" -> "🧠 MEMO" to Color(0xFF5E35B1)
            src == "segmenter_fallback" -> "📐 RULES" to Color(0xFF616161)
            (src == "grok_ai" || src == "forced_ai_fallback") && aiModel != null -> "🤖 $aiModel" to Color(0xFF8D6E63)
            src == "grok_ai" || src == "forced_ai_fallback" -> "🤖 AI" to Color(0xFF8D6E63)
            else -> null
        }
    }.getOrNull()
}
```

The `!= "fast_path"` guard matters: `step_4_ai_model` is set to the literal string `"fast_path"`
when the fast path skipped the AI (see index.ts, `aiModelUsed = 'fast_path'`) — without the guard
the badge would read "🤖 fast_path" on jobs that never called Grok at all, which is worse than the
current generic label, not better.

---

## §D — Files touched

| File | Change |
|---|---|
| `supabase/functions/process-voice-job/phonetic.ts` | §A.4 `matchNorm`/`fromSplit` tracking for NUM/UNIT emissions, tightened `resolutionKind` |
| `app/src/main/java/com/voicetoinvoice/app/domain/parser/OrderingSegmenter.kt` | §A.4 identical mirror of the phonetic.ts change |
| `supabase/functions/process-voice-job/index.ts` | §B.1.1 new helper, §B.1.2 total computation + `unit` declaration moved earlier |
| `supabase/functions/process-voice-job/price_intent.ts` | §B.2 upper-bound check |
| `app/src/main/java/com/voicetoinvoice/app/domain/parser/VoiceParser.kt` | §B.1.1 `calculateTotalForUnits` rewrite — **verify the call site passes the real catalog unit before assuming this is sufficient** |
| `app/src/main/java/com/voicetoinvoice/app/ui/screens/logs/DiagnosticLogsScreen.kt` | §C badge shows real model |

§A and §B.1 both change on both sides (parsing/pricing logic — CLAUDE.md's mirror rule applies).
§B.2 and §C are server-only and client-only respectively.

---

## §E — Verification (by effect, not by build)

0. **§A first — see A.5.** The segmenter fix gates everything downstream; verify it in isolation
   (unit tests on `"सूल गलवालू"` and on a clean control case) before layering §B's arithmetic fix
   on top, so a failure in one doesn't get misattributed to the other.
1. **Unit tests first if they exist** — check for an existing price/total test file
   (`price_intent_test.ts` exists; check it for total-computation coverage, add cases for
   GRAM-vs-KG-catalog and GRAM-vs-GRAM-catalog — i.e. one case per §B.0.4's two directions).
2. **Live verification, server**: after deploy, POST a real or synthetic job where the spoken
   segment resolves to a GRAM quantity against a KG-priced item (e.g. force a segment through
   with quantity=500, unit=GRAM, item=Aaloo) and confirm `total` in the response/trace is `25`,
   not `25000`. Repeat with quantity=50, unit=GRAM, item=Haldi Powder (a GRAM-priced catalog row)
   and confirm `total` is `12.5`, not `0.0125`.
3. **Live verification, client**: build, install, and manually record "500 gram aloo" and "50 gram
   haldi" against this shop's real catalog; confirm the booked/reviewed total in the app UI
   matches, not just the local unit test.
4. **Diagnostic screen**: open a job that used the real AI path (force `DISABLE_FAST_PATH=1` for
   one test recording) and confirm the badge shows the actual model id, not "🤖 AI".
5. **Replay job `379a522f-95d0-4c1d-a01e-dcb5a2485f58`'s exact transcript** (`"सूल गलवालू"`)
   against the fully-fixed pipeline and confirm both fixes together: the segment is now flagged
   (§A), and *if* it somehow still resolved, the total would be unit-correct (§B) — defense in
   depth, not either-or.
6. Quote the actual row/response in the report — a passing build is not evidence per this repo's
   own verification rule.

---

## §F — Bug instance vs bug class

**§A** (see A.6 for the full statement) fixes the confidence-modeling class: no split-derived
NUM/UNIT can silently ride on an exact ITEM match's coattails again, for any word, not just
"गलवालू". It does not, and cannot, fix STT accuracy — garbage audio will still produce garbage
transcripts; what changes is that garbage now gets flagged instead of priced.

**§B** also fixes a class, not an instance: the root cause (unconditional/absent unit scaling) is
replaced with a rule that consults the actual catalog denomination, covering every item/unit
combination in the current catalog, not just Aaloo-in-grams. It does **not** cover a catalog row
whose `unit_id` doesn't fit either GRAM/KG or ML/LITRE (e.g. spoken KG against a PACKET-priced
item) — that mismatch has no correct conversion and falls through to `factor = 1` unchanged, same
as today, which is why §B.2's magnitude ceiling exists as the second line of defense for exactly
that residual case.

Together, §A and §B are deliberately layered: §A stops a fabricated quantity/unit from being
reported as confident in the first place; §B makes the arithmetic correct even if a segment does
legitimately carry a genuine unit mismatch. Neither replaces the other.

---

## §G — Open questions (stop and ask; do not guess)

1. **§A.7 — `SPLIT_UNIT_TRUST_NORM = 0.15`** is a reasoned default, not a measured one (no labeled
   corpus to tune against). Ship it, then watch real traffic for a week — specifically for
   legitimate two-token orders that start getting wrongly flagged as `AMBIGUOUS` — before treating
   it as final.
2. **§B.2's ceiling values** (₹50,000 SALE / ₹500,000 STOCK) — confirm these fit this shop's real
   business, given चांदी legitimately prices at ₹412,000/kg.
3. **VoiceParser.kt call site** — confirm `defaultCatalogUnit` is already populated with the real
   matched catalog unit at the call site, not a placeholder/guess. If it isn't, that's a
   prerequisite fix before §B.1.1's client change does anything.
4. Deploy of `index.ts`/`phonetic.ts`/`price_intent.ts` is pre-authorized per CLAUDE.md once
   verified — no need to ask before deploying, only before choosing the tunable constants (§A.7,
   §B.2).
