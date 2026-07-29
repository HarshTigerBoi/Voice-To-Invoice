import { phoneticKey, normalizedDistance, type RawItemSegment } from './phonetic.ts'
import type { PriceIntent } from './price_intent.ts'

/**
 * Normalized phonetic distance at or below which the deterministic segmenter's reading of
 * an item name overrides a conflicting AI name. At 0.08 this is effectively "the segmenter
 * matched a known vocabulary word exactly, or off by a single vowel".
 *
 * Kept deliberately tight: this rule overrules the arbitration stage, so it should only
 * fire on evidence that is stronger than a judgement call.
 */
export const SEGMENTER_OVERRIDE_MAX_NORM = 0.08

/**
 * Normalized phonetic distance at or BELOW which two names (segmenter vs AI, one usually
 * Devanagari and the other a Latin transliteration) are treated as the SAME word rather
 * than a disagreement. This must be strictly larger than SEGMENTER_OVERRIDE_MAX_NORM's
 * cousin comparisons, because devanagariToLatin's schwa/matra handling and a shopkeeper's
 * own Latin spelling convention do not always converge on an identical phoneticKey even
 * when both readings are correct.
 *
 * Measured directly against phonetic.ts (not guessed): "बैंगन" vs "Baingan" -- genuinely
 * the same word -- sits at normalized distance 0.083 purely from how the "ai" diphthong is
 * encoded differently by the two paths (matra ऐ -> 'e' vs literal Latin "ai"). Without this
 * threshold, resolveItemName's exact-key check flagged EVERY such pair as a disagreement,
 * a false positive discovered when a live replay of job 107cc435 (ISSUE-030) showed
 * "बैंगन"/"Baingan" -- an item that was correctly auto-confirming before this fix --
 * newly flagged as disputed. "अमचूर" vs the genuinely wrong "अंगूर"/"Angoor" sits at 0.250,
 * comfortably above this threshold, so real disagreements are still caught.
 */
export const NAME_AGREEMENT_MAX_NORM = 0.15

export interface ItemNameResolution {
  /** The name to actually use downstream. */
  name: string
  /** Non-null when the two stages disagreed — always attach this to implausibility_reason
   *  so the line reaches a human instead of being resolved silently. */
  disagreementReason: string | null
  usedSegmenterOverride: boolean
}

/**
 * Decides the item name when the step-4 AI and the deterministic segmenter disagree.
 *
 * The segmenter reads the transcript that WON the scoring pass (`chosenRaw`); the AI sees
 * all transcripts and can — and did — lift a word from one that lost. ISSUE-030: the
 * shopkeeper said "पाँच किलो अमचूर", Sarvam (adopted) transcribed it correctly, the
 * segmenter matched `अमचूर` at distance 0.0, Grok misheard `अंगूर`, and the AI returned
 * "Angoor". A correct transcript was corrupted by the stage that exists to improve it.
 *
 * Note this is NOT gated on "the AI's name failed to match the catalog". The dangerous
 * case is precisely when the AI's wrong word DOES resolve to a catalog row, because that
 * books the wrong product at a real price with high confidence and no flag at all. In
 * ISSUE-030's trace that was avoided only by luck — Angoor happened not to be stocked.
 */
export function resolveItemName(
  aiName: string,
  seg: Pick<RawItemSegment, 'itemTokens' | 'itemMatchNorm'> | null | undefined,
  maxNorm: number = SEGMENTER_OVERRIDE_MAX_NORM
): ItemNameResolution {
  const trimmedAi = (aiName || '').trim()
  const segName = seg ? (seg.itemTokens || []).join(' ').trim() : ''
  const segNorm = seg?.itemMatchNorm ?? null

  const segIsNearExact = segName.length > 0 && segNorm !== null && segNorm <= maxNorm
  const nameDistance = normalizedDistance(phoneticKey(segName), phoneticKey(trimmedAi))
  const namesDiffer = segIsNearExact && nameDistance > NAME_AGREEMENT_MAX_NORM

  if (!namesDiffer) {
    return { name: trimmedAi, disagreementReason: null, usedSegmenterOverride: false }
  }

  return {
    name: segName,
    disagreementReason:
      `STT engines disagreed: segmenter heard '${segName}' (exact match) on the adopted transcript, AI read '${trimmedAi}'`,
    usedSegmenterOverride: true,
  }
}

/**
 * Explains a line that cannot be booked because it has no price.
 *
 * A recognized item that simply isn't in the shop's catalog has no price, so it can never
 * clear the "no ₹0 sales" gate (ISSUE-006). Until ISSUE-030 such a line carried NO
 * implausibility_reason, so the review card rendered "Aam · 6 KG · ₹0" with no explanation
 * of what was wrong or what to do about it.
 *
 * This changes no commit decision — such lines already fail the committable gate on
 * `price_at_sale > 0 && total > 0` — it only makes them explicable to the shopkeeper.
 */
export function unpricedLineReason(
  itemName: string,
  isCatalogMatched: boolean,
  priceAtSale: number,
  total: number,
  intent: PriceIntent
): string | null {
  // A rate update has no total by design, and an ambiguous number was deliberately
  // zeroed — neither is an "unpriced item" in the sense the shopkeeper needs to fix.
  if (intent === 'RATE_UPDATE' || intent === 'AMBIGUOUS_UNTRUSTED') return null
  if (priceAtSale !== 0 && total !== 0) return null

  return isCatalogMatched
    ? `'${itemName}' has no price in your catalog — set a rate`
    : `'${itemName}' is not in your catalog yet — set a rate to book it`
}
