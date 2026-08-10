/**
 * ONE canonical identity per item, with every spelling that maps to it.
 *
 * This file is the single source of truth for item identity. Before it existed the same
 * mapping lived in four places that had drifted apart (FuzzyCatalogMatcher.indicAliasMap and
 * three verbatim `when` blocks in AppDatabase/CatalogDao/SyncEngine), and DEFAULT_ITEM_VOCAB
 * listed each item's Devanagari and Latin spellings as if they were two different products —
 * which is what made "छः किलो अदरक" ambiguous against itself and sent a perfect parse to the
 * review queue at 0.55. See ISSUE-107.
 *
 * MIRRORED: app/src/main/java/com/voicetoinvoice/app/domain/lexicon/ItemLexicon.kt.
 * Any edit here must be made there in the same commit, with identical canonical strings.
 */
export interface LexiconEntry {
  /** The display name. MUST equal the catalog_items.name the shop actually uses. */
  canonical: string
  /** Every spoken/typed spelling that means `canonical`. Case-insensitive at lookup. */
  surfaces: string[]
}

export type QualifierKind = 'BRAND' | 'VARIETY'

export interface QualifierEntry {
  /** Canonical display form, used to build the composed identity. */
  canonical: string
  kind: QualifierKind
  surfaces: string[]
}

/**
 * Words that MODIFY an item rather than name one. "अमूल घी" is not a spelling of "घी" — it is a
 * different sellable product at a different price. Before this table existed, a generic word
 * could be listed as a surface of a branded canonical ('चीनी' -> 'Sugar (Madhur)'), which priced
 * loose sugar at Madhur's rate. See ISSUE-109.
 */
export const QUALIFIERS: QualifierEntry[] = [
  // Brands
  { canonical: 'Amul',       kind: 'BRAND', surfaces: ['अमूल', 'amul'] },
  { canonical: 'Saras',      kind: 'BRAND', surfaces: ['सरस', 'saras'] },
  { canonical: 'Madhur',     kind: 'BRAND', surfaces: ['मधुर', 'madhur'] },
  { canonical: 'Tata',       kind: 'BRAND', surfaces: ['टाटा', 'tata'] },
  { canonical: 'Aashirvaad', kind: 'BRAND', surfaces: ['आशीर्वाद', 'aashirvaad'] },
  { canonical: 'Fortune',    kind: 'BRAND', surfaces: ['फॉर्च्यून', 'fortune'] },
  { canonical: 'Mother Dairy', kind: 'BRAND', surfaces: ['मदर डेयरी', 'mother dairy'] },
  { canonical: 'Nestle',     kind: 'BRAND', surfaces: ['नेस्ले', 'nestle'] },
  { canonical: 'Britannia',  kind: 'BRAND', surfaces: ['ब्रिटानिया', 'britannia'] },
  { canonical: 'Parle',      kind: 'BRAND', surfaces: ['पारले', 'parle'] },

  // Varieties
  { canonical: 'Desi',   kind: 'VARIETY', surfaces: ['देसी', 'desi'] },
  { canonical: 'Green',  kind: 'VARIETY', surfaces: ['हरा', 'हरी', 'हरे', 'green'] },
  { canonical: 'Red',    kind: 'VARIETY', surfaces: ['लाल', 'red'] },
  { canonical: 'Black',  kind: 'VARIETY', surfaces: ['काला', 'काली', 'black'] },
  { canonical: 'White',  kind: 'VARIETY', surfaces: ['सफेद', 'सफ़ेद', 'white'] },
  { canonical: 'Fresh',  kind: 'VARIETY', surfaces: ['ताज़ा', 'ताजा', 'fresh'] },
  { canonical: 'Full Cream', kind: 'VARIETY', surfaces: ['फुल क्रीम', 'full cream'] },
  { canonical: 'Toned',  kind: 'VARIETY', surfaces: ['टोंड', 'toned'] },
]

export const ITEM_LEXICON: LexiconEntry[] = [
  // Produce & Vegetables
  { canonical: 'Pyaz', surfaces: ['प्याज', 'प्याज़', 'pyaz', 'pyaaz', 'onion', 'onions'] },
  { canonical: 'Tamatar', surfaces: ['टमाटर', 'tmatar', 'tamatar', 'tomato', 'tomatoes'] },
  { canonical: 'Aaloo', surfaces: ['आलू', 'अलू', 'alu', 'aaloo', 'aalu', 'potato', 'potatoes'] },
  { canonical: 'Adrak', surfaces: ['अदरक', 'adrak', 'ginger'] },
  { canonical: 'Bhindi', surfaces: ['भिंडी', 'bhindi', 'bindi', 'okra', 'ladyfinger'] },
  { canonical: 'Dhaniya', surfaces: ['धनिया', 'dhaniya', 'coriander'] },
  { canonical: 'Mirch', surfaces: ['मिर्च', 'हरी मिर्च', 'mirch', 'mirchi', 'chilli', 'chili'] },
  { canonical: 'Gobhi', surfaces: ['गोभी', 'gobhi', 'cauliflower', 'cabbage'] },
  { canonical: 'Baingan', surfaces: ['बैंगन', 'baingan', 'brinjal', 'eggplant'] },
  { canonical: 'Gajar', surfaces: ['गाजर', 'gajar', 'carrot'] },
  { canonical: 'Matar', surfaces: ['मटर', 'matar', 'peas'] },
  { canonical: 'Kheera', surfaces: ['खीरा', 'kheera', 'cucumber'] },
  { canonical: 'Palak', surfaces: ['पालक', 'palak', 'spinach'] },
  { canonical: 'Lahsun', surfaces: ['लहसुन', 'lahsun', 'garlic'] },
  { canonical: 'Broccoli', surfaces: ['ब्रोकली', 'broccoli'] },
  { canonical: 'Dragon Fruit', surfaces: ['ड्रैगन फ्रूट', 'dragon fruit', 'dragonfruit'] },
  { canonical: 'Seb', surfaces: ['सेब'] },
  { canonical: 'Kela', surfaces: ['केला'] },
  { canonical: 'Nimbu', surfaces: ['नींबू'] },
  { canonical: 'Shimla Mirch', surfaces: ['शिमला मिर्च'] },
  { canonical: 'Lauki', surfaces: ['लौकी'] },
  { canonical: 'Torai', surfaces: ['तोरई'] },
  { canonical: 'Karela', surfaces: ['करेला'] },
  { canonical: 'Kaddu', surfaces: ['कद्दू'] },
  { canonical: 'Mooli', surfaces: ['मूली'] },
  { canonical: 'Chukandar', surfaces: ['चुकंदर'] },
  { canonical: 'Angoor', surfaces: ['अंगूर'] },
  { canonical: 'Aam', surfaces: ['आम'] },
  { canonical: 'Santra', surfaces: ['संतरा'] },
  { canonical: 'Papita', surfaces: ['पपीता'] },
  { canonical: 'Anar', surfaces: ['अनार'] },
  { canonical: 'Tarbooj', surfaces: ['तरबूज'] },
  { canonical: 'Amrood', surfaces: ['अमरूद'] },

  // Dairy & Eggs
  // "गोल्ड"/"gold" alone is a DIFFERENT brand from Amul Gold — both are milk, but a shop
  // stocks and prices them separately. Only the Amul-qualified surfaces belong here.
  { canonical: 'Amul Gold Milk', surfaces: ['अमूल गोल्ड', 'amul gold', 'अमूल gold', 'gold milk', 'गोल्ड दूध'] },
  { canonical: 'Gold', surfaces: ['गोल्ड', 'gold'] },
  { canonical: 'Amul Taaza Milk', surfaces: ['अमूल ताज़ा', 'ताज़ा', 'taaza milk', 'amul taaza', 'taaza'] },
  { canonical: 'Saras Milk', surfaces: ['सरस दूध', 'saras milk'] },
  { canonical: 'Curd (Dahi)', surfaces: ['दही', 'curd', 'dahi'] },
  { canonical: 'Chaas (Buttermilk)', surfaces: ['छाछ', 'मट्ठा', 'chaas', 'chaach', 'buttermilk'] },
  { canonical: 'Paneer', surfaces: ['पनीर', 'paneer'] },
  // Plain "घी"/"ghee" is a different product from branded "Desi Ghee" and is priced
  // separately (₹1200/KG vs ₹650/KG in the reference shop). Do not collapse them.
  { canonical: 'Ghee', surfaces: ['घी', 'ghee', 'gi'] },
  { canonical: 'Desi Ghee', surfaces: ['desi ghee', 'देसी घी'] },
  { canonical: 'Butter', surfaces: ['मक्खन', 'बटर', 'butter', 'amul butter'] },
  { canonical: 'Eggs', surfaces: ['अंडे', 'अंडा', 'anda', 'egg', 'eggs'] },
  { canonical: 'Doodh', surfaces: ['दूध', 'doodh', 'milk', 'dudh'] },
  { canonical: 'Malai', surfaces: ['मलाई'] },

  // FMCG, Biscuits & Staples
  { canonical: 'Good Day Biscuit', surfaces: ['गुड डे', 'good day', 'good day biscuit'] },
  { canonical: 'Good Knight Mosquito Coil', surfaces: ['गुड नाइट', 'गुड नाइट कॉइल', 'good knight', 'good knight coil'] },
  { canonical: 'Yippee Noodles', surfaces: ['इप्पी', 'इप्पी नूडल्स', 'yippee', 'yippee noodles'] },
  { canonical: 'Maggi', surfaces: ['मैगी', 'मैगी नूडल्स', 'maggi'] },
  { canonical: 'Surf Excel', surfaces: ['सर्फ', 'सर्फ एक्सेल', 'surf', 'surf excel'] },
  { canonical: 'Fortune Refined Oil', surfaces: ['फॉर्च्यून तेल', 'fortune oil'] },
  // "आटा" is flour, not a brand. A shop stocks generic atta AND Aashirvaad atta at
  // different prices — only the brand-qualified surfaces may claim the branded identity.
  { canonical: 'Atta', surfaces: ['आटा', 'atta'] },
  { canonical: 'Atta (Aashirvaad)', surfaces: ['आशीर्वाद आटा', 'aashirvaad atta'] },
  { canonical: 'Bourbon Biscuit', surfaces: ['बर्बन', 'bourbon'] },
  { canonical: 'Parle-G Biscuit', surfaces: ['पारले जी', 'parle-g', 'parleg'] },
  { canonical: 'Ponds Cream', surfaces: ['पॉन्ड्स', 'पॉन्ड्स क्रीम', 'ponds', 'ponds cream'] },
  // "चीनी"/"sugar" is the commodity, not Madhur's brand. Madhur, Tata and loose sugar
  // are separate sellable items with separate prices.
  { canonical: 'Sugar', surfaces: ['चीनी', 'शक्कर', 'sugar', 'chini'] },
  { canonical: 'Sugar (Madhur)', surfaces: ['madhur sugar', 'मधुर चीनी'] },
  { canonical: 'Chawal', surfaces: ['चावल'] },
  { canonical: 'Namak', surfaces: ['नमक'] },
  { canonical: 'Tel', surfaces: ['तेल'] },
  { canonical: 'Maida', surfaces: ['मैदा'] },
  { canonical: 'Sooji', surfaces: ['सूजी'] },
  { canonical: 'Besan', surfaces: ['बेसन'] },
  { canonical: 'Poha', surfaces: ['पोहा'] },
  { canonical: 'Sewai', surfaces: ['सेवई'] },
  { canonical: 'Sabudana', surfaces: ['साबूदाना'] },
  { canonical: 'Gud', surfaces: ['गुड़'] },

  // Dals & Pulses
  { canonical: 'Chana', surfaces: ['चना'] },
  { canonical: 'Rajma', surfaces: ['राजमा'] },
  { canonical: 'Moong', surfaces: ['मूंग'] },
  { canonical: 'Masoor', surfaces: ['मसूर'] },
  { canonical: 'Arhar', surfaces: ['अरहर'] },
  { canonical: 'Toor', surfaces: ['तूर'] },
  { canonical: 'Urad', surfaces: ['उड़द'] },
  { canonical: 'Chole', surfaces: ['छोले'] },

  // Spices & Condiments
  { canonical: 'Amchoor', surfaces: ['अमचूर'] },
  { canonical: 'Haldi', surfaces: ['हल्दी'] },
  { canonical: 'Jeera', surfaces: ['जीरा'] },
  { canonical: 'Rai', surfaces: ['राई'] },
  { canonical: 'Methi', surfaces: ['मेथी'] },
  { canonical: 'Saunf', surfaces: ['सौंफ'] },
  { canonical: 'Elaichi', surfaces: ['इलायची'] },
  { canonical: 'Dalchini', surfaces: ['दालचीनी'] },
  { canonical: 'Laung', surfaces: ['लौंग'] },
  { canonical: 'Kali Mirch', surfaces: ['काली मिर्च'] },
  { canonical: 'Tejpatta', surfaces: ['तेजपत्ता'] },
  { canonical: 'Hing', surfaces: ['हींग'] },
  { canonical: 'Ajwain', surfaces: ['अजवाइन'] },
  { canonical: 'Garam Masala', surfaces: ['गरम मसाला'] },
  { canonical: 'Kasuri Methi', surfaces: ['कसूरी मेथी'] },
  { canonical: 'Imli', surfaces: ['इमली'] },
  { canonical: 'Khatai', surfaces: ['खटाई'] },

  // Dry Fruits & Nuts
  { canonical: 'Kaju', surfaces: ['काजू'] },
  { canonical: 'Badam', surfaces: ['बादाम'] },
  { canonical: 'Moongphali', surfaces: ['मूंगफली'] },
  { canonical: 'Kishmish', surfaces: ['किशमिश'] },
  { canonical: 'Akhrot', surfaces: ['अखरोट'] },
  { canonical: 'Pista', surfaces: ['पिस्ता'] },
  { canonical: 'Khajoor', surfaces: ['खजूर'] },
  { canonical: 'Nariyal', surfaces: ['नारियल'] },

  // Household & Beverages
  { canonical: 'Chaipatti', surfaces: ['चायपत्ती'] },
  { canonical: 'Coffee', surfaces: ['कॉफी'] },
  { canonical: 'Sabun', surfaces: ['साबुन'] },
  { canonical: 'Shampoo', surfaces: ['शैम्पू'] },
  { canonical: 'Agarbatti', surfaces: ['अगरबत्ती'] },
  { canonical: 'Machis', surfaces: ['माचिस'] },
  { canonical: 'Biscuit', surfaces: ['बिस्कुट'] },
  { canonical: 'Bread', surfaces: ['ब्रेड'] },
  { canonical: 'Namkeen', surfaces: ['नमकीन'] },
  { canonical: 'Sona', surfaces: ['सोना'] },
  { canonical: 'Chaandi', surfaces: ['चांदी'] },

  // Pooja Items
  { canonical: 'Chandan', surfaces: ['चंदन'] },
  { canonical: 'Kumkum', surfaces: ['कुमकुम'] },
  { canonical: 'Roli', surfaces: ['रोली'] },
  { canonical: 'Mouli', surfaces: ['मौली'] },
  { canonical: 'Akshat', surfaces: ['अक्षत'] },
  { canonical: 'Kapoor', surfaces: ['कपूर'] },
  { canonical: 'Dhoop', surfaces: ['धूप'] },
  { canonical: 'Diya', surfaces: ['दीया'] },
  { canonical: 'Rooi', surfaces: ['रुई'] },
  { canonical: 'Havan Samagri', surfaces: ['हवन सामग्री'] },
  { canonical: 'Gangajal', surfaces: ['गंगाजल'] },
  { canonical: 'Karonja', surfaces: ['करोंजा'] },
  { canonical: 'Karonda', surfaces: ['करौंदा'] },
  { canonical: 'Soyabean', surfaces: ['सोयाबीन'] },
]

/** surface (lowercased, trimmed) -> canonical. Built once at module load. */
const SURFACE_TO_CANONICAL: Map<string, string> = (() => {
  const m = new Map<string, string>()
  for (const e of ITEM_LEXICON) {
    m.set(e.canonical.trim().toLowerCase(), e.canonical)
    for (const s of e.surfaces) m.set(s.trim().toLowerCase(), e.canonical)
  }
  return m
})()

const QUALIFIER_BY_SURFACE: Map<string, QualifierEntry> = (() => {
  const m = new Map<string, QualifierEntry>()
  for (const q of QUALIFIERS) {
    m.set(q.canonical.trim().toLowerCase(), q)
    for (const s of q.surfaces) m.set(s.trim().toLowerCase(), q)
  }
  return m
})()

// Assertion: qualifier surfaces must not collide with item surfaces.
for (const [surface] of QUALIFIER_BY_SURFACE) {
  if (SURFACE_TO_CANONICAL.has(surface)) {
    throw new Error(`Qualifier surface collision: '${surface}' exists as both a qualifier and an item surface`)
  }
}

/** Canonical name for a known surface, else null. */
export function canonicalize(surface: string): string | null {
  if (!surface) return null
  return SURFACE_TO_CANONICAL.get(surface.trim().toLowerCase()) ?? null
}

/** Returns null when the remainder after stripping qualifiers is not a known base item. */
export function composeIdentity(phrase: string): string | null {
  const tokens = phrase.trim().toLowerCase().split(/\s+/).filter(Boolean)
  if (tokens.length < 2) return null

  const brands: string[] = []
  const varieties: string[] = []
  const rest: string[] = []

  let i = 0
  while (i < tokens.length) {
    // Longest-match first so two-word qualifiers ('full cream', 'mother dairy') win.
    let matched = false
    for (let span = Math.min(3, tokens.length - i); span >= 1 && !matched; span--) {
      const probe = tokens.slice(i, i + span).join(' ')
      const q = QUALIFIER_BY_SURFACE.get(probe)
      if (q) {
        if (q.kind === 'BRAND') { if (!brands.includes(q.canonical)) brands.push(q.canonical) }
        else { if (!varieties.includes(q.canonical)) varieties.push(q.canonical) }
        i += span
        matched = true
      }
    }
    if (!matched) { rest.push(tokens[i]); i++ }
  }

  if (rest.length === 0) return null                 // all qualifier, no product
  if (brands.length === 0 && varieties.length === 0) return null  // nothing was stripped

  const base = canonicalize(rest.join(' '))
  if (!base) return null                             // remainder is not a known item

  return [...brands, ...varieties, base].join(' ')
}

/**
 * Canonical identity for any spoken/typed item phrase.
 *
 * Resolution order — explicit first, compositional second, literal fold last:
 *   1. Whole-phrase hit in SURFACE_TO_CANONICAL  -> that canonical. Preserves every existing
 *      name, including multi-word ones like 'हरी मिर्च' -> 'Mirch' and display names carrying
 *      a parenthetical brand ('Sugar (Madhur)').
 *   2. Compositional: strip known qualifiers, resolve the remainder as a base item, recompose
 *      as '<Brand> <Variety> <Base>'. This is what gives an unenumerated brand its own
 *      identity ('tata sugar' -> 'Tata Sugar') instead of letting it inherit whichever brand
 *      owns the generic word.
 *   3. Neither -> the phrase's own folded form, so a shop's private item still groups with
 *      itself across case and whitespace.
 * ISSUE-109.
 */
export function canonicalOf(name: string): string {
  const trimmed = (name ?? '').trim()
  if (!trimmed) return ''

  const explicit = canonicalize(trimmed)
  if (explicit) return explicit

  const composed = composeIdentity(trimmed)
  if (composed) return composed

  return trimmed.toLowerCase().replace(/\s+/g, ' ')
}

/** Returns brands and varieties present in a phrase. */
export function getQualifiers(phrase: string): { brands: string[]; varieties: string[] } {
  const tokens = phrase.trim().toLowerCase().split(/\s+/).filter(Boolean)
  const brands: string[] = []
  const varieties: string[] = []
  for (const t of tokens) {
    const q = QUALIFIER_BY_SURFACE.get(t)
    if (q) {
      if (q.kind === 'BRAND' && !brands.includes(q.canonical)) brands.push(q.canonical)
      if (q.kind === 'VARIETY' && !varieties.includes(q.canonical)) varieties.push(q.canonical)
    }
  }
  return { brands, varieties }
}

export function canonicalQualifierOf(surface: string): string {
  if (!surface) return ''
  return QUALIFIER_BY_SURFACE.get(surface.trim().toLowerCase())?.canonical ?? surface
}

/**
 * Qualifier surfaces, flat. These go into their OWN segmenter vocabulary, never into
 * ALL_ITEM_SURFACES: a modifier in the item vocabulary competes as a product and collapses the
 * ambiguity margin of real products ("हरा" sits 0.167 from "आलू"). But it must still be
 * recognisable, or the lattice fuzzy-matches it to the nearest product instead ("अमूल" -> अंगूर).
 * See ISSUE-109.
 */
export const ALL_QUALIFIER_SURFACES: string[] =
  QUALIFIERS.flatMap(q => [q.canonical, ...q.surfaces])

/**
 * Every ITEM surface in the lexicon, flat. Replaces the hand-maintained DEFAULT_ITEM_VOCAB.
 *
 * Qualifier surfaces are deliberately NOT included. They are modifiers, not products, and
 * putting them in the item vocabulary makes them compete as item candidates in `matchVocab`:
 * "हरा"/"हरी" (Green) sit 0.167 from "आलू", which collapsed the ambiguity margin and flagged
 * a clean "पाँच किलो आलू" as AMBIGUOUS — the exact ISSUE-107 defect, reintroduced. The lattice
 * does not need them here: an unrecognised leading token is still emitted as ITEM at
 * ITEM_BASELINE_COST and joined into the same segment, so "अमूल घी" survives as one item
 * phrase and `canonicalOf` composes the identity from it afterwards. See ISSUE-109.
 */
export const ALL_ITEM_SURFACES: string[] =
  // Surfaces BEFORE canonical, deliberately. Every spelling of one item usually shares a
  // phonetic key, and normalizedLiteralDistance is transliteration-aware, so they tie at 0 on
  // both scores — the tie is settled by whichever the vocabulary lists first. Canonical-first
  // made the segmenter report "Aaloo" for a shopkeeper who said "आलू", changing what shows in
  // the ledger and the trace. The native spelling leads each `surfaces` array, so listing
  // surfaces first restores the pre-ISSUE-107 behaviour. ISSUE-109.
  ITEM_LEXICON.flatMap(e => [...e.surfaces, e.canonical])

