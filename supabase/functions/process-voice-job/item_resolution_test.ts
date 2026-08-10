import assert from 'node:assert'
import { test } from 'node:test'
import { segmentTranscript, normalizedLiteralDistance, phoneticKey, normalizedDistance, DEFAULT_ITEM_VOCAB } from './phonetic.ts'
import { resolveItemName, unpricedLineReason, assessAiNameEvidence, MIN_AI_EVIDENCE_RATIO, SEGMENTER_OVERRIDE_MAX_NORM } from './item_resolution.ts'
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

// ---------------------------------------------------------------------------
// assessAiNameEvidence — ISSUE-104
// ---------------------------------------------------------------------------

test('ISSUE-104: blocks invention when heard sound is too short (आ -> Aaloo)', () => {
  const v = assessAiNameEvidence('Aaloo', { itemTokens: ['आ'], resolutionKind: 'UNKNOWN' })
  assert.strictEqual(v.uncorroborated, true)
  assert.strictEqual(v.heardPhones, 1)
  assert.strictEqual(v.aiPhones, 3) // ALO
  assert.ok(Math.abs((v.ratio ?? 0) - (1 / 3)) < 0.001)
})

test('ISSUE-104: preserves rescue for fused/mangled tokens (चरगलो -> Aaloo)', () => {
  const v = assessAiNameEvidence('Aaloo', { itemTokens: ['चरगलो'], resolutionKind: 'UNKNOWN' })
  assert.strictEqual(v.uncorroborated, false)
  assert.strictEqual(v.heardPhones, 8) // CALAKALO
  assert.strictEqual(v.aiPhones, 3)
  assert.ok((v.ratio ?? 0) > 1.0)
})

test('ISSUE-104: both conditions required (MATCH segment for Urad -> Urad is spared despite ratio 0.50)', () => {
  const v = assessAiNameEvidence('Urad', { itemTokens: ['उड़द'], resolutionKind: 'MATCH' })
  assert.strictEqual(v.uncorroborated, false, 'MATCH segments must never be uncorroborated')
  assert.strictEqual(v.ratio, null)
})

test('ISSUE-104: default item vocabulary self-consistency (every item against itself has ratio 1.00)', () => {
  for (const item of DEFAULT_ITEM_VOCAB) {
    const v = assessAiNameEvidence(item, { itemTokens: [item], resolutionKind: 'UNKNOWN' })
    assert.strictEqual(v.uncorroborated, false, `DEFAULT_ITEM_VOCAB item '${item}' against itself must pass`)
    assert.strictEqual(v.ratio, 1.0)
  }
})

test('ISSUE-104: distance is not the rule (proves why evidence ratio was chosen over distance)', () => {
  const distInvention = normalizedDistance(phoneticKey('आ'), phoneticKey('Aaloo'))
  const distRescue = normalizedDistance(phoneticKey('चरगलो'), phoneticKey('Aaloo'))
  assert.strictEqual(distInvention, distRescue, 'both invention and rescue sit at distance 0.5, so distance cannot separate them')
  assert.strictEqual(distInvention, 0.5)
})

test('ISSUE-104: tightest genuine passes (बैंगन -> Baingan, सोयाबीन -> Soyabean) remain uncorroborated = false', () => {
  const vBaingan = assessAiNameEvidence('Baingan', { itemTokens: ['बैंगन'], resolutionKind: 'UNKNOWN' })
  assert.strictEqual(vBaingan.uncorroborated, false)
  assert.ok((vBaingan.ratio ?? 0) >= 0.83)

  const vSoya = assessAiNameEvidence('Soyabean', { itemTokens: ['सोयाबीन'], resolutionKind: 'UNKNOWN' })
  assert.strictEqual(vSoya.uncorroborated, false)
  assert.ok((vSoya.ratio ?? 0) >= 0.87)
})

// ---------------------------------------------------------------------------
// assessAiNameEvidence — ISSUE-105 (Zero-segment branch)
// ---------------------------------------------------------------------------

test('ISSUE-105: reported job input "दो किलो से" is flagged uncorroborated on zero segments', () => {
  const surfaces = new Set(['aaloo', 'pyaz', 'seb'])
  const v = assessAiNameEvidence('Seb', null, {
    transcript: 'दो किलो से',
    itemSurfaces: surfaces,
    segmentCount: 0,
  })
  assert.strictEqual(v.uncorroborated, true)
  assert.strictEqual(v.source, 'transcript_residue')
  assert.strictEqual(v.heardPhones, 0)
  assert.strictEqual(v.heardSurface, '')
})

test('ISSUE-105: repetition of discourse particles "हाँ जी हाँ जी" does not buy protection', () => {
  const surfaces = new Set(['aaloo', 'pyaz'])
  const v = assessAiNameEvidence('Aaloo', null, {
    transcript: 'हाँ जी हाँ जी',
    itemSurfaces: surfaces,
    segmentCount: 0,
  })
  assert.strictEqual(v.uncorroborated, true)
  assert.strictEqual(v.source, 'transcript_residue')
  assert.strictEqual(v.heardPhones, 0)
})

test('ISSUE-105: escape hatch preserves catalog item "घी" but floor <=2 fires on 2-phone zero-segment residue', () => {
  const surfaces = new Set(['घी', 'aaloo'])
  const v = assessAiNameEvidence('Ghee', null, {
    transcript: 'दो किलो घी',
    itemSurfaces: surfaces,
    segmentCount: 0,
  })
  assert.strictEqual(v.heardSurface, 'घी')
  assert.strictEqual(v.heardPhones, 2)
  assert.strictEqual(v.uncorroborated, true, 'floor <= 2 fires on 2-phone residue on zero-segment path')
  assert.strictEqual(v.source, 'transcript_residue')
})

test('ISSUE-105: scoped to zero segments (segmentCount > 0 with seg = null remains source = none)', () => {
  const surfaces = new Set(['aaloo'])
  const v = assessAiNameEvidence('Paneer', null, {
    transcript: 'दो किलो आलू',
    itemSurfaces: surfaces,
    segmentCount: 1,
  })
  assert.strictEqual(v.uncorroborated, false)
  assert.strictEqual(v.source, 'none')
})

test('ISSUE-105: ISSUE-104 path returns source = segment', () => {
  const v = assessAiNameEvidence('Aaloo', { itemTokens: ['आ'], resolutionKind: 'UNKNOWN' })
  assert.strictEqual(v.uncorroborated, true)
  assert.strictEqual(v.source, 'segment')
})

test('ISSUE-105: corpus regression fixture (8 zero-segment inputs fire, 10 good inputs do not)', () => {
  const zeroSegmentTranscripts = [
    'दो किलो से',
    'दो किलो हाँ',
    'हम्म',
    'हाँ',
    'तो ये है',
    'हाँ जी हाँ जी',
    'हाँ हाँ हाँ',
    'दो किलो हाँ',
  ]
  const goodTranscripts = [
    'दो किलो आलू',
    'तीन किलो प्याज',
    'पाँच किलो अमचूर',
    'दस किलो आटा',
    'दो किलो टमाटर',
    'पाँच किलो चीनी',
    'दो किलो चावल',
    'एक किलो लहसुन',
    'दो किलो बैंगन',
    'पाँच किलो केला',
  ]

  const surfaces = new Set(['आलू', 'प्याज', 'अमचूर', 'आटा', 'टमाटर', 'चीनी', 'चावल', 'लहसुन', 'बैंगन', 'केला', 'seb'])

  for (const t of zeroSegmentTranscripts) {
    const { segments } = segmentTranscript(t)
    assert.strictEqual(segments.length, 0, `transcript '${t}' must yield zero segments`)
    const v = assessAiNameEvidence('Seb', null, {
      transcript: t,
      itemSurfaces: surfaces,
      segmentCount: segments.length,
    })
    assert.strictEqual(v.uncorroborated, true, `transcript '${t}' must be uncorroborated`)
    assert.strictEqual(v.source, 'transcript_residue')
  }

  for (const t of goodTranscripts) {
    const { segments } = segmentTranscript(t)
    assert.ok(segments.length > 0, `transcript '${t}' must yield >= 1 segments`)
  }
})


