package com.voicetoinvoice.app.domain.parser

import kotlin.math.min

data class RawItemSegment(
    val quantity: Double,
    val unit: String?,
    val itemTokens: List<String>,
    val rawSegmentText: String,
    val isSanityFlagged: Boolean = false
)

data class SegmentResult(
    val segments: List<RawItemSegment>,
    val carryoverQty: Double? = null
)

private enum class TokenType { NUM, UNIT, ITEM }

private data class Candidate(
    val type: TokenType,
    val cost: Double,
    val numericValue: Double? = null,
    val canonicalUnit: String? = null
)

private data class DecodedToken(
    val type: TokenType,
    val rawToken: String,
    val numericValue: Double? = null,
    val canonicalUnit: String? = null
)

/**
 * Grammar-aware lattice decoder for shopkeeper voice orders.
 *
 * A shopkeeper utterance almost always follows [QUANTITY] [UNIT] [ITEM], repeated.
 * STT commonly clips the leading syllable of a unit word in fast connected speech
 * (e.g. "एक किलो" -> "एकलो" -> STT hears "एक लो"), and a naive per-token classifier
 * will match the orphaned fragment "लो" against the nearest NUMBER word ("दो", edit
 * distance 1) purely because that's textually closer than the full unit word "किलो"
 * (edit distance 2+). That greedy choice discards the real quantity and invents a
 * second one.
 *
 * Instead of classifying each token in isolation, this decoder scores every
 * plausible (NUM/UNIT/ITEM) reading of every token and runs a Viterbi decode over
 * the whole sequence using the shopkeeper grammar as a transition prior — so a
 * cheap "elided unit" reading of an ambiguous token can beat a technically-closer
 * but grammatically nonsensical "second number in a row" reading.
 */
private object GrammarLatticeDecoder {

    // Emission costs: lower = more confident. Exact matches always win; these
    // constants only apply to tokens that DON'T exactly match anything, so a
    // clean, unambiguous transcript decodes identically to plain lookup.
    private const val EXACT_COST = 0.0
    private const val ELISION_COST = 0.5 // token is the trailing remnant of a canonical unit word
    private const val FUZZY_COST = 1.0   // token is 1 edit away from a canonical number/unit word
    private const val ITEM_BASELINE_COST = 1.2

    // Transition costs encode the [NUM][UNIT][ITEM] grammar. NUM->NUM is
    // heavily penalized: shopkeepers essentially never say two bare numbers
    // back-to-back for a single item, so when it's the only path left it's
    // both correct to pick, cheap enough for closing a genuinely double-quantity
    // utterance, and expensive enough to lose to a plausible unit/item reading.
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

    private fun editDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        return dp[a.length][b.length]
    }

    private fun numCandidate(token: String): Candidate? {
        OrderingSegmenter.HINDI_NUMBER_MAP[token]?.let {
            return Candidate(TokenType.NUM, EXACT_COST, numericValue = it)
        }
        token.toDoubleOrNull()?.let {
            return Candidate(TokenType.NUM, EXACT_COST, numericValue = it)
        }
        if (token.length < 2) return null
        var bestDist = Int.MAX_VALUE
        var bestValue: Double? = null
        for ((word, value) in OrderingSegmenter.HINDI_NUMBER_MAP) {
            if (word.length < 2) continue
            val dist = editDistance(token, word)
            if (dist < bestDist) {
                bestDist = dist
                bestValue = value
            }
        }
        return if (bestDist == 1 && bestValue != null) {
            Candidate(TokenType.NUM, FUZZY_COST, numericValue = bestValue)
        } else null
    }

    private fun unitCandidate(token: String): Candidate? {
        if (OrderingSegmenter.UNIT_SET.contains(token)) {
            return Candidate(TokenType.UNIT, EXACT_COST, canonicalUnit = token)
        }
        if (token.length >= 2) {
            // Elision: STT dropped the leading syllable(s) of the unit word
            // (e.g. "किलो" -> "लो", "पैकेट" -> "केट").
            val elided = OrderingSegmenter.UNIT_SET.firstOrNull { it.length > token.length && it.endsWith(token) }
            if (elided != null) {
                return Candidate(TokenType.UNIT, ELISION_COST, canonicalUnit = elided)
            }
            var bestDist = Int.MAX_VALUE
            var bestUnit: String? = null
            for (unit in OrderingSegmenter.UNIT_SET) {
                val dist = editDistance(token, unit)
                if (dist < bestDist) {
                    bestDist = dist
                    bestUnit = unit
                }
            }
            if (bestDist == 1 && bestUnit != null) {
                return Candidate(TokenType.UNIT, FUZZY_COST, canonicalUnit = bestUnit)
            }
        }
        return null
    }

    /** Decodes the full token sequence via Viterbi, returning the winning type
     *  for each token plus the ambiguity gap at the single tightest decision
     *  point (small gap = a genuinely close call the caller should flag). */
    fun decode(tokens: List<String>): Pair<List<DecodedToken>, Double> {
        if (tokens.isEmpty()) return emptyList<DecodedToken>() to Double.MAX_VALUE

        val candidatesPerToken = tokens.map { raw ->
            val lower = raw.lowercase()
            val candidates = mutableListOf<Candidate>()
            numCandidate(lower)?.let { candidates.add(it) }
            unitCandidate(lower)?.let { candidates.add(it) }
            // An exact vocabulary match is unambiguous by definition — don't offer
            // an ITEM escape hatch that would let the decoder "cheat" a cheaper
            // global path by relabeling a literal number/unit word as a fake item
            // name just to dodge a transition penalty (e.g. avoiding UNIT->NUM by
            // pretending "किलो" is the item being purchased).
            val hasExactMatch = candidates.any { it.cost == EXACT_COST }
            if (!hasExactMatch) {
                candidates.add(Candidate(TokenType.ITEM, ITEM_BASELINE_COST))
            }
            candidates
        }

        // dp[i][type] = cheapest total cost of a path ending in `type` at token i
        val dp = Array(tokens.size) { mutableMapOf<TokenType, Double>() }
        val backPointer = Array(tokens.size) { mutableMapOf<TokenType, TokenType?>() }
        var minGap = Double.MAX_VALUE

        for (i in tokens.indices) {
            val costsHere = mutableMapOf<TokenType, Double>()
            for (cand in candidatesPerToken[i]) {
                var best = Double.MAX_VALUE
                var bestPrev: TokenType? = null
                if (i == 0) {
                    best = cand.cost + transitionCost(null, cand.type)
                    bestPrev = null
                } else {
                    for ((prevType, prevCost) in dp[i - 1]) {
                        val total = prevCost + transitionCost(prevType, cand.type) + cand.cost
                        if (total < best) {
                            best = total
                            bestPrev = prevType
                        }
                    }
                }
                // Keep the cheapest way to reach this (token, type) combination.
                val existing = costsHere[cand.type]
                if (existing == null || best < existing) {
                    costsHere[cand.type] = best
                    backPointer[i][cand.type] = bestPrev
                }
            }
            dp[i] = costsHere

            val sorted = costsHere.values.sorted()
            if (sorted.size >= 2) {
                minGap = min(minGap, sorted[1] - sorted[0])
            }
        }

        // Backtrack from the cheapest final state.
        val lastCosts = dp[tokens.size - 1]
        var currentType = lastCosts.entries.minByOrNull { it.value }?.key ?: TokenType.ITEM
        val typeSequence = arrayOfNulls<TokenType>(tokens.size)
        for (i in tokens.indices.reversed()) {
            typeSequence[i] = currentType
            currentType = backPointer[i][currentType] ?: TokenType.ITEM
        }

        val decoded = tokens.indices.map { i ->
            val type = typeSequence[i]!!
            val chosen = candidatesPerToken[i].firstOrNull { it.type == type }
            DecodedToken(
                type = type,
                rawToken = tokens[i],
                numericValue = chosen?.numericValue,
                canonicalUnit = chosen?.canonicalUnit
            )
        }
        return decoded to minGap
    }
}

class OrderingSegmenter {

    companion object {
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
            "तीस" to 30.0, "tees" to 30.0, "30" to 30.0,
            "चालीस" to 40.0, "chalees" to 40.0, "40" to 40.0,
            "पचास" to 50.0, "pachaas" to 50.0, "pachas" to 50.0, "50" to 50.0,
            "साठ" to 60.0, "saath" to 60.0, "60" to 60.0,
            "सत्तर" to 70.0, "sattar" to 70.0, "70" to 70.0,
            "अस्सी" to 80.0, "assi" to 80.0, "80" to 80.0,
            "नब्बे" to 90.0, "nabbe" to 90.0, "90" to 90.0,
            "सौ" to 100.0, "sau" to 100.0, "100" to 100.0,
            // Indic Fractions & Modifiers
            "आधा" to 0.5, "आधी" to 0.5, "aadha" to 0.5, "aadhi" to 0.5, "half" to 0.5,
            "पाव" to 0.25, "पाओ" to 0.25, "pao" to 0.25, "paao" to 0.25,
            "सवा" to 1.25, "sawa" to 1.25,
            "डेढ़" to 1.5, "डेढ" to 1.5, "dedh" to 1.5,
            "ढाई" to 2.5, "dhai" to 2.5
        )

        val UNIT_SET: Set<String> = setOf(
            "kilo", "kilos", "kg", "kgs", "किलो", "किलोग्राम", "kilometer", "किलोमीटर",
            "gram", "grams", "gm", "gms", "ग्राम", "g",
            "litre", "litres", "liter", "liters", "लीटर", "l",
            "ml", "एमएल",
            "packet", "packets", "pkt", "पैकेट",
            "piece", "pieces", "pcs", "नग",
            "dozen", "dozens", "दर्जन"
        )

        // Ambiguity gap below this threshold means the lattice decoder had a
        // genuinely close call somewhere in the utterance (e.g. a token almost
        // equally plausible as a unit or a number) — worth surfacing as a
        // sanity flag even when a structural double-quantity wasn't detected.
        private const val LOW_CONFIDENCE_GAP_THRESHOLD = 0.15
    }

    fun segmentTranscript(transcript: String, pendingCarryoverQty: Double? = null): SegmentResult {
        val cleanText = transcript
            .replace("।", " ")
            .replace(Regex("[.,?!\\-\\\\(\\)]"), " ")
            .trim()

        if (cleanText.isBlank()) {
            return SegmentResult(emptyList(), pendingCarryoverQty)
        }

        val tokens = cleanText.split(Regex("\\s+")).filter { it.isNotBlank() }
        val lowerTokens = tokens.map { it.lowercase() }
        val (decoded, ambiguityGap) = GrammarLatticeDecoder.decode(lowerTokens)
        // Recover original casing/text for item tokens & rawSegmentText while
        // keeping the decoder's type/number/unit decisions.
        val decodedWithOriginalText = decoded.mapIndexed { i, dt -> dt.copy(rawToken = tokens[i]) }

        var currentQty: Double? = pendingCarryoverQty
        var currentUnit: String? = null
        var currentItemTokens = mutableListOf<String>()
        var currentSegmentTokens = mutableListOf<String>()
        var ambiguousDoubleQty = ambiguityGap < LOW_CONFIDENCE_GAP_THRESHOLD
        val segments = mutableListOf<RawItemSegment>()

        fun closeSegment() {
            if (currentItemTokens.isNotEmpty()) {
                val rawText = currentSegmentTokens.joinToString(" ").trim()
                segments.add(
                    RawItemSegment(
                        quantity = currentQty ?: 1.0,
                        unit = currentUnit,
                        itemTokens = currentItemTokens.toList(),
                        rawSegmentText = if (rawText.isNotBlank()) rawText else currentItemTokens.joinToString(" "),
                        isSanityFlagged = ambiguousDoubleQty
                    )
                )
            }
            currentQty = null
            currentUnit = null
            currentItemTokens = mutableListOf()
            currentSegmentTokens = mutableListOf()
            ambiguousDoubleQty = false
        }

        for (dt in decodedWithOriginalText) {
            when (dt.type) {
                TokenType.NUM -> {
                    if (currentItemTokens.isNotEmpty()) {
                        closeSegment() // Trailing quantity -> belongs to NEXT item
                    } else if (currentQty != null) {
                        ambiguousDoubleQty = true // Two quantities in a row without item -> flag ambiguity
                    }
                    currentQty = dt.numericValue ?: 1.0
                    currentSegmentTokens.add(dt.rawToken)
                }
                TokenType.UNIT -> {
                    currentUnit = normalizeUnit(dt.canonicalUnit ?: dt.rawToken)
                    currentSegmentTokens.add(dt.rawToken)
                }
                TokenType.ITEM -> {
                    currentItemTokens.add(dt.rawToken)
                    currentSegmentTokens.add(dt.rawToken)
                }
            }
        }

        val carryover = if (currentQty != null && currentItemTokens.isEmpty()) currentQty else null
        if (currentItemTokens.isNotEmpty()) {
            closeSegment()
        }

        return SegmentResult(segments, carryover)
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
