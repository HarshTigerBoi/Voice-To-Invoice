import { phoneticKey, normalizedDistance, UNIT_SET, HINDI_NUMBER_MAP } from './phonetic.ts'
import { getQualifiers } from './lexicon.ts'

/**
 * Normalized phonetic distance at which a spoken word is treated as naming a word inside a
 * candidate SKU name. Same value as item_resolution.ts's NAME_AGREEMENT_MAX_NORM and for the
 * same reason: cross-script pairs that ARE the same word (गोल्ड/Gold) sit just under it.
 */
const NARROWING_MAX_NORM = 0.15

/**
 * Base-commodity identity — what a thing IS, independent of language, spelling or brand.
 *
 * This is the layer that lets the system know "rice", "chawal", "चावल" and the SKU
 * "Basmati Rice" all denote one commodity, which no amount of phonetic matching can
 * establish: phoneticKey('rice') is 'LICI' and phoneticKey('चावल') is 'CAVAL'. Translation
 * pairs share no phones by construction. See Docs/concept_layer_plan.md / ISSUE-126.
 *
 * Division of labour, deliberately:
 *   - PHONETICS (phonetic.ts) handles sound-alike spelling variants — chawal/chaval,
 *     bindi/bhindi. That is what it is good at, and why `surfaces` below does NOT need to
 *     enumerate spellings.
 *   - CONCEPT IDS handle synonyms and translations across languages, which phonetics cannot.
 *   - The LLM handles everything absent from both, and is the source of truth for assigning
 *     a concept to a new SKU. This table is the offline/fast-path cache, not the authority.
 *
 * A concept is NOT a sellable product. `milk` is a concept; "Amul Gold Milk @ ₹34/PACKET" is
 * the product. Keeping them separate is what preserves ISSUE-109 (a brand-qualified item is
 * a distinct product at a distinct price) while still resolving the generic spoken word.
 */
export interface ConceptEntry {
  /** Stable id. Never shown to the shopkeeper; only used to join spoken words to SKUs. */
  id: string
  /**
   * Enough surfaces to seed the deterministic path, in both scripts where the Hindi word is
   * in common use. Spelling variants are intentionally omitted — phoneticKey collapses them.
   */
  surfaces: string[]
}

export const CONCEPTS: ConceptEntry[] = [
  // Grains, staples & pulses
  { id: 'rice', surfaces: ['चावल', 'chawal', 'rice', 'akki'] },
  { id: 'atta', surfaces: ['आटा', 'atta', 'flour', 'wheat flour'] },
  { id: 'maida', surfaces: ['मैदा', 'maida'] },
  { id: 'suji', surfaces: ['सूजी', 'suji', 'semolina', 'rava'] },
  { id: 'poha', surfaces: ['पोहा', 'poha', 'flattened rice'] },
  { id: 'dal', surfaces: ['दाल', 'daal', 'dal', 'lentil', 'lentils', 'pulse'] },
  { id: 'chana', surfaces: ['चना', 'chana', 'chickpea', 'gram'] },
  { id: 'rajma', surfaces: ['राजमा', 'rajma', 'kidney bean'] },

  // Dairy
  { id: 'milk', surfaces: ['दूध', 'doodh', 'milk'] },
  { id: 'curd', surfaces: ['दही', 'dahi', 'curd', 'yoghurt', 'yogurt'] },
  { id: 'buttermilk', surfaces: ['छाछ', 'मट्ठा', 'chaas', 'buttermilk'] },
  { id: 'paneer', surfaces: ['पनीर', 'paneer', 'cottage cheese'] },
  { id: 'ghee', surfaces: ['घी', 'ghee', 'clarified butter'] },
  { id: 'butter', surfaces: ['मक्खन', 'butter'] },
  { id: 'cream', surfaces: ['मलाई', 'malai', 'cream'] },
  { id: 'egg', surfaces: ['अंडा', 'अंडे', 'anda', 'egg', 'eggs'] },

  // Vegetables
  { id: 'potato', surfaces: ['आलू', 'aaloo', 'potato', 'potatoes'] },
  { id: 'onion', surfaces: ['प्याज', 'pyaz', 'onion', 'onions'] },
  { id: 'tomato', surfaces: ['टमाटर', 'tamatar', 'tomato', 'tomatoes'] },
  { id: 'brinjal', surfaces: ['बैंगन', 'baingan', 'brinjal', 'eggplant', 'aubergine'] },
  { id: 'okra', surfaces: ['भिंडी', 'bhindi', 'okra', 'ladyfinger'] },
  { id: 'carrot', surfaces: ['गाजर', 'gajar', 'carrot', 'carrots'] },
  { id: 'peas', surfaces: ['मटर', 'matar', 'peas', 'green peas'] },
  { id: 'spinach', surfaces: ['पालक', 'palak', 'spinach'] },
  { id: 'cucumber', surfaces: ['खीरा', 'kheera', 'cucumber'] },
  { id: 'garlic', surfaces: ['लहसुन', 'lahsun', 'garlic'] },
  { id: 'ginger', surfaces: ['अदरक', 'adrak', 'ginger'] },
  { id: 'coriander', surfaces: ['धनिया', 'dhaniya', 'coriander', 'cilantro'] },
  { id: 'chilli', surfaces: ['मिर्च', 'mirch', 'mirchi', 'chilli', 'chili', 'pepper'] },
  { id: 'capsicum', surfaces: ['शिमला मिर्च', 'shimla mirch', 'capsicum', 'bell pepper'] },
  { id: 'cauliflower', surfaces: ['गोभी', 'फूलगोभी', 'gobhi', 'cauliflower'] },
  { id: 'cabbage', surfaces: ['पत्तागोभी', 'patta gobhi', 'cabbage'] },
  { id: 'bittergourd', surfaces: ['करेला', 'karela', 'bitter gourd', 'bittergourd'] },
  { id: 'bottlegourd', surfaces: ['लौकी', 'घीया', 'lauki', 'bottle gourd', 'bottlegourd'] },
  { id: 'ridgegourd', surfaces: ['तोरई', 'torai', 'ridge gourd'] },
  { id: 'pumpkin', surfaces: ['कद्दू', 'kaddu', 'pumpkin'] },
  { id: 'radish', surfaces: ['मूली', 'mooli', 'radish'] },
  { id: 'beetroot', surfaces: ['चुकंदर', 'chukandar', 'beetroot', 'beet'] },
  { id: 'broccoli', surfaces: ['ब्रोकली', 'broccoli'] },

  // Fruit
  { id: 'apple', surfaces: ['सेब', 'seb', 'apple', 'apples'] },
  { id: 'banana', surfaces: ['केला', 'kela', 'banana', 'bananas'] },
  { id: 'lemon', surfaces: ['नींबू', 'nimbu', 'lemon', 'lime'] },
  { id: 'grapes', surfaces: ['अंगूर', 'angoor', 'grape', 'grapes'] },
  { id: 'mango', surfaces: ['आम', 'aam', 'mango', 'mangoes'] },
  { id: 'orange', surfaces: ['संतरा', 'santra', 'orange', 'oranges'] },
  { id: 'papaya', surfaces: ['पपीता', 'papita', 'papaya'] },
  { id: 'pomegranate', surfaces: ['अनार', 'anar', 'pomegranate'] },
  { id: 'watermelon', surfaces: ['तरबूज', 'tarbooj', 'watermelon'] },
  { id: 'guava', surfaces: ['अमरूद', 'amrood', 'guava'] },
  { id: 'dragonfruit', surfaces: ['ड्रैगन फ्रूट', 'dragon fruit', 'dragonfruit'] },

  // Spices & condiments
  { id: 'salt', surfaces: ['नमक', 'namak', 'salt'] },
  { id: 'sugar', surfaces: ['चीनी', 'शक्कर', 'chini', 'shakkar', 'sugar'] },
  { id: 'turmeric', surfaces: ['हल्दी', 'haldi', 'turmeric'] },
  { id: 'cumin', surfaces: ['जीरा', 'jeera', 'cumin'] },
  { id: 'red-chilli-powder', surfaces: ['लाल मिर्च पाउडर', 'lal mirch powder', 'red chilli powder'] },
  { id: 'garam-masala', surfaces: ['गरम मसाला', 'garam masala'] },
  { id: 'amchur', surfaces: ['अमचूर', 'amchur', 'dry mango powder'] },
  { id: 'cardamom', surfaces: ['इलायची', 'elaichi', 'cardamom'] },

  // Oils
  { id: 'oil', surfaces: ['तेल', 'tel', 'oil'] },

  // Packaged & beverages
  { id: 'biscuit', surfaces: ['बिस्किट', 'biscuit', 'biscuits', 'cookie', 'cookies'] },
  { id: 'bread', surfaces: ['ब्रेड', 'डबल रोटी', 'bread'] },
  { id: 'rusk', surfaces: ['रस्क', 'टोस्ट', 'rusk', 'toast'] },
  { id: 'noodles', surfaces: ['नूडल्स', 'noodles'] },
  { id: 'tea', surfaces: ['चाय', 'चायपत्ती', 'chai', 'chai patti', 'tea'] },
  { id: 'coffee', surfaces: ['कॉफ़ी', 'coffee'] },
  { id: 'soft-drink', surfaces: ['कोल्ड ड्रिंक', 'cold drink', 'soft drink', 'soda'] },
  { id: 'energy-drink', surfaces: ['एनर्जी ड्रिंक', 'energy drink'] },
  { id: 'peanut', surfaces: ['मूंगफली', 'moongphali', 'peanut', 'groundnut'] },
  { id: 'cashew', surfaces: ['काजू', 'kaju', 'cashew'] },
  { id: 'almond', surfaces: ['बादाम', 'badam', 'almond', 'almonds'] },
]

/** Surfaces that must never be read as an item — they qualify one. Mirrors QUALIFIERS. */
const SURFACE_TO_CONCEPT = new Map<string, string>()
const PHONETIC_TO_CONCEPT = new Map<string, string>()

for (const c of CONCEPTS) {
  for (const s of c.surfaces) {
    const norm = s.toLowerCase().trim()
    if (!SURFACE_TO_CONCEPT.has(norm)) SURFACE_TO_CONCEPT.set(norm, c.id)
    // Phonetic index is what absorbs spelling variants (chawal/chaval, bindi/bhindi)
    // without listing them. First writer wins so an earlier concept is not shadowed by a
    // later homophone.
    const pk = phoneticKey(norm)
    if (pk && !PHONETIC_TO_CONCEPT.has(pk)) PHONETIC_TO_CONCEPT.set(pk, c.id)
  }
}

export const CONCEPT_IDS: ReadonlySet<string> = new Set(CONCEPTS.map(c => c.id))

/**
 * Concept for a spoken/written token, exact surface first then phonetic.
 *
 * Returns null rather than guessing: an unknown word is the LLM's job, and a wrong concept
 * is worse than no concept because it would route a real item to the wrong SKU.
 */
export function conceptOfSpoken(token: string): string | null {
  const norm = (token || '').toLowerCase().trim()
  if (!norm) return null
  const exact = SURFACE_TO_CONCEPT.get(norm)
  if (exact) return exact
  const pk = phoneticKey(norm)
  if (pk) {
    const byPhone = PHONETIC_TO_CONCEPT.get(pk)
    if (byPhone) return byPhone
  }
  return null
}

/**
 * Concept for a catalog SKU display name, e.g. "Basmati Rice" -> 'rice',
 * "Sugar (Madhur)" -> 'sugar', "Chana Dal" -> 'dal'.
 *
 * Runs at catalog-write time and in the backfill, never on the speech path. Brand and
 * variety words are stripped first so "Amul Gold Milk" reduces to the base noun.
 */
export function conceptOfSku(skuName: string): string | null {
  const raw = (skuName || '').trim()
  if (!raw) return null

  // Parenthesised qualifiers are a naming convention in this catalog -- "Sugar (Madhur)",
  // "Curd (Dahi)", "Atta (Aashirvaad)". The parenthetical may be either a brand or a
  // synonym, so try the whole string first, then the part before the bracket.
  const whole = conceptOfSpoken(raw)
  if (whole) return whole

  const beforeBracket = raw.split('(')[0].trim()
  if (beforeBracket && beforeBracket !== raw) {
    const c = conceptOfSpoken(beforeBracket)
    if (c) return c
  }

  const cleaned = raw.replace(/[()&]/g, ' ').replace(/\s+/g, ' ').trim()
  const { brands, varieties } = getQualifiers(cleaned)
  const qualifierWords = new Set(
    [...brands, ...varieties].flatMap(q => q.toLowerCase().split(/\s+/))
  )

  const tokens = cleaned.toLowerCase().split(/\s+/).filter(Boolean)

  // Longest span first: "shimla mirch" and "garam masala" are two-token concepts that must
  // not be shadowed by their single-token parts ('chilli', and no concept respectively).
  //
  // Within a span, scan RIGHT to LEFT because these compounds are head-final in both
  // languages -- the last noun is the commodity and the earlier ones qualify it. Scanning
  // left to right resolves "Chana Dal" to 'chana' (whole chickpeas, a different product)
  // instead of 'dal', and "Basmati Rice" would depend on 'basmati' happening to be unknown.
  for (let span = Math.min(3, tokens.length); span >= 1; span--) {
    for (let i = tokens.length - span; i >= 0; i--) {
      const probe = tokens.slice(i, i + span).join(' ')
      if (span === 1 && qualifierWords.has(probe)) continue
      const c = conceptOfSpoken(probe)
      if (c) return c
    }
  }
  return null
}

export interface ConceptSku {
  id: string
  name: string
  price: number
  unit_id?: string | null
  concept?: string | null
}

export type ConceptResolutionKind = 'UNIQUE' | 'AMBIGUOUS' | 'NOT_STOCKED'

export interface ConceptResolution {
  kind: ConceptResolutionKind
  /** Set only when kind === 'UNIQUE'. */
  sku: ConceptSku | null
  /** Every SKU in this shop carrying the concept, for the review-queue message. */
  candidates: ConceptSku[]
  concept: string | null
}

/**
 * Maps a concept to the shop's own SKUs, narrowing by any spoken brand/variety.
 *
 * The AMBIGUOUS case is deliberately NOT resolved by picking a "best" candidate. This shop
 * stocks Amul Gold Milk (₹34), Amul Taaza Milk (₹27) and Saras Milk (₹30); silently
 * choosing one is a real money error, and is exactly the defect the seeded alias
 * `दूध -> Amul Gold Milk` (db-setup/index.ts:141-144) already causes today.
 */
export function resolveConceptToSkus(
  concept: string | null,
  spokenPhrase: string,
  catalog: ConceptSku[]
): ConceptResolution {
  if (!concept) return { kind: 'NOT_STOCKED', sku: null, candidates: [], concept: null }

  const all = catalog.filter(ci => (ci.concept || null) === concept)
  if (all.length === 0) return { kind: 'NOT_STOCKED', sku: null, candidates: [], concept }
  if (all.length === 1) return { kind: 'UNIQUE', sku: all[0], candidates: all, concept }

  // More than one SKU shares the concept. Narrow using every distinguishing word actually
  // spoken, matched PHONETICALLY against the candidate names.
  //
  // Deliberately not driven by the QUALIFIERS list: that would require registering every
  // product line a shop might stock -- Gold, Taaza, Bourbon, Parle-G, Chana, Moong, Toor,
  // Mustard -- which is the same unbounded enumeration this whole layer exists to remove.
  // Phonetic token overlap needs no list and is exactly what phonetics is good at.
  const spokenTokens = (spokenPhrase || '')
    .toLowerCase()
    .replace(/[।.,?!\-()&]/g, ' ')
    .split(/\s+/)
    .filter(Boolean)
    .filter(t => !UNIT_SET.some(u => u.toLowerCase() === t))
    .filter(t => HINDI_NUMBER_MAP[t] === undefined && !/^\d+(\.\d+)?$/.test(t))
    // Drop the word that named the concept itself -- "दूध" distinguishes nothing between
    // three milks; only the words around it do.
    .filter(t => conceptOfSpoken(t) !== concept)
    .map(t => phoneticKey(t))
    .filter(Boolean)

  if (spokenTokens.length > 0) {
    let best: ConceptSku[] = []
    let bestScore = 0
    for (const ci of all) {
      const nameKeys = ci.name.toLowerCase().replace(/[()&\-]/g, ' ').split(/\s+/)
        .filter(Boolean).map(t => phoneticKey(t)).filter(Boolean)
      let score = 0
      for (const st of spokenTokens) {
        if (nameKeys.some(nk => normalizedDistance(st, nk) <= NARROWING_MAX_NORM)) score++
      }
      if (score > bestScore) { bestScore = score; best = [ci] }
      else if (score === bestScore && score > 0) best.push(ci)
    }
    if (bestScore > 0 && best.length === 1) {
      return { kind: 'UNIQUE', sku: best[0], candidates: all, concept }
    }
    if (bestScore > 0 && best.length > 1) {
      return { kind: 'AMBIGUOUS', sku: null, candidates: best, concept }
    }
  }

  return { kind: 'AMBIGUOUS', sku: null, candidates: all, concept }
}

/** Review-queue text naming the actual choices, so the shopkeeper can pick. */
export function ambiguousConceptReason(concept: string, candidates: ConceptSku[]): string {
  const list = candidates
    .map(c => `${c.name}${c.price ? ` ₹${c.price}` : ''}`)
    .join(', ')
  return `'${concept}' matches ${candidates.length} items in your catalog — pick one: ${list}`
}
