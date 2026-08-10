# ISSUE-104 — Grok invents a catalog item name (and inherits its price) from audio carrying no evidence

**Status:** plan, not yet implemented
**Trace:** job `489f3a33-4180-4752-9c0f-b67544af5487`, 2026-08-08 17:56:46 UTC
**Spoken:** "do kilo aaaaaaa" (deliberate gibberish) → review card reads **Aaloo · 2 KG · ₹100**
**Relationship to ISSUE-103:** this is that plan's own §7 uncovered-scenario #2 ("Grok returning
its own high `confidence` bypasses Step 3 entirely"), promoted to its own issue. ISSUE-103 hardened
the *deterministic* matcher; nothing hardened the *AI* naming stage.

---

## 1. What actually happened (verified — trace fields quoted, code paths read)

Both STT engines independently collapsed the drawn-out "aaaaaaa" to a single `आ`:
`rawTranscript: "दो किलो आ"`, `grokTranscript` and `sarvamTranscript` identical, and all 3
adaptive re-decode passes agreed. The item surface reaching the parser was one character.

| Stage | What it did | Verdict |
|---|---|---|
| Step 3 segmenter | `itemTokens:["आ"]`, `itemMatchNorm: null`, `resolutionKind:"UNKNOWN"`, `isSanityFlagged:true` | **correct** — refused to guess |
| Step 4 Grok | returned `{item_name:"Aaloo", confidence:0.7, matched_catalog:true, price:0}` | **invented it** |
| `findCatalog` (index.ts:1721) | matched literal `"Aaloo"` → catalog row `ac179aa1…` | did what it was told |
| pricing (index.ts:1802-1805) | no spoken price + matched → `priceAtSale = 50`, `total = 2 × 50 = 100` | **₹100 came from the catalog, not the audio** |
| implausibility (index.ts:1846) | `resolutionKind === 'UNKNOWN'` → attaches reason | **correct** |
| cap (index.ts:1930) | `IMPLAUSIBLE_CONFIDENCE_CAP = 0.55` → 0.7 becomes 0.55 | **correct** |
| commit gate (index.ts:2098) | needs `≥ 0.80` **and** `reason === null` — both fail | **correct → review queue** |

**The proximate cause is the prompt, index.ts:1394-1404.** Rule 1 orders the model to *"find the
closest phonetic match … Catalog items"*; rule 4 orders *"never return empty"*; rule 7 orders it to
distrust the segmenter's hint. Given `आ`, a 68-item catalog and a ban on abstaining, `आलू` is the
nearest thing to one `आ`. **The model has no way to say "I could not make that out."**

**The structural cause is index.ts:1760.** `findCatalog(rawName)` is run on the AI's *invented*
string with **zero cross-check against what was actually heard**. Nothing anywhere asks whether the
audio contained enough signal to justify naming a product.

### Why this still matters even though the line went to review

`PendingConfirmationsSheet.kt:470` calls
`onConfirmLine(job, line, isLastPendingLine, line.itemId, line.priceAtSale)` — it books
**`line.itemId`** (the real Aaloo row) at **`line.priceAtSale`** (₹50). One tap on a card the
shopkeeper never spoke books ₹100. The review queue is a confirm button, not a quarantine.

---

## 2. Blast radius (verified against live `stt_job_logs`, project `lyowklxsbfznnqridtgr`)

Per-item, unnested from `step_4_grok_ai_interpretation` across all traced jobs (n = **434 items /
308 jobs**):

| Measure | Count |
|---|---|
| items flagged `phonetic key is unrecognized` | 7 |
| …of those, bound to a catalog row | **1** |
| …of those, bound **and given an invented price** | **1** ← this job |
| AI-named + catalog-bound + no reason + conf ≥ 0.80 (auto-confirm eligible) | 80 |

**Since 2026-08-01** (n = 217 items): `10` items had **no aligned segment at all**
(`item_match_norm IS NULL`) — zero deterministic corroboration — and of those,
**`0`** were auto-confirm eligible. **The auto-book path is closed today.**

**Two corrections to claims I made before checking, recorded so they are not repeated:**

1. I first reported this pattern had "already auto-confirmed twice" (`eb371874`, `ad0f38b8`). It had
   not. My query read `segments[0]` only; those two jobs have **two** segments — a stray `हर्ष`
   ("Harsh", the speaker's name) prefix at `[0]` that resolved UNKNOWN, and the genuine
   `दस किलो आलू` at `[1]` with `resolutionKind: MATCH`, `itemMatchNorm: 0`. The AI item aligned to
   `[1]`. Both bookings were **correct**.
2. A pre-ISSUE-030 sweep does show this class booking real money — `"पांच किलो दूध"` (5 kg milk)
   → **Chaas (Buttermilk)** ₹75 `AUTO_CONFIRMED` (`dce4e9c2`, 2026-07-26); `"Dua Gluvenin 10 ribu."`
   → **Paneer** ₹720 (`05175ce5`). Every such row predates 2026-07-28 and carries `ai_item_name:
   null` (the field did not exist yet), so these are **historical, already closed** by ISSUE-030's
   match-evidence fallback. They are cited as proof the class is real, **not** as live exposure.

**Honest severity: 1 observed occurrence in 434 items. This is a review-card-integrity and
one-tap-confirm defect, not a live money-loss path.** It is worth fixing because the failure mode is
"system asserts a product and a rupee amount that were never spoken", which is exactly the
"confidently wrong" behaviour `Docs/open-vocabulary-architecture.md` §0 names as the thing to
eliminate.

---

## 3. The rule — and the rule I tried first and **disproved**

### ✗ Rejected: phonetic distance between heard surface and AI name

The obvious guard is "reject the AI's name when it is phonetically far from what was heard." **I ran
it against the live `phonetic.ts` and it does not work.** Probe output (`normalizedDistance` over
`phoneticKey`, real code, not hand arithmetic):

```
आ      [A]        vs Aaloo   [ALO]        norm=0.5000   ← must BLOCK
चरगलो  [CALAKALO] vs Aaloo   [ALO]        norm=0.5000   ← must PASS (fused "chaar kilo aaloo")
वाला   [VALA]     vs Is Wala [ISVALA]     norm=0.2500   ← must BLOCK
प्याज  [PIAC]     vs Pyaz    [PIAS]       norm=0.2500   ← must PASS
```

Both pairs are **exactly equal**. Distance cannot separate invention from rescue, because rescuing
a fused/mangled token is *supposed* to be a long-distance move. Discarding this idea rather than
tuning a threshold onto a collision.

### ✓ Adopted: evidence ratio — does the audio contain enough phones to justify the name?

> `evidenceRatio = |phoneticKey(heardSurface)| / |phoneticKey(aiName)|`
>
> Below 1.0 the AI **added phones that were not in the audio**. Invention adds; rescue rearranges.

Measured on the real matcher (probe, `MIN_AI_EVIDENCE_RATIO = 0.75`):

```
--- invented (want BLOCK) ---            --- genuine rescue (want PASS) ---
आ    -> Aaloo    0.33  BLOCK ✅          चरगलो   -> Aaloo     2.67  PASS ✅
आ    -> Aam      0.50  BLOCK ✅          sebab   -> Seb       1.67  PASS ✅
है   -> Aaloo    0.33  BLOCK ✅          tinggal -> Teen      2.00  PASS ✅
ऊ    -> Urad     0.25  BLOCK ✅          ग्लोसोना -> Sona      1.75  PASS ✅
आ    -> Adrak    0.20  BLOCK ✅          अमचूर   -> Amchoor   1.20  PASS ✅
अ    -> Anaar    0.25  BLOCK ✅          बिंडी   -> Bhindi    1.00  PASS ✅
ओ    -> Onion    0.20  BLOCK ✅          बैंगन   -> Baingan   0.83  PASS ✅
                                          सोयाबीन -> Soyabean  0.88  PASS ✅
```

All 220 `DEFAULT_ITEM_VOCAB` words score ratio `1.00` against themselves — **0 self-failures**.
Minimum `phoneticKey` length across the vocab is **2** (`आम`→`AN`, `घी`→`KI`, `हींग`→`IN`,
`उड़द`→`OT`), so no real product can present a 1-phone surface.

**The one false positive the probe found, and why the fix survives it:** `उड़द → Urad` scores
`0.50` (the nukta `ड़` loses its `r`: `OT` vs `OLAT`) and *would* be wrongly blocked by the ratio
alone. But `उड़द` **is in `DEFAULT_ITEM_VOCAB`**, so its segment resolves `MATCH`, never `UNKNOWN`.
**This is why the guard requires both conditions and not the ratio alone.** (ISSUE-103 Open
Question 1 flagged `उड़द` for the same underlying reason.)

**Fire the guard only when BOTH hold:**
1. the aligned segment's `resolutionKind === 'UNKNOWN'` — no deterministic evidence at all, **and**
2. `evidenceRatio < 0.75` — the AI added more phones than the audio supports.

---

## 4. Implementation steps

**Server only. No client change.** Verified: `BackgroundSttProcessor.kt` and `SttWorker.kt` contain
no segmenter/parser/interpreter imports — the client only reads server results. `TermInterpreterClient`'s
only live caller is `HomeScreen.kt:486` (`confirmTermAlias`), not a parse path.

### Step 1 — new pure predicate in `supabase/functions/process-voice-job/item_resolution.ts`

Append (this file is already the home for name-resolution policy and already imports `phoneticKey`):

```ts
/**
 * Minimum ratio of (phones actually heard) to (phones in the AI's proposed name).
 *
 * Below 1.0 the AI has ADDED phones that were not in the audio. Invention adds phones;
 * a genuine rescue of a fused or mangled token rearranges phones it already has
 * ("चरगलो"[CALAKALO] -> "Aaloo"[ALO] = 2.67). Set at 0.75 from a probe over the live
 * phonetic.ts: every invented pair observed scores <= 0.50, every genuine rescue >= 0.83,
 * and all 220 DEFAULT_ITEM_VOCAB words score 1.00 against themselves.
 *
 * Deliberately NOT a phonetic-distance test: "आ"->"Aaloo" and "चरगलो"->"Aaloo" both sit at
 * normalizedDistance 0.5000, so distance cannot separate invention from rescue. See ISSUE-104.
 */
export const MIN_AI_EVIDENCE_RATIO = 0.75

export interface AiEvidenceVerdict {
  uncorroborated: boolean
  ratio: number | null
  heardSurface: string
  heardPhones: number
  aiPhones: number
}

/**
 * True when the AI named a product that the audio does not support.
 *
 * Requires BOTH no deterministic evidence (resolutionKind UNKNOWN) AND an evidence ratio
 * below the floor. The ratio alone false-positives on "उड़द"->"Urad" (0.50, the nukta ड़
 * drops its r), which is spared because उड़द is in DEFAULT_ITEM_VOCAB and therefore resolves
 * MATCH, never UNKNOWN.
 *
 * NOTE on heardSurface: for a MATCH segment `itemTokens` holds the CANONICAL vocab name
 * (verified: job ad0f38b8 has itemTokens ["Aaloo"] with heardSegmentText "दस किलो आलू").
 * For an UNKNOWN segment it holds the RAW heard token — which is the only case this
 * function reads, so itemTokens is the correct source here.
 */
export function assessAiNameEvidence(
  aiName: string,
  seg: Pick<RawItemSegment, 'itemTokens' | 'resolutionKind'> | null | undefined
): AiEvidenceVerdict {
  const heardSurface = seg ? (seg.itemTokens || []).join(' ').trim() : ''
  const heardPhones = phoneticKey(heardSurface).length
  const aiPhones = phoneticKey((aiName || '').trim()).length
  const base = { heardSurface, heardPhones, aiPhones }

  if (!seg || seg.resolutionKind !== 'UNKNOWN' || aiPhones === 0) {
    return { ...base, uncorroborated: false, ratio: null }
  }
  const ratio = heardPhones / aiPhones
  return { ...base, uncorroborated: ratio < MIN_AI_EVIDENCE_RATIO, ratio }
}
```

Add `RawItemSegment` to the existing `phonetic.ts` type import at the top of the file if the
`resolutionKind` field is not already covered by it (it is declared at `phonetic.ts:508`).

### Step 2 — refuse the catalog binding in `index.ts`

At **index.ts:1760**, replace:

```ts
const matched = findCatalog(rawName)
```

with:

```ts
const aiEvidence = assessAiNameEvidence(rawName, alignedSeg)
// An uncorroborated AI name must never reach findCatalog: binding it to a catalog row is
// what manufactures both the product and its price (ISSUE-104).
const matched = aiEvidence.uncorroborated ? undefined : findCatalog(rawName)
```

Import `assessAiNameEvidence` alongside the existing `resolveItemName` import.

**No pricing change is needed.** With `matched === undefined` and no spoken price, index.ts:1806-1808
already yields `priceAtSale = 0` / `total = 0`. The ₹100 disappears as a consequence, not as a patch.

### Step 3 — name the line honestly and explain it

Immediately **after** the existing `resolutionKind` block (index.ts:1843-1849), add:

```ts
if (aiEvidence.uncorroborated) {
  const inventedReason =
    `couldn't make out the item — only '${aiEvidence.heardSurface}' was audible ` +
    `(${aiEvidence.heardPhones} sound${aiEvidence.heardPhones === 1 ? '' : 's'}), ` +
    `too little to confirm '${rawName}'`
  implausibility = implausibility ? `${implausibility} | ${inventedReason}` : inventedReason
}
```

In the returned object (index.ts:1932-1956) change **only** the name field:

```ts
item_name: matched ? matched.name : (aiEvidence.uncorroborated ? "Unrecognized Item" : rawName),
```

`"Unrecognized Item"` is the sentinel the pipeline **already** honours — it is excluded from
catalog-learning (index.ts:1988-1991, so `"Unrecognized Item"` cannot pollute `catalog_items`) and
fails `isCommittable` (index.ts:2104) and `isStockCommittable` (index.ts:2115).

Then, after the existing cap at index.ts:1930, add:

```ts
if (aiEvidence.uncorroborated) confidence = Math.min(confidence, 0.30)
```

0.30 matches what the pipeline already assigns to genuinely unrecognized lines in production
(`वाला`→`Is Wala` and `"themselves you know"` both recorded `0.3`).

**Resulting review card for this job:** `Unrecognized Item · 2 KG · ₹0` with
`⚠️ couldn't make out the item — only 'आ' was audible (1 sound), too little to confirm 'Aaloo'`.
No UI change required — `PendingConfirmationsSheet.kt:395` already renders `implausibilityReason`.

### Step 4 — give the model a way to abstain (`index.ts`, systemPrompt ~1393-1444)

Amend **rule 4**, currently *"keep it as a new item name … never return empty"*:

```
4. If a word still matches nothing after phonetic reasoning, keep it as a
   new item name (clean Hinglish transliteration). If — and only if — the
   item sounds are too few to identify anything at all (a bare vowel or a
   single syllable, e.g. "आ", "ऊ", "है"), return item_name exactly
   "Unrecognized Item" with confidence 0.2. Do NOT stretch one sound into a
   catalog word: "आ" is NOT "आलू". Returning "Unrecognized Item" is the
   correct answer there, not a failure.
```

Amend **rule 5** so the confidence guidance cannot contradict rule 4:

```
5. Report confidence per item: high (0.85+) ONLY when the item sounds you
   heard are at least as many as the sounds in the name you are returning;
   0.4-0.7 for unlisted items; 0.2 when returning "Unrecognized Item".
   Never report 0.85+ for a name longer than what you actually heard.
```

This is defence-in-depth. **Steps 1-3 do not depend on the model obeying it.**

### Step 5 — make it auditable in the trace

In the returned item object, add alongside `item_match_norm` / `item_margin`:

```ts
ai_evidence: {
  ratio: aiEvidence.ratio,
  heardSurface: aiEvidence.heardSurface,
  heardPhones: aiEvidence.heardPhones,
  aiPhones: aiEvidence.aiPhones,
  uncorroborated: aiEvidence.uncorroborated,
},
```

Without this the next occurrence is undiagnosable from `stt_job_logs` alone — which is exactly the
gap that made this job take a code read to explain.

### Step 6 — tests (`item_resolution_test.ts`, Deno)

Pin the measured numbers, not approximations. Run with
`npx deno test --allow-read supabase/functions/process-voice-job/item_resolution_test.ts`.

1. **Blocks invention:** `assessAiNameEvidence("Aaloo", {itemTokens:["आ"], resolutionKind:"UNKNOWN"})`
   → `uncorroborated === true`, `ratio` ≈ `0.333`.
2. **Preserves rescue:** same call with `itemTokens:["चरगलो"]` → `uncorroborated === false`
   (ratio 2.67).
3. **Both conditions required:** `itemTokens:["उड़द"], resolutionKind:"MATCH"` with `aiName "Urad"`
   → `uncorroborated === false` **even though ratio is 0.50**. This test is the regression guard for
   the one known false positive — do not delete it.
4. **Vocab self-consistency:** every `DEFAULT_ITEM_VOCAB` entry against itself → ratio `1.00`, so
   `uncorroborated === false` for all 220.
5. **Distance is not the rule:** assert `normalizedDistance(phoneticKey("आ"), phoneticKey("Aaloo"))`
   and `normalizedDistance(phoneticKey("चरगलो"), phoneticKey("Aaloo"))` are **equal** (both 0.5),
   documenting in-suite why the rejected design was rejected.
6. **Unchanged behaviour:** `बैंगन`→`Baingan` (0.83) and `सोयाबीन`→`Soyabean` (0.88) stay
   `uncorroborated === false` — these are the tightest genuine passes and the first things a
   threshold raise would break.

### Step 7 — audit + deploy

- Add **ISSUE-104** to `Docs/audit.md` §2 under 🟢 RESOLVED (highest existing is ISSUE-103), in the
  established Symptom / Root Cause / Resolution / Verification Date format, cross-referencing
  ISSUE-103 §7 scenario 2 as its origin.
- Add `MIN_AI_EVIDENCE_RATIO = 0.75` to §1 "Ground-Truth Source-Code Verified Constants".
- Deploy (standing authorization — do not ask):
  ```bash
  npx supabase functions deploy process-voice-job --project-ref lyowklxsbfznnqridtgr
  ```
- Then re-fetch the live bundle and grep for `MIN_AI_EVIDENCE_RATIO` and `assessAiNameEvidence` to
  confirm the deploy carried the change — this repo has a history of placeholder deploys going live.

---

## 5. Verification — by effect, not by build

`BUILD SUCCESSFUL` and green tests prove nothing here.

1. **Re-speak the original input** ("do kilo aaaaaaa") after deploy, then:
   ```sql
   SELECT job_id, raw_transcript, status,
          diagnostic_trace_json::jsonb #>> '{step_4_grok_ai_interpretation,0,item_name}' AS final_name,
          diagnostic_trace_json::jsonb #>> '{step_4_grok_ai_interpretation,0,total}' AS total,
          diagnostic_trace_json::jsonb #>  '{step_4_grok_ai_interpretation,0,ai_evidence}' AS evidence,
          diagnostic_trace_json::jsonb #>> '{step_4_grok_ai_interpretation,0,implausibility_reason}' AS reason
   FROM stt_job_logs WHERE created_at > now() - interval '10 minutes'
   ORDER BY created_at DESC;
   ```
   **Pass =** `final_name` is `Unrecognized Item`, `total` is `0`, `ai_evidence.uncorroborated` is
   `true`. If no row recorded after the change can be found, say the verification did not happen —
   do not report success.
2. **Regression, the expensive direction:** book a genuine `"दो किलो आलू"` and a genuine
   `"पाँच किलो अमचूर"` and confirm both still parse to the right item at the right price. A guard
   that silences real sales is worse than the bug.
3. **Re-run the blast-radius query from §2** after a few days of use and confirm
   `unknown_bound_with_invented_price` is `0`.

---

## 6. Instance vs. class — stated plainly

- **This eliminates the instance** (`आ` → Aaloo) and the family of degenerate short-surface
  inventions (`अ`, `ऊ`, `ओ`, `है` → any catalog word), because a 1-phone surface cannot reach
  `0.75` against any real product name (vocab minimum key length is 2).
- **It narrows but does not eliminate the class.** The class is *"the AI stage may name a product
  the deterministic stage never saw, and that name silently binds to a priced catalog row."*
  Three scenarios that survive this fix — named here so no one treats them as cleared:
  1. **Equal-length invention.** `हाँ`→`Aam` scores ratio `1.00` and passes this guard entirely.
     It is caught today only by ISSUE-103's `DISCOURSE_PARTICLES` stoplist. Any *non*-particle
     2-phone homophone of a catalog item is unguarded by both.
  2. **No aligned segment at all.** When `alignSegmentsToItems` pairs nothing to an AI item,
     `alignedSeg` is `null`, `resolutionKind` is undefined, condition 1 never holds, and the guard
     is inert. **10 of 217 items since 2026-08-01 were in this state** — `0` were auto-confirm
     eligible, so it is currently harmless, but it is harmless by luck, not by design.
  3. **The `resolveItemName` gap.** The segmenter only overrides the AI at
     `SEGMENTER_OVERRIDE_MAX_NORM = 0.08`. Between 0.08 and the match threshold the AI's name wins
     with **no flag at all** — a MATCH segment plus a wrong AI name auto-books at the AI's
     self-reported 0.95. This is the same hole ISSUE-030 opened and is untouched here.
- The deepest cause remains the one `open-vocabulary-architecture.md` §1.2 names: **matching is
  forced-choice, and now so is naming.** A real fix is an abstain-capable matcher plus a learned
  `term_aliases` read path, not another threshold. Not attempted here.

---

## 7. Open questions (stop and ask rather than guessing)

1. **`MIN_AI_EVIDENCE_RATIO = 0.75` blocks reconstruction from truncated audio** — measured:
   `टमा`→`Tamatar` 0.57, `सोया`→`Soyabean` 0.50, `ना`→`Nariyal` 0.33 would all become review taps
   *if* their segment also resolved UNKNOWN. I recommend accepting this (a review tap is cheap, a
   silent mis-book is not — the doctrine ISSUE-103 settled on). Confirm, or lower to 0.60?
2. **Should the AI's rejected guess be surfaced as a tappable suggestion** on the review card
   ("did you mean Aaloo?") rather than only appearing inside the warning text? That needs a
   `PendingConfirmationsSheet.kt` change and is **not** in this plan's scope — it reintroduces the
   one-tap-books-it risk in a friendlier costume, so I have deliberately not designed it.
3. **Scenario 2 above (null `alignedSeg`) is currently harmless but structurally open.** Fix in this
   change by treating a null aligned segment as failing condition 1, or file separately? Folding it
   in would touch the 10 no-aligned-segment items/month and I have not measured what that costs.
