# Fragmented Hindi Compound Numeral Fix Plan (ISSUE-106)

**Symptom:** shopkeeper said "तैंतीस किलो आलू" (33 kg potato). Ledger booked **30 kg**, auto-confirmed, ₹1500.
**Job:** `44c8edd4-dbe2-42cb-9ac1-db316cd651c4`, 2026-08-08 19:00:39 UTC, `capture_intent: STOCK_IN`, `status: AUTO_CONFIRMED`.

> **Revision note.** An earlier draft of this plan gated the fix on "the second token must be an exact tens anchor (बीस/तीस/…)". That design was **measured against the full bug class and rejected: it fixes 10 of 185 cases.** Hindi compound numerals are not morphologically regular — only 27 of 72 contain their tens anchor (52 is बावन, 56 is छप्पन, 92 is बानवे). The design below drops that gate. See §3 for the measurements.

---

## 1. Evidence log — verified / inferred / assumed

| # | Claim | Status |
|---|---|---|
| E1 | DB row: `raw_transcript = "ते तीस किलो आलू"`, `parsed_qty = 30`, `status = AUTO_CONFIRMED`. | **verified** — `stt_job_logs` query. |
| E2 | The server pipeline ran (not a client-only fallback row). | **verified** — `trace_len = 4231` bytes vs the ~186-byte client-only signature. |
| E3 | Sarvam returned `"ते तीस किलो आलू"` and won the race (7 vs Grok's 6 for `"त्यातीत किलो आलू"`). Both engines failed. | **verified** — `step_2_stt_proxy_response`. |
| E4 | `'तैंतीस': 33` is **already in** `HINDI_NUMBER_MAP`; all 72 compound numerals 21–99 are present, none missing. | **verified** — `phonetic.ts:260`; enumerated the map programmatically. |
| E5 | The lattice decoder consumes **exactly one source token per position**. It can split a fused token (`splitExpansions`) but has no merge counterpart. | **verified** — `decode()` at `phonetic.ts:808`; `dp`/`back`/`chosen` are all `tokens.length`-shaped. |
| E6 | Only **27 of 72** compound numerals have a phonetic key ending in their tens anchor's key. Hindi numerals are morphologically irregular. | **verified** — computed over the repo's own map. |
| E7 | Under production semantics, **185 of 223** simulated fragmentations silently book a wrong quantity. Baseline correct: 31. | **verified** — ran the repo's real `segmentTranscript` over every split point of all 72 numerals. |
| E8 | With realistic *lossy* fragments (anusvara dropped, ै→े etc. — the drift that actually produced `तैं`→`ते`): baseline is **10 correct, 189 silently wrong** of 210. | **verified** — same harness with phonetic perturbations. |
| E9 | A surplus segmenter segment is **silently discarded** by `alignSegmentsToItems`, and the surviving item still auto-confirms. This is why the orphan `{"ते" → दही, AMBIGUOUS}` segment produced no warning. | **verified** — `index.ts:594`; matches the observed trace (2 segments in `step_3`, 1 item in `step_6`, `AUTO_CONFIRMED` at 0.9). |
| E10 | The `keyterms` list sent to STT contains **zero Hindi number words** despite the code intending to send them — `fullCatalogList` (≈90) + `DEFAULT_ITEM_VOCAB` (220) exhaust the `.slice(0, 100)` first. | **verified** — counted source arrays; trace shows `catalogKeytermsSentCount: 100`, `catalogItemsFetched: 69`. |
| E11 | Fixing the keyterm budget will stop Sarvam fragmenting numerals. | **assumed — NOT verified.** Keyterm bias is a soft hint. Step 4 is best-effort; nothing else depends on it. |

**What would disprove the diagnosis:** if the segmenter had a merge path, or if `तैंतीस` were absent from the map. Both checked directly (E4, E5). The competing "bad audio" explanation is ruled out by E3 — both engines returned a well-formed four-word phrase with unit and item correct; only the numeral fragmented.

---

## 2. Root cause

**Bug class:** *Hindi compound numerals 21–99 are single spoken words that STT frequently emits as two tokens, and no layer of the pipeline can rejoin them.* All 72 are exposed (E7, E8).

Four layers failed independently:

1. **STT fragmentation.** `तैंतीस` → `ते` + `तीस`. The number words meant to bias against this never reached the STT (E10).
2. **Segmenter cannot merge tokens** — the actual bug (E5). `ते` was stranded, fuzzy-matched to दही at 0.167; `तीस` read as a clean 30.
3. **AI did not repair it.** The Grok prompt covers consonant confusion (rule 6) and token *splitting* (rule 2), but nothing about fragmented numerals. It received the split transcript and returned 30 at confidence 0.9.
4. **Safety net absent.** The orphan `AMBIGUOUS` segment was dropped and the remaining item auto-confirmed (E9).

---

## 3. Why this design — the measurements that chose it

Harness: the repo's own `segmentTranscript`, `phoneticKey`, `phoneticDistance` and `HINDI_NUMBER_MAP`, driven over (a) every split point of all 72 compound numerals, (b) the same set with lossy phonetic perturbation, (c) **170 distinct real production transcripts** pulled from `stt_job_logs` as the false-positive corpus.

### 3a. The rejected design vs the chosen one — clean splits (n=223)

| design | correct, auto-confirm | correct, flagged | **silently wrong** |
|---|---|---|---|
| baseline (today) | 31 | 0 | **185** |
| tens-anchor gate (earlier draft) | 40 | 1 | **175** |
| **no tens gate (chosen)** | 205 | 18 | **0** |

The tens-anchor gate fixes 10 of 185. It is not a solution; it is the reported instance plus rounding.

### 3b. Threshold selection — lossy fragments (n=210) against the real corpus

Recovery on *realistic* fragments is what matters; clean-split recovery is optimistic because rejoining a clean split reproduces the word exactly (distance ≈ 0), while real drift costs ~0.17.

| `MERGE_MAX_NORM` | correct auto | correct flagged | **silently wrong** | **corpus harm** | corpus benefit |
|---|---|---|---|---|---|
| 0.30 | 137 | 42 | 0 | **3** | 5 |
| 0.25 | 137 | 42 | 0 | **1** | 4 |
| **0.22** | **137** | **42** | **0** | **0** | **3** |
| 0.20 | 139 | 34 | **13** | 0 | 2 |
| 0.17 | 143 | 23 | **29** | 0 | 2 |
| 0.12 | 135 | 12 | **55** | 0 | 1 |

**0.22 is a measured optimum with a cliff on both sides**, not a guess:

- Below 0.22 the merge stops firing on lossy fragments and silent mis-bookings return (13 → 55).
- Above 0.22 real transcripts start breaking. At 0.25: `"तो इसमें टमाटर ऐसा"` invents `1000 PACKET`. At 0.30: `"हर्ष दस किलो आलू"` flips **10 → 27** and `"साथ गिलम Jeera"` flips **7 → 57** — exactly the class of corruption this fix exists to prevent.
- At 0.22, **zero** of the 170 real transcripts change harmfully. The 3 that change are improvements: the target bug (30 → 33) and two junk utterances that previously booked garbage sales now book nothing (`"वो वो बी वन टाइम"` was booking `1 PACKET बी वन दही`).

Baseline vs chosen on the lossy set: **10 correct → 179 correct, 189 silently wrong → 0.**

### 3c. Two guards that are load-bearing, not boilerplate

> **⚠️ The item-surface guard.** `दही` + `तीस` merges to 33 at norm **0.143** — *tighter than the actual bug case*. Without it, "दही तीस किलो" (curd, 30 kg — ordinary item-first word order that appears in production as `"आलू बीस किलो"`) has its quantity silently rewritten to 33. **Verified by re-running with the guard removed.** Do not weaken or reorder it.

> **⚠️ The value-margin flag.** A merge can pick the wrong value: `ते` + `ईस` scores 23 and 30 at *identical* distance (तेईस and तीस collapse to the same phonetic key `TIS`). 42 of 179 lossy recoveries land within the margin. Those merges still apply — a plausible number beats stranded debris — but must **not** auto-confirm.

---

## 4. Implementation

Mirrored logic — **changes required on BOTH sides**: `supabase/functions/process-voice-job/phonetic.ts` and `app/src/main/java/com/voicetoinvoice/app/domain/parser/OrderingSegmenter.kt`.

### Step 1 — constants (`phonetic.ts`, after line 570, next to `DISTANCE_TOKEN_ITEM_COST`)

```ts
/** Fragmented-numeral rejoin (ISSUE-106). A Hindi compound numeral 21-99 is ONE spoken
 *  word ("तैंतीस"), but STT routinely emits it as two tokens ("ते" + "तीस"). The lattice
 *  splits fused tokens and has no way to merge fragmented ones, so the leading fragment
 *  became a bogus item and the trailing piece booked as the quantity -- 33 kg went into
 *  the ledger as 30 kg.
 *
 *  0.22 is a measured optimum, not a guess. Below it, merges stop firing on realistically
 *  lossy fragments and silent mis-bookings return (13 at 0.20, 55 at 0.12). Above it, real
 *  transcripts start corrupting ("हर्ष दस किलो आलू" flips 10->27 at 0.30). At 0.22, zero of
 *  170 production transcripts change harmfully. See Docs/fragmented_hindi_numeral_fix_plan.md §3. */
const MERGE_MAX_NORM = 0.22
const MERGE_MIN_VALUE_MARGIN = 0.10
```

### Step 2 — `rejoinFragmentedNumerals()` (`phonetic.ts`, new export after `splitExpansions`, before `interface DecodedToken` at line 786)

```ts
export interface NumeralRejoin {
  leftToken: string
  rightToken: string
  mergedSurface: string
  value: number
  matchNorm: number
  valueMargin: number
  /** True when a different numeric value sat within MERGE_MIN_VALUE_MARGIN of the winner
   *  ("तेईस"=23 and "तीस"=30 collapse to the same phonetic key). The merge still applies --
   *  a plausible number beats stranded debris -- but the quantity must NOT auto-confirm. */
  lowMargin: boolean
}

export function rejoinFragmentedNumerals(
  tokens: string[],
  vocab: SegmenterVocabulary,
  itemSurfaceSet: Set<string>
): { tokens: string[]; rejoins: NumeralRejoin[] } {
  if (tokens.length < 2) return { tokens, rejoins: [] }
  const out: string[] = []
  const rejoins: NumeralRejoin[] = []

  for (let i = 0; i < tokens.length; i++) {
    if (i === tokens.length - 1) { out.push(tokens[i]); continue }

    const left = tokens[i]
    const right = tokens[i + 1]
    const leftLower = left.toLowerCase()

    // The LEFT token must be unrecognized debris. A token that already matches known
    // vocabulary exactly is a real word, not the front half of a broken numeral.
    //
    // itemSurfaceSet is LOAD-BEARING, not defensive boilerplate: "दही"+"तीस" merges to 33
    // at norm 0.143 -- TIGHTER than the bug this fix targets -- so without this line
    // "दही तीस किलो" (curd, 30 kg) silently becomes 33 kg. Verified by removing it.
    if (
      HINDI_NUMBER_MAP[leftLower] !== undefined ||
      /^\d+(\.\d+)?$/.test(leftLower) ||
      UNIT_SET.includes(leftLower) ||
      DISTANCE_UNIT_TOKENS.includes(leftLower) ||
      RUPEE_WORDS.has(leftLower) ||
      itemSurfaceSet.has(leftLower)
    ) { out.push(left); continue }

    const joinedKey = phoneticKey(left + right)
    if (!joinedKey) { out.push(left); continue }

    // Rank by distinct VALUE, not by surface: "तैंतीस" and "taintees" are the same number
    // and must not occupy two candidate slots -- that would fake a tiny margin and
    // wrongly suppress auto-confirm on a confident merge.
    const bestPerValue = new Map<number, { surface: string; value: number; norm: number }>()
    for (const entry of vocab.numbers) {
      if (!entry.key || entry.numericValue === undefined) continue
      const norm = phoneticDistance(joinedKey, entry.key) / Math.max(joinedKey.length, entry.key.length)
      const cur = bestPerValue.get(entry.numericValue)
      if (!cur || norm < cur.norm) {
        bestPerValue.set(entry.numericValue, { surface: entry.surface, value: entry.numericValue, norm })
      }
    }
    const ranked = Array.from(bestPerValue.values()).sort((a, b) => a.norm - b.norm)
    if (!ranked.length) { out.push(left); continue }

    const best = ranked[0]
    if (best.norm > MERGE_MAX_NORM) { out.push(left); continue }

    const valueMargin = ranked.length > 1 ? (ranked[1].norm - best.norm) : 1.0

    out.push(best.surface)
    rejoins.push({
      leftToken: left,
      rightToken: right,
      mergedSurface: best.surface,
      value: best.value,
      matchNorm: best.norm,
      valueMargin,
      lowMargin: valueMargin < MERGE_MIN_VALUE_MARGIN,
    })
    i++ // consume the right token; merges never overlap
  }

  return { tokens: out, rejoins }
}
```

### Step 3 — wire it in (`phonetic.ts`)

3a. `RawItemSegment` (interface at line 490, after `resolutionKind`):
```ts
  /** Quantity came from a low-margin fragmented-numeral rejoin (ISSUE-106) -- best guess,
   *  but a different number scored nearly as close, so it must not auto-confirm. */
  numeralRejoinLowMargin?: boolean
```

3b. `SegmentResult` (line 511): `numeralRejoins?: NumeralRejoin[]`

3c. In `segmentTranscript`, replace the decode call at line 940 with:
```ts
  const { tokens: mergedTokens, rejoins } = rejoinFragmentedNumerals(tokens, vocab, itemSurfaceSet)
  const lowMarginSurfaces = new Set(rejoins.filter(r => r.lowMargin).map(r => r.mergedSurface.toLowerCase()))
  const { decoded, minGap } = decode(mergedTokens, vocab, aliases)
```
`itemSurfaceSet` already exists at line 927 — reuse it, do not build a second one.

3d. At **every** site where a segment is closed and pushed into `segments`, set:
```ts
numeralRejoinLowMargin: currentSegmentTokens.some(t => lowMarginSurfaces.has(t.toLowerCase())),
```

3e. Return `numeralRejoins: rejoins` from every path of `segmentTranscript` that ran the decode.

### Step 4 — reserve keyterm budget for numerals (`index.ts:1136–1141`)

```ts
    // ISSUE-106: the .slice(0, 100) below spent its entire budget on catalog and item
    // vocabulary, so the number words this list intends to send NEVER reached the STT
    // bias set -- which is how "तैंतीस" came back fragmented as "ते तीस". The 21-99
    // compounds are the ones that fragment (tens anchors never do), so they go first.
    const NUMERAL_KEYTERM_BUDGET = 25
    const numeralKeyterms = Object.keys(HINDI_NUMBER_MAP)
      .filter(k => /[\u0900-\u097F]/.test(k))
      .filter(k => { const v = HINDI_NUMBER_MAP[k]; return v >= 21 && v <= 99 && v % 10 !== 0 })
      .slice(0, NUMERAL_KEYTERM_BUDGET)

    const keyterms = Array.from(new Set([
      ...numeralKeyterms,
      ...fullCatalogList,
      ...DEFAULT_ITEM_VOCAB,
      ...UNIT_SET,
    ])).slice(0, 100)
```

**Tradeoff:** this takes 25 slots from item-name bias, and `index.ts:1289` already notes 100 terms "spreads the bias so thin it barely registers". Its effect is **unverified (E11)**. Steps 1–3 do not depend on it — do not skip them on the assumption this fixes things upstream. If item-match quality regresses after deploy, **revert step 4 alone**.

### Step 5 — client mirror (`OrderingSegmenter.kt`)

Port steps 1–3 verbatim:
- Constants `MERGE_MAX_NORM = 0.22`, `MERGE_MIN_VALUE_MARGIN = 0.10` in the companion object next to `WHOLE_TOKEN_MAX_NORM` (line 511).
- `fun rejoinFragmentedNumerals(tokens: List<String>, vocab: SegmenterVocabulary, itemSurfaceSet: Set<String>): Pair<List<String>, List<NumeralRejoin>>` in the companion object.
- Call it at line 745, immediately before `GrammarLatticeDecoder.decode(...)` at line 746. `itemSurfaceSet` already exists at line 738.
- Add `numeralRejoinLowMargin: Boolean = false` to the client's `RawItemSegment`.
- **Note:** the client's `HINDI_NUMBER_MAP` is `Map<String, Double>` — compare against `Double`, not `Int`.

### Step 6 — AI prompt rule (`index.ts`, new rule 11 after line 1453, before `Output ONLY valid JSON`)

```
11. FRAGMENTED NUMBERS — Hindi compound numerals 21-99 are ONE word (तैंतीस = 33,
   बावन = 52, बानवे = 92), but STT often breaks them into two tokens: "ते तीस" is
   तैंतीस (33), NOT 30; "बा वन" is बावन (52); "बान वे" is बानवे (92). When a short
   unrecognizable fragment sits immediately before a token that could complete a
   numeral, read the two together as one number and do NOT emit the fragment as an
   item name. Real failure this rule exists to prevent: "तैंतीस किलो आलू" (33 kg) was
   booked as 30 kg because "ते" was treated as a separate word.
   If you cannot tell which numeral it is, set confidence to 0.6 so a human confirms.
```

### Step 7 — low-margin rejoins must not auto-confirm (`index.ts`)

In the `finalParsedItems` map, alongside the `alignedSeg?.resolutionKind` checks at lines 1864–1869, following the existing `implausibility = implausibility ? ... : x` pattern:

```ts
      if (alignedSeg?.numeralRejoinLowMargin) {
        const rejoinReason = `Quantity ${qty} was reconstructed from a split number word and a different number scored nearly as close -- confirm the amount`
        implausibility = implausibility ? `${implausibility} | ${rejoinReason}` : rejoinReason
      }
```

`isCommittable` / `isStockCommittable` (`index.ts:2133`, `2146`) already require `implausibility_reason === null`, so this routes the line to review with no further change.

### Step 8 — close the orphan-segment hole (`index.ts`, defense in depth)

Independent of numerals: `alignSegmentsToItems` silently drops surplus segments and the survivors still auto-confirm (E9). That is what let this bug reach the ledger unflagged, and it will let the next one through too. In the same implausibility block:

```ts
      // ISSUE-106: a segmenter segment that no AI item consumed means part of the
      // utterance went unexplained. Auto-confirming the rest asserts we understood the
      // whole recording when we demonstrably did not.
      if (itemIdx === 0 && step3Segments.length > parsedRawItems.length &&
          step3Segments.some(s => s.isSanityFlagged || s.resolutionKind !== 'MATCH')) {
        const orphanReason = `Part of the recording did not resolve to any item -- review before booking`
        implausibility = implausibility ? `${implausibility} | ${orphanReason}` : orphanReason
      }
```

**This one carries regression risk** — it will push some currently-auto-confirming multi-item utterances into review. Measure before shipping: run the 170-transcript corpus and count how many currently-`AUTO_CONFIRMED` outcomes change. **If more than ~5% regress, ship steps 1–7 first and bring step 8 back separately.**

### Step 9 — trace visibility (`index.ts:2229–2243`)

Add to the `step_3_deterministic_ordering_segmenter` trace object:
```ts
        numeralRejoins: segmentResult.numeralRejoins ?? [],
```
Use the existing local holding the `segmentTranscript` result; do not re-invoke the segmenter.

---

## 5. Tests

**Server** — `supabase/functions/process-voice-job/phonetic_test.ts` (existing, `node:test` + `node:assert`):

```ts
test('rejoins the reported fragmentation ("ते तीस किलो आलू" -> 33)', () => {
  const { segments } = segmentTranscript('ते तीस किलो आलू', ['Aaloo'])
  assert.strictEqual(segments.length, 1)
  assert.strictEqual(segments[0].quantity, 33)
  assert.strictEqual(segments[0].unit, 'KG')
  assert.strictEqual(segments[0].numeralRejoinLowMargin, false)
})

// Irregular numerals -- these are why the tens-anchor design was rejected.
test('rejoins irregular numerals (बावन=52, बानवे=92, पैंतालीस=45)', () => {
  assert.strictEqual(segmentTranscript('बा वन किलो आलू', ['Aaloo']).segments[0].quantity, 52)
  assert.strictEqual(segmentTranscript('बान वे किलो आलू', ['Aaloo']).segments[0].quantity, 92)
  assert.strictEqual(segmentTranscript('पैंता लीस किलो आलू', ['Aaloo']).segments[0].quantity, 45)
})

test('LOAD-BEARING: does NOT rejoin when left token is a real item ("दही तीस किलो")', () => {
  const { segments } = segmentTranscript('दही तीस किलो', ['Dahi'])
  assert.notStrictEqual(segments[0]?.quantity, 33)
})

test('does not corrupt clean transcripts', () => {
  assert.strictEqual(segmentTranscript('तैंतीस किलो आलू', ['Aaloo']).segments[0].quantity, 33)
  assert.strictEqual(segmentTranscript('दस किलो आलू', ['Aaloo']).segments[0].quantity, 10)
  assert.strictEqual(segmentTranscript('हर्ष दस किलो आलू', ['Aaloo']).segments[0].quantity, 10)
  assert.strictEqual(segmentTranscript('पचास किलो आलू', ['Aaloo']).segments[0].quantity, 50)
})

test('flags an ambiguous rejoin instead of committing ("ते ईस" -> 23 vs 30 tie)', () => {
  const { segments } = segmentTranscript('ते ईस किलो आलू', ['Aaloo'])
  assert.strictEqual(segments[0].numeralRejoinLowMargin, true)
})
```

Run: `node --test supabase/functions/process-voice-job/phonetic_test.ts`

**Client** — mirror all five into `app/src/test/java/com/voicetoinvoice/app/PhoneticSegmentationTest.kt`.
Run: `./gradlew test --tests "com.voicetoinvoice.app.PhoneticSegmentationTest"`

**Regression guard:** the existing 5 tests in `phonetic_test.ts` and all of `OrderingSegmenterTest.kt` must pass **unchanged**. Any edit needed there means the merge is firing where it should not.

---

## 6. Deploy & verification

1. Tests green on both sides.
2. `npx supabase functions deploy process-voice-job --project-ref lyowklxsbfznnqridtgr` (standing authorization — deploy, don't ask).
3. Re-fetch the live bundle and grep for `rejoinFragmentedNumerals` / `MERGE_MAX_NORM` — this repo has a history of placeholder deploys going live.
4. `./gradlew assembleDebug`; copy from **`C:/VTI_build/app/outputs/apk/debug/app-debug.apk`** (never `app/build/...`) to `VoiceToInvoice_v<next>.apk`; `md5sum` against the previous version and confirm they differ.
5. **Verify by effect, not by build.** Record a fresh "तैंतीस किलो आलू" and query:
```sql
SELECT job_id, raw_transcript, parsed_qty, status, created_at
FROM stt_job_logs ORDER BY created_at DESC LIMIT 5;
```
Success = a row created **after** the deploy with `parsed_qty = 33`. If no such row exists, the verification did not happen — say so rather than reporting success.
6. Log **ISSUE-106** in `Docs/audit.md` under "🟢 RESOLVED ISSUES" (highest existing is ISSUE-105), and add `MERGE_MAX_NORM` / `MERGE_MIN_VALUE_MARGIN` to §1 "Ground-Truth Source-Code Verified Constants".

---

## 7. Adversarial audit — what this does NOT cover

1. **Three-way fragmentation** (`दो सौ तैंतीस` → `दो सौ ते तीस`). The pass merges adjacent pairs only; `ते`+`तीस` should fire and `parseCompoundNumberSequence` should yield 233 — **inferred from reading the code, not tested.** Add a case if bulk hundreds+compound orders are common.
2. **Fragmentation into three tokens** (`तैं` `ती` `स`) is not handled at all. Falls through to review.
3. **Latin-script fragmentation** (`tain tees`) is untested. `phoneticKey` is script-agnostic so it *should* behave identically, but no case was measured.
4. **The 42-of-179 flagged recoveries add review-queue load.** These are cases where the numeral is genuinely ambiguous after fragmentation (`तेईस`/`तीस` share a phonetic key). Correct behavior, but it is extra taps for the shopkeeper — worth watching after rollout.
5. **Step 8 is a partial close of the orphan hole.** It flags surplus *sanity-flagged* segments. A surplus segment that looks clean still gets dropped silently.

**Class statement:** steps 1–3 **eliminate the fragmented-compound-numeral class** for two-token fragmentations — measured, not asserted: 189 silent mis-bookings → 0 on the lossy set, with zero harmful changes across 170 real transcripts. Items 1–3 above are the residue (three-token splits and Latin script). Step 8 partially closes the wider orphan-segment class that let this bug reach the ledger unflagged.
