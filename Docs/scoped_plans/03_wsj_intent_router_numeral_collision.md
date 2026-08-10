# Scoped plan 3/11 — WS-J: Intent router numeral collision (ISSUE-120)

**Scope:** this file ONLY. Do not read or implement any other section of any other plan.
**Mirrored change — BOTH sides must change together** (CLAUDE.md mirror rule):
- `supabase/functions/process-voice-job/intent_router.ts` (server — this is where the observed bug fired)
- `app/src/main/java/com/voicetoinvoice/app/domain/router/IntentRouter.kt` (client mirror)

---

## 1. The bug, with live evidence

Three plain cash sales were classified `ACTION_COMMAND` and **routed to review instead of booked**:

```
54e7fe50  "चार किलो चाच"   ACTION_COMMAND 0.5263157894736842  runnerUp SALE 0.9  routedToReview: true
8430fe59  "चार किलो आलू"   ACTION_COMMAND 0.5263157894736842  runnerUp SALE 0.9  routedToReview: true
467ea9d5  "चार किलो गोल्ड"  ACTION_COMMAND 0.5263157894736842  runnerUp SALE 0.9  routedToReview: true
```

All three begin **"चार"** (four).

### Root cause — verified empirically, not inferred

Ran against the real `phonetic.ts` via Deno on 2026-08-10:

```
"चार"      -> CAL          "call"       -> CAL        d=0.0000  quality=1.000   <-- EXACT COLLISION
"chaar"    -> CAL          "call"       -> CAL        d=0.0000  quality=1.000
"char"     -> CAL          "call"       -> CAL        d=0.0000  quality=1.000
"चारकिलो"   -> CALAKILO     "call karo"  -> CALKALO    d=0.1250  quality=0.500   <-- SECOND COLLISION
```

`'call'` is an `ACTION_COMMAND` trigger at weight 1.0. So:
- `ACTION_COMMAND` = 1.0 × 1.0 = **1.0**
- `SALE` = `BASE_SALE_SCORE` = **0.9** (goods present, no keyword)
- confidence = 1.0 / (1.0 + 0.9) = **0.5263157894736842** — reproduces the trace value exactly.

Above `ARBITRATION_FLOOR` (0.45), so it returns `ACTION_COMMAND` rather than falling through to UNKNOWN.

### Why the threshold cannot be tuned

The distance is **0.0000**. Any `MAX_TRIGGER_DISTANCE` above zero admits it. Raising the bar for short triggers does **not** fix this — do not attempt that approach.

---

## 2. The fix — exclude quantity-only spans from trigger matching

A span of words consisting **only** of numerals and/or units can never establish an intent. No trigger phrase in either lexicon is a number or a unit, so this is safe by construction.

Filter at **n-gram construction time**, not at match time — the bigram collision (`चारकिलो` vs `call karo`) is only detectable when you still know which words composed the span.

### J1 — Server: `supabase/functions/process-voice-job/intent_router.ts`

Extend the existing import (line 17) to pull in the vocabularies already exported by `phonetic.ts`:

```typescript
import { phoneticKey, normalizedDistance, HINDI_NUMBER_MAP, UNIT_SET } from './phonetic.ts'
```

Add above `buildNgramKeys` (line 202):

```typescript
/**
 * Phone keys of every numeral and unit surface.
 *
 * ISSUE-120: "चार" (four) keys to CAL, which is EXACTLY the key of the ACTION_COMMAND
 * trigger "call" -- normalized distance 0.0000, quality 1.000. That scored ACTION_COMMAND
 * 1.0 against SALE's 0.9 baseline and sent three plain cash sales to review instead of
 * booking them (traces 54e7fe50, 8430fe59, 467ea9d5, all opening "चार किलो"). The bigram
 * "चारकिलो" collides with "call karo" at 0.125 independently.
 *
 * Threshold tuning cannot fix a 0.0000 distance. No trigger phrase in this lexicon is a
 * number or a unit, so a span made only of those can be excluded outright.
 */
const QUANTITY_KEYS: Set<string> = new Set(
  [...Object.keys(HINDI_NUMBER_MAP), ...UNIT_SET]
    .map(w => phoneticKey(w))
    .filter(k => k.length > 0)
)

/** True when a word is a bare number, a number word, or a unit. */
function isQuantityToken(word: string): boolean {
  const lower = word.toLowerCase().trim()
  if (lower.length === 0) return true
  if (!Number.isNaN(Number(lower))) return true
  const key = phoneticKey(lower)
  return key.length > 0 && QUANTITY_KEYS.has(key)
}
```

Replace the body of `buildNgramKeys` (lines 203-219) so every span is skipped when **all** of its words are quantity tokens:

```typescript
function buildNgramKeys(transcript: string): string[] {
  const words = transcript.split(/\s+/).filter(w => w.length > 0)
  const isQty = words.map(isQuantityToken)
  const keys = new Set<string>()
  for (let i = 0; i < words.length; i++) {
    if (!isQty[i]) {
      const uni = phoneticKey(words[i])
      if (uni) keys.add(uni)
    }
    if (i + 1 < words.length && !(isQty[i] && isQty[i + 1])) {
      const bi = phoneticKey(words[i] + words[i + 1])
      if (bi) keys.add(bi)
    }
    if (i + 2 < words.length && !(isQty[i] && isQty[i + 1] && isQty[i + 2])) {
      const tri = phoneticKey(words[i] + words[i + 1] + words[i + 2])
      if (tri) keys.add(tri)
    }
  }
  return Array.from(keys)
}
```

### J2 — Client: `app/src/main/java/com/voicetoinvoice/app/domain/router/IntentRouter.kt`

Same change, same reasoning. The Kotlin vocabularies live on `OrderingSegmenter` (`HINDI_NUMBER_MAP`, `UNIT_SET` — both already `internal`/accessible in the same module).

Add to the `IntentRouter` object's private members (near `RETURN_STRONG_KEYS`, line ~322):

```kotlin
/**
 * Phone keys of every numeral and unit surface. See ISSUE-120 — "चार" keys to CAL,
 * identical to the ACTION_COMMAND trigger "call" (verified distance 0.0000), which routed
 * three plain cash sales to review instead of booking them. Threshold tuning cannot fix a
 * zero distance; no trigger phrase is a number or a unit, so quantity-only spans are excluded.
 */
private val QUANTITY_KEYS: Set<String> =
    (OrderingSegmenter.HINDI_NUMBER_MAP.keys + OrderingSegmenter.UNIT_SET)
        .map { PhoneticKey.of(it) }
        .filter { it.isNotBlank() }
        .toSet()

/** True when a word is a bare number, a number word, or a unit. */
private fun isQuantityToken(word: String): Boolean {
    val lower = word.lowercase().trim()
    if (lower.isEmpty()) return true
    if (lower.toDoubleOrNull() != null) return true
    val key = PhoneticKey.of(lower)
    return key.isNotBlank() && key in QUANTITY_KEYS
}
```

Add the import if not already present: `import com.voicetoinvoice.app.domain.parser.OrderingSegmenter`.

Replace `buildNgramKeys` (lines 251-266) with the same all-quantity-span skip:

```kotlin
private fun buildNgramKeys(transcript: String): List<String> {
    val words = transcript.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    val isQty = words.map { isQuantityToken(it) }
    val keys = LinkedHashSet<String>()
    for (i in words.indices) {
        if (!isQty[i]) {
            PhoneticKey.of(words[i]).takeIf { it.isNotBlank() }?.let { keys.add(it) }
        }
        if (i + 1 < words.size && !(isQty[i] && isQty[i + 1])) {
            PhoneticKey.of(words[i] + words[i + 1]).takeIf { it.isNotBlank() }?.let { keys.add(it) }
        }
        if (i + 2 < words.size && !(isQty[i] && isQty[i + 1] && isQty[i + 2])) {
            PhoneticKey.of(words[i] + words[i + 1] + words[i + 2]).takeIf { it.isNotBlank() }
                ?.let { keys.add(it) }
        }
    }
    return keys.toList()
}
```

**Do not change** `MAX_TRIGGER_DISTANCE`, `BASE_SALE_SCORE`, `ARBITRATION_FLOOR`, `DIRECT_ROUTE_CONFIDENCE`, or any trigger phrase list. The collision is structural, not a tuning problem.

### J3 — Regression tests, both sides

`supabase/functions/process-voice-job/intent_router_test.ts` — add cases asserting `SALE` (not `ACTION_COMMAND`) for, with one item line present (`[{item_name:'आलू', quantity:4}]`):
- `"चार किलो आलू"`
- `"चार किलो चाच"`
- `"chaar kilo aloo"`

And assert the genuine action command still classifies as `ACTION_COMMAND` with **no** item lines:
- `"रमेश को बिल भेजो"`
- `"ramesh ko call karo"`  ← this must still work; it is the reason `call` is in the lexicon

Mirror the same five cases into the client fixture (`IntentRouterFixtureTest` / `app/src/test/.../IntentRouterTest.kt` — use whichever already exists; the file header of `intent_router.ts` says both sides run a shared 60-phrase fixture, so add to that fixture rather than creating a new file).

### J4 — Deploy the server side

```bash
npx supabase functions deploy process-voice-job --project-ref lyowklxsbfznnqridtgr
```

---

## 3. Bug class statement (required before closing)

State explicitly in the audit entry which of these is true:

- This eliminates the **class** ("a quantity word can establish an intent") because quantity-only spans are now structurally excluded from trigger matching everywhere, rather than one phrase being retuned.
- Remaining exposure: a **non-quantity** word that collides with a trigger is still possible (e.g. an item name keying onto a trigger). That is a different class and is **not** fixed here — say so rather than implying full coverage.

## 4. Verification (by effect, not by build)

1. `npx --no-install deno test supabase/functions/process-voice-job/intent_router_test.ts` passes.
2. `./gradlew.bat test --tests "*IntentRouter*"` passes.
3. After deploy, record **"चार किलो आलू"** on the phone and query:
   ```sql
   SELECT job_id, raw_transcript, status,
          substring(diagnostic_trace_json from '"step_2b_intent_classification":\{[^}]*\}') AS intent
   FROM stt_job_logs ORDER BY created_at DESC LIMIT 3;
   ```
   Pass = `"intent":"SALE"`, `bookedServerSide: true`, `routedToReview: false`.
   **No new row = verification did not happen — say so, do not report success.**

## 5. Audit log

Add a 🟢 RESOLVED entry for **ISSUE-120** in `Docs/audit.md`'s single `### 🟢 RESOLVED ISSUES` section (there is exactly one such section — do not create another). Include the measured collision table above, the bug-class statement, and state plainly what was verified vs. what was not.

## Deviations

End with a "Deviations" section. If none, say "None."
