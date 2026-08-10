import assert from 'node:assert'
import { test } from 'node:test'

const WEIGHT_BASE_UNITS: Record<string, number> = { GRAM: 1, KG: 1000 }
const VOLUME_BASE_UNITS: Record<string, number> = { ML: 1, LITRE: 1000 }

function unitConversionFactor(spokenUnit: string, catalogUnit: string): number {
  const s = (spokenUnit || '').toUpperCase()
  const c = (catalogUnit || '').toUpperCase()
  if (s === c) return 1
  if (s in WEIGHT_BASE_UNITS && c in WEIGHT_BASE_UNITS) return WEIGHT_BASE_UNITS[s] / WEIGHT_BASE_UNITS[c]
  if (s in VOLUME_BASE_UNITS && c in VOLUME_BASE_UNITS) return VOLUME_BASE_UNITS[s] / VOLUME_BASE_UNITS[c]
  return 1
}

test('unitConversionFactor converts GRAM spoken against KG catalog item correctly', () => {
  // Spoken 500 GRAM Aaloo against catalog KG price ₹50 -> factor 500/1000 = 0.001
  const factor = unitConversionFactor('GRAM', 'KG')
  assert.strictEqual(factor, 0.001)
  const total = 500 * factor * 50
  assert.strictEqual(total, 25)
})

test('unitConversionFactor keeps GRAM spoken against GRAM catalog item unchanged', () => {
  // Spoken 50 GRAM Haldi Powder against catalog GRAM price ₹0.25 -> factor 1
  const factor = unitConversionFactor('GRAM', 'GRAM')
  assert.strictEqual(factor, 1)
  const total = 50 * factor * 0.25
  assert.strictEqual(total, 12.5)
})

test('unitConversionFactor converts ML spoken against LITRE catalog item correctly', () => {
  // Spoken 500 ML Milk against catalog LITRE price ₹60 -> factor 0.001
  const factor = unitConversionFactor('ML', 'LITRE')
  assert.strictEqual(factor, 0.001)
  const total = 500 * factor * 60
  assert.strictEqual(total, 30)
})

test('unitConversionFactor returns 1 for identical or non-convertible units', () => {
  assert.strictEqual(unitConversionFactor('PACKET', 'KG'), 1)
  assert.strictEqual(unitConversionFactor('KG', 'KG'), 1)
})
