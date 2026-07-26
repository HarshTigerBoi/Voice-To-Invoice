// -----------------------------------------------------------------------------
// SCRIPT-AGNOSTIC PHONETIC SEGMENTATION ENGINE
//
// Server-side mirror of:
//   app/src/main/java/com/voicetoinvoice/app/domain/parser/PhoneticKey.kt
//   app/src/main/java/com/voicetoinvoice/app/domain/parser/OrderingSegmenter.kt
// Keep the two in sync — constants, vocabulary, and cost model are all load-bearing
// and the Kotlin side is the one with the regression suite (PhoneticSegmentationTest).
//
// This replaces the previous `combinatorialFuzzySegmenter`, which compared raw
// Devanagari vocabulary against whatever string STT returned using uniform-cost
// orthographic edit distance. That failed structurally in two ways:
//
//  1. CROSS-SCRIPT BLINDNESS. STT is not guaranteed to return Devanagari even when
//     asked for `hi`. A Latin-script token shares zero characters with a Devanagari
//     vocabulary entry, so distance is effectively infinite and the whole splitter
//     became dead code. Observed: "तीन किलो सेब" -> "tinggal sebab", which scored
//     distance 7 against "तीन" under a budget of 1, losing quantity, unit and item
//     in one shot and booking "1 PACKET tinggal sebab". See ISSUE-020.
//
//  2. SPELLING DISTANCE IS THE WRONG METRIC. Even with romanized vocabulary added,
//     editDistance("tinggal","teen") = 3 — orthographic distance cannot model g↔k
//     devoicing or vowel elision. STT's errors are phonetic, so the comparison has
//     to happen in phone space.
// -----------------------------------------------------------------------------

const DEVA_CONSONANTS: Record<string, string> = {
  'क': 'k', 'ख': 'k', 'ग': 'g', 'घ': 'g', 'ङ': 'n',
  'च': 'c', 'छ': 'c', 'ज': 'j', 'झ': 'j', 'ञ': 'n',
  'ट': 't', 'ठ': 't', 'ड': 'd', 'ढ': 'd', 'ण': 'n',
  'त': 't', 'थ': 't', 'द': 'd', 'ध': 'd', 'न': 'n',
  'प': 'p', 'फ': 'f', 'ब': 'b', 'भ': 'b', 'म': 'm',
  'य': 'y', 'र': 'r', 'ल': 'l', 'व': 'v',
  'श': 's', 'ष': 's', 'स': 's', 'ह': 'h',
}

const DEVA_VOWELS: Record<string, string> = {
  'अ': 'a', 'आ': 'a', 'इ': 'i', 'ई': 'i', 'उ': 'u', 'ऊ': 'u',
  'ए': 'e', 'ऐ': 'e', 'ओ': 'o', 'औ': 'o', 'ऋ': 'r',
}

const DEVA_MATRAS: Record<string, string> = {
  'ा': 'a', 'ि': 'i', 'ी': 'i', 'ु': 'u', 'ू': 'u',
  'े': 'e', 'ै': 'e', 'ो': 'o', 'ौ': 'o', 'ॉ': 'o', 'ृ': 'r',
}

const VIRAMA = '्'
const ANUSVARA = 'ं'
const CHANDRABINDU = 'ँ'
const VISARGA = 'ः'
const NUKTA = '़'

const VOWEL_CLASSES = new Set(['A', 'I', 'O'])

function isDevanagari(s: string): boolean {
  for (const ch of s) if (ch >= 'ऀ' && ch <= 'ॿ') return true
  return false
}

function devanagariToLatin(s: string): string {
  let out = ''
  for (let i = 0; i < s.length; i++) {
    const ch = s[i]
    const next = i + 1 < s.length ? s[i + 1] : null
    if (DEVA_CONSONANTS[ch]) {
      out += DEVA_CONSONANTS[ch]
      // Schwa deletion: Hindi drops the word-final inherent vowel, so "तीन" is /tiːn/
      // not /tiːnə/. Without this the Devanagari key never converges with the
      // romanized one and the entire cross-script premise collapses.
      const suppressed = next === null || next === VIRAMA || next === NUKTA || !!DEVA_MATRAS[next]
      if (!suppressed) out += 'a'
    } else if (DEVA_VOWELS[ch]) {
      out += DEVA_VOWELS[ch]
    } else if (DEVA_MATRAS[ch]) {
      out += DEVA_MATRAS[ch]
    } else if (ch === ANUSVARA || ch === CHANDRABINDU) {
      out += 'n'
    } else if (ch === VISARGA) {
      out += 'h'
    } else if (ch === VIRAMA || ch === NUKTA) {
      // dropped; nukta forms fold onto their base consonant automatically
    } else if (/[a-z0-9]/i.test(ch)) {
      out += ch.toLowerCase()
    }
  }
  return out
}

/**
 * Projects a token (either script) onto the collapsed phone alphabet. Each collapse
 * rule targets a confusion seen in this app's own production traces: aspiration
 * (kh→k, bh→b), voicing (k↔g, t↔d, p↔b), vowel length (aa→a, ee→i, oo→u), nasal and
 * sibilant merge, l↔r, v↔w, and degemination at fused word boundaries.
 */
export function phoneticKey(raw: string): string {
  let s = (raw || '').toLowerCase().trim()
  if (isDevanagari(s)) s = devanagariToLatin(s)
  s = s.replace(/[^a-z0-9]/g, '')
  if (!s) return ''

  // Digraphs first — must precede single-char collapse or "sh" becomes "s"+"h".
  s = s.replace(/ph/g, 'f').replace(/kh/g, 'k').replace(/gh/g, 'g')
    .replace(/ch/g, 'c').replace(/jh/g, 'j').replace(/th/g, 't')
    .replace(/dh/g, 'd').replace(/bh/g, 'b').replace(/sh/g, 's')
    .replace(/ng/g, 'n')

  s = s.replace(/aa/g, 'a').replace(/ee/g, 'i').replace(/ii/g, 'i')
    .replace(/oo/g, 'u').replace(/uu/g, 'u')

  let mapped = ''
  for (const c of s) {
    if (c === 'h') continue
    if (c === 'k' || c === 'g') mapped += 'K'
    else if (c === 't' || c === 'd') mapped += 'T'
    else if (c === 'p' || c === 'b' || c === 'f') mapped += 'P'
    else if (c === 'c' || c === 'j') mapped += 'C'
    else if (c === 's' || c === 'z') mapped += 'S'
    else if (c === 'n' || c === 'm') mapped += 'N'
    else if (c === 'v' || c === 'w') mapped += 'V'
    else if (c === 'l' || c === 'r') mapped += 'L'
    else if (c === 'i' || c === 'e' || c === 'y') mapped += 'I'
    else if (c === 'o' || c === 'u') mapped += 'O'
    else if (c === 'a') mapped += 'A'
    else mapped += c
  }

  let out = ''
  for (const c of mapped) if (!out.length || out[out.length - 1] !== c) out += c
  return out
}

/**
 * Edit distance over phone keys with vowel operations discounted to 0.5. STT
 * reconstructs consonant skeletons far more reliably than vowels, so a vowel-only
 * discrepancy is weak evidence of a different word while a consonant discrepancy is
 * strong evidence. Uniform-cost distance conflates the two and ranks candidates badly.
 */
export function phoneticDistance(a: string, b: string): number {
  if (a === b) return 0
  const w = (ch: string) => (VOWEL_CLASSES.has(ch) ? 0.5 : 1)
  if (!a.length) return [...b].reduce((s, ch) => s + w(ch), 0)
  if (!b.length) return [...a].reduce((s, ch) => s + w(ch), 0)

  const dp: number[][] = Array.from({ length: a.length + 1 }, () => new Array(b.length + 1).fill(0))
  for (let i = 1; i <= a.length; i++) dp[i][0] = dp[i - 1][0] + w(a[i - 1])
  for (let j = 1; j <= b.length; j++) dp[0][j] = dp[0][j - 1] + w(b[j - 1])

  for (let i = 1; i <= a.length; i++) {
    for (let j = 1; j <= b.length; j++) {
      const av = VOWEL_CLASSES.has(a[i - 1])
      const bv = VOWEL_CLASSES.has(b[j - 1])
      const sub = a[i - 1] === b[j - 1] ? 0 : (av && bv ? 0.5 : 1)
      dp[i][j] = Math.min(
        dp[i - 1][j - 1] + sub,
        dp[i - 1][j] + (av ? 0.5 : 1),
        dp[i][j - 1] + (bv ? 0.5 : 1)
      )
    }
  }
  return dp[a.length][b.length]
}

/**
 * Distance per phone. Raw distance lets a 2-phone fragment cheaply claim a 5-phone
 * word, which is how a naive splitter hallucinates items — an early build split
 * "tinggal" into "teen"+"kg"+"Aaloo", inventing Aaloo from the "-lo" of "kilo".
 */
export function normalizedDistance(a: string, b: string): number {
  if (!a.length && !b.length) return 0
  return phoneticDistance(a, b) / Math.max(a.length, b.length)
}

// ---------------------------------------------------------------------------
// Vocabulary — mirrors OrderingSegmenter.kt's companion object.
// ---------------------------------------------------------------------------

export const HINDI_NUMBER_MAP: Record<string, number> = {
  'एक': 1, 'ek': 1, 'one': 1, '1': 1,
  'दो': 2, 'do': 2, 'two': 2, '2': 2,
  'तीन': 3, 'teen': 3, 'three': 3, '3': 3,
  'चार': 4, 'chaar': 4, 'four': 4, '4': 4,
  'पांच': 5, 'पाँच': 5, 'paanch': 5, 'five': 5, '5': 5,
  'छह': 6, 'छे': 6, 'chhah': 6, 'six': 6, '6': 6,
  'सात': 7, 'saat': 7, 'seven': 7, '7': 7,
  'आठ': 8, 'aath': 8, 'eight': 8, '8': 8,
  'नौ': 9, 'nau': 9, 'nine': 9, '9': 9,
  'दस': 10, 'das': 10, 'ten': 10, '10': 10,
  'ग्यारह': 11, 'gyarah': 11, '11': 11,
  'बारह': 12, 'baarah': 12, '12': 12,
  'तेरह': 13, 'terah': 13, '13': 13,
  'चौदह': 14, 'chaudah': 14, '14': 14,
  'पंद्रह': 15, 'pandrah': 15, '15': 15,
  'सोलह': 16, 'solah': 16, '16': 16,
  'सत्रह': 17, 'satrah': 17, '17': 17,
  'अठारह': 18, 'athaarah': 18, '18': 18,
  'उन्नीस': 19, 'unnees': 19, '19': 19,
  'बीस': 20, 'bees': 20, '20': 20,
  'तीस': 30, 'tees': 30, '30': 30,
  'चालीस': 40, 'chalees': 40, '40': 40,
  'पचास': 50, 'pachaas': 50, 'pachas': 50, '50': 50,
  'साठ': 60, 'saath': 60, '60': 60,
  'सत्तर': 70, 'sattar': 70, '70': 70,
  'अस्सी': 80, 'assi': 80, '80': 80,
  'नब्बे': 90, 'nabbe': 90, '90': 90,
  'सौ': 100, 'sau': 100, '100': 100,
  'आधा': 0.5, 'आधी': 0.5, 'aadha': 0.5, 'aadhi': 0.5, 'half': 0.5,
  'पाव': 0.25, 'पाओ': 0.25, 'pao': 0.25, 'paao': 0.25,
  'सवा': 1.25, 'sawa': 1.25,
  'डेढ़': 1.5, 'डेढ': 1.5, 'dedh': 1.5,
  'ढाई': 2.5, 'dhai': 2.5,
}

export const UNIT_SET: string[] = [
  'kilo', 'kilos', 'kg', 'kgs', 'किलो', 'किलोग्राम',
  'gram', 'grams', 'gm', 'gms', 'ग्राम', 'g',
  'litre', 'litres', 'liter', 'liters', 'लीटर', 'l',
  'ml', 'एमएल',
  'packet', 'packets', 'pkt', 'पैकेट',
  'piece', 'pieces', 'pcs', 'नग',
  'dozen', 'dozens', 'दर्जन',
]

/**
 * Distance words — impossible as shop units, therefore always an STT mis-decode.
 *
 * Mirrors OrderingSegmenter.DISTANCE_UNIT_TOKENS. These used to sit in UNIT_SET as a
 * band-aid for "किलो X" being heard as "किलोमीटर", which backfired badly: an exact
 * UNIT_SET hit emits at EXACT_COST and returns early from wholeTokenExpansions, which
 * sets `exactOnly` in decode() and suppresses split expansions altogether. So
 * "पांच किलोमीटर" decoded to NUM(5)+UNIT(KG) with no ITEM, closeSegment() never fired,
 * and the sale came back as `segments: []`. See ISSUE-021.
 */
export const DISTANCE_UNIT_TOKENS: string[] = [
  'kilometer', 'kilometers', 'kilometre', 'kilometres', 'km', 'kms',
  'किलोमीटर', 'किलोमीटर्स',
  'meter', 'meters', 'metre', 'metres', 'मीटर',
  'centimeter', 'centimetre', 'cm', 'सेंटीमीटर',
  'mile', 'miles', 'मील', 'foot', 'feet', 'फुट', 'फीट',
]

/** Item words usable as the ITEM half of a fused token, in BOTH scripts. Kept in sync
 *  with OrderingSegmenter.DEFAULT_ITEM_VOCAB and FuzzyCatalogMatcher.indicAliasMap. */
/**
 * A word absent from this vocabulary cannot be recognized — only mapped onto the
 * nearest word that IS present. In ISSUE-022 the shopkeeper said "अमचूर", which
 * appeared nowhere in the codebase, so the matcher resolved it to "Jeera" and booked
 * it. Breadth here is a correctness requirement, not a nice-to-have.
 *
 * Mirrors OrderingSegmenter.DEFAULT_ITEM_VOCAB — keep the two identical.
 */
export const DEFAULT_ITEM_VOCAB: string[] = [
  'सेब', 'Seb', 'आलू', 'Aaloo', 'प्याज', 'Pyaz',
  'टमाटर', 'Tamatar', 'भिंडी', 'Bhindi', 'धनिया', 'Dhaniya',
  'मिर्च', 'Mirch', 'गोभी', 'Gobhi', 'बैंगन', 'Baingan',
  'गाजर', 'Gajar', 'मटर', 'Matar', 'खीरा', 'Kheera',
  'पालक', 'Palak', 'लहसुन', 'Lahsun', 'अदरक', 'Adrak',
  'केला', 'Kela', 'नींबू', 'Nimbu', 'शिमला मिर्च', 'Shimla Mirch',
  'लौकी', 'Lauki', 'तोरई', 'Torai', 'करेला', 'Karela',
  'कद्दू', 'Kaddu', 'मूली', 'Mooli', 'चुकंदर', 'Chukandar',
  'अंगूर', 'Angoor', 'आम', 'Aam', 'संतरा', 'Santra',
  'पपीता', 'Papita', 'अनार', 'Anar', 'तरबूज', 'Tarbooj',
  'अमरूद', 'Amrood', 'दूध', 'Doodh', 'दही', 'Dahi',
  'पनीर', 'Paneer', 'घी', 'Ghee', 'मक्खन', 'Butter',
  'अंडे', 'Anda', 'मलाई', 'Malai', 'छाछ', 'Chaach',
  'चीनी', 'Chini', 'आटा', 'Atta', 'चावल', 'Chawal',
  'नमक', 'Namak', 'तेल', 'Tel', 'मैदा', 'Maida',
  'सूजी', 'Sooji', 'बेसन', 'Besan', 'पोहा', 'Poha',
  'सेवई', 'Sewai', 'साबूदाना', 'Sabudana', 'गुड़', 'Gud',
  'चना', 'Chana', 'राजमा', 'Rajma', 'मूंग', 'Moong',
  'मसूर', 'Masoor', 'अरहर', 'Arhar', 'तूर', 'Toor',
  'उड़द', 'Urad', 'छोले', 'Chole', 'अमचूर', 'Amchoor',
  'हल्दी', 'Haldi', 'जीरा', 'Jeera', 'राई', 'Rai',
  'मेथी', 'Methi', 'सौंफ', 'Saunf', 'इलायची', 'Elaichi',
  'दालचीनी', 'Dalchini', 'लौंग', 'Laung', 'काली मिर्च', 'Kali Mirch',
  'तेजपत्ता', 'Tejpatta', 'हींग', 'Hing', 'अजवाइन', 'Ajwain',
  'गरम मसाला', 'Garam Masala', 'कसूरी मेथी', 'Kasuri Methi', 'इमली', 'Imli',
  'खटाई', 'Khatai', 'काजू', 'Kaju', 'बादाम', 'Badam',
  'मूंगफली', 'Moongphali', 'किशमिश', 'Kishmish', 'अखरोट', 'Akhrot',
  'पिस्ता', 'Pista', 'खजूर', 'Khajoor', 'नारियल', 'Nariyal',
  'मैगी', 'Maggi', 'चायपत्ती', 'Chaipatti', 'कॉफी', 'Coffee',
  'साबुन', 'Sabun', 'शैम्पू', 'Shampoo', 'अगरबत्ती', 'Agarbatti',
  'माचिस', 'Machis', 'बिस्कुट', 'Biscuit', 'ब्रेड', 'Bread',
  'नमकीन', 'Namkeen', 'सोना', 'Sona', 'चांदी', 'Chaandi',
  // Pooja / religious items — ISSUE-023: "चंदन" (chandan) was absent everywhere, so a
  // mis-hearing of it ("संधन") matched the phonetically-closer-but-wrong "संतरा" (Santra)
  // at 0.214 instead of the actually-closer "चंदन" at 0.167, which did not exist to compete.
  'चंदन', 'Chandan', 'कुमकुम', 'Kumkum', 'रोली', 'Roli', 'मौली', 'Mouli',
  'अक्षत', 'Akshat', 'कपूर', 'Kapoor', 'धूप', 'Dhoop', 'दीया', 'Diya',
  'रुई', 'Rooi', 'हवन सामग्री', 'Havan Samagri', 'गंगाजल', 'Gangajal',
]

export function normalizeUnit(unitStr: string): string {
  const lower = (unitStr || '').toLowerCase()
  if (lower.includes('kilo') || lower.includes('kg') || lower.includes('किलो')) return 'KG'
  if (lower.includes('gram') || lower.includes('gm') || lower.includes('ग्राम')) return 'GRAM'
  if (lower.includes('litre') || lower.includes('liter') || lower.includes('लीटर')) return 'LITRE'
  if (lower.includes('ml') || lower.includes('एमएल')) return 'ML'
  if (lower.includes('packet') || lower.includes('पैकेट') || lower.includes('pkt')) return 'PACKET'
  if (lower.includes('dozen') || lower.includes('दर्जन')) return 'DOZEN'
  if (lower.includes('piece') || lower.includes('pcs') || lower.includes('नग')) return 'PIECE'
  return (unitStr || '').toUpperCase()
}

// ---------------------------------------------------------------------------
// Grammar-aware token-expansion lattice
// ---------------------------------------------------------------------------

type TokenType = 'NUM' | 'UNIT' | 'ITEM'

interface VocabEntry { key: string; surface: string; numericValue?: number; canonicalUnit?: string }
interface VocabHit { entry: VocabEntry; normalized: number }
interface Emission { type: TokenType; cost: number; surface: string; numericValue?: number; canonicalUnit?: string; suspect?: boolean; matchNorm?: number }
interface Expansion { emissions: Emission[]; emissionCost: number }

export interface SegmenterVocabulary {
  numbers: VocabEntry[]
  units: VocabEntry[]
  items: VocabEntry[]
}

export function buildVocabulary(catalogNames: string[] = []): SegmenterVocabulary {
  return {
    numbers: Object.entries(HINDI_NUMBER_MAP)
      .filter(([w]) => w.length >= 2)
      .map(([w, v]) => ({ key: phoneticKey(w), surface: w, numericValue: v })),
    units: UNIT_SET.map(u => ({ key: phoneticKey(u), surface: u, canonicalUnit: u })),
    items: Array.from(new Set([...DEFAULT_ITEM_VOCAB, ...catalogNames].filter(n => n && n.trim())))
      .map(n => ({ key: phoneticKey(n), surface: n })),
  }
}

const EXACT_COST = 0.0
const ELISION_COST = 0.5
const ITEM_BASELINE_COST = 1.2
const ITEM_MATCHED_BASE_COST = 0.2
// See OrderingSegmenter.kt for the tuning history behind these three: 0.34/0.35 let
// "एकलो" match ग्यारह(11) and "ग्लोसोना" match लहसुन whole, swallowing fused tokens
// as the wrong word instead of splitting them.
const WHOLE_TOKEN_MAX_NORM = 0.25
const SPLIT_PART_MAX_NORM = 0.30
const MIN_SPLIT_PHONES = 2
const SPLIT_PENALTY = 0.10
// A distance word read whole, as an item name — priced above any plausible split of the
// same token, so it only wins when nothing else matches at all and the token would
// otherwise vanish.
const DISTANCE_TOKEN_ITEM_COST = 2.5

function transitionCost(prev: TokenType | null, curr: TokenType): number {
  if (prev === null) return curr === 'NUM' ? 0.0 : curr === 'ITEM' ? 0.3 : 1.0
  if (prev === 'NUM') return curr === 'UNIT' ? 0.0 : curr === 'ITEM' ? 0.3 : 4.0
  if (prev === 'UNIT') return curr === 'ITEM' ? 0.0 : curr === 'NUM' ? 2.0 : 3.0
  return curr === 'NUM' ? 0.0 : curr === 'UNIT' ? 1.5 : 0.2
}

/**
 * Cost of finishing the utterance on `last`. A shopkeeper does not say "five kilos" and
 * stop, so a decode ending on a bare quantity/unit is structurally incomplete and should
 * lose to any reading that yields an item. Small on purpose: an exact UNIT_SET match
 * costs 0.0 and returns before any ITEM alternative is offered, so a genuine trailing
 * "चार किलो" (carryover to the next recording) has no competing path this can flip.
 */
function endCost(last: TokenType): number {
  return last === 'ITEM' ? 0.0 : 0.6
}

function matchVocab(
  fragment: string,
  vocab: VocabEntry[],
  maxNorm: number,
  opts: { allowElision?: boolean; allowEcho?: boolean } = {}
): VocabHit | null {
  if (!fragment) return null
  let best: VocabHit | null = null
  for (const entry of vocab) {
    if (!entry.key) continue
    let cost = phoneticDistance(fragment, entry.key)
    if (opts.allowElision && entry.key.length > fragment.length && entry.key.endsWith(fragment)) {
      cost = Math.min(cost, ELISION_COST)
    }
    // Trailing-echo bonus: whole-token catalog reads only ("sebab" -> "seb"). Never
    // inside a split, where every phone must be paid for by some part or a fragment
    // steals phones from its neighbour and the split silently misaligns.
    if (opts.allowEcho && fragment.length > entry.key.length && fragment.startsWith(entry.key)) {
      cost = Math.min(cost, (fragment.length - entry.key.length) * 0.5)
    }
    const norm = cost / Math.max(fragment.length, entry.key.length)
    if (!best || norm < best.normalized) best = { entry, normalized: norm }
  }
  return best && best.normalized <= maxNorm ? best : null
}

function wholeTokenExpansions(raw: string, vocab: SegmenterVocabulary): Expansion[] {
  const lower = raw.toLowerCase()
  const out: Expansion[] = []

  // Distance word: never a real reading of shop speech. Offer only a suspect ITEM
  // fallback so it can't be consumed as a unit, and so `exactOnly` stays false in
  // decode() — which is what re-enables the split expansions that should actually
  // carry this token (किलोमीटर -> किलो + मीटर).
  if (DISTANCE_UNIT_TOKENS.includes(lower)) {
    return [{
      emissions: [{ type: 'ITEM', cost: DISTANCE_TOKEN_ITEM_COST, surface: raw, suspect: true }],
      emissionCost: DISTANCE_TOKEN_ITEM_COST,
    }]
  }

  if (HINDI_NUMBER_MAP[lower] !== undefined) {
    out.push({ emissions: [{ type: 'NUM', cost: EXACT_COST, surface: raw, numericValue: HINDI_NUMBER_MAP[lower] }], emissionCost: EXACT_COST })
  } else if (/^\d+(\.\d+)?$/.test(lower)) {
    out.push({ emissions: [{ type: 'NUM', cost: EXACT_COST, surface: raw, numericValue: parseFloat(lower) }], emissionCost: EXACT_COST })
  }
  if (UNIT_SET.includes(lower)) {
    out.push({ emissions: [{ type: 'UNIT', cost: EXACT_COST, surface: raw, canonicalUnit: lower }], emissionCost: EXACT_COST })
  }
  // An exact vocabulary match is unambiguous — don't offer an ITEM escape hatch that
  // would let the decoder buy a cheaper global path by relabelling a literal
  // number/unit as a fake item name just to dodge a transition penalty.
  if (out.length > 0) return out

  const key = phoneticKey(lower)
  if (key) {
    const n = matchVocab(key, vocab.numbers, WHOLE_TOKEN_MAX_NORM)
    if (n) out.push({ emissions: [{ type: 'NUM', cost: n.normalized, surface: raw, numericValue: n.entry.numericValue }], emissionCost: n.normalized })

    const u = matchVocab(key, vocab.units, WHOLE_TOKEN_MAX_NORM, { allowElision: true })
    if (u) out.push({ emissions: [{ type: 'UNIT', cost: u.normalized, surface: raw, canonicalUnit: u.entry.canonicalUnit }], emissionCost: u.normalized })

    const it = matchVocab(key, vocab.items, WHOLE_TOKEN_MAX_NORM, { allowEcho: true })
    if (it) {
      const cost = ITEM_MATCHED_BASE_COST + it.normalized
      out.push({ emissions: [{ type: 'ITEM', cost, surface: it.entry.surface, matchNorm: it.normalized }], emissionCost: cost })
    }
  }

  // Always available: an unrecognized word is far more likely to be an item the
  // catalog hasn't seen than a mangled number. This is what keeps a genuinely new
  // item name intact rather than rewriting it to the nearest catalog entry.
  out.push({ emissions: [{ type: 'ITEM', cost: ITEM_BASELINE_COST, surface: raw }], emissionCost: ITEM_BASELINE_COST })
  return out
}

function splitExpansions(raw: string, vocab: SegmenterVocabulary): Expansion[] {
  const key = phoneticKey(raw.toLowerCase())
  if (key.length < MIN_SPLIT_PHONES * 2) return []
  const out: Expansion[] = []

  const em = (type: TokenType, hit: VocabHit): Emission => ({
    type, cost: hit.normalized, surface: hit.entry.surface,
    numericValue: hit.entry.numericValue, canonicalUnit: hit.entry.canonicalUnit,
    matchNorm: type === 'ITEM' ? hit.normalized : undefined,
  })

  for (let i = MIN_SPLIT_PHONES; i <= key.length - MIN_SPLIT_PHONES; i++) {
    const p1 = key.slice(0, i)
    const p2 = key.slice(i)

    const n = matchVocab(p1, vocab.numbers, SPLIT_PART_MAX_NORM)
    if (n) {
      const u = matchVocab(p2, vocab.units, SPLIT_PART_MAX_NORM, { allowElision: true })
      if (u) out.push({ emissions: [em('NUM', n), em('UNIT', u)], emissionCost: n.normalized + u.normalized + 2 * SPLIT_PENALTY })
      const it = matchVocab(p2, vocab.items, SPLIT_PART_MAX_NORM)
      if (it) out.push({ emissions: [em('NUM', n), em('ITEM', it)], emissionCost: n.normalized + it.normalized + 2 * SPLIT_PENALTY })
    }
    const u1 = matchVocab(p1, vocab.units, SPLIT_PART_MAX_NORM)
    if (u1) {
      const it = matchVocab(p2, vocab.items, SPLIT_PART_MAX_NORM)
      if (it) out.push({ emissions: [em('UNIT', u1), em('ITEM', it)], emissionCost: u1.normalized + it.normalized + 2 * SPLIT_PENALTY })
    }
  }

  for (let i = MIN_SPLIT_PHONES; i <= key.length - 2 * MIN_SPLIT_PHONES; i++) {
    for (let j = i + MIN_SPLIT_PHONES; j <= key.length - MIN_SPLIT_PHONES; j++) {
      const n = matchVocab(key.slice(0, i), vocab.numbers, SPLIT_PART_MAX_NORM)
      if (!n) continue
      const u = matchVocab(key.slice(i, j), vocab.units, SPLIT_PART_MAX_NORM, { allowElision: true })
      if (!u) continue
      const it = matchVocab(key.slice(j), vocab.items, SPLIT_PART_MAX_NORM)
      if (!it) continue
      out.push({
        emissions: [em('NUM', n), em('UNIT', u), em('ITEM', it)],
        emissionCost: n.normalized + u.normalized + it.normalized + 3 * SPLIT_PENALTY,
      })
    }
  }
  return out
}

/** `suspect`: this reading rests on a token STT is known to have mangled (a distance
 *  word). Quantity and unit are trustworthy; the item name is a guess that must reach
 *  the review queue instead of auto-confirming. */
interface DecodedToken { type: TokenType; rawToken: string; numericValue?: number; canonicalUnit?: string; suspect?: boolean; matchNorm?: number }

/**
 * Viterbi over a token-expansion lattice. State is (source token index, type of the
 * last emission produced), so an expansion emitting several typed pieces pays the
 * internal transitions between them as well as the transition from the previous
 * token. Splits therefore compete against whole-token readings under the shopkeeper
 * grammar rather than being decided greedily in isolation — which matters because
 * "एकलो" reads equally well as "ek kilo" and "ek aaloo" on its own, and only a
 * following item token settles it.
 */
function decode(tokens: string[], vocab: SegmenterVocabulary): { decoded: DecodedToken[]; minGap: number } {
  if (!tokens.length) return { decoded: [], minGap: Number.MAX_VALUE }

  const expansionsPerToken = tokens.map(raw => {
    const whole = wholeTokenExpansions(raw, vocab)
    const exactOnly = whole.length >= 1 && whole.every(e => e.emissionCost === EXACT_COST)
    const all = exactOnly ? whole : [...whole, ...splitExpansions(raw, vocab)]
    // Suspicion is a property of the SOURCE token, not of one reading of it.
    // "किलोमीटर" is a mis-decode however we carve it up, so split readings inherit the
    // flag — otherwise the split wins the lattice (as it should) and drops the warning.
    if (!DISTANCE_UNIT_TOKENS.includes(raw.toLowerCase())) return all
    return all.map(exp => ({
      ...exp,
      emissions: exp.emissions.map(e => ({ ...e, suspect: true })),
    }))
  })

  const dp: Array<Partial<Record<TokenType, number>>> = tokens.map(() => ({}))
  const back: Array<Partial<Record<TokenType, { prev: TokenType | null; exp: Expansion }>>> = tokens.map(() => ({}))
  let minGap = Number.MAX_VALUE

  for (let i = 0; i < tokens.length; i++) {
    const costsHere: Partial<Record<TokenType, number>> = {}
    for (const exp of expansionsPerToken[i]) {
      let internal = exp.emissionCost
      for (let k = 1; k < exp.emissions.length; k++) {
        internal += transitionCost(exp.emissions[k - 1].type, exp.emissions[k].type)
      }
      const firstType = exp.emissions[0].type
      const lastType = exp.emissions[exp.emissions.length - 1].type

      let best = Number.MAX_VALUE
      let bestPrev: TokenType | null = null
      if (i === 0) {
        best = internal + transitionCost(null, firstType)
      } else {
        for (const [prevType, prevCost] of Object.entries(dp[i - 1]) as Array<[TokenType, number]>) {
          const total = prevCost + transitionCost(prevType, firstType) + internal
          if (total < best) { best = total; bestPrev = prevType }
        }
      }
      const existing = costsHere[lastType]
      if (existing === undefined || best < existing) {
        costsHere[lastType] = best
        back[i][lastType] = { prev: bestPrev, exp }
      }
    }
    dp[i] = costsHere

    const sorted = Object.values(costsHere).sort((a, b) => (a as number) - (b as number)) as number[]
    if (sorted.length >= 2) minGap = Math.min(minGap, sorted[1] - sorted[0])
  }

  // The final state is picked on path cost PLUS the terminal penalty, so a decode that
  // leaves the utterance hanging on a quantity or unit has to actually be cheaper to win.
  const chosen: Expansion[] = new Array(tokens.length)
  const last = dp[tokens.length - 1]
  let currentType: TokenType = (Object.entries(last)
    .sort((a, b) =>
      ((a[1] as number) + endCost(a[0] as TokenType)) -
      ((b[1] as number) + endCost(b[0] as TokenType))
    )[0]?.[0] as TokenType) || 'ITEM'
  for (let i = tokens.length - 1; i >= 0; i--) {
    const entry = back[i][currentType]
    if (!entry) {
      chosen[i] = { emissions: [{ type: 'ITEM', cost: ITEM_BASELINE_COST, surface: tokens[i] }], emissionCost: ITEM_BASELINE_COST }
      currentType = 'ITEM'
      continue
    }
    chosen[i] = entry.exp
    currentType = entry.prev ?? 'ITEM'
  }

  const decoded: DecodedToken[] = []
  for (const exp of chosen) {
    for (const e of exp.emissions) {
      decoded.push({ type: e.type, rawToken: e.surface, numericValue: e.numericValue, canonicalUnit: e.canonicalUnit, suspect: e.suspect, matchNorm: e.matchNorm })
    }
  }
  return { decoded, minGap }
}

export interface RawItemSegment {
  rawSegmentText: string
  quantity: number
  unit: string
  itemTokens: string[]
  isSanityFlagged: boolean
  /**
   * Normalized phonetic distance of the item match behind this segment: 0.0 = the
   * transcript token IS the vocabulary word, 0.25 (WHOLE_TOKEN_MAX_NORM) = the loosest
   * match accepted at all, null = matched nothing and kept verbatim as a new name.
   *
   * Confidence used to be `isCatalogMatched ? 0.95 : 0.60`, discarding the distance the
   * matcher had just computed. Trace e0b68f80-6876-42e2-b556-2adf73ce463f matched "चोर"
   * to catalog item "Jeera" at exactly 0.250 — the worst match the thresholds allow —
   * and auto-confirmed it to the ledger at 0.95. See ISSUE-022.
   */
  itemMatchNorm: number | null
}

const LOW_CONFIDENCE_GAP_THRESHOLD = 0.15

/** Segments a transcript into [QUANTITY][UNIT][ITEM] groups. Returns segments plus a
 *  trailing quantity with no item attached (carryover for the next utterance). */
export function segmentTranscript(
  transcript: string,
  catalogNames: string[] = [],
  pendingCarryoverQty: number | null = null
): { segments: RawItemSegment[]; carryoverQty: number | null } {
  const cleanText = (transcript || '')
    .replace(/।/g, ' ')
    .replace(/[.,?!\-\\()]/g, ' ')
    .trim()

  if (!cleanText) return { segments: [], carryoverQty: pendingCarryoverQty }

  const vocab = buildVocabulary(catalogNames)
  const tokens = cleanText.split(/\s+/).filter(t => t.length > 0)
  const { decoded, minGap } = decode(tokens, vocab)

  let currentQty: number | null = pendingCarryoverQty
  let currentUnit: string | null = null
  let currentItemTokens: string[] = []
  let currentSegmentTokens: string[] = []
  let ambiguousDoubleQty = minGap < LOW_CONFIDENCE_GAP_THRESHOLD
  // Set when a token in the current segment came from a reading the decoder itself
  // distrusts (a distance word STT invented). Quantity and unit stay good; the item
  // name is a guess, so the segment must reach review instead of auto-confirming.
  let suspectReading = false
  // Worst (largest) match distance among this segment's item tokens — confidence must
  // be built on the weakest link, not the best one.
  let worstItemNorm: number | null = null
  const segments: RawItemSegment[] = []

  const closeSegment = () => {
    if (currentItemTokens.length > 0) {
      const name = currentItemTokens.join(' ').trim()
      const rawText = currentSegmentTokens.join(' ').trim()
      segments.push({
        rawSegmentText: rawText || name,
        quantity: currentQty ?? 1.0,
        unit: currentUnit ?? 'PACKET',
        itemTokens: [name],
        isSanityFlagged: ambiguousDoubleQty || suspectReading,
        itemMatchNorm: worstItemNorm,
      })
    }
    currentQty = null
    currentUnit = null
    currentItemTokens = []
    currentSegmentTokens = []
    ambiguousDoubleQty = false
    suspectReading = false
    worstItemNorm = null
  }

  for (const dt of decoded) {
    if (dt.suspect) suspectReading = true
    if (dt.type === 'NUM') {
      if (currentItemTokens.length > 0) closeSegment()
      else if (currentQty !== null) ambiguousDoubleQty = true
      currentQty = dt.numericValue ?? 1.0
      currentSegmentTokens.push(dt.rawToken)
    } else if (dt.type === 'UNIT') {
      currentUnit = normalizeUnit(dt.canonicalUnit ?? dt.rawToken)
      currentSegmentTokens.push(dt.rawToken)
    } else {
      if (dt.matchNorm !== undefined && dt.matchNorm !== null) {
        worstItemNorm = worstItemNorm === null ? dt.matchNorm : Math.max(worstItemNorm, dt.matchNorm)
      }
      currentItemTokens.push(dt.rawToken)
      currentSegmentTokens.push(dt.rawToken)
    }
  }

  const carryover = currentQty !== null && currentItemTokens.length === 0 ? currentQty : null
  if (currentItemTokens.length > 0) closeSegment()

  return { segments, carryoverQty: carryover }
}

/** Rewrites a transcript into canonical space-separated tokens. Used to feed the AI
 *  step a cleaned hint and to keep the diagnostic trace readable. */
export function normalizeTranscript(transcript: string, catalogNames: string[] = []): string {
  const { segments } = segmentTranscript(transcript, catalogNames)
  if (!segments.length) return transcript
  return segments.map(s => s.rawSegmentText).join(' ')
}
