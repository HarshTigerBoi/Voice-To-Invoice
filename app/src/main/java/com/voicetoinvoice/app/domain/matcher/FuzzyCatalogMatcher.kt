package com.voicetoinvoice.app.domain.matcher

import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.domain.lexicon.ItemLexicon
import com.voicetoinvoice.app.domain.parser.OrderingSegmenter

data class MatchResult(
    val item: CatalogItem,
    val confidence: Float,
    val matchedToken: String,
    val isAmbiguous: Boolean = false
)

class FuzzyCatalogMatcher {

    companion object {
        val BLACKLISTED_ITEM_NAMES = setOf(
            "kilometer", "kilometers", "किलोमीटर", "km",
            "kilo", "kilos", "किलो", "kg", "kgs",
            "gram", "grams", "ग्राम", "gm", "gms",
            "litre", "litres", "liter", "liters", "लीटर", "ml",
            "packet", "packets", "पैकेट", "pkt",
            "piece", "pieces", "नग", "pcs",
            "dozen", "dozens", "दर्जन",
            "paao", "pao", "पाव", "पाओ",
            "aadha", "aadhi", "आधा", "आधी",
            "sawa", "सवा", "dhai", "ढाई", "dedh", "डेढ़",
            "item", "items", "unit", "units"
        )

        fun isBlacklistedItemName(name: String): Boolean {
            val lower = name.lowercase().trim()
            if (lower.isEmpty()) return true
            return BLACKLISTED_ITEM_NAMES.contains(lower) ||
                    lower.startsWith("kilometer") || lower.startsWith("किलोमीटर") ||
                    lower == "kilo" || lower == "kg" || lower == "item" ||
                    isQuantityPhrase(lower)
        }

        /**
         * True when the "item name" is really a leftover quantity — a bare number word, or a
         * phrase that opens with one ("अठारह के लोग", "सत्रह की").
         *
         * The unit words above were denylisted one at a time as each STT misfire surfaced, and
         * number words were never covered: the live catalog had accumulated "पंद्रह" (15),
         * "सत्रह की" (17), "सत्ताईस" (27) and "अठारह के लोग" (18) as ₹0 items, each shown to the
         * shopkeeper as a real product in the quick-stepper. `AppDatabase.onOpen` purges a
         * hardcoded handful of these ('सत्तर', 'पचास') after the fact, which only ever removes
         * the specific misfires someone already reported.
         *
         * Reuses [OrderingSegmenter.HINDI_NUMBER_MAP] rather than restating the numerals, so a
         * numeral the parser learns to recognise is automatically one the catalog will refuse.
         * Digit-only names are rejected the same way. A real product name that merely *contains*
         * a number ("Amul Gold 500") is untouched — only the leading token is tested.
         */
        fun isQuantityPhrase(lowerName: String): Boolean {
            val firstToken = lowerName.split(Regex("\\s+")).firstOrNull()?.trim() ?: return false
            if (firstToken.isEmpty()) return false
            if (firstToken.all { it.isDigit() || it == '.' }) return true
            if (OrderingSegmenter.HINDI_NUMBER_MAP.containsKey(firstToken)) return true
            return OrderingSegmenter.DISCOURSE_PARTICLES.contains(firstToken)
        }
    }

    // Comprehensive Indic Produce & FMCG Brand Alias Dictionary derived from ItemLexicon
    private val indicAliasMap: Map<String, String> get() = ItemLexicon.surfaceMapForMatcher()

    fun findBestMatch(transcript: String, catalog: List<CatalogItem>): MatchResult? {
        val validCatalog = catalog.filter { !isBlacklistedItemName(it.name) }
        if (transcript.isBlank() || validCatalog.isEmpty()) return null

        val cleanTranscript = transcript
            .replace("।", " ")
            .replace(Regex("[.,?!\\-\\\\(\\)]"), " ")
            .trim()
            .lowercase()

        // 1. Direct Indic & Brand Alias Match
        for ((alias, canonicalName) in indicAliasMap) {
            if (cleanTranscript.contains(alias.lowercase())) {
                val matchedCatalogItem = validCatalog.find {
                    it.name.equals(canonicalName, ignoreCase = true) ||
                            normalizeIndicText(it.name).equals(normalizeIndicText(canonicalName), ignoreCase = true)
                }
                if (matchedCatalogItem != null) {
                    return MatchResult(matchedCatalogItem, 0.98f, alias, isAmbiguous = false)
                }
            }
        }

        // 2. Direct catalog item substring match
        val tokens = cleanTranscript.split(Regex("\\s+")).filter { !isBlacklistedItemName(it) }
        for (item in validCatalog) {
            val normalizedItemName = normalizeIndicText(item.name).lowercase()

            if (cleanTranscript.contains(normalizedItemName)) {
                return MatchResult(item, 0.95f, normalizedItemName, isAmbiguous = false)
            }

            for (token in tokens) {
                if (token.length < 2) continue
                if (normalizedItemName.contains(token) || token.contains(normalizedItemName)) {
                    return MatchResult(item, 0.85f, token, isAmbiguous = false)
                }
            }
        }

        // 3. Token-level Levenshtein similarity fallback with Top-2 Margin Guard (Good Day vs Good Knight)
        val candidateScores = mutableListOf<Pair<CatalogItem, Float>>()

        for (item in validCatalog) {
            val normalizedItemName = normalizeIndicText(item.name).lowercase()

            var itemMaxSim = 0f
            for (token in tokens) {
                if (token.length < 2) continue
                val distance = computeLevenshteinDistance(token, normalizedItemName)
                val maxLen = maxOf(token.length, normalizedItemName.length)
                val similarity = 1.0f - (distance.toFloat() / maxLen.toFloat())
                if (similarity > itemMaxSim) {
                    itemMaxSim = similarity
                }
            }
            if (itemMaxSim >= 0.55f) {
                candidateScores.add(Pair(item, itemMaxSim))
            }
        }

        candidateScores.sortByDescending { it.second }

        if (candidateScores.isEmpty()) return null

        val topMatch = candidateScores.first()

        // Check top-2 margin safety (e.g. Good Day vs Good Knight)
        var isAmbiguous = false
        var confidence = topMatch.second

        if (candidateScores.size >= 2) {
            val secondMatch = candidateScores[1]
            val margin = topMatch.second - secondMatch.second
            if (margin < 0.15f) { // Score gap is too narrow — flag ambiguity!
                isAmbiguous = true
                confidence = 0.40f // Lower confidence below 0.65 threshold
            }
        }

        return MatchResult(topMatch.first, confidence, cleanTranscript, isAmbiguous = isAmbiguous)
    }

    private fun normalizeIndicText(text: String): String {
        return text
            .lowercase()
            .replace("oo", "u")
            .replace("ee", "i")
            .replace("aa", "a")
            .replace("yaz", "pyaz")
            .replace("tmatar", "tamatar")
            .replace("alu", "aaloo")
            .replace("bindi", "bhindi")
            .trim()
    }

    private fun computeLevenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }

        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}
