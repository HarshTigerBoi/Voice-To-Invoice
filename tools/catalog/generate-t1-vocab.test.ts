import { assertEquals, assert } from 'jsr:@std/assert'
import {
  processPhoneticCollisions,
  filterFewerThan2Aliases,
  dedupeCanonical,
  type GeneratedEntry,
} from './generate-t1-vocab.ts'

Deno.test('filterFewerThan2Aliases rejects entries with fewer than 2 total aliases', () => {
  const candidates: GeneratedEntry[] = [
    {
      canonical: 'Valid Item',
      vertical: 'kirana',
      category_key: 'rice',
      default_unit: 'KG',
      aliases: { devanagari: ['चावल'], hinglish: ['chawal'], english: [] },
      size_modifiers_apply: false,
    },
    {
      canonical: 'Invalid Item',
      vertical: 'kirana',
      category_key: 'rice',
      default_unit: 'KG',
      aliases: { devanagari: [], hinglish: ['only_one'], english: [] },
      size_modifiers_apply: false,
    },
  ]

  const result = filterFewerThan2Aliases(candidates)
  assertEquals(result.surviving.length, 1)
  assertEquals(result.surviving[0].canonical, 'Valid Item')
  assertEquals(result.rejected.length, 1)
  assertEquals(result.rejected[0].canonical, 'Invalid Item')
  assertEquals(result.rejected[0].reason, 'fewer_than_2_aliases')
})

Deno.test('dedupeCanonical keeps first occurrence and logs duplicates', () => {
  const candidates: GeneratedEntry[] = [
    {
      canonical: 'Sugar',
      vertical: 'kirana',
      category_key: 'sugar_jaggery',
      default_unit: 'KG',
      aliases: { devanagari: ['चीनी'], hinglish: ['chini'], english: ['Sugar'] },
      size_modifiers_apply: false,
    },
    {
      canonical: 'Sugar',
      vertical: 'kirana',
      category_key: 'sweets',
      default_unit: 'KG',
      aliases: { devanagari: ['चीनी'], hinglish: ['chini'], english: ['Sugar'] },
      size_modifiers_apply: false,
    },
  ]

  const result = dedupeCanonical(candidates)
  assertEquals(result.surviving.length, 1)
  assertEquals(result.surviving[0].category_key, 'sugar_jaggery')
  assertEquals(result.duplicates.length, 1)
  assertEquals(result.duplicates[0].canonical, 'Sugar')
  assertEquals(result.duplicates[0].kept_in_category, 'sugar_jaggery')
})

Deno.test('processPhoneticCollisions rejects cross-category collision and keeps same-category match', () => {
  const candidates: GeneratedEntry[] = [
    // Cross-category collision pair on key "PASNATI" (from "basmati")
    {
      canonical: 'Basmati Rice',
      vertical: 'kirana',
      category_key: 'rice',
      default_unit: 'KG',
      aliases: { devanagari: ['बासमती'], hinglish: ['basmati'], english: ['Basmati Rice'] },
      size_modifiers_apply: false,
    },
    {
      canonical: 'Basmati Flavor',
      vertical: 'kirana',
      category_key: 'spices_whole',
      default_unit: 'PACKET',
      aliases: { devanagari: ['बासमती'], hinglish: ['basmati'], english: ['Basmati Flavor'] },
      size_modifiers_apply: false,
    },
    // Same-category match pair on key "TUL TAL" (from "toor dal")
    {
      canonical: 'Toor Dal',
      vertical: 'kirana',
      category_key: 'dal_pulses',
      default_unit: 'KG',
      aliases: { devanagari: ['तूर दाल'], hinglish: ['toor dal'], english: ['Toor Dal'] },
      size_modifiers_apply: false,
    },
    {
      canonical: 'Arhar Dal',
      vertical: 'kirana',
      category_key: 'dal_pulses',
      default_unit: 'KG',
      aliases: { devanagari: ['अरहर दाल'], hinglish: ['toor dal'], english: ['Arhar Dal'] },
      size_modifiers_apply: false,
    },
  ]

  const result = processPhoneticCollisions(candidates)

  // Same-category entries should survive (Toor Dal & Arhar Dal)
  const survivingCanonicals = result.surviving.map(e => e.canonical).sort()
  assertEquals(survivingCanonicals, ['Arhar Dal', 'Toor Dal'])

  // Cross-category entries should be rejected (Basmati Rice & Basmati Flavor)
  const rejectedCanonicals = result.rejected.map(r => r.canonical).sort()
  assertEquals(rejectedCanonicals, ['Basmati Flavor', 'Basmati Rice'])
  assert(result.rejected.every(r => r.reason === 'cross_category_collision'))

  // TSV Collision log must contain cross-category collisions for both keys (PASANATI and PASNATI)
  assertEquals(result.collisions.length, 2)
  assert(result.collisions.some(line => line.startsWith('PASANATI') && line.includes('rice') && line.includes('spices_whole')))
  assert(result.collisions.some(line => line.startsWith('PASNATI') && line.includes('rice') && line.includes('spices_whole')))
})
