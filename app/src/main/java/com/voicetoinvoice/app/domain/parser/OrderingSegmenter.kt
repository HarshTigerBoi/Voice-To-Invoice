package com.voicetoinvoice.app.domain.parser

import com.voicetoinvoice.app.domain.lexicon.ItemLexicon
import kotlin.math.min

data class CandidateRank(
    val itemName: String,
    val distance: Double,
    val score: Double,
    val margin: Double? = null
)

enum class ResolutionKind { MATCH, AMBIGUOUS, UNKNOWN }

data class NumeralRejoin(
    val leftToken: String,
    val rightToken: String,
    val mergedSurface: String,
    val value: Double,
    val matchNorm: Double,
    val valueMargin: Double,
    val lowMargin: Boolean
)

data class RawItemSegment(
    val quantity: Double,
    val unit: String?,
    val itemTokens: List<String>,
    val rawSegmentText: String,
    val heardSegmentText: String = "",
    val isSanityFlagged: Boolean = false,
    val itemMatchNorm: Double? = null,
    val itemMargin: Double? = null,
    val top3Candidates: List<CandidateRank> = emptyList(),
    val resolutionKind: ResolutionKind = ResolutionKind.MATCH,
    val numeralRejoinLowMargin: Boolean = false
)

data class SegmentResult(
    val segments: List<RawItemSegment>,
    val carryoverQty: Double? = null,
    val numeralRejoins: List<NumeralRejoin> = emptyList()
)

private enum class TokenType { NUM, UNIT, ITEM }

private data class DecodedToken(
    val type: TokenType,
    val rawToken: String,
    val heardText: String = "",
    val numericValue: Double? = null,
    val canonicalUnit: String? = null,
    val suspect: Boolean = false,
    val matchNorm: Double? = null,
    val matchMargin: Double? = null,
    val top3Candidates: List<CandidateRank> = emptyList(),
    val fromSplit: Boolean = false,
    val isQualifier: Boolean = false
)

/** One typed piece a source token can decode to. A source token yields several of these
 *  when STT fused two or three words into it. */
private data class Emission(
    val type: TokenType,
    val cost: Double,
    val surface: String,
    val heardText: String,
    val numericValue: Double? = null,
    val canonicalUnit: String? = null,
    val suspect: Boolean = false,
    val matchNorm: Double? = null,
    val matchMargin: Double? = null,
    val top3Candidates: List<CandidateRank> = emptyList(),
    val fromSplit: Boolean = false,
    val isQualifier: Boolean = false
)

/** One complete reading of a single source token: either a whole-token reading
 *  (one emission) or a fused-token reading (two or three emissions). */
private data class Expansion(val emissions: List<Emission>, val emissionCost: Double)

internal data class VocabEntry(
    val key: String,
    val surface: String,
    val canonical: String,
    val numericValue: Double? = null,
    val canonicalUnit: String? = null
)

private data class VocabHit(
    val entry: VocabEntry,
    val normalized: Double,
    val margin: Double? = null,
    val top3: List<CandidateRank> = emptyList()
)

/**
 * Grammar-aware lattice decoder for shopkeeper voice orders.
 *
 * A shopkeeper utterance almost always follows [QUANTITY] [UNIT] [ITEM], repeated.
 * Two STT behaviours break naive per-token classification, and this decoder exists to
 * absorb both without letting either one corrupt a clean transcript:
 *
 *  - ELISION. STT clips the leading syllable of a unit word in connected speech
 *    ("एक किलो" -> "एकलो" -> heard as "एक लो"). A per-token classifier matches the
 *    orphan "लो" to the number "दो" (distance 1) rather than the unit "किलो"
 *    (distance 2+), inventing a second quantity. See ISSUE-019.
 *
 *  - FUSION. STT welds two or three words into one token ("चार किलो" -> "चरगलो",
 *    "teen kilo" -> "tinggal"). Splitting these greedily in a pre-pass is unsafe: the
 *    cheapest split of a fused token in isolation is frequently the wrong one, because
 *    the evidence that disambiguates it lives in the *neighbouring* tokens. "एकलो"
 *    reads equally well as "ek kilo" and "ek aaloo" on its own; only a following item
 *    token settles it. See ISSUE-020.
 *
 * So splits are not decided up front. Each source token contributes a set of competing
 * *expansions* (whole-token, 2-way, 3-way) into a lattice, and a Viterbi decode over
 * the whole utterance picks the combination that best satisfies the shopkeeper grammar.
 * A fused reading has to earn its place against the surrounding sequence, and a clean
 * transcript still decodes exactly as plain lookup would.
 */
private object GrammarLatticeDecoder {

    // Emission costs: lower = more confident. Exact matches always win; these constants
    // only apply to tokens that DON'T exactly match anything, so a clean, unambiguous
    // transcript decodes identically to plain lookup.
    private const val EXACT_COST = 0.0
    private const val ELISION_COST = 0.5   // token is the trailing remnant of a canonical unit word
    private const val ITEM_BASELINE_COST = 1.2   // unrecognized word, read as an item name
    private const val ITEM_MATCHED_BASE_COST = 0.2 // word phonetically resolves to a known item

    // Max average phonetic distance per phone for a whole-token fuzzy read.
    // Tuned down from an initial 0.34, which was loose enough to accept outright
    // nonsense: "एकलो" (IKALO) matched ग्यारह/11 (KIALA) at 0.300 and "ग्लोसोना"
    // (KLOSONA) matched लहसुन (LASON) at 0.286, so both fused tokens were swallowed
    // whole as the wrong word instead of being split. 0.25 rejects both while still
    // admitting the trailing-echo read "sebab" -> सेब (0.200).
    private const val WHOLE_TOKEN_MAX_NORM = 0.25
    // Split parts are shorter and therefore noisier, so they keep slightly more room
    // than a whole-token read — "tinggal" needs KAL -> किलो at exactly 0.25.
    private const val SPLIT_PART_MAX_NORM = 0.30
    // A split part must rest on at least this many phones of evidence.
    private const val MIN_SPLIT_PHONES = 2
    // Per-part surcharge on a split, so a fused reading never wins a coin-flip against
    // an equally-scoring whole-token reading. Deliberately small: at 0.35 it swamped
    // the emission scores entirely, letting a mediocre whole-token match (0.286) beat
    // a near-perfect split (0.125 + 0.000). The grammar transitions, not this penalty,
    // are what should decide between readings.
    private const val SPLIT_PENALTY = 0.10

    // A distance word read whole, as an item name. Priced well above a normal split so
    // any halfway-plausible [UNIT][ITEM] split of the same token wins — this exists only
    // so the token still reaches the review queue if no split matches at all.
    private const val DISTANCE_TOKEN_ITEM_COST = 2.5

    /**
     * Cost of finishing the utterance on [last].
     *
     * A shopkeeper does not say "five kilos" and stop — an order that decodes to a
     * quantity and unit with no item is a structurally incomplete reading, and should
     * lose to any reading that produces an item. Kept deliberately small: an exact
     * UNIT_SET match costs 0.0 and returns before any ITEM alternative is even offered,
     * so a trailing "चार किलो" (which carries over to the next item within this transcript) has no
     * competing path this could flip. Carryover is strictly intra-transcript and never crosses recordings.
     */
    private fun endCost(last: TokenType): Double = when (last) {
        TokenType.ITEM -> 0.0
        TokenType.NUM -> 0.6
        TokenType.UNIT -> 0.6
    }

    private fun transitionCost(prev: TokenType?, curr: TokenType): Double {
        if (prev == null) {
            return when (curr) {
                TokenType.NUM -> 0.0
                TokenType.ITEM -> 0.3
                TokenType.UNIT -> 1.0
            }
        }
        return when (prev) {
            TokenType.NUM -> when (curr) {
                TokenType.UNIT -> 0.0
                TokenType.ITEM -> 0.3
                TokenType.NUM -> 4.0
            }
            TokenType.UNIT -> when (curr) {
                TokenType.ITEM -> 0.0
                TokenType.NUM -> 2.0
                TokenType.UNIT -> 3.0
            }
            TokenType.ITEM -> when (curr) {
                TokenType.NUM -> 0.0
                TokenType.UNIT -> 1.5
                TokenType.ITEM -> 0.2
            }
        }
    }

    /** Best phonetic match for [fragment] in [vocab].
     *  @param allowElision accept a fragment that is the tail of a longer unit word.
     *  @param allowEcho accept trailing material the vocabulary entry doesn't account
     *   for ("sebab" -> "seb"). Whole-token catalog reads only: inside a split, every
     *   phone must be paid for by some part, or a fragment steals phones from its
     *   neighbour and the split silently misaligns. */
    private fun matchVocab(
        fragment: String,
        vocab: List<VocabEntry>,
        maxNorm: Double,
        allowElision: Boolean = false,
        allowEcho: Boolean = false,
        aliases: Map<String, String> = emptyMap()
    ): VocabHit? {
        if (fragment.isEmpty() || vocab.isEmpty()) return null

        if (aliases.containsKey(fragment)) {
            val canonicalName = aliases[fragment]!!
            val matchingEntry = vocab.find { it.surface.equals(canonicalName, ignoreCase = true) }
            if (matchingEntry != null) {
                return VocabHit(
                    entry = matchingEntry,
                    normalized = 0.0,
                    margin = 1.0,
                    top3 = listOf(CandidateRank(itemName = matchingEntry.surface, distance = 0.0, score = 0.0))
                )
            }
        }

        // Keyed by CANONICAL identity, not phonetic key: "अदरक" (ATALAK) and "Adrak" (ATLAK) are
        // one item with two keys, and keying by key made them two candidates one edit apart, which
        // read as an ambiguous match and capped a correct parse at 0.55. ISSUE-107.
        val candidateMap = mutableMapOf<String, VocabHit>()
        for (entry in vocab) {
            if (entry.key.isEmpty()) continue
            var cost = PhoneticKey.distance(fragment, entry.key)
            if (allowElision && entry.key.length > fragment.length && entry.key.endsWith(fragment)) {
                cost = min(cost, ELISION_COST)
            }
            if (allowEcho && fragment.length > entry.key.length && fragment.startsWith(entry.key)) {
                cost = min(cost, (fragment.length - entry.key.length) * 0.5)
            }
            val norm = cost / maxOf(fragment.length, entry.key.length).toDouble()
            val existing = candidateMap[entry.canonical]
            if (existing == null || norm < existing.normalized) {
                candidateMap[entry.canonical] = VocabHit(entry, norm)
            }
        }
        if (candidateMap.isEmpty()) return null
        val candidates = candidateMap.values.sortedBy { it.normalized }
        val best = candidates[0]
        if (best.normalized > maxNorm) return null
        val second = if (candidates.size > 1) candidates[1] else null
        val margin = second?.let { it.normalized - best.normalized }
        val top3 = candidates.take(3).map { c ->
            CandidateRank(
                itemName = c.entry.surface,
                distance = c.normalized,
                score = c.normalized,
                margin = margin
            )
        }
        return VocabHit(best.entry, best.normalized, margin, top3)
    }

    private fun wholeTokenExpansions(
        raw: String,
        vocab: SegmenterVocabulary,
        aliases: Map<String, String> = emptyMap()
    ): List<Expansion> {
        val lower = raw.lowercase()
        val out = mutableListOf<Expansion>()

        if (OrderingSegmenter.DISTANCE_UNIT_TOKENS.contains(lower)) {
            return listOf(
                Expansion(
                    listOf(Emission(TokenType.ITEM, DISTANCE_TOKEN_ITEM_COST, raw, heardText = raw, suspect = true)),
                    DISTANCE_TOKEN_ITEM_COST
                )
            )
        }

        OrderingSegmenter.HINDI_NUMBER_MAP[lower]?.let {
            out.add(Expansion(listOf(Emission(TokenType.NUM, EXACT_COST, raw, heardText = raw, numericValue = it)), EXACT_COST))
        }
        lower.toDoubleOrNull()?.let {
            out.add(Expansion(listOf(Emission(TokenType.NUM, EXACT_COST, raw, heardText = raw, numericValue = it)), EXACT_COST))
        }
        if (OrderingSegmenter.UNIT_SET.contains(lower)) {
            out.add(Expansion(listOf(Emission(TokenType.UNIT, EXACT_COST, raw, heardText = raw, canonicalUnit = lower)), EXACT_COST))
        }
        if (out.isNotEmpty()) {
            return out
        }

        val key = PhoneticKey.of(lower)
        if (key.isNotEmpty()) {
            matchVocab(key, vocab.numbers, WHOLE_TOKEN_MAX_NORM)?.let {
                out.add(Expansion(listOf(Emission(TokenType.NUM, it.normalized, raw, heardText = raw, numericValue = it.entry.numericValue, matchNorm = it.normalized)), it.normalized))
            }
            matchVocab(key, vocab.units, WHOLE_TOKEN_MAX_NORM, allowElision = true)?.let {
                out.add(Expansion(listOf(Emission(TokenType.UNIT, it.normalized, raw, heardText = raw, canonicalUnit = it.entry.canonicalUnit, matchNorm = it.normalized)), it.normalized))
            }
            matchVocab(key, vocab.qualifiers, WHOLE_TOKEN_MAX_NORM)?.let { q ->
                val cost = ITEM_MATCHED_BASE_COST + q.normalized
                out.add(
                    Expansion(
                        listOf(
                            Emission(
                                type = TokenType.ITEM,
                                cost = cost,
                                surface = raw,
                                heardText = raw,
                                matchNorm = q.normalized,
                                isQualifier = true
                            )
                        ),
                        cost
                    )
                )
            }
            matchVocab(key, vocab.items, WHOLE_TOKEN_MAX_NORM, allowEcho = true, aliases = aliases)?.let {
                val cost = ITEM_MATCHED_BASE_COST + it.normalized
                out.add(
                    Expansion(
                        listOf(
                            Emission(
                                type = TokenType.ITEM,
                                cost = cost,
                                surface = it.entry.surface,
                                heardText = raw,
                                matchNorm = it.normalized,
                                matchMargin = it.margin,
                                top3Candidates = it.top3
                            )
                        ),
                        cost
                    )
                )
            }
        }

        // ITEM is always available as a fallback reading — an unknown word is far more
        // likely to be an item the catalog hasn't seen than a mangled number. This is
        // what keeps a genuinely new item name intact instead of being rewritten into
        // the nearest catalog entry (the ISSUE-011 failure mode).
        out.add(Expansion(listOf(Emission(TokenType.ITEM, ITEM_BASELINE_COST, raw, heardText = raw)), ITEM_BASELINE_COST))
        return out
    }

    private fun splitExpansions(
        raw: String,
        vocab: SegmenterVocabulary,
        aliases: Map<String, String> = emptyMap()
    ): List<Expansion> {
        val key = PhoneticKey.of(raw.lowercase())
        if (key.length < MIN_SPLIT_PHONES * 2) return emptyList()
        val out = mutableListOf<Expansion>()

        fun emissionFor(type: TokenType, hit: VocabHit, cost: Double, heardText: String = raw): Emission = Emission(
            type = type,
            cost = cost,
            surface = hit.entry.surface,
            heardText = heardText,
            numericValue = hit.entry.numericValue,
            canonicalUnit = hit.entry.canonicalUnit,
            matchNorm = hit.normalized,
            matchMargin = if (type == TokenType.ITEM) hit.margin else null,
            top3Candidates = if (type == TokenType.ITEM) hit.top3 else emptyList(),
            fromSplit = true
        )

        // 2-way: [NUM][UNIT] ("चरगलो"), [UNIT][ITEM] ("ग्लोसोना"), [NUM][ITEM] ("एकलो").
        for (i in MIN_SPLIT_PHONES..key.length - MIN_SPLIT_PHONES) {
            val p1 = key.substring(0, i)
            val p2 = key.substring(i)

            matchVocab(p1, vocab.numbers, SPLIT_PART_MAX_NORM)?.let { n ->
                matchVocab(p2, vocab.units, SPLIT_PART_MAX_NORM, allowElision = true)?.let { u ->
                    val c = n.normalized + u.normalized + 2 * SPLIT_PENALTY
                    out.add(Expansion(listOf(emissionFor(TokenType.NUM, n, n.normalized), emissionFor(TokenType.UNIT, u, u.normalized)), c))
                }
                matchVocab(p2, vocab.items, SPLIT_PART_MAX_NORM, aliases = aliases)?.let { it2 ->
                    val c = n.normalized + it2.normalized + 2 * SPLIT_PENALTY
                    out.add(Expansion(listOf(emissionFor(TokenType.NUM, n, n.normalized), emissionFor(TokenType.ITEM, it2, it2.normalized)), c))
                }
            }
            matchVocab(p1, vocab.units, SPLIT_PART_MAX_NORM)?.let { u1 ->
                matchVocab(p2, vocab.items, SPLIT_PART_MAX_NORM, aliases = aliases)?.let { it2 ->
                    val c = u1.normalized + it2.normalized + 2 * SPLIT_PENALTY
                    out.add(Expansion(listOf(emissionFor(TokenType.UNIT, u1, u1.normalized), emissionFor(TokenType.ITEM, it2, it2.normalized)), c))
                }
            }
        }

        // 3-way: [NUM][UNIT][ITEM] — a whole order collapsed into one token.
        for (i in MIN_SPLIT_PHONES..key.length - 2 * MIN_SPLIT_PHONES) {
            for (j in i + MIN_SPLIT_PHONES..key.length - MIN_SPLIT_PHONES) {
                val n = matchVocab(key.substring(0, i), vocab.numbers, SPLIT_PART_MAX_NORM) ?: continue
                val u = matchVocab(key.substring(i, j), vocab.units, SPLIT_PART_MAX_NORM, allowElision = true) ?: continue
                val it3 = matchVocab(key.substring(j), vocab.items, SPLIT_PART_MAX_NORM) ?: continue
                val c = n.normalized + u.normalized + it3.normalized + 3 * SPLIT_PENALTY
                out.add(
                    Expansion(
                        listOf(
                            emissionFor(TokenType.NUM, n, n.normalized),
                            emissionFor(TokenType.UNIT, u, u.normalized),
                            emissionFor(TokenType.ITEM, it3, it3.normalized)
                        ), c
                    )
                )
            }
        }
        return out
    }

    /**
     * Viterbi over a token-expansion lattice.
     *
     * State is (source token index, type of the last emission produced so far), so an
     * expansion that emits several typed pieces pays the internal transitions between
     * them as well as the transition from the previous token's final type. Returns the
     * flattened winning emission sequence plus the ambiguity gap at the tightest
     * decision point (small gap = genuinely close call worth sanity-flagging).
     */
    fun decode(
        tokens: List<String>,
        vocab: SegmenterVocabulary,
        aliases: Map<String, String> = emptyMap()
    ): Pair<List<DecodedToken>, Double> {
        if (tokens.isEmpty()) return emptyList<DecodedToken>() to Double.MAX_VALUE

        val expansionsPerToken = tokens.map { raw ->
            val whole = wholeTokenExpansions(raw, vocab, aliases)
            val exactOnly = whole.size == 1 && whole[0].emissionCost == EXACT_COST
            // Don't even consider splitting a token that exactly matches a known word.
            val all = if (exactOnly) whole else whole + splitExpansions(raw, vocab, aliases)
            // Suspicion is a property of the SOURCE token, not of one reading of it.
            // "किलोमीटर" is a mis-decode however we choose to carve it up, so the split
            // readings have to inherit the flag too — otherwise the split wins the
            // lattice (as it should) and silently drops the warning with it.
            if (OrderingSegmenter.DISTANCE_UNIT_TOKENS.contains(raw.lowercase())) {
                all.map { exp ->
                    exp.copy(emissions = exp.emissions.map { it.copy(suspect = true) })
                }
            } else all
        }

        // dp[i][lastType] = cheapest cost of consuming tokens 0..i ending on lastType.
        val dp = Array(tokens.size) { mutableMapOf<TokenType, Double>() }
        val back = Array(tokens.size) { mutableMapOf<TokenType, Pair<TokenType?, Expansion>>() }
        var minGap = Double.MAX_VALUE

        for (i in tokens.indices) {
            val costsHere = mutableMapOf<TokenType, Double>()
            for (expansion in expansionsPerToken[i]) {
                // Internal cost of the expansion: its emissions plus the transitions between them.
                var internal = expansion.emissionCost
                for (k in 1 until expansion.emissions.size) {
                    internal += transitionCost(expansion.emissions[k - 1].type, expansion.emissions[k].type)
                }
                val firstType = expansion.emissions.first().type
                val lastType = expansion.emissions.last().type

                var best = Double.MAX_VALUE
                var bestPrev: TokenType? = null
                if (i == 0) {
                    best = internal + transitionCost(null, firstType)
                } else {
                    for ((prevType, prevCost) in dp[i - 1]) {
                        val total = prevCost + transitionCost(prevType, firstType) + internal
                        if (total < best) {
                            best = total
                            bestPrev = prevType
                        }
                    }
                }
                val existing = costsHere[lastType]
                if (existing == null || best < existing) {
                    costsHere[lastType] = best
                    back[i][lastType] = bestPrev to expansion
                }
            }
            dp[i] = costsHere

            val sorted = costsHere.values.sorted()
            if (sorted.size >= 2) minGap = min(minGap, sorted[1] - sorted[0])
        }

        // Backtrack, collecting each token's winning expansion. The final state is
        // picked on path cost PLUS the terminal penalty, so a decode that leaves the
        // utterance hanging on a quantity or unit has to actually be cheaper to win.
        val chosen = arrayOfNulls<Expansion>(tokens.size)
        var currentType = dp[tokens.size - 1].entries
            .minByOrNull { it.value + endCost(it.key) }?.key ?: TokenType.ITEM
        for (i in tokens.indices.reversed()) {
            val entry = back[i][currentType]
            if (entry == null) {
                chosen[i] = Expansion(listOf(Emission(TokenType.ITEM, ITEM_BASELINE_COST, tokens[i], heardText = tokens[i])), ITEM_BASELINE_COST)
                currentType = TokenType.ITEM
                continue
            }
            chosen[i] = entry.second
            currentType = entry.first ?: TokenType.ITEM
        }

        val decoded = mutableListOf<DecodedToken>()
        for (i in tokens.indices) {
            for (em in chosen[i]!!.emissions) {
                decoded.add(
                    DecodedToken(
                        type = em.type,
                        rawToken = em.surface,
                        heardText = em.heardText,
                        numericValue = em.numericValue,
                        canonicalUnit = em.canonicalUnit,
                        suspect = em.suspect,
                        matchNorm = em.matchNorm,
                        matchMargin = em.matchMargin,
                        top3Candidates = em.top3Candidates,
                        fromSplit = em.fromSplit,
                        isQualifier = em.isQualifier
                    )
                )
            }
        }
        return decoded to minGap
    }
}

/** Phonetically-indexed vocabulary the lattice decodes against. */
class SegmenterVocabulary(catalogNames: List<String> = emptyList()) {
    internal val numbers: List<VocabEntry> = OrderingSegmenter.HINDI_NUMBER_MAP
        .filterKeys { it.length >= 2 }
        .map { (word, value) -> VocabEntry(PhoneticKey.of(word), word, canonical = "num:$value", numericValue = value) }

    internal val units: List<VocabEntry> = OrderingSegmenter.UNIT_SET
        .map { unit -> VocabEntry(PhoneticKey.of(unit), unit, canonical = "unit:$unit", canonicalUnit = unit) }

    internal val qualifiers: List<VocabEntry> = ItemLexicon.ALL_QUALIFIER_SURFACES
        .map { q -> VocabEntry(PhoneticKey.of(q), q, canonical = "qual:${ItemLexicon.canonicalQualifierOf(q)}") }

    internal val items: List<VocabEntry> =
        (OrderingSegmenter.DEFAULT_ITEM_VOCAB + catalogNames)
            .filter { it.isNotBlank() }
            .distinct()
            .map { name -> VocabEntry(PhoneticKey.of(name), name, canonical = ItemLexicon.canonicalOf(name)) }
}

class OrderingSegmenter(catalogNames: List<String> = emptyList()) {

    companion object {
        const val EXACT_COST = 0.0
        const val WHOLE_TOKEN_MAX_NORM = 0.25
        const val SPLIT_PART_MAX_NORM = 0.30
        const val SPLIT_UNIT_TRUST_NORM = 0.15
        const val ITEM_MATCHED_BASE_COST = 0.20
        const val ITEM_BASELINE_COST = 1.20
        const val DISTANCE_TOKEN_ITEM_COST = 2.50
        const val SPLIT_PENALTY = 0.10
        const val ELISION_COST = 0.10
        const val MIN_SPLIT_PHONES = 2

        /** A runner-up within one consonant edit of the winner is ambiguous regardless of key
         *  length. Replaces TAU_MARGIN = 0.08, which was unreachable for keys <= 6 phones
         *  (margin granularity is ~0.5/keyLength) and therefore never fired. See ISSUE-103. */
        const val MIN_MARGIN_PHONE_EDITS = 1.0

        /** A runner-up 0.30 normalized away is a clear win regardless of key length. Short real
         *  products (घी -> KI, आम -> AN, 2 phones) can never satisfy margin*keyLen >= 1.0, which
         *  demands margin >= 0.5 — unreachable against a genuine neighbour like गोभी at 0.375. Without
         *  this, every 2-phone item in the catalog is permanently review-only. ISSUE-109. */
        const val CLEAR_WIN_ABS_MARGIN = 0.30

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
        const val MERGE_MAX_NORM = 0.22
        const val MERGE_MIN_VALUE_MARGIN = 0.10

        /** Hindi/Hinglish discourse particles, fillers and postpositions. Never a product in
         *  any shop, but short enough that the lossy phone key collides them with real catalog
         *  items -- "हाँ" and "आम" are both key "AN". See ISSUE-103. */
        val DISCOURSE_PARTICLES: Set<String> = setOf(
            "हाँ", "हां", "हा", "ना", "नहीं", "है", "हैं", "ये", "यह", "वो", "वह", "अच्छा", "ठीक", "ओके", "जी",
            "अरे", "बस", "और", "तो", "भी", "का", "की", "के", "में", "से", "पर", "अब", "क्या", "अम", "उम", "हम्म", "आँ",
            "haan", "han", "haa", "hai", "ye", "wo", "achha", "theek", "ji", "bas", "aur", "ok", "okay", "hmm", "umm"
        )

        val HINDI_NUMBER_MAP: Map<String, Double> = mapOf(
            "एक" to 1.0, "ek" to 1.0, "one" to 1.0, "1" to 1.0,
            "दो" to 2.0, "do" to 2.0, "two" to 2.0, "2" to 2.0,
            "तीन" to 3.0, "teen" to 3.0, "three" to 3.0, "3" to 3.0,
            "चार" to 4.0, "chaar" to 4.0, "four" to 4.0, "4" to 4.0,
            "पांच" to 5.0, "पाँच" to 5.0, "paanch" to 5.0, "five" to 5.0, "5" to 5.0,
            "छह" to 6.0, "छे" to 6.0, "chhah" to 6.0, "six" to 6.0, "6" to 6.0,
            "सात" to 7.0, "saat" to 7.0, "seven" to 7.0, "7" to 7.0,
            "आठ" to 8.0, "aath" to 8.0, "eight" to 8.0, "8" to 8.0,
            "नौ" to 9.0, "nau" to 9.0, "nine" to 9.0, "9" to 9.0,
            "दस" to 10.0, "das" to 10.0, "ten" to 10.0, "10" to 10.0,
            "ग्यारह" to 11.0, "gyarah" to 11.0, "11" to 11.0,
            "बारह" to 12.0, "baarah" to 12.0, "12" to 12.0,
            "तेरह" to 13.0, "terah" to 13.0, "13" to 13.0,
            "चौदह" to 14.0, "chaudah" to 14.0, "14" to 14.0,
            "पंद्रह" to 15.0, "pandrah" to 15.0, "15" to 15.0,
            "सोलह" to 16.0, "solah" to 16.0, "16" to 16.0,
            "सत्रह" to 17.0, "satrah" to 17.0, "17" to 17.0,
            "अठारह" to 18.0, "athaarah" to 18.0, "18" to 18.0,
            "उन्नीस" to 19.0, "unnees" to 19.0, "19" to 19.0,
            "बीस" to 20.0, "bees" to 20.0, "20" to 20.0,
            // 21-99. Hindi compound numerals are irregular single words, not composable from
            // tens + units, so every one of them has to be listed. Their absence was not
            // cosmetic: an unmapped numeral is not recognised as a quantity, so the segmenter
            // treated it as the ITEM and the catalog accumulated ₹0 rows named "सत्ताईस" (27),
            // "पंद्रह"-style leftovers and "अठारह के लोग". Anything 21-99 that was not an exact
            // ten simply could not be spoken as a quantity before this.
            "इक्कीस" to 21.0, "ikkees" to 21.0, "21" to 21.0,
            "बाईस" to 22.0, "baees" to 22.0, "22" to 22.0,
            "तेईस" to 23.0, "teees" to 23.0, "teis" to 23.0, "23" to 23.0,
            "चौबीस" to 24.0, "chaubees" to 24.0, "24" to 24.0,
            "पच्चीस" to 25.0, "pachchees" to 25.0, "pachees" to 25.0, "25" to 25.0,
            "छब्बीस" to 26.0, "chhabbees" to 26.0, "26" to 26.0,
            "सत्ताईस" to 27.0, "sattaees" to 27.0, "27" to 27.0,
            "अट्ठाईस" to 28.0, "atthaees" to 28.0, "28" to 28.0,
            "उनतीस" to 29.0, "untees" to 29.0, "29" to 29.0,
            "तीस" to 30.0, "tees" to 30.0, "30" to 30.0,
            "इकतीस" to 31.0, "ikattees" to 31.0, "31" to 31.0,
            "बत्तीस" to 32.0, "battees" to 32.0, "32" to 32.0,
            "तैंतीस" to 33.0, "taintees" to 33.0, "33" to 33.0,
            "चौंतीस" to 34.0, "chauntees" to 34.0, "34" to 34.0,
            "पैंतीस" to 35.0, "paintees" to 35.0, "35" to 35.0,
            "छत्तीस" to 36.0, "chhattees" to 36.0, "36" to 36.0,
            "सैंतीस" to 37.0, "saintees" to 37.0, "37" to 37.0,
            "अड़तीस" to 38.0, "adtees" to 38.0, "38" to 38.0,
            "उनतालीस" to 39.0, "untaalees" to 39.0, "39" to 39.0,
            "चालीस" to 40.0, "chalees" to 40.0, "40" to 40.0,
            "इकतालीस" to 41.0, "ikataalees" to 41.0, "41" to 41.0,
            "बयालीस" to 42.0, "bayaalees" to 42.0, "42" to 42.0,
            "तैंतालीस" to 43.0, "taintaalees" to 43.0, "43" to 43.0,
            "चवालीस" to 44.0, "chavaalees" to 44.0, "44" to 44.0,
            "पैंतालीस" to 45.0, "paintaalees" to 45.0, "45" to 45.0,
            "छियालीस" to 46.0, "chhiyaalees" to 46.0, "46" to 46.0,
            "सैंतालीस" to 47.0, "saintaalees" to 47.0, "47" to 47.0,
            "अड़तालीस" to 48.0, "adtaalees" to 48.0, "48" to 48.0,
            "उनचास" to 49.0, "unchaas" to 49.0, "49" to 49.0,
            "पचास" to 50.0, "pachaas" to 50.0, "pachas" to 50.0, "50" to 50.0,
            "इक्यावन" to 51.0, "ikyaavan" to 51.0, "51" to 51.0,
            "बावन" to 52.0, "baavan" to 52.0, "52" to 52.0,
            "तिरेपन" to 53.0, "tirepan" to 53.0, "53" to 53.0,
            "चौवन" to 54.0, "chauvan" to 54.0, "54" to 54.0,
            "पचपन" to 55.0, "pachpan" to 55.0, "55" to 55.0,
            "छप्पन" to 56.0, "chhappan" to 56.0, "56" to 56.0,
            "सत्तावन" to 57.0, "sattaavan" to 57.0, "57" to 57.0,
            "अट्ठावन" to 58.0, "atthaavan" to 58.0, "58" to 58.0,
            "उनसठ" to 59.0, "unsath" to 59.0, "59" to 59.0,
            "साठ" to 60.0, "saath" to 60.0, "60" to 60.0,
            "इकसठ" to 61.0, "ikasath" to 61.0, "61" to 61.0,
            "बासठ" to 62.0, "baasath" to 62.0, "62" to 62.0,
            "तिरेसठ" to 63.0, "tiresath" to 63.0, "63" to 63.0,
            "चौंसठ" to 64.0, "chaunsath" to 64.0, "64" to 64.0,
            "पैंसठ" to 65.0, "painsath" to 65.0, "65" to 65.0,
            "छियासठ" to 66.0, "chhiyaasath" to 66.0, "66" to 66.0,
            "सड़सठ" to 67.0, "sadsath" to 67.0, "67" to 67.0,
            "अड़सठ" to 68.0, "adsath" to 68.0, "68" to 68.0,
            "उनहत्तर" to 69.0, "unhattar" to 69.0, "69" to 69.0,
            "सत्तर" to 70.0, "sattar" to 70.0, "70" to 70.0,
            "इकहत्तर" to 71.0, "ikahattar" to 71.0, "71" to 71.0,
            "बहत्तर" to 72.0, "bahattar" to 72.0, "72" to 72.0,
            "तिहत्तर" to 73.0, "tihattar" to 73.0, "73" to 73.0,
            "चौहत्तर" to 74.0, "chauhattar" to 74.0, "74" to 74.0,
            "पचहत्तर" to 75.0, "pachhattar" to 75.0, "75" to 75.0,
            "छिहत्तर" to 76.0, "chhihattar" to 76.0, "76" to 76.0,
            "सतहत्तर" to 77.0, "satahattar" to 77.0, "77" to 77.0,
            "अठहत्तर" to 78.0, "athahattar" to 78.0, "78" to 78.0,
            "उन्यासी" to 79.0, "unyaasee" to 79.0, "79" to 79.0,
            "अस्सी" to 80.0, "assi" to 80.0, "80" to 80.0,
            "इक्यासी" to 81.0, "ikyaasee" to 81.0, "81" to 81.0,
            "बयासी" to 82.0, "bayaasee" to 82.0, "82" to 82.0,
            "तिरासी" to 83.0, "tiraasee" to 83.0, "83" to 83.0,
            "चौरासी" to 84.0, "chauraasee" to 84.0, "84" to 84.0,
            "पचासी" to 85.0, "pachaasee" to 85.0, "85" to 85.0,
            "छियासी" to 86.0, "chhiyaasee" to 86.0, "86" to 86.0,
            "सत्तासी" to 87.0, "sattaasee" to 87.0, "87" to 87.0,
            "अट्ठासी" to 88.0, "atthaasee" to 88.0, "88" to 88.0,
            "नवासी" to 89.0, "navaasee" to 89.0, "89" to 89.0,
            "नब्बे" to 90.0, "nabbe" to 90.0, "90" to 90.0,
            "इक्यानवे" to 91.0, "ikyaanave" to 91.0, "91" to 91.0,
            "बानवे" to 92.0, "baanave" to 92.0, "92" to 92.0,
            "तिरानवे" to 93.0, "tiraanave" to 93.0, "93" to 93.0,
            "चौरानवे" to 94.0, "chauraanave" to 94.0, "94" to 94.0,
            "पंचानवे" to 95.0, "panchaanave" to 95.0, "95" to 95.0,
            "छियानवे" to 96.0, "chhiyaanave" to 96.0, "96" to 96.0,
            "सत्तानवे" to 97.0, "sattaanave" to 97.0, "97" to 97.0,
            "अट्ठानवे" to 98.0, "atthaanave" to 98.0, "98" to 98.0,
            "निन्यानवे" to 99.0, "ninyaanave" to 99.0, "99" to 99.0,
            "सौ" to 100.0, "sau" to 100.0, "hundred" to 100.0, "100" to 100.0,
            "हजार" to 1000.0, "hazaar" to 1000.0, "thousand" to 1000.0, "1000" to 1000.0,
            // Indic Fractions & Modifiers
            "आधा" to 0.5, "आधी" to 0.5, "aadha" to 0.5, "aadhi" to 0.5, "half" to 0.5,
            "पाव" to 0.25, "पाओ" to 0.25, "pao" to 0.25, "paao" to 0.25,
            "सवा" to 1.25, "sawa" to 1.25,
            "डेढ़" to 1.5, "डेढ" to 1.5, "dedh" to 1.5,
            "ढाई" to 2.5, "dhai" to 2.5
        )

        val UNIT_SET: Set<String> = setOf(
            "kilo", "kilos", "kg", "kgs", "किलो", "किलोग्राम",
            "gram", "grams", "gm", "gms", "ग्राम", "g",
            "litre", "litres", "liter", "liters", "लीटर", "l",
            "ml", "एमएल",
            "packet", "packets", "pkt", "पैकेट",
            "piece", "pieces", "pcs", "नग",
            "dozen", "dozens", "दर्जन"
        )

        val DISTANCE_UNIT_TOKENS: Set<String> = setOf(
            "kilometer", "kilometers", "kilometre", "kilometres", "km", "kms",
            "किलोमीटर", "किलोमीटर्स",
            "meter", "meters", "metre", "metres", "मीटर",
            "centimeter", "centimetre", "cm", "सेंटीमीटर",
            "mile", "miles", "मील", "foot", "feet", "फुट", "फीट"
        )

        /** Derived from ItemLexicon — do not hand-edit. Add items in ItemLexicon.kt. */
        val DEFAULT_ITEM_VOCAB: List<String> = ItemLexicon.ALL_SURFACES

        private const val LOW_CONFIDENCE_GAP_THRESHOLD = 0.15

        fun rejoinFragmentedNumerals(
            tokens: List<String>,
            vocab: SegmenterVocabulary,
            itemSurfaceSet: Set<String>
        ): Pair<List<String>, List<NumeralRejoin>> {
            if (tokens.size < 2) return Pair(tokens, emptyList())
            val out = mutableListOf<String>()
            val rejoins = mutableListOf<NumeralRejoin>()

            var i = 0
            while (i < tokens.size) {
                if (i == tokens.size - 1) {
                    out.add(tokens[i])
                    i++
                    continue
                }

                val left = tokens[i]
                val right = tokens[i + 1]
                val leftLower = left.lowercase()

                if (
                    HINDI_NUMBER_MAP[leftLower] != null ||
                    leftLower.matches(Regex("^\\d+(\\.\\d+)?$")) ||
                    UNIT_SET.contains(leftLower) ||
                    DISTANCE_UNIT_TOKENS.contains(leftLower) ||
                    itemSurfaceSet.contains(leftLower)
                ) {
                    out.add(left)
                    i++
                    continue
                }

                val joinedKey = PhoneticKey.of(left + right)
                if (joinedKey.isEmpty()) {
                    out.add(left)
                    i++
                    continue
                }

                data class CandidateVal(val surface: String, val value: Double, val norm: Double)
                val bestPerValue = mutableMapOf<Double, CandidateVal>()

                for (entry in vocab.numbers) {
                    if (entry.key.isEmpty() || entry.numericValue == null) continue
                    val keyLen = maxOf(joinedKey.length, entry.key.length)
                    val norm = PhoneticKey.distance(joinedKey, entry.key).toDouble() / keyLen.toDouble()
                    val cur = bestPerValue[entry.numericValue]
                    if (cur == null || norm < cur.norm) {
                        bestPerValue[entry.numericValue] = CandidateVal(entry.surface, entry.numericValue, norm)
                    }
                }

                val ranked = bestPerValue.values.sortedBy { it.norm }
                if (ranked.isEmpty()) {
                    out.add(left)
                    i++
                    continue
                }

                val best = ranked[0]
                if (best.norm > MERGE_MAX_NORM) {
                    out.add(left)
                    i++
                    continue
                }

                val valueMargin = if (ranked.size > 1) ranked[1].norm - best.norm else 1.0

                out.add(best.surface)
                rejoins.add(
                    NumeralRejoin(
                        leftToken = left,
                        rightToken = right,
                        mergedSurface = best.surface,
                        value = best.value,
                        matchNorm = best.norm,
                        valueMargin = valueMargin,
                        lowMargin = valueMargin < MERGE_MIN_VALUE_MARGIN
                    )
                )
                i += 2
            }

            return Pair(out, rejoins)
        }
    }

    private val defaultVocabulary = SegmenterVocabulary(catalogNames)

    fun segmentTranscript(
        transcript: String,
        pendingCarryoverQty: Double? = null,
        catalogNames: List<String> = emptyList(),
        aliases: Map<String, String> = emptyMap()
    ): SegmentResult {
        val cleanText = transcript
            .replace("।", " ")
            .replace(Regex("[.,?!\\-\\\\(\\)]"), " ")
            .trim()

        if (cleanText.isBlank()) {
            return SegmentResult(emptyList(), pendingCarryoverQty, emptyList())
        }

        val vocabulary = if (catalogNames.isEmpty()) defaultVocabulary else SegmenterVocabulary(catalogNames)
        val rawTokens = cleanText.split(Regex("\\s+")).filter { it.isNotBlank() }
        val itemSurfaceSet = (DEFAULT_ITEM_VOCAB + catalogNames)
            .filter { it.isNotBlank() }
            .map { it.trim().lowercase() }
            .toSet()
        val tokens = rawTokens.filter { token ->
            val lower = token.lowercase()
            if (DISCOURSE_PARTICLES.contains(lower) || DISCOURSE_PARTICLES.contains(token)) {
                itemSurfaceSet.contains(lower)
            } else {
                true
            }
        }
        val (mergedTokens, rejoins) = rejoinFragmentedNumerals(tokens, vocabulary, itemSurfaceSet)
        val lowMarginSurfaces = rejoins.filter { it.lowMargin }.map { it.mergedSurface.lowercase() }.toSet()
        val (decoded, minGap) = GrammarLatticeDecoder.decode(mergedTokens, vocabulary, aliases)

        var currentQty: Double? = pendingCarryoverQty
        var currentUnit: String? = null
        var currentItemTokens = mutableListOf<String>()
        var currentSegmentTokens = mutableListOf<String>()
        var currentHeardTokens = mutableListOf<String>()
        var ambiguousDoubleQty = minGap < LOW_CONFIDENCE_GAP_THRESHOLD
        var suspectReading = false
        var worstItemNorm: Double? = null
        var bestItemMargin: Double? = null
        var worstNonItemNorm: Double? = null
        var anyFromSplit = false
        var segmentTop3 = mutableListOf<CandidateRank>()
        val segments = mutableListOf<RawItemSegment>()

        fun closeSegment() {
            if (currentItemTokens.isNotEmpty()) {
                val rawText = currentSegmentTokens.joinToString(" ").trim()
                val heardText = currentHeardTokens.joinToString(" ").trim()
                val nonItemUntrustworthy = anyFromSplit && worstNonItemNorm != null && worstNonItemNorm!! > SPLIT_UNIT_TRUST_NORM
                val itemKeyLength = if (currentItemTokens.isNotEmpty()) PhoneticKey.of(currentItemTokens.joinToString(" ")).length else 0
                val isAmbiguousByMargin = bestItemMargin != null && itemKeyLength > 0 &&
                    bestItemMargin!! < CLEAR_WIN_ABS_MARGIN &&
                    (bestItemMargin!! * itemKeyLength) < MIN_MARGIN_PHONE_EDITS
                val resKind = when {
                    worstItemNorm == null -> ResolutionKind.UNKNOWN
                    nonItemUntrustworthy || isAmbiguousByMargin -> ResolutionKind.AMBIGUOUS
                    else -> ResolutionKind.MATCH
                }
                val hasLowMarginRejoin = currentSegmentTokens.any { lowMarginSurfaces.contains(it.lowercase()) }
                segments.add(
                    RawItemSegment(
                        quantity = currentQty ?: 1.0,
                        unit = currentUnit,
                        itemTokens = currentItemTokens.toList(),
                        rawSegmentText = if (rawText.isNotBlank()) rawText else currentItemTokens.joinToString(" "),
                        heardSegmentText = if (heardText.isNotBlank()) heardText else currentHeardTokens.joinToString(" "),
                        isSanityFlagged = ambiguousDoubleQty || suspectReading || (resKind != ResolutionKind.MATCH),
                        itemMatchNorm = worstItemNorm,
                        itemMargin = bestItemMargin,
                        top3Candidates = segmentTop3.toList(),
                        resolutionKind = resKind,
                        numeralRejoinLowMargin = hasLowMarginRejoin
                    )
                )
            }
            currentQty = null
            currentUnit = null
            currentItemTokens = mutableListOf()
            currentSegmentTokens = mutableListOf()
            currentHeardTokens = mutableListOf()
            ambiguousDoubleQty = false
            suspectReading = false
            worstItemNorm = null
            bestItemMargin = null
            worstNonItemNorm = null
            anyFromSplit = false
            segmentTop3 = mutableListOf()
        }

        for (dt in decoded) {
            if (dt.suspect) suspectReading = true
            when (dt.type) {
                TokenType.NUM -> {
                    if (currentItemTokens.isNotEmpty()) {
                        closeSegment() // Trailing quantity -> belongs to NEXT item
                    } else if (currentQty != null) {
                        ambiguousDoubleQty = true // Two quantities in a row without item -> flag ambiguity
                    }
                    currentQty = dt.numericValue ?: 1.0
                    currentSegmentTokens.add(dt.rawToken)
                    currentHeardTokens.add(dt.heardText)
                    dt.matchNorm?.let { n ->
                        worstNonItemNorm = worstNonItemNorm?.let { maxOf(it, n) } ?: n
                    }
                    if (dt.fromSplit) anyFromSplit = true
                }
                TokenType.UNIT -> {
                    currentUnit = normalizeUnit(dt.canonicalUnit ?: dt.rawToken)
                    currentSegmentTokens.add(dt.rawToken)
                    currentHeardTokens.add(dt.heardText)
                    dt.matchNorm?.let { n ->
                        worstNonItemNorm = worstNonItemNorm?.let { maxOf(it, n) } ?: n
                    }
                    if (dt.fromSplit) anyFromSplit = true
                }
                TokenType.ITEM -> {
                    if (dt.isQualifier) {
                        // A brand or variety word is part of the item PHRASE but is not a competing product. Letting
                        // its match statistics into the ambiguity margin is what flagged "पाँच किलो आलू" as AMBIGUOUS
                        // (हरा/हरी sit 0.167 from आलू). ISSUE-109.
                    } else {
                        dt.matchNorm?.let { n ->
                            worstItemNorm = worstItemNorm?.let { maxOf(it, n) } ?: n
                        }
                        dt.matchMargin?.let { m ->
                            bestItemMargin = m
                        }
                        if (dt.top3Candidates.isNotEmpty()) {
                            segmentTop3 = dt.top3Candidates.toMutableList()
                        }
                    }
                    currentItemTokens.add(dt.rawToken)
                    currentSegmentTokens.add(dt.rawToken)
                    currentHeardTokens.add(dt.heardText)
                }
            }
        }

        val carryover = if (currentQty != null && currentItemTokens.isEmpty()) currentQty else null
        if (currentItemTokens.isNotEmpty()) {
            closeSegment()
        }

        return SegmentResult(segments, carryover, rejoins)
    }

    private fun normalizeUnit(unitStr: String): String {
        val lower = unitStr.lowercase()
        return when {
            lower.contains("kilo") || lower.contains("kg") || lower.contains("किलो") -> "KG"
            lower.contains("gram") || lower.contains("gm") || lower.contains("ग्राम") -> "GRAM"
            lower.contains("litre") || lower.contains("liter") || lower.contains("लीटर") -> "LITRE"
            lower.contains("ml") || lower.contains("एमएल") -> "ML"
            lower.contains("packet") || lower.contains("पैकेट") || lower.contains("pkt") -> "PACKET"
            lower.contains("dozen") || lower.contains("दर्जन") -> "DOZEN"
            lower.contains("piece") || lower.contains("pcs") || lower.contains("नग") -> "PIECE"
            else -> unitStr.uppercase()
        }
    }
}
