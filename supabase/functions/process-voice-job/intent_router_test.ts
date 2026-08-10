/**
 * The SAME trilingual fixture the client runs in `IntentRouterFixtureTest.kt`.
 *
 * Both sides must agree on every phrase. If they drift, a recording processed while the app is
 * closed behaves differently from the same words spoken with it open -- the worst failure mode for
 * a shopkeeper, because it is intermittent and invisible.
 *
 * Run: deno test --allow-all intent_router_test.ts
 */

import { assertEquals, assert } from 'https://deno.land/std@0.168.0/testing/asserts.ts'
import { classifyIntent, DIRECT_ROUTE_CONFIDENCE, type AssistantIntent, type ParsedLineLike } from './intent_router.ts'

const noLines: ParsedLineLike[] = []
const onePotato: ParsedLineLike[] = [{ item_name: 'आलू', quantity: 5, total: 150 }]
const fourPotato: ParsedLineLike[] = [{ item_name: 'आलू', quantity: 4 }]
const singleUnitPotato: ParsedLineLike[] = [{ item_name: 'आलू', quantity: 1, total: 30 }]
const oneTomato: ParsedLineLike[] = [{ item_name: 'टमाटर', quantity: 2, total: 40 }]

function expectIntent(expected: AssistantIntent, transcript: string, items: ParsedLineLike[] = []) {
  const res = classifyIntent(transcript, items)
  assertEquals(
    res.intent,
    expected,
    `"${transcript}" -> expected ${expected} but got ${res.intent} ` +
      `(confidence=${res.confidence.toFixed(2)}, scores=${JSON.stringify(res.scores)})`,
  )
}

Deno.test('READ_QUERY - Devanagari', () => {
  expectIntent('READ_QUERY', 'आज कितना कमाया')
  expectIntent('READ_QUERY', 'आलू कितना बचा है')
  expectIntent('READ_QUERY', 'रमेश का कितना बाकी है')
  expectIntent('READ_QUERY', 'आज का हिसाब बताओ')
})

Deno.test('READ_QUERY - Hinglish (all returned UNKNOWN before the rewrite)', () => {
  expectIntent('READ_QUERY', 'aaj ki sale kitni hui')
  expectIntent('READ_QUERY', 'aaloo kitna bacha hai')
  expectIntent('READ_QUERY', 'aaj kitna kamaya')
  expectIntent('READ_QUERY', 'ramesh ka kitna baki hai')
})

Deno.test('READ_QUERY - English', () => {
  expectIntent('READ_QUERY', 'how much stock is left')
  expectIntent('READ_QUERY', 'what is today sale')
  expectIntent('READ_QUERY', 'tell me today profit')
})

Deno.test('SALE - default when goods present with no keyword', () => {
  expectIntent('SALE', 'पाँच किलो आलू', onePotato)
  expectIntent('SALE', 'paanch kilo aaloo', onePotato)
  expectIntent('SALE', 'five kilo potato', onePotato)
  expectIntent('SALE', 'चार किलो आलू', fourPotato)
  expectIntent('SALE', 'चार किलो चाच', fourPotato)
  expectIntent('SALE', 'chaar kilo aloo', fourPotato)
})

Deno.test('SALE - a customer name alone is not Udhaar', () => {
  expectIntent('SALE', 'रमेश जी दो किलो टमाटर', oneTomato)
})

Deno.test('CREDIT_SALE - all scripts', () => {
  expectIntent('CREDIT_SALE', 'रमेश जी को दो किलो प्याज उधार', onePotato)
  expectIntent('CREDIT_SALE', 'ramesh ko do kilo pyaz udhaar', onePotato)
  expectIntent('CREDIT_SALE', 'khate mein likh do', onePotato)
  expectIntent('CREDIT_SALE', 'two kilo onion on credit', onePotato)
})

Deno.test('PAYMENT_RECEIVED - never confused with CREDIT_SALE', () => {
  expectIntent('PAYMENT_RECEIVED', 'Ramesh ne 500 diye')
  expectIntent('PAYMENT_RECEIVED', 'रमेश ने 500 दे दिए')
  expectIntent('PAYMENT_RECEIVED', 'ramesh ne 500 chuka diya')
  expectIntent('PAYMENT_RECEIVED', 'ramesh paid 500')
})

Deno.test('PRICE_UPDATE - all scripts', () => {
  expectIntent('PRICE_UPDATE', 'aaloo ka rate 30 kar do', singleUnitPotato)
  expectIntent('PRICE_UPDATE', 'आलू का रेट 30 कर दो', singleUnitPotato)
  expectIntent('PRICE_UPDATE', 'aaloo ka bhav 30', singleUnitPotato)
  expectIntent('PRICE_UPDATE', 'potato price 30', singleUnitPotato)
})

Deno.test('PRICE_UPDATE - does not swallow a bulk sale', () => {
  expectIntent('SALE', 'paanch kilo aaloo 150 ka', onePotato)
})

Deno.test('STOCK_IN - all scripts', () => {
  expectIntent('STOCK_IN', 'पचास किलो आलू आया', onePotato)
  expectIntent('STOCK_IN', 'pachas kilo aaloo aaya', onePotato)
  expectIntent('STOCK_IN', 'fifty kilo potato purchase', onePotato)
  expectIntent('STOCK_IN', 'maal aaya 20 kilo pyaz', onePotato)
})

Deno.test('RETURN - all scripts', () => {
  expectIntent('RETURN', 'do kilo aaloo wapas kar diya', onePotato)
  expectIntent('RETURN', 'दो किलो आलू वापस कर दिया', onePotato)
  expectIntent('RETURN', 'two kilo potato return', onePotato)
})

Deno.test('WASTE - all scripts', () => {
  expectIntent('WASTE', 'do kilo tamatar kharab ho gaya', onePotato)
  expectIntent('WASTE', 'दो किलो टमाटर खराब हो गया', onePotato)
  expectIntent('WASTE', 'two kilo tomato spoiled', onePotato)
})

Deno.test('EXPIRY beats WASTE (more specific)', () => {
  expectIntent('EXPIRY_WRITEOFF', 'ye biscuit expire ho gaya', onePotato)
  expectIntent('EXPIRY_WRITEOFF', 'इसकी तारीख निकल गई', onePotato)
})

Deno.test('VOID_LAST - all scripts', () => {
  expectIntent('VOID_LAST', 'galat ho gaya cancel karo')
  expectIntent('VOID_LAST', 'पिछला गलत था मिटा दो')
  expectIntent('VOID_LAST', 'cancel that, it was wrong')
})

Deno.test('ACTION_COMMAND is reachable at all', () => {
  // Previously impossible on both sides: ACTION_WORDS was declared and never read.
  expectIntent('ACTION_COMMAND', 'Ramesh ko bill bhejo')
  expectIntent('ACTION_COMMAND', 'रमेश को बिल भेज दो')
  expectIntent('ACTION_COMMAND', 'send bill to Ramesh on whatsapp')
  expectIntent('ACTION_COMMAND', 'ramesh ko yaad dilao')
  expectIntent('ACTION_COMMAND', 'रमेश को बिल भेजो')
  expectIntent('ACTION_COMMAND', 'ramesh ko call karo')
})

Deno.test('empty and noise yield UNKNOWN', () => {
  expectIntent('UNKNOWN', '')
  expectIntent('UNKNOWN', '   ')
})

Deno.test('a question is always read-only', () => {
  const res = classifyIntent('aaj ki sale kitni hui', noLines)
  assertEquals(res.intent, 'READ_QUERY')
  assert(res.isReadOnly, 'a question must never be able to write to the ledger')
})

Deno.test('write intents are never read-only', () => {
  for (const [text, items] of [
    ['paanch kilo aaloo', onePotato],
    ['ramesh ko udhaar likh do', onePotato],
    ['pachas kilo aaloo aaya', onePotato],
  ] as [string, ParsedLineLike[]][]) {
    assertEquals(classifyIntent(text, items).isReadOnly, false, `${text} should not be read-only`)
  }
})

Deno.test('ambiguous utterance is flagged rather than guessed', () => {
  const res = classifyIntent('kharab aaloo ka udhaar likh do', onePotato)
  assert(
    res.confidence < DIRECT_ROUTE_CONFIDENCE || res.needsArbitration,
    `expected low/mid confidence for a mixed utterance, got ${res.confidence}`,
  )
})
