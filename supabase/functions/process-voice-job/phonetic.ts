import { ALL_ITEM_SURFACES, ALL_QUALIFIER_SURFACES, canonicalOf, canonicalQualifierOf } from './lexicon.ts'

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

function toNormalizedLatinText(s: string): string {
  let str = (s || '').toLowerCase().trim()
  if (isDevanagari(str)) str = devanagariToLatin(str)
  str = str.replace(/[^a-z0-9]/g, '')
  return str.replace(/aa/g, 'a').replace(/ee/g, 'i').replace(/oo/g, 'u').replace(/ii/g, 'i').replace(/uu/g, 'u')
}

export function literalLevenshteinDistance(aStr: string, bStr: string): number {
  const a = (aStr || '').toLowerCase().trim()
  const b = (bStr || '').toLowerCase().trim()
  if (a === b) return 0
  if (!a.length) return b.length
  if (!b.length) return a.length

  const matrix: number[][] = []
  for (let i = 0; i <= b.length; i++) {
    matrix[i] = [i]
  }
  for (let j = 0; j <= a.length; j++) {
    matrix[0][j] = j
  }

  for (let i = 1; i <= b.length; i++) {
    for (let j = 1; j <= a.length; j++) {
      if (b.charAt(i - 1) === a.charAt(j - 1)) {
        matrix[i][j] = matrix[i - 1][j - 1]
      } else {
        matrix[i][j] = Math.min(
          matrix[i - 1][j - 1] + 1,
          matrix[i][j - 1] + 1,
          matrix[i - 1][j] + 1
        )
      }
    }
  }
  return matrix[b.length][a.length]
}

export function normalizedLiteralDistance(a: string, b: string): number {
  const cleanA = toNormalizedLatinText(a)
  const cleanB = toNormalizedLatinText(b)
  const maxLen = Math.max(cleanA.length, cleanB.length)
  if (maxLen === 0) return 0.0
  return literalLevenshteinDistance(cleanA, cleanB) / maxLen
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
  // 21-99. Mirrors OrderingSegmenter.HINDI_NUMBER_MAP on the client. Hindi compound numerals
  // are irregular single words, not composable from tens + units, so each must be listed. An
  // unmapped numeral is not recognised as a quantity and leaks into the ITEM slot -- which is
  // how ₹0 catalog rows named "सत्ताईस" (27) and "अठारह के लोग" got created server-side.
  'इक्कीस': 21, 'ikkees': 21, '21': 21,
  'बाईस': 22, 'baees': 22, '22': 22,
  'तेईस': 23, 'teees': 23, 'teis': 23, '23': 23,
  'चौबीस': 24, 'chaubees': 24, '24': 24,
  'पच्चीस': 25, 'pachchees': 25, 'pachees': 25, '25': 25,
  'छब्बीस': 26, 'chhabbees': 26, '26': 26,
  'सत्ताईस': 27, 'sattaees': 27, '27': 27,
  'अट्ठाईस': 28, 'atthaees': 28, '28': 28,
  'उनतीस': 29, 'untees': 29, '29': 29,
  'तीस': 30, 'tees': 30, '30': 30,
  'इकतीस': 31, 'ikattees': 31, '31': 31,
  'बत्तीस': 32, 'battees': 32, '32': 32,
  'तैंतीस': 33, 'taintees': 33, '33': 33,
  'चौंतीस': 34, 'chauntees': 34, '34': 34,
  'पैंतीस': 35, 'paintees': 35, '35': 35,
  'छत्तीस': 36, 'chhattees': 36, '36': 36,
  'सैंतीस': 37, 'saintees': 37, '37': 37,
  'अड़तीस': 38, 'adtees': 38, '38': 38,
  'उनतालीस': 39, 'untaalees': 39, '39': 39,
  'चालीस': 40, 'chalees': 40, '40': 40,
  'इकतालीस': 41, 'ikataalees': 41, '41': 41,
  'बयालीस': 42, 'bayaalees': 42, '42': 42,
  'तैंतालीस': 43, 'taintaalees': 43, '43': 43,
  'चवालीस': 44, 'chavaalees': 44, '44': 44,
  'पैंतालीस': 45, 'paintaalees': 45, '45': 45,
  'छियालीस': 46, 'chhiyaalees': 46, '46': 46,
  'सैंतालीस': 47, 'saintaalees': 47, '47': 47,
  'अड़तालीस': 48, 'adtaalees': 48, '48': 48,
  'उनचास': 49, 'unchaas': 49, '49': 49,
  'पचास': 50, 'pachaas': 50, 'pachas': 50, '50': 50,
  'इक्यावन': 51, 'ikyaavan': 51, '51': 51,
  'बावन': 52, 'baavan': 52, '52': 52,
  'तिरेपन': 53, 'tirepan': 53, '53': 53,
  'चौवन': 54, 'chauvan': 54, '54': 54,
  'पचपन': 55, 'pachpan': 55, '55': 55,
  'छप्पन': 56, 'chhappan': 56, '56': 56,
  'सत्तावन': 57, 'sattaavan': 57, '57': 57,
  'अट्ठावन': 58, 'atthaavan': 58, '58': 58,
  'उनसठ': 59, 'unsath': 59, '59': 59,
  'साठ': 60, 'saath': 60, '60': 60,
  'इकसठ': 61, 'ikasath': 61, '61': 61,
  'बासठ': 62, 'baasath': 62, '62': 62,
  'तिरेसठ': 63, 'tiresath': 63, '63': 63,
  'चौंसठ': 64, 'chaunsath': 64, '64': 64,
  'पैंसठ': 65, 'painsath': 65, '65': 65,
  'छियासठ': 66, 'chhiyaasath': 66, '66': 66,
  'सड़सठ': 67, 'sadsath': 67, '67': 67,
  'अड़सठ': 68, 'adsath': 68, '68': 68,
  'उनहत्तर': 69, 'unhattar': 69, '69': 69,
  'सत्तर': 70, 'sattar': 70, '70': 70,
  'इकहत्तर': 71, 'ikahattar': 71, '71': 71,
  'बहत्तर': 72, 'bahattar': 72, '72': 72,
  'तिहत्तर': 73, 'tihattar': 73, '73': 73,
  'चौहत्तर': 74, 'chauhattar': 74, '74': 74,
  'पचहत्तर': 75, 'pachhattar': 75, '75': 75,
  'छिहत्तर': 76, 'chhihattar': 76, '76': 76,
  'सतहत्तर': 77, 'satahattar': 77, '77': 77,
  'अठहत्तर': 78, 'athahattar': 78, '78': 78,
  'उन्यासी': 79, 'unyaasee': 79, '79': 79,
  'अस्सी': 80, 'assi': 80, '80': 80,
  'इक्यासी': 81, 'ikyaasee': 81, '81': 81,
  'बयासी': 82, 'bayaasee': 82, '82': 82,
  'तिरासी': 83, 'tiraasee': 83, '83': 83,
  'चौरासी': 84, 'chauraasee': 84, '84': 84,
  'पचासी': 85, 'pachaasee': 85, '85': 85,
  'छियासी': 86, 'chhiyaasee': 86, '86': 86,
  'सत्तासी': 87, 'sattaasee': 87, '87': 87,
  'अट्ठासी': 88, 'atthaasee': 88, '88': 88,
  'नवासी': 89, 'navaasee': 89, '89': 89,
  'नब्बे': 90, 'nabbe': 90, '90': 90,
  'इक्यानवे': 91, 'ikyaanave': 91, '91': 91,
  'बानवे': 92, 'baanave': 92, '92': 92,
  'तिरानवे': 93, 'tiraanave': 93, '93': 93,
  'चौरानवे': 94, 'chauraanave': 94, '94': 94,
  'पंचानवे': 95, 'panchaanave': 95, '95': 95,
  'छियानवे': 96, 'chhiyaanave': 96, '96': 96,
  'सत्तानवे': 97, 'sattaanave': 97, '97': 97,
  'अट्ठानवे': 98, 'atthaanave': 98, '98': 98,
  'निन्यानवे': 99, 'ninyaanave': 99, '99': 99,
  'सौ': 100, 'sau': 100, 'hundred': 100, '100': 100,
  'हजार': 1000, 'hazaar': 1000, 'thousand': 1000, '1000': 1000,
  'आधा': 0.5, 'आधी': 0.5, 'aadha': 0.5, 'aadhi': 0.5, 'half': 0.5,
  'पाव': 0.25, 'पाओ': 0.25, 'pao': 0.25, 'paao': 0.25,
  'सवा': 1.25, 'sawa': 1.25,
  'डेढ़': 1.5, 'डेढ': 1.5, 'dedh': 1.5,
  'ढाई': 2.5, 'dhai': 2.5,
}

/** Mirrors VoiceParser.kt's RUPEE_WORDS gate. A number is only ever a price when a
 *  rupee word sits next to it -- everything else (a bare trailing number) stays a
 *  quantity or falls through as an unattached carryover. Moved here (rather than
 *  price_intent.ts) so the segmenter's lattice can recognize price runs directly
 *  during segmentation instead of the whole-utterance post-hoc scan that used to
 *  apply one price to every item in the utterance -- see ISSUE-029. */
export const RUPEE_WORDS: Set<string> = new Set([
  'rs', 'rs.', '₹',
  'rupay', 'rupaye', 'rupaya', 'rupaiya', 'rupaiye',
  'rupee', 'rupees',
  'रुपये', 'रुपया', 'रुपए', 'रु', 'रूपये', 'रूपए',
])

export function parseHindiOrNumericValue(token: string): number | null {
  if (!token) return null
  const num = parseFloat(token)
  if (!isNaN(num)) return num
  const norm = token.toLowerCase()
  if (HINDI_NUMBER_MAP[norm] !== undefined) return HINDI_NUMBER_MAP[norm]
  return null
}

/**
 * Evaluates a sequence of numeric/number-word tokens into a resolved compound number.
 * Correctly resolves "दो सौ पचास" -> 250, "डेढ़ सौ" -> 150, "तीन सौ" -> 300, "100" -> 100.
 */
export function parseCompoundNumberSequence(tokens: string[]): number | null {
  if (!tokens || tokens.length === 0) return null

  let totalSum = 0
  let currentGroup = 0
  let hasValidToken = false

  for (const t of tokens) {
    const norm = t.toLowerCase()
    const val = parseHindiOrNumericValue(norm)
    if (val === null) continue

    hasValidToken = true

    if (norm === 'सौ' || norm === 'sau' || norm === 'hundred' || val === 100) {
      if (currentGroup === 0) currentGroup = 1
      totalSum += currentGroup * 100
      currentGroup = 0
    } else if (norm === 'हजार' || norm === 'hazaar' || norm === 'thousand' || val === 1000) {
      if (currentGroup === 0) currentGroup = 1
      totalSum += currentGroup * 1000
      currentGroup = 0
    } else if (val >= 100) {
      totalSum += currentGroup + val
      currentGroup = 0
    } else {
      currentGroup += val
    }
  }

  totalSum += currentGroup
  return hasValidToken ? totalSum : null
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

export const DISTANCE_UNIT_TOKENS: string[] = [
  'kilometer', 'kilometers', 'kilometre', 'kilometres', 'km', 'kms',
  'किलोमीटर', 'किलोमीटर्स',
  'meter', 'meters', 'metre', 'metres', 'मीटर',
  'centimeter', 'centimetre', 'cm', 'सेंटीमीटर',
  'mile', 'miles', 'मील', 'foot', 'feet', 'फुट', 'फीट',
]

/** Derived from ITEM_LEXICON — do not hand-edit. Add items in lexicon.ts. */
export const DEFAULT_ITEM_VOCAB: string[] = ALL_ITEM_SURFACES

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

export function baseUnitOf(unitStr: string): string {
  const norm = normalizeUnit(unitStr)
  switch (norm) {
    case 'KG':
    case 'GRAM':
    case 'PAO':
    case 'AADHA':
    case 'DHAI':
    case 'SAWA':
      return 'KG'
    case 'PIECE':
    case 'DOZEN':
      return 'PIECE'
    case 'PACKET':
      return 'PACKET'
    case 'LITRE':
    case 'ML':
      return 'LITRE'
    case 'BOX':
      return 'BOX'
    default:
      return norm || 'PACKET'
  }
}

export function unitMultiplier(unitStr: string): number {
  const norm = normalizeUnit(unitStr)
  switch (norm) {
    case 'GRAM':
    case 'ML':
      return 0.001
    case 'PAO':
      return 0.25
    case 'AADHA':
      return 0.5
    case 'SAWA':
      return 1.25
    case 'DHAI':
      return 2.5
    case 'DOZEN':
      return 12.0
    case 'KG':
    case 'LITRE':
    case 'PIECE':
    case 'PACKET':
    case 'BOX':
    default:
      return 1.0
  }
}

export type ResolutionKind = 'MATCH' | 'AMBIGUOUS' | 'UNKNOWN'

export interface RawItemSegment {
  rawSegmentText: string
  heardSegmentText?: string
  quantity: number
  unit: string
  itemTokens: string[]
  isSanityFlagged?: boolean
  itemMatchNorm?: number | null
  itemMargin?: number | null
  top3Candidates?: CandidateRank[]
  /** Price attached to THIS item's segment only (see ISSUE-029) -- null when no rupee
   *  word/price run was found within the segment's own token span. */
  spokenPrice?: number | null
  rupeeWordPresent?: boolean
  /** Whether a quantity was actually spoken before the item, vs defaulted to 1.0 --
   *  the RATE_UPDATE / BULK_SALE_TOTAL discriminator needs the real answer, not the
   *  post-default value. */
  hasLeadingQty?: boolean
  resolutionKind?: ResolutionKind
  /** Quantity came from a low-margin fragmented-numeral rejoin (ISSUE-106) -- best guess,
   *  but a different number scored nearly as close, so it must not auto-confirm. */
  numeralRejoinLowMargin?: boolean
}

export interface NumeralRejoin {
  leftToken: string
  rightToken: string
  mergedSurface: string
  value: number
  matchNorm: number
  valueMargin: number
  /** True when a different numeric value sat within MERGE_MIN_VALUE_MARGIN of the winner
   *  ("तेईस"=23 and "तीस"=30 collapse to the same phonetic key). The merge still applies --
   *  a plausible number beats stranded debris -- but the quantity must NOT auto-confirm. */
  lowMargin: boolean
}

export interface SegmentResult {
  segments: RawItemSegment[]
  carryoverQty?: number | null
  numeralRejoins?: NumeralRejoin[]
}

type TokenType = 'NUM' | 'UNIT' | 'ITEM'

interface Emission {
  type: TokenType
  cost: number
  surface: string
  heardText: string
  numericValue?: number
  canonicalUnit?: string
  suspect?: boolean
  matchNorm?: number
  matchMargin?: number | null
  top3Candidates?: CandidateRank[]
  fromSplit?: boolean
  isQualifier?: boolean
}

interface Expansion {
  emissions: Emission[]
  emissionCost: number
}

interface VocabEntry {
  key: string
  surface: string
  /** Identity this surface belongs to. Two surfaces of one item share it, so the
   *  ambiguity margin measures distance to the next DIFFERENT item. ISSUE-107. */
  canonical: string
  numericValue?: number
  canonicalUnit?: string
}

export interface SegmenterVocabulary {
  numbers: VocabEntry[]
  units: VocabEntry[]
  qualifiers: VocabEntry[]
  items: VocabEntry[]
}

export function buildVocabulary(catalogNames: string[] = []): SegmenterVocabulary {
  return {
    numbers: Object.entries(HINDI_NUMBER_MAP)
      .filter(([w]) => w.length >= 2)
      .map(([w, v]) => ({ key: phoneticKey(w), surface: w, canonical: `num:${v}`, numericValue: v })),
    units: UNIT_SET.map(u => ({ key: phoneticKey(u), surface: u, canonical: `unit:${normalizeUnit(u) ?? u}`, canonicalUnit: u })),
    qualifiers: ALL_QUALIFIER_SURFACES.map(q => ({
      key: phoneticKey(q), surface: q, canonical: `qual:${canonicalQualifierOf(q)}`
    })),
    items: Array.from(new Set([...DEFAULT_ITEM_VOCAB, ...catalogNames].filter(n => n && n.trim())))
      .map(n => ({ key: phoneticKey(n), surface: n, canonical: canonicalOf(n) })),
  }
}

const EXACT_COST = 0.0
const ELISION_COST = 0.5
const ITEM_BASELINE_COST = 1.2
const ITEM_MATCHED_BASE_COST = 0.2
const WHOLE_TOKEN_MAX_NORM = 0.25
const SPLIT_PART_MAX_NORM = 0.30
const SPLIT_UNIT_TRUST_NORM = 0.15
const MIN_SPLIT_PHONES = 2
const SPLIT_PENALTY = 0.10
const DISTANCE_TOKEN_ITEM_COST = 2.5

/** A runner-up within one consonant edit of the winner is ambiguous regardless of key
 *  length. Replaces TAU_MARGIN = 0.08, which was unreachable for keys <= 6 phones
 *  (margin granularity is ~0.5/keyLength) and therefore never fired. See ISSUE-103. */
const MIN_MARGIN_PHONE_EDITS = 1.0

/** A runner-up 0.30 normalized away is a clear win regardless of key length. Short real
 *  products (घी -> KI, आम -> AN, 2 phones) can never satisfy margin*keyLen >= 1.0, which
 *  demands margin >= 0.5 — unreachable against a genuine neighbour like गोभी at 0.375. Without
 *  this, every 2-phone item in the catalog is permanently review-only. ISSUE-109. */
const CLEAR_WIN_ABS_MARGIN = 0.30

/** Fragmented-numeral rejoin (ISSUE-106). A Hindi compound numeral 21-99 is ONE spoken
 *  word ("तैंतीस"), but STT routinely emits it as two tokens ("ते" + "तीस"). The lattice
 *  splits fused tokens and has no way to merge fragmented ones, so the leading fragment
 *  became a bogus item and the trailing piece booked as the quantity -- 33 kg went into
 *  the ledger as 30 kg.
 *
 *  0.22 is a measured optimum, not a guess. Below it, merges stop firing on realistically
 *  lossy fragments and silent mis-bookings return (13 at 0.20, 55 at 0.12). Above it, real
 *  transcripts start corrupting ("हर्ष दस किलो आलू" flips 10->27 at 0.30). At 0.22, zero of
 *  170 production transcripts change harmfully. See Docs/fragmented_hindi_numeral_fix_plan.md §3. */
const MERGE_MAX_NORM = 0.22
const MERGE_MIN_VALUE_MARGIN = 0.10

/** Hindi/Hinglish discourse particles, fillers and postpositions. Never a product in
 *  any shop, but short enough that the lossy phone key collides them with real catalog
 *  items -- "हाँ" and "आम" are both key "AN". See ISSUE-103. */
export const DISCOURSE_PARTICLES: Set<string> = new Set([
  'हाँ','हां','हा','ना','नहीं','है','हैं','ये','यह','वो','वह','अच्छा','ठीक','ओके','जी',
  'अरे','बस','और','तो','भी','का','की','के','में','से','पर','अब','क्या','अम','उम','हम्म','आँ',
  'haan','han','haa','hai','ye','wo','achha','theek','ji','bas','aur','ok','okay','hmm','umm',
])

/** Words that are never half of a fragmented numeral. The rejoin refused only a LEFT token that
 *  was already a number, unit, rupee word or catalog surface — ordinary vocabulary sailed
 *  through, and "आज"+"उधार" merged into "चौहत्तर" (74) at norm 0.143, turning a question into a
 *  74 kg / ₹2960 line. ISSUE-126. Mirrored in OrderingSegmenter.kt — change both. */
export const NEVER_NUMERAL_FRAGMENT: Set<string> = new Set([
  'आज','कल','परसों','अभी','अब','रोज','कितना','कितने','कितनी','क्या','कौन','कब','कहाँ','कहां',
  'उधार','उधारी','खाता','बकाया','बचा','बाकी','सामान','माल','कुल','टोटल','हिसाब','स्टॉक',
  'बेचा','बेची','बिका','बिके','बिकी','मुनाफा','मुनाफ़ा','कमाई','खराब',
  'aaj','kal','abhi','ab','roz','kitna','kitne','kitni','kya','kaun','kab','kahan',
  'udhaar','udhar','udhari','khata','bakaya','bacha','baki','saman','samaan','maal',
  'kul','total','hisaab','stock','becha','bika','bike','munafa','kamai','kharab',
])


function transitionCost(prev: TokenType | null, curr: TokenType): number {
  if (prev === null) return curr === 'NUM' ? 0.0 : curr === 'ITEM' ? 0.3 : 1.0
  if (prev === 'NUM') return curr === 'UNIT' ? 0.0 : curr === 'ITEM' ? 0.3 : 4.0
  if (prev === 'UNIT') return curr === 'ITEM' ? 0.0 : curr === 'NUM' ? 2.0 : 3.0
  return curr === 'NUM' ? 0.0 : curr === 'UNIT' ? 1.5 : 0.2
}

function endCost(last: TokenType): number {
  return last === 'ITEM' ? 0.0 : 0.6
}

export interface CandidateRank {
  itemName: string
  distance: number
  score: number
  margin?: number | null
}

export interface VocabHit {
  entry: VocabEntry
  normalized: number
  margin?: number | null
  top3?: CandidateRank[]
}

function matchVocab(
  fragment: string,
  vocab: VocabEntry[],
  maxNorm: number,
  opts: { allowElision?: boolean; allowEcho?: boolean; rawFragment?: string; aliases?: Map<string, string> } = {}
): VocabHit | null {
  if (!fragment || !vocab.length) return null

  if (opts.aliases && opts.aliases.has(fragment)) {
    const canonicalName = opts.aliases.get(fragment)!
    const matchingEntry = vocab.find(v => v.surface.toLowerCase() === canonicalName.toLowerCase())
    if (matchingEntry) {
      return {
        entry: matchingEntry,
        normalized: 0.0,
        margin: 1.0,
        top3: [{ itemName: matchingEntry.surface, distance: 0.0, score: 0.0 }]
      }
    }
  }

  // Keyed by CANONICAL identity, not phonetic key: "अदरक" (ATALAK) and "Adrak" (ATLAK) are
  // one item with two keys, and keying by key made them two candidates one edit apart, which
  // read as an ambiguous match and capped a correct parse at 0.55. ISSUE-107.
  const candidateMap = new Map<string, VocabHit>()
  for (const entry of vocab) {
    if (!entry.key) continue
    let cost = phoneticDistance(fragment, entry.key)
    if (opts.allowElision && entry.key.length > fragment.length && entry.key.endsWith(fragment)) {
      cost = Math.min(cost, ELISION_COST)
    }
    if (opts.allowEcho && fragment.length > entry.key.length && fragment.startsWith(entry.key)) {
      cost = Math.min(cost, (fragment.length - entry.key.length) * 0.5)
    }
    const norm = cost / Math.max(fragment.length, entry.key.length)
    const existing = candidateMap.get(entry.canonical)
    if (!existing || norm < existing.normalized) {
      candidateMap.set(entry.canonical, { entry, normalized: norm })
    } else if (norm === existing.normalized) {
      const compareText = opts.rawFragment || fragment
      const existingLitDist = normalizedLiteralDistance(compareText, existing.entry.surface)
      const newLitDist = normalizedLiteralDistance(compareText, entry.surface)
      if (newLitDist < existingLitDist) {
        candidateMap.set(entry.canonical, { entry, normalized: norm })
      }
    }
  }
  if (!candidateMap.size) return null
  const candidates = Array.from(candidateMap.values()).sort((a, b) => {
    if (a.normalized !== b.normalized) return a.normalized - b.normalized
    const compareText = opts.rawFragment || fragment
    return normalizedLiteralDistance(compareText, a.entry.surface) - normalizedLiteralDistance(compareText, b.entry.surface)
  })
  const best = candidates[0]
  if (best.normalized > maxNorm) return null
  const second = candidates.length > 1 ? candidates[1] : null
  const margin = second ? (second.normalized - best.normalized) : null
  const top3: CandidateRank[] = candidates.slice(0, 3).map(c => ({
    itemName: c.entry.surface,
    distance: c.normalized,
    score: c.normalized,
    margin,
  }))
  return {
    ...best,
    margin,
    top3,
  }
}

function wholeTokenExpansions(
  raw: string,
  vocab: SegmenterVocabulary,
  aliases: Map<string, string> = new Map()
): Expansion[] {
  const lower = raw.toLowerCase()
  const out: Expansion[] = []

  if (DISTANCE_UNIT_TOKENS.includes(lower)) {
    return [{
      emissions: [{ type: 'ITEM', cost: DISTANCE_TOKEN_ITEM_COST, surface: raw, heardText: raw, suspect: true }],
      emissionCost: DISTANCE_TOKEN_ITEM_COST,
    }]
  }

  if (HINDI_NUMBER_MAP[lower] !== undefined) {
    out.push({ emissions: [{ type: 'NUM', cost: EXACT_COST, surface: raw, heardText: raw, numericValue: HINDI_NUMBER_MAP[lower] }], emissionCost: EXACT_COST })
  } else if (/^\d+(\.\d+)?$/.test(lower)) {
    out.push({ emissions: [{ type: 'NUM', cost: EXACT_COST, surface: raw, heardText: raw, numericValue: parseFloat(lower) }], emissionCost: EXACT_COST })
  }
  if (UNIT_SET.includes(lower)) {
    out.push({ emissions: [{ type: 'UNIT', cost: EXACT_COST, surface: raw, heardText: raw, canonicalUnit: lower }], emissionCost: EXACT_COST })
  }
  if (out.length > 0) return out

  const key = phoneticKey(lower)
  if (key) {
    const n = matchVocab(key, vocab.numbers, WHOLE_TOKEN_MAX_NORM)
    if (n) out.push({ emissions: [{ type: 'NUM', cost: n.normalized, surface: raw, heardText: raw, numericValue: n.entry.numericValue, matchNorm: n.normalized }], emissionCost: n.normalized })

    const u = matchVocab(key, vocab.units, WHOLE_TOKEN_MAX_NORM, { allowElision: true })
    if (u) out.push({ emissions: [{ type: 'UNIT', cost: u.normalized, surface: raw, heardText: raw, canonicalUnit: u.entry.canonicalUnit, matchNorm: u.normalized }], emissionCost: u.normalized })

    const q = matchVocab(key, vocab.qualifiers, WHOLE_TOKEN_MAX_NORM, { rawFragment: raw })
    if (q) {
      out.push({
        emissions: [{
          type: 'ITEM',
          cost: ITEM_MATCHED_BASE_COST + q.normalized,
          surface: raw,
          heardText: raw,
          matchNorm: q.normalized,
          isQualifier: true,
        }],
        emissionCost: ITEM_MATCHED_BASE_COST + q.normalized,
      })
    }

    const it = matchVocab(key, vocab.items, WHOLE_TOKEN_MAX_NORM, { allowEcho: true, rawFragment: raw, aliases })
    if (it) {
      const cost = ITEM_MATCHED_BASE_COST + it.normalized
      out.push({
        emissions: [{
          type: 'ITEM',
          cost,
          surface: it.entry.surface,
          heardText: raw,
          matchNorm: it.normalized,
          matchMargin: it.margin,
          top3Candidates: it.top3,
        }],
        emissionCost: cost,
      })
    }
  }

  out.push({ emissions: [{ type: 'ITEM', cost: ITEM_BASELINE_COST, surface: raw, heardText: raw }], emissionCost: ITEM_BASELINE_COST })
  return out
}

function splitExpansions(
  raw: string,
  vocab: SegmenterVocabulary,
  aliases: Map<string, string> = new Map()
): Expansion[] {
  const key = phoneticKey(raw.toLowerCase())
  if (key.length < MIN_SPLIT_PHONES * 2) return []
  const out: Expansion[] = []

  const em = (type: TokenType, hit: VocabHit, heardText: string = raw): Emission => ({
    type, cost: hit.normalized, surface: hit.entry.surface, heardText,
    numericValue: hit.entry.numericValue, canonicalUnit: hit.entry.canonicalUnit,
    matchNorm: hit.normalized,
    matchMargin: type === 'ITEM' ? hit.margin : undefined,
    top3Candidates: type === 'ITEM' ? hit.top3 : undefined,
    fromSplit: true,
  })

  for (let i = MIN_SPLIT_PHONES; i <= key.length - MIN_SPLIT_PHONES; i++) {
    const p1 = key.slice(0, i)
    const p2 = key.slice(i)

    const n = matchVocab(p1, vocab.numbers, SPLIT_PART_MAX_NORM)
    if (n) {
      const u = matchVocab(p2, vocab.units, SPLIT_PART_MAX_NORM, { allowElision: true })
      if (u) out.push({ emissions: [em('NUM', n), em('UNIT', u)], emissionCost: n.normalized + u.normalized + 2 * SPLIT_PENALTY })
      const it = matchVocab(p2, vocab.items, SPLIT_PART_MAX_NORM, { aliases })
      if (it) out.push({ emissions: [em('NUM', n), em('ITEM', it)], emissionCost: n.normalized + it.normalized + 2 * SPLIT_PENALTY })
    }
    const u1 = matchVocab(p1, vocab.units, SPLIT_PART_MAX_NORM)
    if (u1) {
      const it = matchVocab(p2, vocab.items, SPLIT_PART_MAX_NORM, { aliases })
      if (it) out.push({ emissions: [em('UNIT', u1), em('ITEM', it)], emissionCost: u1.normalized + it.normalized + 2 * SPLIT_PENALTY })
    }
  }

  for (let i = MIN_SPLIT_PHONES; i <= key.length - 2 * MIN_SPLIT_PHONES; i++) {
    for (let j = i + MIN_SPLIT_PHONES; j <= key.length - MIN_SPLIT_PHONES; j++) {
      const n = matchVocab(key.slice(0, i), vocab.numbers, SPLIT_PART_MAX_NORM)
      if (!n) continue
      const u = matchVocab(key.slice(i, j), vocab.units, SPLIT_PART_MAX_NORM, { allowElision: true })
      if (!u) continue
      const it = matchVocab(key.slice(j), vocab.items, SPLIT_PART_MAX_NORM, { aliases })
      if (!it) continue
      out.push({
        emissions: [em('NUM', n), em('UNIT', u), em('ITEM', it)],
        emissionCost: n.normalized + u.normalized + it.normalized + 3 * SPLIT_PENALTY,
      })
    }
  }
  return out
}



export function rejoinFragmentedNumerals(
  tokens: string[],
  vocab: SegmenterVocabulary,
  itemSurfaceSet: Set<string>
): { tokens: string[]; rejoins: NumeralRejoin[] } {
  if (tokens.length < 2) return { tokens, rejoins: [] }
  const out: string[] = []
  const rejoins: NumeralRejoin[] = []

  for (let i = 0; i < tokens.length; i++) {
    if (i === tokens.length - 1) { out.push(tokens[i]); continue }

    const left = tokens[i]
    const right = tokens[i + 1]
    const leftLower = left.toLowerCase()

    // The LEFT token must be unrecognized debris. A token that already matches known
    // vocabulary exactly is a real word, not the front half of a broken numeral.
    //
    // itemSurfaceSet is LOAD-BEARING, not defensive boilerplate: "दही"+"तीस" merges to 33
    // at norm 0.143 -- TIGHTER than the bug this fix targets -- so without this line
    // "दही तीस किलो" (curd, 30 kg) silently becomes 33 kg. Verified by removing it.
    if (
      HINDI_NUMBER_MAP[leftLower] !== undefined ||
      /^\d+(\.\d+)?$/.test(leftLower) ||
      UNIT_SET.includes(leftLower) ||
      DISTANCE_UNIT_TOKENS.includes(leftLower) ||
      RUPEE_WORDS.has(leftLower) ||
      itemSurfaceSet.has(leftLower) ||
      NEVER_NUMERAL_FRAGMENT.has(leftLower) ||
      DISCOURSE_PARTICLES.has(leftLower)
    ) { out.push(left); continue }

    // The RIGHT half was never checked at all. A known word is a word, not the tail of a broken
    // numeral — this is the half that turned "उधार" into "-हत्तर".
    const rightLower = right.toLowerCase()
    if (
      NEVER_NUMERAL_FRAGMENT.has(rightLower) ||
      DISCOURSE_PARTICLES.has(rightLower) ||
      itemSurfaceSet.has(rightLower)
    ) { out.push(left); continue }

    const joinedKey = phoneticKey(left + right)
    if (!joinedKey) { out.push(left); continue }

    // Rank by distinct VALUE, not by surface: "तैंतीस" and "taintees" are the same number
    // and must not occupy two candidate slots -- that would fake a tiny margin and
    // wrongly suppress auto-confirm on a confident merge.
    const bestPerValue = new Map<number, { surface: string; value: number; norm: number }>()
    for (const entry of vocab.numbers) {
      if (!entry.key || entry.numericValue === undefined) continue
      const norm = phoneticDistance(joinedKey, entry.key) / Math.max(joinedKey.length, entry.key.length)
      const cur = bestPerValue.get(entry.numericValue)
      if (!cur || norm < cur.norm) {
        bestPerValue.set(entry.numericValue, { surface: entry.surface, value: entry.numericValue, norm })
      }
    }
    const ranked = Array.from(bestPerValue.values()).sort((a, b) => a.norm - b.norm)
    if (!ranked.length) { out.push(left); continue }

    const best = ranked[0]
    if (best.norm > MERGE_MAX_NORM) { out.push(left); continue }

    const valueMargin = ranked.length > 1 ? (ranked[1].norm - best.norm) : 1.0

    out.push(best.surface)
    rejoins.push({
      leftToken: left,
      rightToken: right,
      mergedSurface: best.surface,
      value: best.value,
      matchNorm: best.norm,
      valueMargin,
      lowMargin: valueMargin < MERGE_MIN_VALUE_MARGIN,
    })
    i++ // consume the right token; merges never overlap
  }

  return { tokens: out, rejoins }
}

interface DecodedToken {
  type: TokenType
  rawToken: string
  heardText: string
  numericValue?: number
  canonicalUnit?: string
  suspect?: boolean
  matchNorm?: number
  matchMargin?: number | null
  top3Candidates?: CandidateRank[]
  fromSplit?: boolean
  isQualifier?: boolean
}

/**
 * Viterbi over a token-expansion lattice. State is (source token index, type of the
 * last emission produced), so an expansion emitting several typed pieces pays the
 * internal transitions between them as well as the transition from the previous
 * token. Splits therefore compete against whole-token readings under the shopkeeper
 * grammar rather than being decided greedily in isolation — which matters because
 * "एकलो" reads equally well as "ek kilo" and "ek aaloo" on its own, and only a
 * following item token settles it.
 */
function decode(
  tokens: string[],
  vocab: SegmenterVocabulary,
  aliases: Map<string, string> = new Map()
): { decoded: DecodedToken[]; minGap: number } {
  if (!tokens.length) return { decoded: [], minGap: Number.MAX_VALUE }

  const expansionsPerToken = tokens.map(raw => {
    const whole = wholeTokenExpansions(raw, vocab, aliases)
    const exactOnly = whole.length >= 1 && whole.every(e => e.emissionCost === EXACT_COST)
    const all = exactOnly ? whole : [...whole, ...splitExpansions(raw, vocab, aliases)]
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
      chosen[i] = { emissions: [{ type: 'ITEM', cost: ITEM_BASELINE_COST, surface: tokens[i], heardText: tokens[i] }], emissionCost: ITEM_BASELINE_COST }
      currentType = 'ITEM'
      continue
    }
    chosen[i] = entry.exp
    currentType = entry.prev ?? 'ITEM'
  }

  const decoded: DecodedToken[] = []
  for (const exp of chosen) {
    for (const e of exp.emissions) {
      decoded.push({
        type: e.type,
        rawToken: e.surface,
        heardText: e.heardText,
        numericValue: e.numericValue,
        canonicalUnit: e.canonicalUnit,
        suspect: e.suspect,
        matchNorm: e.matchNorm,
        matchMargin: e.matchMargin,
        top3Candidates: e.top3Candidates,
        fromSplit: e.fromSplit,
        isQualifier: e.isQualifier,
      })
    }
  }
  return { decoded, minGap }
}

// (RawItemSegment is declared once, above — a second declaration here used to shadow-merge
// with it under different optionality modifiers, which TypeScript reports as
// TS2687/TS2717 on every build.)

const LOW_CONFIDENCE_GAP_THRESHOLD = 0.15

/** Segments a transcript into [QUANTITY][UNIT][ITEM] groups. Returns segments plus a
 *  trailing quantity with no item attached (carryover for the next utterance). */
export function segmentTranscript(
  transcript: string,
  catalogNames: string[] = [],
  pendingCarryoverQty: number | null = null,
  aliases: Map<string, string> = new Map()
): SegmentResult {
  const cleanText = (transcript || '')
    .replace(/।/g, ' ')
    .replace(/[.,?!\-\\()]/g, ' ')
    .trim()

  if (!cleanText) return { segments: [], carryoverQty: pendingCarryoverQty, numeralRejoins: [] }

  const vocab = buildVocabulary(catalogNames)
  const itemSurfaceSet = new Set(
    [...DEFAULT_ITEM_VOCAB, ...catalogNames]
      .filter(n => n && n.trim())
      .map(n => n.trim().toLowerCase())
  )
  const rawTokens = cleanText.split(/\s+/).filter(t => t.length > 0)
  const tokens = rawTokens.filter(t => {
    const lower = t.toLowerCase()
    if (DISCOURSE_PARTICLES.has(lower) || DISCOURSE_PARTICLES.has(t)) {
      return itemSurfaceSet.has(lower)
    }
    return true
  })
  const { tokens: mergedTokens, rejoins } = rejoinFragmentedNumerals(tokens, vocab, itemSurfaceSet)
  const lowMarginSurfaces = new Set(rejoins.filter(r => r.lowMargin).map(r => r.mergedSurface.toLowerCase()))
  const { decoded, minGap } = decode(mergedTokens, vocab, aliases)

  // Price-run precompute: a rupee word decodes as a generic ITEM (nothing in the
  // number/unit/item vocab matches "रुपये"), so without this pass a mid-utterance
  // price silently became its own bogus item segment -- "आलू 50 रुपये" produced
  // TWO segments, {Aaloo} and {qty:50, item:"रुपये"} -- instead of one priced item.
  // For every rupee-word token, walk backward through contiguous NUM tokens (the
  // compound number run immediately before it, e.g. "दो सौ पचास"); if none, walk
  // forward. Mirrors detectPriceIntent's backward-then-forward walk, but scoped per
  // rupee word so a multi-item utterance can carry a different price per item.
  const priceNumIndices = new Set<number>()
  const rupeeIndices = new Set<number>()
  const priceValueAtRupeeIndex = new Map<number, number | null>()
  for (let i = 0; i < decoded.length; i++) {
    const dt = decoded[i]
    if (!RUPEE_WORDS.has((dt.rawToken || '').toLowerCase())) continue
    rupeeIndices.add(i)
    const runIdx: number[] = []
    for (let j = i - 1; j >= 0 && decoded[j].type === 'NUM' && !rupeeIndices.has(j); j--) runIdx.unshift(j)
    if (runIdx.length === 0) {
      for (let j = i + 1; j < decoded.length && decoded[j].type === 'NUM'; j++) runIdx.push(j)
    }
    for (const j of runIdx) priceNumIndices.add(j)
    priceValueAtRupeeIndex.set(
      i,
      runIdx.length > 0 ? parseCompoundNumberSequence(runIdx.map(j => decoded[j].rawToken)) : null
    )
  }

  let currentQty: number | null = pendingCarryoverQty
  let currentUnit: string | null = null
  let currentItemTokens: string[] = []
  let currentSegmentTokens: string[] = []
  let currentHeardTokens: string[] = []
  let ambiguousDoubleQty = minGap < LOW_CONFIDENCE_GAP_THRESHOLD
  let suspectReading = false
  let worstItemNorm: number | null = null
  let bestItemMargin: number | null = null
  let worstNonItemNorm: number | null = null
  let anyFromSplit = false
  let segmentTop3: CandidateRank[] = []
  let segmentSpokenPrice: number | null = null
  let segmentSawRupeeWord = false
  let sawExplicitQty = pendingCarryoverQty !== null
  const segments: RawItemSegment[] = []

  const closeSegment = () => {
    if (currentItemTokens.length > 0) {
      const name = currentItemTokens.join(' ').trim()
      const rawText = currentSegmentTokens.join(' ').trim()
      const heardText = currentHeardTokens.join(' ').trim()
      const nonItemUntrustworthy = anyFromSplit && worstNonItemNorm !== null && worstNonItemNorm > SPLIT_UNIT_TRUST_NORM
      const itemKeyLength = currentItemTokens.length > 0 ? phoneticKey(currentItemTokens.join(' ')).length : 0
      const isAmbiguousByMargin =
        bestItemMargin !== null && itemKeyLength > 0 &&
        bestItemMargin < CLEAR_WIN_ABS_MARGIN &&
        (bestItemMargin * itemKeyLength) < MIN_MARGIN_PHONE_EDITS
      const resKind: 'MATCH' | 'AMBIGUOUS' | 'UNKNOWN' = worstItemNorm === null ? 'UNKNOWN' :
                      (nonItemUntrustworthy || isAmbiguousByMargin) ? 'AMBIGUOUS' : 'MATCH'
      segments.push({
        rawSegmentText: rawText || name,
        heardSegmentText: heardText || name,
        quantity: currentQty ?? 1.0,
        unit: currentUnit ?? 'PACKET',
        itemTokens: [name],
        isSanityFlagged: ambiguousDoubleQty || suspectReading || (resKind !== 'MATCH'),
        itemMatchNorm: worstItemNorm,
        itemMargin: bestItemMargin,
        top3Candidates: segmentTop3.length > 0 ? segmentTop3 : undefined,
        spokenPrice: segmentSpokenPrice,
        rupeeWordPresent: segmentSawRupeeWord,
        hasLeadingQty: sawExplicitQty,
        resolutionKind: resKind,
        numeralRejoinLowMargin: currentSegmentTokens.some(t => lowMarginSurfaces.has(t.toLowerCase())),
      })
    }
    currentQty = null
    currentUnit = null
    currentItemTokens = []
    currentSegmentTokens = []
    currentHeardTokens = []
    ambiguousDoubleQty = false
    suspectReading = false
    worstItemNorm = null
    bestItemMargin = null
    worstNonItemNorm = null
    anyFromSplit = false
    segmentTop3 = []
    segmentSpokenPrice = null
    segmentSawRupeeWord = false
    sawExplicitQty = false
  }

  for (let idx = 0; idx < decoded.length; idx++) {
    const dt = decoded[idx]
    if (dt.suspect) suspectReading = true

    // Price-run tokens (a rupee word and any NUM tokens feeding it) attach to
    // whichever segment is currently open instead of starting/ending a segment --
    // they carry no item-boundary information of their own.
    if (rupeeIndices.has(idx)) {
      segmentSawRupeeWord = true
      const val = priceValueAtRupeeIndex.get(idx)
      if (val !== null && val !== undefined) segmentSpokenPrice = val
      currentSegmentTokens.push(dt.rawToken)
      currentHeardTokens.push(dt.heardText)
      continue
    }
    if (priceNumIndices.has(idx)) {
      currentSegmentTokens.push(dt.rawToken)
      currentHeardTokens.push(dt.heardText)
      continue
    }

    if (dt.type === 'NUM') {
      if (currentItemTokens.length > 0) closeSegment()
      else if (currentQty !== null) ambiguousDoubleQty = true
      currentQty = dt.numericValue ?? 1.0
      sawExplicitQty = true
      currentSegmentTokens.push(dt.rawToken)
      currentHeardTokens.push(dt.heardText)
      if (dt.matchNorm !== undefined && dt.matchNorm !== null) {
        worstNonItemNorm = worstNonItemNorm === null ? dt.matchNorm : Math.max(worstNonItemNorm, dt.matchNorm)
      }
      if (dt.fromSplit) anyFromSplit = true
    } else if (dt.type === 'UNIT') {
      currentUnit = normalizeUnit(dt.canonicalUnit ?? dt.rawToken)
      currentSegmentTokens.push(dt.rawToken)
      currentHeardTokens.push(dt.heardText)
      if (dt.matchNorm !== undefined && dt.matchNorm !== null) {
        worstNonItemNorm = worstNonItemNorm === null ? dt.matchNorm : Math.max(worstNonItemNorm, dt.matchNorm)
      }
      if (dt.fromSplit) anyFromSplit = true
    } else {
      if (dt.isQualifier) {
        // A brand or variety word is part of the item PHRASE but is not a competing product. Letting
        // its match statistics into the ambiguity margin is what flagged "पाँच किलो आलू" as AMBIGUOUS
        // (हरा/हरी sit 0.167 from आलू). ISSUE-109.
      } else {
        if (dt.matchNorm !== undefined && dt.matchNorm !== null) {
          worstItemNorm = worstItemNorm === null ? dt.matchNorm : Math.max(worstItemNorm, dt.matchNorm)
        }
        if (dt.matchMargin !== undefined && dt.matchMargin !== null) {
          bestItemMargin = dt.matchMargin
        }
        if (dt.top3Candidates && dt.top3Candidates.length > 0) {
          segmentTop3 = dt.top3Candidates
        }
      }
      currentItemTokens.push(dt.rawToken)
      currentSegmentTokens.push(dt.rawToken)
      currentHeardTokens.push(dt.heardText)
    }
  }

  const carryover = currentQty !== null && currentItemTokens.length === 0 ? currentQty : null
  if (currentItemTokens.length > 0) closeSegment()

  return { segments, carryoverQty: carryover, numeralRejoins: rejoins }
}

/** Rewrites a transcript into canonical space-separated tokens. Used to feed the AI
 *  step a cleaned hint and to keep the diagnostic trace readable. */
export function normalizeTranscript(transcript: string, catalogNames: string[] = []): string {
  const { segments } = segmentTranscript(transcript, catalogNames)
  if (!segments.length) return transcript
  return segments.map(s => s.heardSegmentText || s.rawSegmentText).join(' ')
}
