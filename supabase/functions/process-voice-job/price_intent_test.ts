import assert from 'node:assert'
import { test } from 'node:test'
import { detectPriceIntent, parseCompoundNumberSequence, implausibilityReason } from './price_intent.ts'

test('parseCompoundNumberSequence resolves compound Hindi/Hinglish numbers correctly', () => {
  assert.strictEqual(parseCompoundNumberSequence(['पचास']), 50)
  assert.strictEqual(parseCompoundNumberSequence(['250']), 250)
  assert.strictEqual(parseCompoundNumberSequence(['दो', 'सौ']), 200)
  assert.strictEqual(parseCompoundNumberSequence(['दो', 'सौ', 'पचास']), 250)
  assert.strictEqual(parseCompoundNumberSequence(['डेढ़', 'सौ']), 150)
  assert.strictEqual(parseCompoundNumberSequence(['ढाई', 'सौ']), 250)
  assert.strictEqual(parseCompoundNumberSequence(['तीन', 'सौ', 'पचास']), 350)
  assert.strictEqual(parseCompoundNumberSequence(['पाँच', 'सौ']), 500)
  assert.strictEqual(parseCompoundNumberSequence(['एक', 'हजार', 'दो', 'सौ']), 1200)
})

test('detectPriceIntent classifies RATE_UPDATE with compound spoken prices', () => {
  const p1 = detectPriceIntent('गोल्ड पचास रुपये')
  assert.strictEqual(p1.priceIntent, 'RATE_UPDATE')
  assert.strictEqual(p1.spokenPrice, 50)
  assert.strictEqual(p1.hasLeadingQty, false)

  const p2 = detectPriceIntent('गोल्ड दो सौ पचास रुपये')
  assert.strictEqual(p2.priceIntent, 'RATE_UPDATE')
  assert.strictEqual(p2.spokenPrice, 250)
  assert.strictEqual(p2.hasLeadingQty, false)

  const p3 = detectPriceIntent('गोल्ड डेढ़ सौ रुपये')
  assert.strictEqual(p3.priceIntent, 'RATE_UPDATE')
  assert.strictEqual(p3.spokenPrice, 150)
  assert.strictEqual(p3.hasLeadingQty, false)
})

test('detectPriceIntent classifies BULK_SALE_TOTAL with leading quantity and compound total', () => {
  const p1 = detectPriceIntent('पाँच पैकेट गोल्ड दो सौ पचास रुपये')
  assert.strictEqual(p1.priceIntent, 'BULK_SALE_TOTAL')
  assert.strictEqual(p1.spokenPrice, 250)
  assert.strictEqual(p1.hasLeadingQty, true)

  const p2 = detectPriceIntent('5 kg aaloo 200 rs')
  assert.strictEqual(p2.priceIntent, 'BULK_SALE_TOTAL')
  assert.strictEqual(p2.spokenPrice, 200)
  assert.strictEqual(p2.hasLeadingQty, true)
})

test('detectPriceIntent classifies AMBIGUOUS_UNTRUSTED when price number lacks Rupee word', () => {
  const p1 = detectPriceIntent('पाँच आलू पचास')
  assert.strictEqual(p1.priceIntent, 'AMBIGUOUS_UNTRUSTED')
  assert.strictEqual(p1.hasAmbiguousPriceNumber, true)
})

test('implausibilityReason does NOT falsely flag valid compound bulk sales', () => {
  // "पाँच किलो आलू दो सौ पचास रुपये" -> qty 5, priceAtSale 50, total 250
  const reason = implausibilityReason('KG', 5, 250, 'पाँच किलो आलू दो सौ पचास रुपये', 50)
  assert.strictEqual(reason, null)
})

test('implausibilityReason FLAGS unrepresented spoken numbers >= 10', () => {
  // Spoke "पचास रुपये" (50) but parsed total is 10 and priceAtSale is 10 -> MUST FLAG!
  const reason = implausibilityReason('KG', 5, 10, 'पाँच किलो आलू पचास रुपये', 10)
  assert.notStrictEqual(reason, null)
  assert.ok(reason?.includes('50'))
})
