package com.voicetoinvoice.app.domain.matcher

import com.voicetoinvoice.app.data.local.entity.CatalogItem

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
            return BLACKLISTED_ITEM_NAMES.contains(lower) ||
                    lower.startsWith("kilometer") || lower.startsWith("किलोमीटर") ||
                    lower == "kilo" || lower == "kg" || lower == "item"
        }
    }

    // Comprehensive Indic Produce & FMCG Brand Alias Dictionary
    private val indicAliasMap: Map<String, String> = mapOf(
        // Vegetables & Produce
        "प्याज" to "Pyaz", "प्याज़" to "Pyaz", "pyaz" to "Pyaz", "pyaaz" to "Pyaz", "onion" to "Pyaz", "onions" to "Pyaz",
        "टमाटर" to "Tamatar", "tmatar" to "Tamatar", "tamatar" to "Tamatar", "tomato" to "Tamatar", "tomatoes" to "Tamatar",
        "आलू" to "Aaloo", "अलू" to "Aaloo", "alu" to "Aaloo", "aaloo" to "Aaloo", "aalu" to "Aaloo", "potato" to "Aaloo", "potatoes" to "Aaloo",
        "अदरक" to "Adrak", "adrak" to "Adrak", "ginger" to "Adrak",
        "भिंडी" to "Bhindi", "bhindi" to "Bhindi", "bindi" to "Bhindi", "okra" to "Bhindi", "ladyfinger" to "Bhindi",
        "धनिया" to "Dhaniya", "dhaniya" to "Dhaniya", "coriander" to "Dhaniya",
        "मिर्च" to "Mirch", "हरी मिर्च" to "Mirch", "mirch" to "Mirch", "chilli" to "Mirch", "chili" to "Mirch",
        "गोभी" to "Gobhi", "gobhi" to "Gobhi", "cauliflower" to "Gobhi", "cabbage" to "Gobhi",
        "बैंगन" to "Baingan", "baingan" to "Baingan", "brinjal" to "Baingan", "eggplant" to "Baingan",
        "गाजर" to "Gajar", "gajar" to "Gajar", "carrot" to "Gajar",
        "मटर" to "Matar", "matar" to "Matar", "peas" to "Matar",
        "खीरा" to "Kheera", "kheera" to "Kheera", "cucumber" to "Kheera",
        "पालक" to "Palak", "palak" to "Palak", "spinach" to "Palak",
        "लहसुन" to "Lahsun", "lahsun" to "Lahsun", "garlic" to "Lahsun",
        "ब्रोकली" to "Broccoli", "broccoli" to "Broccoli",
        "ड्रैगन फ्रूट" to "Dragon Fruit", "dragon fruit" to "Dragon Fruit", "dragonfruit" to "Dragon Fruit",

        // Dairy & Bakery
        "अमूल गोल्ड" to "Amul Gold Milk", "गोल्ड दूध" to "Amul Gold Milk", "गोल्ड" to "Amul Gold Milk", "gold milk" to "Amul Gold Milk", "amul gold" to "Amul Gold Milk", "gold" to "Amul Gold Milk",
        "अमूल ताज़ा" to "Amul Taaza Milk", "ताज़ा" to "Amul Taaza Milk", "taaza milk" to "Amul Taaza Milk", "amul taaza" to "Amul Taaza Milk", "taaza" to "Amul Taaza Milk",
        "सरस" to "Saras Milk", "सरस दूध" to "Saras Milk", "saras milk" to "Saras Milk", "saras" to "Saras Milk",
        "दही" to "Curd (Dahi)", "curd" to "Curd (Dahi)", "dahi" to "Curd (Dahi)",
        "छाछ" to "Chaas (Buttermilk)", "मट्ठा" to "Chaas (Buttermilk)", "chaas" to "Chaas (Buttermilk)", "buttermilk" to "Chaas (Buttermilk)",
        "पनीर" to "Paneer", "paneer" to "Paneer",
        "घी" to "Desi Ghee", "ghee" to "Desi Ghee", "desi ghee" to "Desi Ghee", "gi" to "Desi Ghee",
        "मक्खन" to "Butter", "बटर" to "Butter", "butter" to "Butter", "amul butter" to "Butter",
        "अंडे" to "Eggs", "अंडा" to "Eggs", "anda" to "Eggs", "egg" to "Eggs", "eggs" to "Eggs",

        // Biscuits & FMCG
        "गुड डे" to "Good Day Biscuit", "good day" to "Good Day Biscuit", "good day biscuit" to "Good Day Biscuit",
        "गुड नाइट" to "Good Knight Mosquito Coil", "गुड नाइट कॉइल" to "Good Knight Mosquito Coil", "good knight" to "Good Knight Mosquito Coil", "good knight coil" to "Good Knight Mosquito Coil",
        "इप्पी" to "Yippee Noodles", "इप्पी नूडल्स" to "Yippee Noodles", "yippee" to "Yippee Noodles", "yippee noodles" to "Yippee Noodles",
        "मैगी" to "Maggi", "मैगी नूडल्स" to "Maggi", "maggi" to "Maggi",
        "सर्फ" to "Surf Excel", "सर्फ एक्सेल" to "Surf Excel", "surf" to "Surf Excel", "surf excel" to "Surf Excel",
        "फॉर्च्यून" to "Fortune Refined Oil", "फॉर्च्यून तेल" to "Fortune Refined Oil", "fortune oil" to "Fortune Refined Oil",
        "आशीर्वाद" to "Atta (Aashirvaad)", "आशीर्वाद आटा" to "Atta (Aashirvaad)", "aashirvaad" to "Atta (Aashirvaad)", "aashirvaad atta" to "Atta (Aashirvaad)",
        "बर्बन" to "Bourbon Biscuit", "bourbon" to "Bourbon Biscuit",
        "पारले जी" to "Parle-G Biscuit", "parle-g" to "Parle-G Biscuit", "parleg" to "Parle-G Biscuit",
        "पॉन्ड्स" to "Ponds Cream", "पॉन्ड्स क्रीम" to "Ponds Cream", "ponds" to "Ponds Cream", "ponds cream" to "Ponds Cream",
        "चीनी" to "Sugar (Madhur)", "शक्कर" to "Sugar (Madhur)", "sugar" to "Sugar (Madhur)", "madhur sugar" to "Sugar (Madhur)"
    )

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
