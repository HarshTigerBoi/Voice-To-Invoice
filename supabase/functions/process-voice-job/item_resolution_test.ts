import assert from 'node:assert'
import { test } from 'node:test'
import { segmentTranscript, normalizedLiteralDistance } from './phonetic.ts'
import { resolveItemName, unpricedLineReason, SEGMENTER_OVERRIDE_MAX_NORM } from './item_resolution.ts'
import { implausibilityReason } from './price_intent.ts'

// ---------------------------------------------------------------------------
// implausibilityReason — STOCK mode (ISSUE-041)
// ---------------------------------------------------------------------------

test('implausibilityReason STOCK mode higher limits and no sale floor', () => {
  assert.strictEqual(implausibilityReason('KG', 500, 0, '', 0, 'STOCK'), null)
  assert.notStrictEqual(implausibilityReason('KG', 500, 0, '', 0, 'SALE'), null)
  assert.strictEqual(implausibilityReason('KG', 50, 0, 'पचास किलो आलू आया', 0, 'STOCK'), null)
  assert.notStrictEqual(implausibilityReason('GRAM', 5, 0, '', 0, 'STOCK'), null)
})

// ---------------------------------------------------------------------------
// resolveItemName — ISSUE-030
// ---------------------------------------------------------------------------

test('the real ISSUE-030 case: segmenter अमचूर (distance 0) beats AI "Angoor"', () => {
  // Reproduce the actual adopted (Sarvam) transcript from job 107cc435.
  const { segments } = segmentTranscript('पाँच किलो अमचूर')
  assert.strictEqual(segments.length, 1)
  assert.strictEqual(segments[0].itemTokens[0], 'अमचूर')
  assert.strictEqual(segments[0].itemMatchNorm, 0, 'segmenter must match अमचूर exactly')

  const r = resolveItemName('Angoor', segments[0])
  assert.strictEqual(r.name, 'अमचूर', 'the segmenter reading must win')
  assert.strictEqual(r.usedSegmenterOverride, true)
  assert.ok(r.disagreementReason?.includes('अमचूर'))
  assert.ok(r.disagreementReason?.includes('Angoor'))
})

test('DANGEROUS VARIANT: override still fires when the AI name would match the catalog', () => {
  // The benign version of ISSUE-030 stalled at ₹0 only because "Angoor" is not stocked.
  // If the AI's wrong word IS a catalog item, the old behaviour would have booked the
  // wrong product at a real price with confidence 0.95 and no flag. The rule must not be
  // gated on catalog membership -- assert that here with a stocked wrong name.
  const { segments } = segmentTranscript('दो किलो अमचूर')
  assert.strictEqual(segments[0].itemMatchNorm, 0)

  // "Aaloo" is very much in the catalog; the segmenter still wins.
  const r = resolveItemName('Aaloo', segments[0])
  assert.strictEqual(r.name, 'अमचूर')
  assert.strictEqual(r.usedSegmenterOverride, true)
  assert.notStrictEqual(r.disagreementReason, null, 'must always flag so a human sees it')
})

test('no override when the two stages agree (same word, different script)', () => {
  const { segments } = segmentTranscript('दो किलो आलू')
  // "Aaloo" and "आलू" share a phonetic key, so this is agreement, not a conflict.
  const r = resolveItemName('Aaloo', segments[0])
  assert.strictEqual(r.usedSegmenterOverride, false)
  assert.strictEqual(r.disagreementReason, null)
  assert.strictEqual(r.name, 'Aaloo', 'AI name is kept when there is no disagreement')
})

test('no override when the segmenter match is weak (above the norm threshold)', () => {
  const weakSeg = { itemTokens: ['संतरा'], itemMatchNorm: 0.214 }
  const r = resolveItemName('Chandan', weakSeg)
  assert.strictEqual(r.usedSegmenterOverride, false)
  assert.strictEqual(r.name, 'Chandan', 'a 0.214 match is not strong enough to overrule the AI')
})

test('no override when there is no aligned segment at all', () => {
  const r = resolveItemName('Paneer', null)
  assert.strictEqual(r.name, 'Paneer')
  assert.strictEqual(r.usedSegmenterOverride, false)
  assert.strictEqual(r.disagreementReason, null)
})

test('threshold boundary is inclusive', () => {
  const atThreshold = { itemTokens: ['बैंगन'], itemMatchNorm: SEGMENTER_OVERRIDE_MAX_NORM }
  assert.strictEqual(resolveItemName('Kaju', atThreshold).usedSegmenterOverride, true)

  const justOver = { itemTokens: ['बैंगन'], itemMatchNorm: SEGMENTER_OVERRIDE_MAX_NORM + 0.001 }
  assert.strictEqual(resolveItemName('Kaju', justOver).usedSegmenterOverride, false)
})

// REGRESSION (found on a live replay of job 107cc435): comparing segmenter vs AI names
// by exact phoneticKey equality flagged "बैंगन" against the AI's own "Baingan" as a
// disagreement, even though they are the same word -- Devanagari matra ऐ and the Latin
// spelling "ai" round-trip to slightly different phone sequences (measured: normalized
// distance 0.083). A line that used to auto-confirm cleanly would have started failing
// with a bogus "STT engines disagreed" flag. classifySegmentPriceIntent-style near-exact
// matching (a normalized-distance threshold, not string equality) fixes this.
test('REGRESSION: बैंगन vs AI "Baingan" is agreement, not a disagreement', () => {
  const { segments } = segmentTranscript('चार किलो बैंगन')
  assert.strictEqual(segments[0].itemMatchNorm, 0)

  const r = resolveItemName('Baingan', segments[0])
  assert.strictEqual(r.usedSegmenterOverride, false, 'बैंगन/Baingan must be treated as agreement')
  assert.strictEqual(r.disagreementReason, null)
  assert.strictEqual(r.name, 'Baingan')
})

test('REGRESSION: अमचूर vs AI "Amchur"/"Amchoor" (near-miss Latin spellings) is agreement', () => {
  const { segments } = segmentTranscript('पाँच किलो अमचूर')
  for (const aiSpelling of ['Amchur', 'Amchoor']) {
    const r = resolveItemName(aiSpelling, segments[0])
    assert.strictEqual(r.usedSegmenterOverride, false, `अमचूर/${aiSpelling} must be treated as agreement`)
  }
})

test('genuine disagreement (अमचूर vs Angoor, normalized distance 0.25) still overrides', () => {
  const { segments } = segmentTranscript('पाँच किलो अमचूर')
  const r = resolveItemName('Angoor', segments[0])
  assert.strictEqual(r.usedSegmenterOverride, true, 'a real mis-hearing must still be caught')
})

// ---------------------------------------------------------------------------
// unpricedLineReason — ISSUE-030
// ---------------------------------------------------------------------------

test('unlisted item gets a "not in your catalog" reason (the Aam / Angoor case)', () => {
  const reason = unpricedLineReason('Aam', false, 0, 0, 'NONE')
  assert.notStrictEqual(reason, null)
  assert.ok(reason!.includes('Aam'))
  assert.ok(reason!.includes('not in your catalog'))
})

test('catalog-matched but zero-priced item gets a different, accurate reason', () => {
  const reason = unpricedLineReason('Kaju', true, 0, 0, 'NONE')
  assert.notStrictEqual(reason, null)
  assert.ok(reason!.includes('no price in your catalog'))
  assert.ok(!reason!.includes('not in your catalog yet'))
})

test('NO REGRESSION: a matched, priced line gains no reason', () => {
  // Lines 0-2 of job 107cc435 (Tamatar 2KG ₹80, Aaloo 3KG ₹150, Baingan 4KG ₹160)
  // must stay clean, or they would stop auto-confirming.
  assert.strictEqual(unpricedLineReason('Tamatar', true, 40, 80, 'NONE'), null)
  assert.strictEqual(unpricedLineReason('Aaloo', true, 50, 150, 'NONE'), null)
  assert.strictEqual(unpricedLineReason('Baingan', true, 40, 160, 'NONE'), null)
})

test('RATE_UPDATE and AMBIGUOUS_UNTRUSTED are exempt (zero total is correct for them)', () => {
  assert.strictEqual(unpricedLineReason('Aaloo', true, 30, 0, 'RATE_UPDATE'), null)
  assert.strictEqual(unpricedLineReason('Aaloo', true, 0, 0, 'AMBIGUOUS_UNTRUSTED'), null)
})

test('a bulk sale with a real total gains no reason', () => {
  assert.strictEqual(unpricedLineReason('Paneer', true, 50, 250, 'BULK_SALE_TOTAL'), null)
})

test('Kela vs Kheera literal distance rejection test (ISSUE-040)', () => {
  const dist = normalizedLiteralDistance('Kela', 'Kheera')
  assert.ok(dist > 0.15, `Kela vs Kheera literal distance (${dist}) must exceed 0.15`)

  const distDeva = normalizedLiteralDistance('केला', 'खीरा')
  assert.ok(distDeva > 0.15, `केला vs खीरा literal distance (${distDeva}) must exceed 0.15`)

  const distMatch = normalizedLiteralDistance('केला', 'Kela')
  assert.ok(distMatch <= 0.15, `केला vs Kela literal distance (${distMatch}) must be <= 0.15`)
})
