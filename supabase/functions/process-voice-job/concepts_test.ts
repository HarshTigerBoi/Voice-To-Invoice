import { test } from 'node:test'
import assert from 'node:assert'
import {
  conceptOfSpoken,
  conceptOfSku,
  resolveConceptToSkus,
  type ConceptSku,
} from './concepts.ts'

/**
 * The live catalog of pilot shop 780d830d-bc71-4a3e-b0df-7f53a67d1dec, read from Supabase
 * on 2026-08-11. Concepts are asserted against real SKU names rather than invented ones so
 * a regression shows up as a real shop's item failing to resolve. See ISSUE-126.
 */
const LIVE_CATALOG: Array<[string, string, number]> = [
  ['Aaloo', 'KG', 30], ['Adrak', 'KG', 120], ['Amul Gold Milk', 'PACKET', 34],
  ['Amul Taaza Milk', 'PACKET', 27], ['Atta (Aashirvaad)', 'KG', 42], ['Baingan', 'KG', 40],
  ['Basmati Rice', 'KG', 90], ['Bhindi', 'KG', 50], ['Bourbon Biscuit', 'PACKET', 30],
  ['Bread', 'PACKET', 45], ['Broccoli', 'KG', 150], ['Butter', 'PACKET', 56],
  ['Chaas (Buttermilk)', 'PACKET', 15], ['Chana Dal', 'KG', 90], ['Curd (Dahi)', 'PACKET', 35],
  ['Desi Ghee', 'KG', 650], ['Dhaniya', 'KG', 60], ['Dragon Fruit', 'PIECE', 80],
  ['Eggs', 'DOZEN', 84], ['Fortune Refined Oil', 'LITRE', 140], ['Gajar', 'KG', 35],
  ['Garam Masala', 'GRAM', 0.6], ['Gobhi', 'PIECE', 30], ['Good Day Biscuit', 'PACKET', 20],
  ['Haldi Powder', 'GRAM', 0.25], ['Hide & Seek Biscuit', 'PACKET', 30], ['Jeera', 'GRAM', 0.4],
  ['Karela', 'KG', 45], ['Kheera', 'KG', 30], ['Lahsun', 'KG', 160],
  ['Lal Mirch Powder', 'GRAM', 0.3], ['Lauki', 'PIECE', 20], ['Maggi', 'PACKET', 14],
  ['Matar', 'KG', 60], ['Mirchi', 'KG', 80], ['Moong Dal', 'KG', 110],
  ['Mustard Oil', 'LITRE', 150], ['Nescafe Coffee', 'PACKET', 160], ['Nimbu', 'PIECE', 5],
  ['Palak', 'KG', 40], ['Paneer', 'KG', 360], ['Parle-G Biscuit', 'PACKET', 10],
  ['Poha', 'KG', 50], ['Pyaz', 'KG', 35], ['Red Bull', 'PIECE', 125],
  ['Rusk', 'PACKET', 40], ['Saras Milk', 'PACKET', 30], ['Sugar (Madhur)', 'KG', 45],
  ['Tamatar', 'KG', 40], ['Tata Salt', 'PACKET', 28], ['Tata Tea', 'PACKET', 140],
  ['Thums Up', 'PIECE', 40], ['Toor Dal', 'KG', 160],
]

/** Concepts conceptOfSku must derive unaided. Names absent here are expected to be NULL. */
const EXPECTED_SKU_CONCEPTS: Record<string, string> = {
  'Aaloo': 'potato', 'Adrak': 'ginger', 'Amul Gold Milk': 'milk', 'Amul Taaza Milk': 'milk',
  'Atta (Aashirvaad)': 'atta', 'Baingan': 'brinjal', 'Basmati Rice': 'rice', 'Bhindi': 'okra',
  'Bourbon Biscuit': 'biscuit', 'Bread': 'bread', 'Broccoli': 'broccoli', 'Butter': 'butter',
  'Chaas (Buttermilk)': 'buttermilk', 'Chana Dal': 'dal', 'Curd (Dahi)': 'curd',
  'Desi Ghee': 'ghee', 'Dhaniya': 'coriander', 'Dragon Fruit': 'dragonfruit', 'Eggs': 'egg',
  'Fortune Refined Oil': 'oil', 'Gajar': 'carrot', 'Garam Masala': 'garam-masala',
  'Gobhi': 'cauliflower', 'Good Day Biscuit': 'biscuit', 'Haldi Powder': 'turmeric',
  'Hide & Seek Biscuit': 'biscuit', 'Jeera': 'cumin', 'Karela': 'bittergourd',
  'Kheera': 'cucumber', 'Lahsun': 'garlic', 'Lal Mirch Powder': 'red-chilli-powder',
  'Lauki': 'bottlegourd', 'Matar': 'peas', 'Mirchi': 'chilli', 'Moong Dal': 'dal',
  'Mustard Oil': 'oil', 'Nescafe Coffee': 'coffee', 'Nimbu': 'lemon', 'Palak': 'spinach',
  'Paneer': 'paneer', 'Parle-G Biscuit': 'biscuit', 'Poha': 'poha', 'Pyaz': 'onion',
  'Rusk': 'rusk', 'Saras Milk': 'milk', 'Sugar (Madhur)': 'sugar', 'Tamatar': 'tomato',
  'Tata Salt': 'salt', 'Tata Tea': 'tea', 'Toor Dal': 'dal',
}

const catalogWithConcepts: ConceptSku[] = LIVE_CATALOG.map(([name, unit, price], i) => ({
  id: `sku-${i}`,
  name,
  price,
  unit_id: unit,
  concept: conceptOfSku(name),
}))

test('every live SKU with a derivable concept gets the right one', () => {
  for (const [name, expected] of Object.entries(EXPECTED_SKU_CONCEPTS)) {
    assert.strictEqual(conceptOfSku(name), expected, `${name} should be concept '${expected}'`)
  }
})

test('head-final compounds resolve to the commodity, not the qualifier', () => {
  // 'chana' is itself a concept (whole chickpeas), a different product from Chana Dal.
  assert.strictEqual(conceptOfSku('Chana Dal'), 'dal')
  assert.strictEqual(conceptOfSku('Basmati Rice'), 'rice')
  assert.strictEqual(conceptOfSku('Mustard Oil'), 'oil')
  assert.strictEqual(conceptOfSku('Amul Gold Milk'), 'milk')
})

test('cross-language surfaces share one concept — the thing phonetics cannot do', () => {
  for (const spoken of ['rice', 'chawal', 'चावल']) {
    assert.strictEqual(conceptOfSpoken(spoken), 'rice', `${spoken} should be concept 'rice'`)
  }
  for (const spoken of ['milk', 'doodh', 'दूध']) {
    assert.strictEqual(conceptOfSpoken(spoken), 'milk')
  }
})

test('phonetics absorbs spelling variants without listing them', () => {
  // 'chaval' and 'chaawal' are NOT in the surfaces array; phoneticKey collapses them.
  assert.strictEqual(conceptOfSpoken('chaval'), 'rice')
  assert.strictEqual(conceptOfSpoken('chaawal'), 'rice')
  assert.strictEqual(conceptOfSpoken('dudh'), 'milk')
})

test('ISSUE-030 pair stays distinct — amchur is not grapes', () => {
  assert.strictEqual(conceptOfSpoken('अमचूर'), 'amchur')
  assert.strictEqual(conceptOfSpoken('अंगूर'), 'grapes')
  assert.notStrictEqual(conceptOfSpoken('अमचूर'), conceptOfSpoken('अंगूर'))
})

test('job 735469d9: चावल resolves to the one rice SKU this shop stocks', () => {
  const r = resolveConceptToSkus(conceptOfSpoken('चावल'), 'चावल', catalogWithConcepts)
  assert.strictEqual(r.kind, 'UNIQUE')
  assert.strictEqual(r.sku?.name, 'Basmati Rice')
  assert.strictEqual(r.sku?.price, 90)
})

test('job b6ebbef5: दूध is ambiguous across three milk SKUs, never silently picked', () => {
  const r = resolveConceptToSkus(conceptOfSpoken('दूध'), 'दूध', catalogWithConcepts)
  assert.strictEqual(r.kind, 'AMBIGUOUS')
  assert.strictEqual(r.sku, null)
  assert.deepStrictEqual(
    r.candidates.map(c => c.name).sort(),
    ['Amul Gold Milk', 'Amul Taaza Milk', 'Saras Milk']
  )
})

test('a spoken brand narrows an ambiguous concept to one SKU', () => {
  const r = resolveConceptToSkus(conceptOfSpoken('दूध'), 'अमूल गोल्ड दूध', catalogWithConcepts)
  assert.strictEqual(r.kind, 'UNIQUE')
  assert.strictEqual(r.sku?.name, 'Amul Gold Milk')
})

test('narrowing needs no registered qualifier list', () => {
  // None of 'Gold', 'Taaza', 'Chana', 'Moong', 'Parle-G' or 'Mustard' is in QUALIFIERS.
  // Narrowing works purely on phonetic overlap with the candidate SKU names.
  const cases: Array<[string, string, string]> = [
    ['milk', 'amul taaza milk', 'Amul Taaza Milk'],
    ['milk', 'saras milk', 'Saras Milk'],
    ['dal', 'चना दाल', 'Chana Dal'],
    ['dal', 'moong dal', 'Moong Dal'],
    ['biscuit', 'parle g biscuit', 'Parle-G Biscuit'],
    ['oil', 'sarson ka tel mustard', 'Mustard Oil'],
  ]
  for (const [concept, spoken, expected] of cases) {
    const r = resolveConceptToSkus(concept, spoken, catalogWithConcepts)
    assert.strictEqual(r.kind, 'UNIQUE', `"${spoken}" should resolve uniquely`)
    assert.strictEqual(r.sku?.name, expected, `"${spoken}" -> ${expected}`)
  }
})

test('KNOWN GAP: Devanagari ज़ vs Latin z does not narrow — fails safe to a question', () => {
  // phonetic.ts folds nukta onto the base consonant, so ज़ -> 'j' -> class C, while Latin
  // 'z' -> class S. "ताज़ा"(TACA) vs "Taaza"(TASA) is 0.25 apart, over NARROWING_MAX_NORM.
  // The same gap already exists for प्याज(PIAC)/pyaz(PIAS); it is masked there only because
  // ITEM_LEXICON happens to list both surfaces.
  //
  // Asserted rather than skipped so the limitation is recorded, and because the outcome is
  // SAFE: the shopkeeper is asked which milk instead of being charged for the wrong one.
  // Fixing it means merging z into the C class in phoneticKey, which is a core-engine change
  // mirrored into Kotlin and out of scope for Stage 1. Tracked in Docs/concept_layer_plan.md.
  const r = resolveConceptToSkus('milk', 'अमूल ताज़ा दूध', catalogWithConcepts)
  assert.strictEqual(r.kind, 'AMBIGUOUS')
  // It still narrows on 'अमूल', excluding Saras — partial credit, not a wrong answer.
  assert.deepStrictEqual(
    r.candidates.map(c => c.name).sort(),
    ['Amul Gold Milk', 'Amul Taaza Milk']
  )
})

test('quantity and unit words never drive narrowing', () => {
  // "पांच किलो दूध" must stay ambiguous: neither 'पांच' nor 'किलो' distinguishes a milk.
  const r = resolveConceptToSkus('milk', 'पांच किलो दूध', catalogWithConcepts)
  assert.strictEqual(r.kind, 'AMBIGUOUS')
  assert.strictEqual(r.candidates.length, 3)
})

test('dal and biscuit are ambiguous; sugar and ghee are not, in THIS shop', () => {
  assert.strictEqual(resolveConceptToSkus('dal', 'दाल', catalogWithConcepts).kind, 'AMBIGUOUS')
  assert.strictEqual(resolveConceptToSkus('biscuit', 'बिस्किट', catalogWithConcepts).kind, 'AMBIGUOUS')
  assert.strictEqual(resolveConceptToSkus('sugar', 'चीनी', catalogWithConcepts).kind, 'UNIQUE')
  assert.strictEqual(resolveConceptToSkus('ghee', 'घी', catalogWithConcepts).kind, 'UNIQUE')
})

test('a concept the shop does not stock is NOT_STOCKED, not a wrong guess', () => {
  const r = resolveConceptToSkus(conceptOfSpoken('अनार'), 'अनार', catalogWithConcepts)
  assert.strictEqual(r.kind, 'NOT_STOCKED')
  assert.strictEqual(r.sku, null)
})

test('unknown words yield null rather than a wrong concept', () => {
  assert.strictEqual(conceptOfSpoken('zzzqqq'), null)
  assert.strictEqual(conceptOfSpoken(''), null)
})

// ── Stage 1b: what index.ts actually does with an ambiguous concept ──────────────
// index.ts computes finalNameConcept + resolveConceptToSkus itself (not exported as one
// function), so these tests exercise the exact same two calls with the exact shapes
// index.ts builds, proving the wiring produces AMBIGUOUS for both real job transcripts.

test('Stage 1b: दूध (job b6ebbef5) is ambiguous regardless of which brand either engine guessed', () => {
  // Segmenter's alias-forced pick was 'Amul Gold Milk'; AI's ungrounded guess was 'Saras Milk'.
  // Both must independently classify as ambiguous once resolveItemName hands either name over.
  for (const rawName of ['Amul Gold Milk', 'Saras Milk', 'दूध']) {
    const concept = conceptOfSpoken(rawName) ?? conceptOfSku(rawName)
    const r = resolveConceptToSkus(concept, 'पांच किलो दूध', catalogWithConcepts)
    assert.strictEqual(r.kind, 'AMBIGUOUS', `rawName="${rawName}" should be AMBIGUOUS, concept=${concept}`)
  }
})

test('Stage 1b: सत्रह किलो चावल (job 735469d9) is UNIQUE, not blocked', () => {
  for (const rawName of ['Basmati Rice', 'चावल']) {
    const concept = conceptOfSpoken(rawName) ?? conceptOfSku(rawName)
    const r = resolveConceptToSkus(concept, 'सत्रह किलो चावल', catalogWithConcepts)
    assert.strictEqual(r.kind, 'UNIQUE')
    assert.strictEqual(r.sku?.name, 'Basmati Rice')
  }
})

test('Stage 1b: an unambiguous single-SKU concept (sugar) is never blocked', () => {
  const concept = conceptOfSpoken('चीनी') ?? conceptOfSku('चीनी')
  const r = resolveConceptToSkus(concept, 'दो किलो चीनी', catalogWithConcepts)
  assert.strictEqual(r.kind, 'UNIQUE')
})
