import assert from 'node:assert'
import { test } from 'node:test'
import { segmentTranscript } from './phonetic.ts'
import { classifySegmentPriceIntent } from './price_intent.ts'

test('segmentTranscript splits a plain multi-item order into separate segments', () => {
  const { segments } = segmentTranscript('दो किलो आलू तीन किलो प्याज')
  assert.strictEqual(segments.length, 2)
  assert.strictEqual(segments[0].quantity, 2)
  assert.strictEqual(segments[0].unit, 'KG')
  assert.strictEqual(segments[0].itemTokens[0], 'आलू')
  assert.strictEqual(segments[1].quantity, 3)
  assert.strictEqual(segments[1].unit, 'KG')
  assert.strictEqual(segments[1].itemTokens[0], 'प्याज')
})

test('segmentTranscript attaches a rate to the item it follows, not a phantom next item', () => {
  // "आलू 50 रुपये" used to produce TWO segments: {Aaloo} and {qty:50, item:"रुपये"}.
  const { segments } = segmentTranscript('आलू पचास रुपये')
  assert.strictEqual(segments.length, 1)
  assert.strictEqual(segments[0].itemTokens[0], 'आलू')
  assert.strictEqual(segments[0].spokenPrice, 50)
  assert.strictEqual(segments[0].rupeeWordPresent, true)
  assert.strictEqual(segments[0].hasLeadingQty, false)
})

test('segmentTranscript keeps a mixed rate-update + sale utterance as two clean segments', () => {
  // "आलू तीस रुपये किलो, दो किलो प्याज" -- Aaloo is a bare rate announcement,
  // Pyaz is a normal 2kg sale with no price at all.
  const { segments } = segmentTranscript('आलू तीस रुपये किलो दो किलो प्याज')
  assert.strictEqual(segments.length, 2)

  const aaloo = segments[0]
  assert.strictEqual(aaloo.itemTokens[0], 'आलू')
  assert.strictEqual(aaloo.spokenPrice, 30)
  assert.strictEqual(aaloo.hasLeadingQty, false)

  const pyaz = segments[1]
  assert.strictEqual(pyaz.itemTokens[0], 'प्याज')
  assert.strictEqual(pyaz.spokenPrice ?? null, null)
  assert.strictEqual(pyaz.quantity, 2)
  assert.strictEqual(pyaz.unit, 'KG')
})

test('segmentTranscript resolves a compound bulk total on the correct item only', () => {
  // "पाँच किलो पनीर दो सौ पचास रुपये तीन किलो आलू" -- Paneer has the 250 total,
  // Aaloo has none.
  const { segments } = segmentTranscript('पांच किलो पनीर दो सौ पचास रुपये तीन किलो आलू')
  assert.strictEqual(segments.length, 2)
  assert.strictEqual(segments[0].itemTokens[0], 'पनीर')
  assert.strictEqual(segments[0].spokenPrice, 250)
  assert.strictEqual(segments[0].hasLeadingQty, true)
  assert.strictEqual(segments[1].itemTokens[0], 'आलू')
  assert.strictEqual(segments[1].spokenPrice ?? null, null)
})

test('classifySegmentPriceIntent: no leading qty + price -> RATE_UPDATE', () => {
  const c = classifySegmentPriceIntent({ spokenPrice: 30, hasLeadingQty: false })
  assert.strictEqual(c.priceIntent, 'RATE_UPDATE')
  assert.strictEqual(c.spokenPrice, 30)
})

test('classifySegmentPriceIntent: leading qty + price -> BULK_SALE_TOTAL', () => {
  const c = classifySegmentPriceIntent({ spokenPrice: 250, hasLeadingQty: true })
  assert.strictEqual(c.priceIntent, 'BULK_SALE_TOTAL')
  assert.strictEqual(c.spokenPrice, 250)
})

test('classifySegmentPriceIntent: no price at all -> NONE', () => {
  const c = classifySegmentPriceIntent({ spokenPrice: null, hasLeadingQty: true })
  assert.strictEqual(c.priceIntent, 'NONE')
  assert.strictEqual(c.spokenPrice, null)
})

test('classifySegmentPriceIntent: null segment (unaligned item) -> NONE, does not throw', () => {
  const c = classifySegmentPriceIntent(null)
  assert.strictEqual(c.priceIntent, 'NONE')
})

test('segmentTranscript still segments three plain items with no prices at all', () => {
  const { segments } = segmentTranscript('2 किलो सेब 3 किलो आलू 1 दर्जन केला')
  assert.strictEqual(segments.length, 3)
  assert.strictEqual(segments[0].quantity, 2)
  assert.strictEqual(segments[1].quantity, 3)
  assert.strictEqual(segments[2].quantity, 1)
  assert.strictEqual(segments[2].unit, 'DOZEN')
  for (const seg of segments) assert.strictEqual(seg.spokenPrice ?? null, null)
})
