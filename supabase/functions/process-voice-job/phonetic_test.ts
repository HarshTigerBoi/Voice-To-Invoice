import assert from 'node:assert'
import { test } from 'node:test'
import { segmentTranscript } from './phonetic.ts'
import { canonicalOf } from './lexicon.ts'

test('segmentTranscript flags split-derived unit from garbled token ("सूल गलवालू")', () => {
  const { segments } = segmentTranscript('सूल गलवालू', ['Aaloo'])
  assert.strictEqual(segments.length, 1)
  assert.strictEqual(segments[0].quantity, 16)
  assert.strictEqual(segments[0].unit, 'GRAM')
  assert.strictEqual(segments[0].resolutionKind, 'AMBIGUOUS')
  assert.strictEqual(segments[0].isSanityFlagged, true)
})

test('segmentTranscript accepts clean two-token read ("पाँच किलो आलू")', () => {
  const { segments } = segmentTranscript('पाँच किलो आलू', ['Aaloo'])
  assert.strictEqual(segments.length, 1)
  assert.strictEqual(segments[0].quantity, 5)
  assert.strictEqual(segments[0].unit, 'KG')
  assert.strictEqual(segments[0].resolutionKind, 'MATCH')
  assert.strictEqual(segments[0].isSanityFlagged, false)
})

test('segmentTranscript filters discourse particles ("दो किलो हाँ")', () => {
  const { segments, carryoverQty } = segmentTranscript('दो किलो हाँ')
  assert.strictEqual(segments.length, 0)
  assert.strictEqual(carryoverQty, 2)
})

test('segmentTranscript filters discourse particles ("दो किलो के")', () => {
  const { segments, carryoverQty } = segmentTranscript('दो किलो के')
  assert.strictEqual(segments.length, 0)
  assert.strictEqual(carryoverQty, 2)
})

test('segmentTranscript preserves genuine item ("दो किलो आम")', () => {
  const { segments } = segmentTranscript('दो किलो आम', ['Aam'])
  assert.strictEqual(segments.length, 1)
  assert.strictEqual(segments[0].quantity, 2)
  assert.strictEqual(segments[0].itemMatchNorm, 0)
  assert.strictEqual(segments[0].resolutionKind, 'AMBIGUOUS') // 2-phone key margin < 1.0 phone edit
})

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
  const harishRes = segmentTranscript('हर्ष दस किलो आलू', ['Aaloo'])
  assert.strictEqual(harishRes.segments.find(s => s.quantity === 10)?.quantity, 10)
  assert.strictEqual(segmentTranscript('पचास किलो आलू', ['Aaloo']).segments[0].quantity, 50)
})

test('flags an ambiguous rejoin instead of committing ("ते ईस" -> 23 vs 30 tie)', () => {
  const { segments } = segmentTranscript('ते ईस किलो आलू', ['Aaloo'])
  assert.strictEqual(segments[0].numeralRejoinLowMargin, true)
})

test('qualifier namespace & short-key segmenter behavior and identity (§5.1 & §5.2)', () => {
  const s1 = segmentTranscript('पाँच किलो आलू', ['Aaloo']).segments[0]
  assert.strictEqual(s1.resolutionKind, 'MATCH')
  assert.strictEqual(canonicalOf(s1.itemTokens.join(' ')), 'Aaloo')

  const s2 = segmentTranscript('छः किलो अदरक', ['Adrak']).segments[0]
  assert.strictEqual(s2.resolutionKind, 'MATCH')
  assert.strictEqual(canonicalOf(s2.itemTokens.join(' ')), 'Adrak')

  const s3 = segmentTranscript('एक किलो घी', ['Ghee']).segments[0]
  assert.strictEqual(s3.resolutionKind, 'MATCH')
  assert.strictEqual(canonicalOf(s3.itemTokens.join(' ')), 'Ghee')

  const s4 = segmentTranscript('दो पैकेट अमूल गोल्ड').segments[0]
  const phrase4 = s4.itemTokens.join(' ')
  assert.ok(phrase4.includes('अमूल'))
  assert.ok(!phrase4.includes('Angoor'))
  assert.strictEqual(s4.resolutionKind, 'MATCH')
  assert.strictEqual(canonicalOf(phrase4), 'Amul Gold Milk')

  const s5 = segmentTranscript('एक पैकेट हरी नींबू').segments[0]
  const phrase5 = s5.itemTokens.join(' ')
  assert.ok(phrase5.includes('हरी'))
  assert.ok(!phrase5.includes('Aaloo'))
  assert.strictEqual(s5.resolutionKind, 'MATCH')
  assert.strictEqual(canonicalOf(phrase5), 'Green Nimbu')

  assert.strictEqual(canonicalOf('amul milk'), 'Amul Doodh')
})


