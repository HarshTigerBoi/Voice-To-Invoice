import assert from 'node:assert'
import { test } from 'node:test'
import { canonicalOf } from './lexicon.ts'

test('canonicalOf brand/variant/unit compositional identity test cases', () => {
  assert.strictEqual(canonicalOf("saras milk"), "Saras Milk")
  assert.strictEqual(canonicalOf("amul milk"), "Amul Doodh")
  assert.strictEqual(canonicalOf("milk"), "Doodh")
  assert.strictEqual(canonicalOf("madhur sugar"), "Sugar (Madhur)")
  assert.strictEqual(canonicalOf("tata sugar"), "Tata Sugar")
  assert.strictEqual(canonicalOf("sugar"), "Sugar")
  assert.strictEqual(canonicalOf(" हरी मिर्च "), "Mirch")
  assert.strictEqual(canonicalOf("desi ghee"), "Desi Ghee")
  assert.strictEqual(canonicalOf("saras ghee"), "Saras Ghee")
  assert.strictEqual(canonicalOf("amul ghee"), "Amul Ghee")
  assert.strictEqual(canonicalOf("ghee"), "Ghee")
})
