import { phoneticKey, normalizedDistance, type RawItemSegment, UNIT_SET, HINDI_NUMBER_MAP, RUPEE_WORDS, DISCOURSE_PARTICLES } from './phonetic.ts'
import type { PriceIntent } from './price_intent.ts'

/**
 * Item sounds that remain once quantity, unit, price and pure discourse are accounted for.
 *
 * Mirrors segmentTranscript's own token filter (phonetic.ts:583) including its escape hatch:
 * a DISCOURSE_PARTICLES token is KEPT when it is a genuine item surface, so a shop that stocks
 * a particle-shaped name is unaffected. Verified over all 182 production transcripts: the 8 that
 * segment to nothing all reduce to "" here, and no legitimate transcript reaches this path.
 * See ISSUE-105.
 */
export function transcriptItemResidue(transcript: string, itemSurfaces: Set<string>): string {
  const clean = (transcript || '').replace(/।/g, ' ').replace(/[.,?!\-\\()]/g, ' ').trim()
  return clean.split(/\s+/).filter(Boolean).filter(t => {
    const lower = t.toLowerCase()
    if (itemSurfaces.has(lower)) return true          // a real product outranks every strip rule
    if (RUPEE_WORDS.has(lower)) return false
    if (UNIT_SET.some(u => u.toLowerCase() === lower)) return false
    if (HINDI_NUMBER_MAP[lower] !== undefined) return false
    if (/^\d+(\.\d+)?$/.test(lower)) return false
    if (DISCOURSE_PARTICLES.has(lower) || DISCOURSE_PARTICLES.has(t)) return false
    return true
  }).join(' ')
}

/**
 * At or below this many phones, nothing identifiable was said. Set at 2 because the shortest
 * real product key in DEFAULT_ITEM_VOCAB is 2 phones (आम -> AN, घी -> KI) — and such an item
 * would have produced a segment, so it never reaches this branch. See ISSUE-105.
 */
export const MAX_UNIDENTIFIABLE_RESIDUE_PHONES = 2

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

/**
 * Concept evidence for the segmenter-vs-AI arbitration. Supplied by the caller so this
 * module stays free of catalog and lexicon imports. See ISSUE-126.
 */
export interface ConceptArbitration {
  /** Base commodity of the segmenter's reading, or null when unknown. */
  segConcept: string | null
  /** Base commodity of the AI's reading, or null when unknown. */
  aiConcept: string | null
  /** True when the AI's name resolves to a real, priced SKU in this shop's catalog. */
  aiNameIsStocked: boolean
}

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
  maxNorm: number = SEGMENTER_OVERRIDE_MAX_NORM,
  concepts?: ConceptArbitration
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

  // ISSUE-126: the two names differ as STRINGS but may denote the SAME commodity, which is
  // the case phonetics cannot see -- phoneticKey('चावल') is CAVAL and phoneticKey('rice') is
  // LICI, so a translation pair always looks like a disagreement here.
  //
  // Job 735469d9: segmenter matched 'चावल' at 0.0 and overrode the AI's 'Basmati Rice'. Both
  // are concept 'rice', but only 'Basmati Rice' is a stocked, priced SKU, so the override
  // turned a bookable ₹90/KG line into an unpriced review-queue row.
  //
  // Gated on sameConcept AND aiNameIsStocked so ISSUE-030 is untouched: 'अमचूर' vs 'अंगूर'
  // are concepts 'amchur' and 'grapes', so they still disagree and the segmenter still wins,
  // exactly as before -- including when the AI's wrong word happens to be stocked.
  if (
    concepts &&
    concepts.segConcept !== null &&
    concepts.aiConcept !== null &&
    concepts.segConcept === concepts.aiConcept &&
    concepts.aiNameIsStocked
  ) {
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

/**
 * Minimum ratio of (phones actually heard) to (phones in the AI's proposed name).
 *
 * Below 1.0 the AI has ADDED phones that were not in the audio. Invention adds phones;
 * a genuine rescue of a fused or mangled token rearranges phones it already has
 * ("चरगलो"[CALAKALO] -> "Aaloo"[ALO] = 2.67). Set at 0.75 from a probe over the live
 * phonetic.ts: every invented pair observed scores <= 0.50, every genuine rescue >= 0.83,
 * and all 220 DEFAULT_ITEM_VOCAB words score 1.00 against themselves.
 *
 * Deliberately NOT a phonetic-distance test: "आ"->"Aaloo" and "चरगलो"->"Aaloo" both sit at
 * normalizedDistance 0.5000, so distance cannot separate invention from rescue. See ISSUE-104.
 */
export const MIN_AI_EVIDENCE_RATIO = 0.75

export interface AiEvidenceVerdict {
  uncorroborated: boolean
  ratio: number | null
  heardSurface: string
  heardPhones: number
  aiPhones: number
  source: 'segment' | 'transcript_residue' | 'none'
}

/**
 * True when the AI named a product that the audio does not support.
 *
 * Requires BOTH no deterministic evidence (resolutionKind UNKNOWN) AND an evidence ratio
 * below the floor. The ratio alone false-positives on "उड़द"->"Urad" (0.50, the nukta ड़
 * drops its r), which is spared because उड़द is in DEFAULT_ITEM_VOCAB and therefore resolves
 * MATCH, never UNKNOWN.
 *
 * NOTE on heardSurface: for a MATCH segment `itemTokens` holds the CANONICAL vocab name
 * (verified: job ad0f38b8 has itemTokens ["Aaloo"] with heardSegmentText "दस किलो आलू").
 * For an UNKNOWN segment it holds the RAW heard token — which is the only case this
 * function reads, so itemTokens is the correct source here.
 */
export function assessAiNameEvidence(
  aiName: string,
  seg: Pick<RawItemSegment, 'itemTokens' | 'resolutionKind'> | null | undefined,
  noSegmentContext?: { transcript: string; itemSurfaces: Set<string>; segmentCount: number }
): AiEvidenceVerdict {
  const aiPhones = phoneticKey((aiName || '').trim()).length

  // Path 1 (ISSUE-104): a segment exists but resolved to nothing recognisable.
  if (seg && seg.resolutionKind === 'UNKNOWN' && aiPhones > 0) {
    const heardSurface = (seg.itemTokens || []).join(' ').trim()
    const heardPhones = phoneticKey(heardSurface).length
    const ratio = heardPhones / aiPhones
    return {
      heardSurface, heardPhones, aiPhones, ratio,
      uncorroborated: ratio < MIN_AI_EVIDENCE_RATIO,
      source: 'segment',
    }
  }

  // Path 2 (ISSUE-105): the segmenter produced NOTHING. That is weaker evidence than UNKNOWN,
  // not absent evidence — so measure what the transcript actually carried. Scoped to
  // segmentCount === 0: when other segments DID resolve, the whole-transcript residue belongs
  // to those siblings and would wrongly clear this item.
  if (!seg && noSegmentContext && noSegmentContext.segmentCount === 0 && aiPhones > 0) {
    const heardSurface = transcriptItemResidue(noSegmentContext.transcript, noSegmentContext.itemSurfaces)
    const heardPhones = phoneticKey(heardSurface).length
    return {
      heardSurface, heardPhones, aiPhones,
      ratio: heardPhones / aiPhones,
      uncorroborated: heardPhones <= MAX_UNIDENTIFIABLE_RESIDUE_PHONES,
      source: 'transcript_residue',
    }
  }

  const heardSurface = seg ? (seg.itemTokens || []).join(' ').trim() : ''
  return {
    heardSurface,
    heardPhones: phoneticKey(heardSurface).length,
    aiPhones,
    ratio: null,
    uncorroborated: false,
    source: 'none',
  }
}


