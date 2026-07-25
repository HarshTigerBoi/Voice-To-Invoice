package com.voicetoinvoice.app.domain.parser

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
        var currentQty: Double? = pendingCarryoverQty
        var currentUnit: String? = null
        var currentItemTokens = mutableListOf<String>()
        var currentSegmentTokens = mutableListOf<String>()
        var collectingItemName = false
        var ambiguousDoubleQty = false
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

        for (token in tokens) {
            val lower = token.lowercase()
            val parsedNum = parseQuantityValue(lower)

            when {
                parsedNum != null -> {
                    if (collectingItemName) {
                        closeSegment() // Trailing quantity -> belongs to NEXT item
                    } else if (currentQty != null) {
                        ambiguousDoubleQty = true // Two quantities in a row without item -> flag ambiguity
                    }
                    currentQty = parsedNum
                    currentSegmentTokens.add(token)
                    collectingItemName = false
                }
                isUnitWord(lower) -> {
                    currentUnit = normalizeUnit(lower)
                    currentSegmentTokens.add(token)
                }
                else -> {
                    currentItemTokens.add(token)
                    currentSegmentTokens.add(token)
                    collectingItemName = true
                }
            }
        }

        val carryover = if (currentQty != null && currentItemTokens.isEmpty()) currentQty else null
        if (currentItemTokens.isNotEmpty()) {
            closeSegment()
        }

        return SegmentResult(segments, carryover)
    }

    private fun isQuantityWord(token: String): Boolean {
        return HINDI_NUMBER_MAP.containsKey(token.lowercase()) || token.toDoubleOrNull() != null
    }

    private fun parseQuantityValue(token: String): Double? {
        val lower = token.lowercase()
        return token.toDoubleOrNull() ?: HINDI_NUMBER_MAP[lower]
    }

    private fun isUnitWord(token: String): Boolean {
        val lower = token.lowercase()
        return UNIT_SET.contains(lower)
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
