import {
  HINDI_NUMBER_MAP,
  UNIT_SET,
  RUPEE_WORDS,
  parseHindiOrNumericValue,
  parseCompoundNumberSequence,
  type RawItemSegment,
} from './phonetic.ts'

// RUPEE_WORDS, parseHindiOrNumericValue, and parseCompoundNumberSequence now live in
// phonetic.ts (the segmenter needs them to recognize price runs during segmentation
// itself -- see ISSUE-029) and are re-exported here so existing imports elsewhere
// (index.ts, price_intent_test.ts) keep working unchanged.
export { RUPEE_WORDS, parseHindiOrNumericValue, parseCompoundNumberSequence }

export type PriceIntent = 'NONE' | 'RATE_UPDATE' | 'BULK_SALE_TOTAL' | 'AMBIGUOUS_UNTRUSTED'

export interface DeterministicPriceDetection {
  priceIntent: PriceIntent
  spokenPrice: number | null
  hasLeadingQty: boolean
  hasAmbiguousPriceNumber: boolean
}

/**
 * Server-side mirror of VoiceParser.kt's deterministic price intent classifier.
 * Analyzes raw transcript for Rupee words ("रुपये", "रुपए", "rs") and price numbers.
 */
export function detectPriceIntent(transcript: string): DeterministicPriceDetection {
  if (!transcript || !transcript.trim()) {
    return { priceIntent: 'NONE', spokenPrice: null, hasLeadingQty: false, hasAmbiguousPriceNumber: false }
  }

  const clean = transcript
    .replace(/।/g, ' ')
    .replace(/[.,?!\-\\()]/g, ' ')
    .toLowerCase()
    .trim()

  const tokens = clean.split(/\s+/).filter(t => t.length > 0)
  if (!tokens.length) {
    return { priceIntent: 'NONE', spokenPrice: null, hasLeadingQty: false, hasAmbiguousPriceNumber: false }
  }

  let rupeeWordIdx = -1
  for (let i = 0; i < tokens.length; i++) {
    if (RUPEE_WORDS.has(tokens[i])) {
      rupeeWordIdx = i
      break
    }
  }

  // Extract contiguous compound number before or after Rupee word
  let spokenPrice: number | null = null
  if (rupeeWordIdx !== -1) {
    const beforeTokens: string[] = []
    for (let i = rupeeWordIdx - 1; i >= 0; i--) {
      if (parseHindiOrNumericValue(tokens[i]) !== null) {
        beforeTokens.unshift(tokens[i])
      } else {
        break
      }
    }
    if (beforeTokens.length > 0) {
      spokenPrice = parseCompoundNumberSequence(beforeTokens)
    }

    if ((spokenPrice === null || spokenPrice === 0) && rupeeWordIdx < tokens.length - 1) {
      const afterTokens: string[] = []
      for (let i = rupeeWordIdx + 1; i < tokens.length; i++) {
        if (parseHindiOrNumericValue(tokens[i]) !== null) {
          afterTokens.push(tokens[i])
        } else {
          break
        }
      }
      if (afterTokens.length > 0) {
        spokenPrice = parseCompoundNumberSequence(afterTokens)
      }
    }
  }

  // Check if there is a leading quantity before the item name (token 0 is a number/unit)
  const firstTokenVal = parseHindiOrNumericValue(tokens[0])
  const firstTokenIsUnit = UNIT_SET.includes(tokens[0])
  const hasLeadingQty = (firstTokenVal !== null && firstTokenVal > 0) || firstTokenIsUnit

  // Check for ambiguous price number (a number >= 10 at the end without a Rupee word)
  let hasAmbiguousPriceNumber = false
  if (rupeeWordIdx === -1 && tokens.length >= 2) {
    const trailingTokens: string[] = []
    for (let i = tokens.length - 1; i >= 0; i--) {
      if (parseHindiOrNumericValue(tokens[i]) !== null) {
        trailingTokens.unshift(tokens[i])
      } else {
        break
      }
    }
    if (trailingTokens.length > 0) {
      const trailingVal = parseCompoundNumberSequence(trailingTokens)
      if (trailingVal !== null && trailingVal >= 10) {
        hasAmbiguousPriceNumber = true
      }
    }
  }

  let priceIntent: PriceIntent = 'NONE'
  if (hasAmbiguousPriceNumber) {
    priceIntent = 'AMBIGUOUS_UNTRUSTED'
  } else if (spokenPrice !== null && spokenPrice > 0 && !hasLeadingQty) {
    priceIntent = 'RATE_UPDATE'
  } else if (spokenPrice !== null && spokenPrice > 0 && hasLeadingQty) {
    priceIntent = 'BULK_SALE_TOTAL'
  }

  return { priceIntent, spokenPrice, hasLeadingQty, hasAmbiguousPriceNumber }
}

export interface SegmentPriceClassification {
  priceIntent: PriceIntent
  spokenPrice: number | null
}

/**
 * Per-item price classification, scoped to ONE segment instead of the whole
 * transcript. The segmenter (phonetic.ts) already attaches spokenPrice/
 * rupeeWordPresent/hasLeadingQty to each RawItemSegment while it walks the token
 * lattice, so this is a straight application of the RATE_UPDATE vs BULK_SALE_TOTAL
 * discriminator to that one item's own signal -- it cannot see, and therefore cannot
 * apply, another item's price.
 *
 * This is what fixes "आलू तीस रुपये किलो, दो किलो प्याज": Aaloo's segment carries
 * spokenPrice=30, hasLeadingQty=false -> RATE_UPDATE; Pyaz's segment carries no price
 * at all -> NONE, priced from its own catalog entry. The old detectPriceIntent(chosenRaw)
 * ran once on the whole string and applied its single answer to every item, silently
 * turning the Pyaz sale into a second Aaloo rate update. See ISSUE-029.
 */
export function classifySegmentPriceIntent(
  seg: Pick<RawItemSegment, 'spokenPrice' | 'hasLeadingQty'> | null | undefined
): SegmentPriceClassification {
  const spokenPrice = seg?.spokenPrice ?? null
  if (spokenPrice !== null && spokenPrice > 0) {
    return { priceIntent: seg?.hasLeadingQty ? 'BULK_SALE_TOTAL' : 'RATE_UPDATE', spokenPrice }
  }
  return { priceIntent: 'NONE', spokenPrice: null }
}

/**
 * Extracts all resolved compound numbers >= 10 from a raw transcript string.
 */
export function extractSpokenNumbers(transcript: string): number[] {
  if (!transcript) return []
  const clean = transcript.toLowerCase().replace(/।/g, ' ').replace(/[.,?!\-\\()]/g, ' ')
  const tokens = clean.split(/\s+/).filter(t => t.length > 0)
  const results: number[] = []

  let i = 0
  while (i < tokens.length) {
    if (parseHindiOrNumericValue(tokens[i]) !== null) {
      const seq: string[] = []
      while (i < tokens.length && parseHindiOrNumericValue(tokens[i]) !== null) {
        seq.push(tokens[i])
        i++
      }
      const val = parseCompoundNumberSequence(seq)
      if (val !== null && val >= 10) {
        results.push(val)
      }
    } else {
      i++
    }
  }

  return results
}

const MIN_PLAUSIBLE_SALE_VALUE = 5.0

/**
 * Domain sanity, independent of transcript confidence. A shopkeeper does not sell
 * 7 grams of anything — weight-in-grams is sold in 50/100/250/500 steps, and a
 * quantity of 7 there is a mis-heard unit (almost always KG heard as GRAM).
 *
 * Also checks numeric consistency: extracts compound numbers >= 10 from transcript and
 * verifies if any were dropped during parsing.
 *
 * Returns a human-readable reason, or null when the combination is plausible.
 */
export function implausibilityReason(unit: string, qty: number, total: number, rawTranscript: string = '', priceAtSale: number = 0): string | null {
  const u = (unit || '').toUpperCase()
  if (!(qty > 0)) return `quantity ${qty} is not positive`

  if (u === 'GRAM' || u === 'ML') {
    if (qty < 10) return `${qty} ${u} is below any real retail quantity (likely a mis-heard KG/LITRE)`
    if (qty > 5000) return `${qty} ${u} exceeds a plausible single sale`
  } else if (u === 'KG' || u === 'LITRE') {
    if (qty > 200) return `${qty} ${u} exceeds a plausible single sale`
  } else if (u === 'PIECE' || u === 'PACKET' || u === 'DOZEN') {
    if (qty > 500) return `${qty} ${u} exceeds a plausible single sale`
  }

  if (total > 0 && total < MIN_PLAUSIBLE_SALE_VALUE) {
    return `sale value ₹${total.toFixed(2)} is below the ₹${MIN_PLAUSIBLE_SALE_VALUE} auto-confirm floor`
  }

  // Numeric consistency guard: check if any compound number >= 10 in transcript was silently dropped
  if (rawTranscript) {
    const spokenNumbers = extractSpokenNumbers(rawTranscript)
    for (const val of spokenNumbers) {
      const matchesQty = Math.abs(qty - val) < 0.01
      const matchesPrice = Math.abs(priceAtSale - val) < 0.01
      const matchesTotal = Math.abs(total - val) < 0.01
      if (!matchesQty && !matchesPrice && !matchesTotal) {
        return `spoken compound number ${val} in '${rawTranscript}' was not reflected in qty (${qty}), price (${priceAtSale}), or total (${total})`
      }
    }
  }

  return null
}
