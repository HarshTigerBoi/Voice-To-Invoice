/**
 * Server mirror of the Kotlin `domain/router/IntentRouter.kt` + `IntentLexicon.kt`.
 *
 * The client and the edge function independently classify the same utterance (see CLAUDE.md's
 * mirror rule). If only the client knew the new intents, a recording processed while the app was
 * closed -- which is the entire point of server-first processing -- would silently fall back to the
 * old Devanagari-only behaviour and drop returns, payments, price updates and voids.
 *
 * Both sides run the same 60-phrase fixture (`intent_router_test.ts` here, `IntentRouterFixtureTest`
 * on the client) and must agree on every one. That shared fixture is the drift guard.
 *
 * Matching happens in phone-key space via `phoneticKey`/`normalizedDistance` from `phonetic.ts` --
 * the SAME functions the item segmenter uses -- so cross-script (Devanagari / Latin-Hinglish /
 * English) matching works by construction rather than by duplicating vocabulary per script.
 */

import { phoneticKey, normalizedDistance, HINDI_NUMBER_MAP, UNIT_SET } from './phonetic.ts'

export type AssistantIntent =
  | 'SALE'
  | 'CREDIT_SALE'
  | 'PAYMENT_RECEIVED'
  | 'STOCK_IN'
  | 'RETURN'
  | 'WASTE'
  | 'EXPIRY_WRITEOFF'
  | 'PRICE_UPDATE'
  | 'VOID_LAST'
  | 'READ_QUERY'
  | 'ACTION_COMMAND'
  | 'UNKNOWN'

export interface IntentTrigger {
  intent: AssistantIntent
  phrases: string[]
  weight: number
}

export interface ParsedLineLike {
  item_name?: string
  quantity?: number
  total?: number
  price_at_sale?: number
}

export interface IntentClassification {
  intent: AssistantIntent
  confidence: number
  isReadOnly: boolean
  scores: Record<string, number>
  runnerUp: AssistantIntent | null
  needsArbitration: boolean
}

/** Keep in lockstep with `IntentLexicon.MAX_TRIGGER_DISTANCE` on the client. */
export const MAX_TRIGGER_DISTANCE = 0.25
/** Keep in lockstep with `IntentRouter.DIRECT_ROUTE_CONFIDENCE`. */
export const DIRECT_ROUTE_CONFIDENCE = 0.75
/** Keep in lockstep with `IntentRouter.ARBITRATION_FLOOR`. */
export const ARBITRATION_FLOOR = 0.45
/** Keep in lockstep with `IntentRouter.BASE_SALE_SCORE`. */
const BASE_SALE_SCORE = 0.9

export const TRIGGERS: IntentTrigger[] = [
  // READ_QUERY -- strong. Only these establish that an utterance is a question.
  {
    intent: 'READ_QUERY',
    weight: 1.0,
    phrases: [
      'कितना', 'कितने', 'कितनी', 'kitna', 'kitne', 'kitni', 'kitna hua', 'kitni hui',
      'how much', 'how many', 'बताओ', 'बता दो', 'batao', 'bata do', 'tell me',
      'हिसाब', 'hisaab', 'hisab', 'कमाई', 'kamai', 'कमाया', 'kamaya',
      'total kya', 'आज की सेल', 'aaj ki sale', 'today sale', 'todays sales',
      'स्टॉक कितना', 'stock kitna', 'how much stock', 'बाकी कितना', 'baki kitna',
      'मुनाफ़ा', 'munafa', 'profit',
    ],
  },
  {
    // Weak: these collide with ordinary speech once voicing/vowel length collapse
    // ("kya"~"gaya", "fayda"~"paid"), so they support a question but never establish one.
    intent: 'READ_QUERY',
    weight: 0.35,
    phrases: ['क्या', 'kya', 'what', 'कौन', 'kaun', 'who', 'कब', 'kab', 'when', 'फायदा', 'fayda'],
  },

  {
    intent: 'PAYMENT_RECEIVED',
    weight: 1.0,
    phrases: [
      'दे दिए', 'de diye', 'diye', 'दिये', 'paise diye', 'पैसे दिए',
      'चुका दिया', 'chuka diya', 'chukaya', 'चुकाया',
      'जमा', 'jama', 'jama kiye', 'paid', 'payment', 'received',
      'बाकी चुकाया', 'baki chukaya', 'settle', 'hisaab kar diya',
      'wapas kar diye', 'लौटा दिए',
    ],
  },
  {
    intent: 'RETURN',
    weight: 1.0,
    phrases: [
      'वापस', 'wapas', 'wapis', 'return', 'returned', 'लौटा', 'lauta', 'lautaya', 'लौटाया',
      'वापस कर दिया', 'wapas kar diya', 'return kar diya', 'wapas le liya',
      'वापस लिया', 'wapas liya', 'give back', 'took back',
    ],
  },
  {
    intent: 'PRICE_UPDATE',
    weight: 1.0,
    phrases: [
      'रेट', 'rate', 'भाव', 'bhav', 'bhaav', 'price', 'दाम', 'daam',
      'रेट बदलो', 'rate badlo', 'rate change', 'price change', 'new rate',
      'रेट कर दो', 'rate kar do', 'bhav kar do',
    ],
  },
  {
    intent: 'PRICE_UPDATE',
    weight: 0.6,
    phrases: ['कर दो', 'kar do', 'kardo', 'set karo', 'बदल दो', 'badal do'],
  },
  {
    intent: 'WASTE',
    weight: 1.0,
    phrases: [
      'खराब', 'kharab', 'सड़ गया', 'sad gaya', 'sadd gaya', 'गल गया', 'gal gaya',
      'फेंका', 'phenka', 'phek diya', 'फेंक दिया', 'टूटा', 'toota', 'tuta',
      'वेस्ट', 'waste', 'spoiled', 'damaged', 'बर्बाद', 'barbaad', 'rotten',
    ],
  },
  {
    intent: 'EXPIRY_WRITEOFF',
    weight: 1.0,
    phrases: [
      'एक्सपायर', 'expire', 'expired', 'expiry',
      'तारीख निकल गई', 'tareekh nikal gayi', 'date nikal gayi', 'date khatam',
      'out of date', 'एक्सपायरी',
    ],
  },
  {
    intent: 'STOCK_IN',
    weight: 1.0,
    phrases: [
      'आया', 'aaya', 'आयी', 'aayi', 'आ गया', 'aa gaya', 'aagaya',
      'खरीदा', 'kharida', 'खरीद', 'kharid', 'लाया', 'laya', 'laaya',
      'माल आया', 'maal aaya', 'mal aaya', 'stock aaya', 'स्टॉक आया',
      'स्टॉक में', 'stock mein', 'purchase', 'bought', 'received stock',
      'भरा', 'bhara', 'मंगवाया', 'mangwaya', 'aa gaya hai',
    ],
  },
  {
    intent: 'CREDIT_SALE',
    weight: 1.0,
    phrases: [
      'उधार', 'udhaar', 'udhar', 'udhaari',
      'खाते में', 'khate mein', 'khaate mein', 'खाता', 'khata', 'khaata',
      'बही', 'bahi', 'credit', 'on credit', 'बाकी', 'baki', 'baaki',
      'लिख दो', 'likh do', 'likhdo', 'लिख देना', 'likh dena', 'note kar do',
    ],
  },
  {
    intent: 'VOID_LAST',
    weight: 1.0,
    phrases: [
      'गलत', 'galat', 'ग़लत', 'wrong', 'mistake',
      'कैंसिल', 'cancel', 'cancel karo', 'हटाओ', 'hatao', 'hata do',
      'मिटा दो', 'mita do', 'delete', 'remove', 'undo', 'रद्द', 'radd',
    ],
  },
  {
    intent: 'ACTION_COMMAND',
    weight: 1.0,
    phrases: [
      'भेजो', 'bhejo', 'bhej do', 'भेज दो', 'send', 'send karo',
      'व्हाट्सएप', 'whatsapp', 'whats app', 'वाट्सएप',
      'बिल भेजो', 'bill bhejo', 'send bill', 'बिल भेज दो',
      'कॉल', 'call', 'call karo', 'फ़ोन', 'phone karo', 'डायल', 'dial',
      'मैसेज', 'message', 'msg', 'sms',
      'याद दिलाओ', 'yaad dilao', 'remind', 'reminder', 'रिमाइंडर',
    ],
  },
]

/** Precomputed phone keys, so classification does not re-key the lexicon per utterance. */
const TRIGGER_KEYS: { trigger: IntentTrigger; keys: string[] }[] = TRIGGERS.map(t => ({
  trigger: t,
  keys: t.phrases.map(phoneticKey).filter(k => k.length > 0),
}))

const STRONG_QUESTION_KEYS: string[] = Array.from(
  new Set(
    TRIGGERS.filter(t => t.intent === 'READ_QUERY' && t.weight >= 1.0)
      .flatMap(t => t.phrases.map(phoneticKey))
      .filter(k => k.length > 0),
  ),
)

const RETURN_STRONG_KEYS = ['वापस', 'wapas', 'return', 'लौटा', 'lauta']
  .map(phoneticKey)
  .filter(k => k.length > 0)

const MONEY_PATTERN = /(₹|rs\.?|rupee|रुपये|रुपए)?\s*\d+(\.\d+)?/i

/**
 * Phone keys of every numeral and unit surface.
 *
 * ISSUE-120: "चार" (four) keys to CAL, which is EXACTLY the key of the ACTION_COMMAND
 * trigger "call" -- normalized distance 0.0000, quality 1.000. That scored ACTION_COMMAND
 * 1.0 against SALE's 0.9 baseline and sent three plain cash sales to review instead of
 * booking them (traces 54e7fe50, 8430fe59, 467ea9d5, all opening "चार किलो"). The bigram
 * "चारकिलो" collides with "call karo" at 0.125 independently.
 *
 * Threshold tuning cannot fix a 0.0000 distance, so quantity-only spans are excluded from
 * trigger matching outright.
 */
const QUANTITY_KEYS: Set<string> = new Set(
  [...Object.keys(HINDI_NUMBER_MAP), ...UNIT_SET]
    .map(w => phoneticKey(w))
    .filter(k => k.length > 0)
)

/**
 * Literal lowercased surfaces of every trigger word.
 *
 * The first version of this fix asserted "no trigger phrase is a number or a unit, so
 * filtering is safe by construction." That was WRONG and the fixture caught it: the
 * PRICE_UPDATE trigger `भाव`/`bhav` (rate) and the quantity word `पाव` (quarter) BOTH key to
 * `PAV`, so filtering by key alone deleted the `bhav` trigger and `"aaloo ka bhav 30"`
 * degraded from PRICE_UPDATE to SALE — turning a rate change into a fake one-unit sale.
 *
 * The asymmetry that resolves it: the trigger lexicon is the authority on what a trigger
 * word is. `bhav` is literally in the trigger list and is NOT literally a numeral surface,
 * so it is kept. `चार` is literally a numeral surface and is NOT literally a trigger word,
 * so it is still filtered and the original ISSUE-120 collision stays fixed.
 */
const TRIGGER_SURFACES: Set<string> = new Set(
  TRIGGERS.flatMap(t => t.phrases)
    .flatMap(p => p.toLowerCase().split(/\s+/))
    .filter(w => w.length > 0)
)

/** True when a word is a bare number, a number word, or a unit — and is not itself a trigger word. */
function isQuantityToken(word: string): boolean {
  const lower = word.toLowerCase().trim()
  if (lower.length === 0) return true
  // A literal trigger word is never a quantity, whatever it happens to key to.
  if (TRIGGER_SURFACES.has(lower)) return false
  if (!Number.isNaN(Number(lower))) return true
  const key = phoneticKey(lower)
  return key.length > 0 && QUANTITY_KEYS.has(key)
}

function buildNgramKeys(transcript: string): string[] {
  const words = transcript.split(/\s+/).filter(w => w.length > 0)
  const isQty = words.map(isQuantityToken)
  const keys = new Set<string>()
  for (let i = 0; i < words.length; i++) {
    if (!isQty[i]) {
      const uni = phoneticKey(words[i])
      if (uni) keys.add(uni)
    }
    if (i + 1 < words.length && !(isQty[i] && isQty[i + 1])) {
      const bi = phoneticKey(words[i] + words[i + 1])
      if (bi) keys.add(bi)
    }
    if (i + 2 < words.length && !(isQty[i] && isQty[i + 1] && isQty[i + 2])) {
      const tri = phoneticKey(words[i] + words[i + 1] + words[i + 2])
      if (tri) keys.add(tri)
    }
  }
  return Array.from(keys)
}

/** Best match quality in 0..1 for a trigger across every n-gram; 0 when nothing matched. */
function bestTriggerQuality(keys: string[], ngrams: string[]): number {
  let best = 0
  for (const key of keys) {
    for (const ng of ngrams) {
      const d = normalizedDistance(ng, key)
      if (d <= MAX_TRIGGER_DISTANCE) {
        const quality = 1 - d / MAX_TRIGGER_DISTANCE
        if (quality > best) best = quality
        if (best >= 1) return 1
      }
    }
  }
  return best
}

function usableItemCount(items: ParsedLineLike[]): number {
  return items.filter(
    it =>
      (it.quantity ?? 0) > 0 &&
      !!(it.item_name ?? '').trim() &&
      (it.item_name ?? '').trim() !== 'Unrecognized Item',
  ).length
}

function maxQuantity(items: ParsedLineLike[]): number {
  return items.reduce((m, it) => Math.max(m, it.quantity ?? 0), 0)
}

export function classifyIntent(
  transcript: string,
  parsedItems: ParsedLineLike[] = [],
): IntentClassification {
  const clean = (transcript ?? '').trim()
  const empty: IntentClassification = {
    intent: 'UNKNOWN',
    confidence: 0,
    isReadOnly: false,
    scores: {},
    runnerUp: null,
    needsArbitration: false,
  }
  if (!clean) return empty

  const ngrams = buildNgramKeys(clean)
  if (ngrams.length === 0) return empty

  const itemCount = usableItemCount(parsedItems)
  const hasItemLines = itemCount > 0
  const hasMoney = MONEY_PATTERN.test(clean)

  const scores: Record<string, number> = {}
  for (const { trigger, keys } of TRIGGER_KEYS) {
    const q = bestTriggerQuality(keys, ngrams)
    if (q > 0) scores[trigger.intent] = (scores[trigger.intent] ?? 0) + trigger.weight * q
  }

  // ---- structural gates (mirror of IntentRouter.applyStructuralGates) ----

  // A payment with an amount and no goods is decisive; suppress the opposite-sign credit read.
  if (scores['PAYMENT_RECEIVED'] !== undefined && hasMoney && !hasItemLines) {
    scores['PAYMENT_RECEIVED'] += 1.0
    delete scores['CREDIT_SALE']
  }

  // A rate update needs exactly one item, a price, and no bulk quantity.
  if (scores['PRICE_UPDATE'] !== undefined) {
    const singleItem = itemCount <= 1
    const qtyLooksLikeSale = maxQuantity(parsedItems) > 1
    if (!singleItem || qtyLooksLikeSale || !hasMoney) {
      delete scores['PRICE_UPDATE']
    } else {
      scores['PRICE_UPDATE'] += 0.8
      delete scores['SALE']
    }
  }

  // Question boost is PROPORTIONAL to match quality, not a flat bonus past a threshold: a flat
  // bonus has a cliff at the accept threshold, and "gaya" keys within exactly 0.25 of "kamaya",
  // which turned "galat ho gaya cancel karo" into a question.
  let strongQuestionQuality = 0
  for (const ng of ngrams) {
    for (const key of STRONG_QUESTION_KEYS) {
      const d = normalizedDistance(ng, key)
      if (d <= MAX_TRIGGER_DISTANCE) {
        const q = 1 - d / MAX_TRIGGER_DISTANCE
        if (q > strongQuestionQuality) strongQuestionQuality = q
      }
    }
  }
  if (scores['READ_QUERY'] !== undefined) {
    if (strongQuestionQuality > 0 && !hasItemLines) {
      scores['READ_QUERY'] += 1.5 * strongQuestionQuality
    }
    if (strongQuestionQuality <= 0) scores['READ_QUERY'] *= 0.5
  }

  // Expiry is a more specific waste.
  if (scores['EXPIRY_WRITEOFF'] !== undefined) delete scores['WASTE']

  // A void is a correction about a previous utterance, so it carries no goods of its own.
  if (scores['VOID_LAST'] !== undefined && hasItemLines) scores['VOID_LAST'] *= 0.4

  if (scores['ACTION_COMMAND'] !== undefined && !hasItemLines) scores['ACTION_COMMAND'] += 1.0

  // A purchase phrase plus goods should not read as a return without an explicit return word.
  if (scores['RETURN'] !== undefined && scores['STOCK_IN'] !== undefined) {
    const hasReturnWord = ngrams.some(ng =>
      RETURN_STRONG_KEYS.some(k => normalizedDistance(ng, k) <= MAX_TRIGGER_DISTANCE),
    )
    if (!hasReturnWord) delete scores['RETURN']
  }

  // Goods with no keyword at all is a plain cash sale -- the commonest utterance in the shop,
  // and it has no trigger word by nature.
  if (hasItemLines) scores['SALE'] = (scores['SALE'] ?? 0) + BASE_SALE_SCORE

  const ranked = Object.entries(scores).sort((a, b) => b[1] - a[1])
  if (ranked.length === 0) return empty

  const [topIntent, topScore] = ranked[0]
  const secondScore = ranked[1]?.[1] ?? 0
  const runnerUp = (ranked[1]?.[0] as AssistantIntent) ?? null

  const confidence =
    topScore + secondScore <= 0 ? 0 : Math.min(1, Math.max(0, topScore / (topScore + secondScore)))

  if (confidence < ARBITRATION_FLOOR) {
    return { intent: 'UNKNOWN', confidence, isReadOnly: false, scores, runnerUp, needsArbitration: false }
  }

  return {
    intent: topIntent as AssistantIntent,
    confidence,
    isReadOnly: topIntent === 'READ_QUERY',
    scores,
    runnerUp,
    needsArbitration: confidence < DIRECT_ROUTE_CONFIDENCE,
  }
}

/** Maps an intent onto the client's `CaptureIntent` name, for the shared job record. */
export function captureIntentFor(intent: AssistantIntent): string {
  switch (intent) {
    case 'SALE': return 'SALE'
    case 'CREDIT_SALE': return 'CREDIT_SALE'
    case 'STOCK_IN': return 'STOCK_IN'
    case 'WASTE': return 'WASTE'
    case 'EXPIRY_WRITEOFF': return 'EXPIRY_WRITEOFF'
    case 'RETURN': return 'RETURN'
    case 'PAYMENT_RECEIVED': return 'PAYMENT_RECEIVED'
    case 'PRICE_UPDATE': return 'PRICE_UPDATE'
    case 'VOID_LAST': return 'VOID_LAST'
    default: return 'ASSISTANT'
  }
}
