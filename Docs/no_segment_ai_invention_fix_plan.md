# ISSUE-105 — ISSUE-104's guard is disarmed when the segmenter produces *zero* segments

**Status:** plan, not yet implemented
**Trace:** job `7516c666-6950-4c6a-9a80-ed194bd416a8`, 2026-08-08 18:19:30 UTC
**Spoken:** "do kilo seeeee" (deliberate gibberish) → review card reads **Seb · 2 KG**, `item_id`
bound to the real catalog row `5967018d-eac8-46eb-9123-1ff04397f455`
**Relationship to ISSUE-104:** this is that plan's own §6 scenario 2 and Open Question 3, which I
deferred rather than folded in. It fired one day after ISSUE-104 shipped. The fix belongs inside
`assessAiNameEvidence`; no new concept is introduced.

---

## 1. What actually happened (verified — code read + live queries + probe over prod corpus)

**First: the `step_3` block in this trace is not segmenter output.** [index.ts:2221](../supabase/functions/process-voice-job/index.ts:2221):

```ts
segments: step3Segments.length > 0 ? step3Segments : finalParsedItems.map(item => ({
  rawSegmentText: `${item.quantity} ${item.unit} ${item.item_name}`,
```

When the real segmenter returns **zero** segments the trace substitutes a synthetic echo of the AI's
own answer. `rawSegmentText: "2 KG Seb"` is literally `${quantity} ${unit} ${item_name}`. The tell is
the missing fields — no `resolutionKind`, no `heardSegmentText`, no `top3Candidates`, no
`hasLeadingQty` — every one of which the real `closeSegment()` writes. Corroborated independently by
`step_4_fast_path.skipReason: "no_segments"`.

**Anyone debugging this reads the trace as "the segmenter matched Seb". It did not. It matched
nothing.** Fixing that display is Step 4 below.

### The chain

| Stage | Result |
|---|---|
| STT (both engines, all 3 re-decode passes) | `"दो किलो से"` — `से` is a real Hindi postposition ("from") |
| ISSUE-103 `DISCOURSE_PARTICLES` | contains `से` → token dropped → **0 segments**. *Working as designed.* |
| `alignSegmentsToItems` ([index.ts:604](../supabase/functions/process-voice-job/index.ts:604)) | empty `segments` → `bestIdx === -1` → returns **`null`** for the item |
| `assessAiNameEvidence` ([item_resolution.ts:153](../supabase/functions/process-voice-job/item_resolution.ts:153)) | `if (!seg \|\| …) return { uncorroborated: false }` → **guard inert** |
| `findCatalog("Seb")` | binds catalog row `5967018d…` |
| Grok self-reported confidence | **0.85** |

The trace records the bail-out verbatim:
`"ai_evidence":{"ratio":null,"heardSurface":"","heardPhones":0,"aiPhones":3,"uncorroborated":false}`.
An empty `heardSurface` with a non-zero `aiPhones` is the signature of this bug.

### Why "aaa" was caught and "seeee" was not

`आ` is **not** a discourse particle → the segmenter emitted a segment with `resolutionKind: UNKNOWN`
→ ISSUE-104's guard was armed and fired. `से` **is** a particle → the segmenter emitted nothing →
the guard was never armed.

> **ISSUE-103's stoplist disarms ISSUE-104's guard.** Every particle added to that list moves more
> inputs from the protected path to the unprotected one. The two fixes are coupled in the wrong
> direction, and that coupling is the actual defect.

### What stopped the booking — and it was not a safety mechanism

**Verified by query:** `Seb` has `price = 0`. The line's *only* implausibility reason was
`'Seb' has no price in your catalog — set a rate`, and that reason alone is what capped the AI's
0.85 → `IMPLAUSIBLE_CONFIDENCE_CAP` 0.55.

**Give Seb a price and the reason disappears, the confidence stays 0.85, it clears the 0.80 gate,
and 2 KG of apples auto-books from a nonsense sound.** 121 of this shop's 131 catalog rows have a
price. Seb being one of the 10 unpriced rows is the only reason this is a review card and not a
ledger entry.

---

## 2. Blast radius (verified)

**Live, since 2026-08-01** (`stt_job_logs`, n = 181 jobs):

| Measure | Count |
|---|---|
| jobs where the segmenter produced nothing (`skipReason = 'no_segments'`) | **1** (this job) |
| …of those, `AUTO_CONFIRMED` | 0 |
| …of those, saved *only* by the matched item having no price | **1** |
| items with no aligned segment at all (`item_match_norm IS NULL`) | 10 |

**Probe over the full production transcript corpus** — the real `segmentTranscript` run against this
shop's real 76-name catalog, all 182 distinct `raw_transcript` values ever recorded:

```
182 transcripts -> 174 produce >= 1 segment (rule never evaluated for them)
                 ->   8 produce ZERO segments:
      "दो किलो से"        "दो किलो हाँ"     "हम्म"          "हाँ"
      "तो ये है"          "हाँ जी हाँ जी"    "हाँ हाँ हाँ"    "दो किलो हाँ"
```

**All 8 are noise. There is no legitimate transcript in the entire corpus that produces zero
segments.** That is what makes this fix safe: the branch it touches is only ever reached by input
the deterministic stage has already rejected in full.

---

## 3. The rule

> When the segmenter produced **no segments at all**, reconstruct the item surface from the
> transcript. If what remains after removing quantity, unit, price and pure discourse is
> **≤ 2 phones**, nothing identifiable was said and the AI's name is invention.

Zero segments is *weaker* evidence than `resolutionKind: UNKNOWN`, not absent evidence — UNKNOWN
means "an item slot was found but not recognised", zero segments means "no item slot was found at
all". The current code treats the weaker signal as a pass.

### Calibration (probe output, real code)

Two residue definitions were measured. **Variant B is the recommendation.**

| Residue strips… | fires | misses |
|---|---|---|
| **A** — numbers, units, rupee words | 5/8 | `"तो ये है"` (3 phones), `"हाँ जी हाँ जी"` (8), `"हाँ हाँ हाँ"` (6) — **repetition inflates the residue past the floor** |
| **B** — the above **+ discourse particles**, with the catalog escape hatch | **8/8**, every one at `phones = 0` | none |

Variant A's miss is disqualifying: saying "हाँ" four times must not buy protection that saying it
once does not. Variant B mirrors `segmentTranscript`'s own token filter
([phonetic.ts:583](../supabase/functions/process-voice-job/phonetic.ts:583)) exactly, **including its
escape hatch** — a token in `DISCOURSE_PARTICLES` is kept if it is a real item surface, so a shop
stocking a particle-shaped name is unaffected. Verified:

```
"घी"  -> kept   (inCatalog=true)      "हाँ" -> stripped (inCatalog=false)
"आम"  -> kept   (inCatalog=true)      "से"  -> stripped (inCatalog=false)
```

**False positives across the whole corpus: 0.** The `≤ 2` floor is not even load-bearing on real
data (every fire lands at 0 phones); it is headroom for a single stray non-particle syllable, and
matches the minimum `phoneticKey` length of any real product (2 — `आम`→`AN`, `घी`→`KI`).

---

## 4. Implementation steps

**Server only.** Verified again this session: `BackgroundSttProcessor.kt` and `SttWorker.kt` contain
no segmenter/parser imports; the client only reads server results.

### Step 1 — residue extractor, `supabase/functions/process-voice-job/item_resolution.ts`

Append. Extend the existing `phonetic.ts` import to add `UNIT_SET`, `HINDI_NUMBER_MAP`,
`RUPEE_WORDS`, `DISCOURSE_PARTICLES`.

```ts
/**
 * Item sounds that remain once quantity, unit, price and pure discourse are accounted for.
 *
 * Mirrors segmentTranscript's own token filter (phonetic.ts:583) including its escape hatch:
 * a DISCOURSE_PARTICLES token is KEPT when it is a genuine item surface, so a shop that stocks
 * a particle-shaped name is unaffected. Verified over all 182 production transcripts: the 8 that
 * segment to nothing all reduce to "" here, and no legitimate transcript reaches this path.
 * See ISSUE-105.
 */
export function transcriptItemResidue(transcript: string, itemSurfaces: Set<string>): string {
  const clean = (transcript || '').replace(/।/g, ' ').replace(/[.,?!\-\\()]/g, ' ').trim()
  return clean.split(/\s+/).filter(Boolean).filter(t => {
    const lower = t.toLowerCase()
    if (itemSurfaces.has(lower)) return true          // a real product outranks every strip rule
    if (RUPEE_WORDS.has(lower)) return false
    if (UNIT_SET.some(u => u.toLowerCase() === lower)) return false
    if (HINDI_NUMBER_MAP[lower] !== undefined) return false
    if (/^\d+(\.\d+)?$/.test(lower)) return false
    if (DISCOURSE_PARTICLES.has(lower) || DISCOURSE_PARTICLES.has(t)) return false
    return true
  }).join(' ')
}

/**
 * At or below this many phones, nothing identifiable was said. Set at 2 because the shortest
 * real product key in DEFAULT_ITEM_VOCAB is 2 phones (आम -> AN, घी -> KI) — and such an item
 * would have produced a segment, so it never reaches this branch. See ISSUE-105.
 */
export const MAX_UNIDENTIFIABLE_RESIDUE_PHONES = 2
```

### Step 2 — arm the guard on the zero-segment branch

Add `source` to the verdict interface so the trace says *which* rule ran:

```ts
export interface AiEvidenceVerdict {
  uncorroborated: boolean
  ratio: number | null
  heardSurface: string
  heardPhones: number
  aiPhones: number
  source: 'segment' | 'transcript_residue' | 'none'   // NEW
}
```

Replace the body of `assessAiNameEvidence` (item_resolution.ts:144-158). **Keep the existing
signature working** — the third parameter is optional so no other caller breaks:

```ts
export function assessAiNameEvidence(
  aiName: string,
  seg: Pick<RawItemSegment, 'itemTokens' | 'resolutionKind'> | null | undefined,
  noSegmentContext?: { transcript: string; itemSurfaces: Set<string>; segmentCount: number }
): AiEvidenceVerdict {
  const aiPhones = phoneticKey((aiName || '').trim()).length

  // Path 1 (ISSUE-104): a segment exists but resolved to nothing recognisable.
  if (seg && seg.resolutionKind === 'UNKNOWN' && aiPhones > 0) {
    const heardSurface = (seg.itemTokens || []).join(' ').trim()
    const heardPhones = phoneticKey(heardSurface).length
    const ratio = heardPhones / aiPhones
    return {
      heardSurface, heardPhones, aiPhones, ratio,
      uncorroborated: ratio < MIN_AI_EVIDENCE_RATIO,
      source: 'segment',
    }
  }

  // Path 2 (ISSUE-105): the segmenter produced NOTHING. That is weaker evidence than UNKNOWN,
  // not absent evidence — so measure what the transcript actually carried. Scoped to
  // segmentCount === 0: when other segments DID resolve, the whole-transcript residue belongs
  // to those siblings and would wrongly clear this item.
  if (!seg && noSegmentContext && noSegmentContext.segmentCount === 0 && aiPhones > 0) {
    const heardSurface = transcriptItemResidue(noSegmentContext.transcript, noSegmentContext.itemSurfaces)
    const heardPhones = phoneticKey(heardSurface).length
    return {
      heardSurface, heardPhones, aiPhones,
      ratio: heardPhones / aiPhones,
      uncorroborated: heardPhones <= MAX_UNIDENTIFIABLE_RESIDUE_PHONES,
      source: 'transcript_residue',
    }
  }

  const heardSurface = seg ? (seg.itemTokens || []).join(' ').trim() : ''
  return {
    heardSurface,
    heardPhones: phoneticKey(heardSurface).length,
    aiPhones,
    ratio: null,
    uncorroborated: false,
    source: 'none',
  }
}
```

### Step 3 — pass the context at the call site

At [index.ts:1768](../supabase/functions/process-voice-job/index.ts:1768):

```ts
const aiEvidence = assessAiNameEvidence(rawName, alignedSeg, {
  transcript: chosenRaw,
  itemSurfaces: aiEvidenceItemSurfaces,
  segmentCount: step3Segments.length,
})
```

Build `aiEvidenceItemSurfaces` **once**, outside the `parsedRawItems.map(...)` at
[index.ts:1718](../supabase/functions/process-voice-job/index.ts:1718) — not per item. Use
`fullCatalogList` ([index.ts:1134](../supabase/functions/process-voice-job/index.ts:1134)), which is
already in scope and is the same list `segmentTranscript` receives:

```ts
const aiEvidenceItemSurfaces = new Set(
  [...DEFAULT_ITEM_VOCAB, ...fullCatalogList]
    .filter(n => n && n.trim())
    .map(n => n.trim().toLowerCase())
)
```

**Everything downstream already works** — the reason text (index.ts:1862), the 0.30 confidence floor
(index.ts:1950), the `"Unrecognized Item"` rename (index.ts:1953) and the catalog-bind refusal
(index.ts:1771) all key off `aiEvidence.uncorroborated` and need no change.

One wording fix at index.ts:1862-1866: the existing message reads *"only 'X' was audible"*, which
renders as `only '' was audible` when the residue is empty. Make it read correctly for both paths:

```ts
if (aiEvidence.uncorroborated) {
  const heard = aiEvidence.heardSurface
    ? `only '${aiEvidence.heardSurface}' was audible (${aiEvidence.heardPhones} sound${aiEvidence.heardPhones === 1 ? '' : 's'})`
    : `no item name was audible at all`
  implausibility = appendReason(implausibility, `couldn't make out the item — ${heard}, too little to confirm '${rawName}'`)
}
```
(keep the existing inline `implausibility = implausibility ? … : …` idiom if `appendReason` does not
already exist — do **not** add a helper the rest of the file does not use.)

### Step 4 — stop the trace from lying about the segmenter

At [index.ts:2221](../supabase/functions/process-voice-job/index.ts:2221), the synthetic-segment
fallback is what made this job read as "the segmenter matched Seb". Keep the fallback (the Diagnostic
Logs screen depends on a non-empty array) but label it:

```ts
step_3_deterministic_ordering_segmenter: {
  carryoverQty: null,
  engine: 'phonetic_grammar_lattice_v2',
  // TRUE when `segments` below is NOT segmenter output but a synthetic echo of the AI's own
  // answer, rendered so the logs screen has rows. A reader who misses this concludes the
  // segmenter matched the item when it matched nothing at all. See ISSUE-105.
  segmentsAreSyntheticFromAi: step3Segments.length === 0,
  segments: step3Segments.length > 0 ? step3Segments : finalParsedItems.map(item => ({ … })),
}
```

Add `ai_evidence.source` to the item trace object at
[index.ts:1971](../supabase/functions/process-voice-job/index.ts:1971) alongside the existing fields.

### Step 5 — tests (`item_resolution_test.ts`)

Run: `npx deno test --allow-read supabase/functions/process-voice-job/item_resolution_test.ts`

1. **The reported job.** `assessAiNameEvidence('Seb', null, {transcript:'दो किलो से', itemSurfaces:<set without से>, segmentCount:0})`
   → `uncorroborated === true`, `source === 'transcript_residue'`, `heardPhones === 0`.
2. **Repetition does not buy protection.** Same call with `transcript:'हाँ जी हाँ जी'` →
   `uncorroborated === true`. This is the case Variant A missed — do not delete this test.
3. **Escape hatch.** `transcript:'दो किलो घी'`, `itemSurfaces` containing `घी`, `segmentCount:0`,
   `aiName:'Ghee'` → residue `'घी'`, `heardPhones === 2`… **and therefore still fires at the ≤2
   floor.** *This is the one real cost of the rule* — see Open Question 1. Assert current behaviour
   explicitly so the trade-off is visible in the suite rather than discovered in production.
4. **Scoped to zero segments.** `segmentCount: 1` with `seg: null` → `uncorroborated === false`,
   `source === 'none'` — a partially-aligned multi-item utterance is untouched.
5. **ISSUE-104 unchanged.** Re-run the four existing `assessAiNameEvidence` tests unmodified; all
   must still pass with `source === 'segment'`.
6. **Corpus regression.** Add a test that runs `segmentTranscript` over a fixture of the 8
   zero-segment transcripts plus 10 known-good ones and asserts 8 fire / 10 do not.

### Step 6 — audit + deploy

- Add **ISSUE-105** to `Docs/audit.md` §2 under 🟢 RESOLVED (highest existing is ISSUE-104), in the
  established Symptom / Root Cause / Resolution / Verification Date format. Cross-reference it from
  ISSUE-104's entry as the completion of that issue's Open Question 3 — do **not** leave 104 reading
  as if it closed the class.
- Add `MAX_UNIDENTIFIABLE_RESIDUE_PHONES = 2` to §1 "Ground-Truth Source-Code Verified Constants".
- Deploy (standing authorization — do not ask):
  ```bash
  npx supabase functions deploy process-voice-job --project-ref lyowklxsbfznnqridtgr
  ```
- Re-fetch the live bundle and grep for `transcriptItemResidue` and `segmentsAreSyntheticFromAi` to
  confirm the deploy carried the change.

---

## 5. Verification — by effect, not by build

1. **Re-speak the failing input** ("do kilo seeeee"), then:
   ```sql
   SELECT job_id, raw_transcript, status,
          diagnostic_trace_json::jsonb #>> '{step_4_grok_ai_interpretation,0,item_name}' AS final_name,
          diagnostic_trace_json::jsonb #>> '{step_4_grok_ai_interpretation,0,item_id}'   AS item_id,
          diagnostic_trace_json::jsonb #>  '{step_4_grok_ai_interpretation,0,ai_evidence}' AS evidence,
          diagnostic_trace_json::jsonb #>> '{step_3_deterministic_ordering_segmenter,segmentsAreSyntheticFromAi}' AS synthetic
   FROM stt_job_logs WHERE created_at > now() - interval '10 minutes' ORDER BY created_at DESC;
   ```
   **Pass =** `final_name` is `Unrecognized Item`, `item_id` is **NULL**,
   `evidence.source` is `transcript_residue`, `evidence.uncorroborated` is `true`,
   `synthetic` is `true`. If no row recorded after the change exists, say the verification did not
   happen — do not report success.
2. **Also re-speak "do kilo aaaaaaa"** and confirm ISSUE-104's path still fires with
   `evidence.source === 'segment'`. This change rewrites the function 104 depends on.
3. **Regression, the expensive direction:** book genuine `"दो किलो आलू"`, `"पाँच किलो अमचूर"` and
   `"दो किलो घी"` and confirm each still resolves to the right item at the right price. `घी` is the
   specific one at risk (Open Question 1).
4. **Set a price on `Seb`** (`5967018d-eac8-46eb-9123-1ff04397f455`, currently ₹0) and re-run test 1.
   Until that is done, a pass proves nothing — the ₹0 alone would have produced the same status.

---

## 6. Instance vs. class

- **This eliminates the instance** (`से` → Seb) and the whole zero-segment family: every one of the
  8 noise transcripts in the production corpus now refuses to name a product.
- **It does not eliminate the class.** The class is *"the AI stage may name a product the
  deterministic stage never saw."* Surviving scenarios, named so they are not treated as cleared:
  1. **Partial alignment.** `segmentCount > 0` but this item aligned to nothing — Step 2 deliberately
     scopes itself out of this case. **9 of the 10 no-aligned-segment items since 2026-08-01 are
     here**, not in the zero-segment case this plan fixes. `0` were auto-confirm eligible, so it is
     currently harmless; it is harmless by luck.
  2. **Equal-length invention** (ISSUE-104 §6.1) — `हाँ`→`Aam` at ratio 1.00 passes the *segment*
     path. It is caught here only because `हाँ` produces zero segments and is a listed particle. An
     unlisted 2-phone homophone remains unguarded on both paths.
  3. **Long garbage.** `"Dua Gluvenin 10 ribu."` → Paneer ₹720 (historical, `05175ce5`). The residue
     is long, so this rule passes it. Nothing in this plan addresses invention from *plentiful*
     nonsense — only from absent evidence.
- The deepest cause is unchanged and is not attempted here: matching and naming are both
  forced-choice (`open-vocabulary-architecture.md` §1.2). Each of ISSUE-103/104/105 is a patch on a
  different entry point to the same missing "abstain" outcome. **This is the third consecutive
  instance-level fix in that class** — the architectural answer is an abstain-capable matcher plus a
  live `term_aliases` read path, and it is worth costing that out before an ISSUE-106 of the same
  shape arrives.

---

## 7. Open questions (stop and ask rather than guessing)

1. **The `≤ 2` floor blocks a genuine 2-phone item on the zero-segment path.** If STT ever renders
   `घी`/`आम`/`हींग`/`उड़द` in a form the segmenter cannot match, the residue is 2 phones and the line
   is refused → one review tap. **Measured cost across 182 production transcripts: zero occurrences.**
   I recommend accepting it (a tap is cheap, a silent mis-book is not — the doctrine ISSUE-103
   settled on). Confirm, or lower the floor to 1 and accept `से`-class inputs leaking through?
2. **Should scenario 1 above (partial alignment) be folded in now?** It is the larger population
   (9 items vs 1). Doing it needs a per-item residue — attributing transcript spans to individual
   items — which is materially harder than this plan and could regress multi-item utterances. I have
   **not** measured it. Separate issue, or expand scope here?
3. **`Seb` is priced ₹0**, along with 9 other rows in this shop's catalog. That ₹0 is what masked
   this bug's severity. Should unpriced catalog rows be excluded from AI catalog-binding entirely
   (they can never book anyway), or is the current "bind, then explain via `unpricedLineReason`"
   behaviour deliberate? This is out of scope here but touches the same call path.
