package com.voicetoinvoice.app.domain.parser

import com.voicetoinvoice.app.data.local.entity.CatalogItem
import com.voicetoinvoice.app.domain.matcher.FuzzyCatalogMatcher
import java.util.Locale

enum class PriceIntent {
    NONE,                 // no price spoken; use existing catalog rate
    RATE_UPDATE,          // price + rupee-word, no quantity spoken
    BULK_SALE_TOTAL,      // price + rupee-word + quantity spoken
    AMBIGUOUS_UNTRUSTED   // price-looking number present, but no rupee-word adjacent to it
}

data class ParsedVoiceSale(
    val matchedItem: CatalogItem?,
    val rawTranscript: String,
    val quantity: Double,
    val unit: String,
    val estimatedTotal: Double,
    val confidence: Float,
    val priceOverridden: Boolean = false,
    val updatedUnitPrice: Double = 0.0,
    val isPendingPrice: Boolean = false,
    val isSanityFlagged: Boolean = false,
    val trailingQty: Double? = null,
    val trailingUnit: String? = null,
    val hasLeadingQty: Boolean = false,
    val priceIntent: PriceIntent = PriceIntent.NONE
)

class VoiceParser(private val matcher: FuzzyCatalogMatcher = FuzzyCatalogMatcher()) {

    private val rupeeWords: Set<String> = setOf(
        "rs", "rs.", "₹",
        "rupay", "rupaye", "rupaya", "rupaiya", "rupaiye",
        "rupee", "rupees",
        "रुपये", "रुपया", "रुपए", "रु", "रूपये", "रूपए"
    )

    // Comprehensive Hindi & Hinglish Number Words Mapping Table (0-100+)
    private val hindiNumberMap: Map<String, Double> = mapOf(
        "एक" to 1.0, "ek" to 1.0, "one" to 1.0,
        "दो" to 2.0, "do" to 2.0, "two" to 2.0,
        "तीन" to 3.0, "teen" to 3.0, "three" to 3.0,
        "चार" to 4.0, "chaar" to 4.0, "four" to 4.0,
        "पांच" to 5.0, "पाँच" to 5.0, "paanch" to 5.0, "five" to 5.0,
        "छह" to 6.0, "छे" to 6.0, "chhah" to 6.0, "six" to 6.0,
        "सात" to 7.0, "saat" to 7.0, "seven" to 7.0,
        "आठ" to 8.0, "aath" to 8.0, "eight" to 8.0,
        "नौ" to 9.0, "nau" to 9.0, "nine" to 9.0,
        "दस" to 10.0, "das" to 10.0, "ten" to 10.0,
        "ग्यारह" to 11.0, "gyarah" to 11.0,
        "बारह" to 12.0, "baarah" to 12.0,
        "तेरह" to 13.0, "terah" to 13.0,
        "चौदह" to 14.0, "chaudah" to 14.0,
        "पंद्रह" to 15.0, "pandrah" to 15.0,
        "सोलह" to 16.0, "solah" to 16.0,
        "सत्रह" to 17.0, "satrah" to 17.0,
        "अठारह" to 18.0, "athaarah" to 18.0,
        "उन्नीस" to 19.0, "unnees" to 19.0,
        "बीस" to 20.0, "bees" to 20.0,
        "तीस" to 30.0, "tees" to 30.0,
        "चालीस" to 40.0, "chalees" to 40.0,
        "पचास" to 50.0, "pachaas" to 50.0, "pachas" to 50.0,
        "साठ" to 60.0, "saath" to 60.0,
        "सत्तर" to 70.0, "sattar" to 70.0,
        "अस्सी" to 80.0, "assi" to 80.0,
        "नब्बे" to 90.0, "nabbe" to 90.0,
        "सौ" to 100.0, "sau" to 100.0, "hundred" to 100.0,
        "हजार" to 1000.0, "hazaar" to 1000.0, "thousand" to 1000.0
    )

    private val phonemeConfusionPairs: Map<Double, Double> = mapOf(
        70.0 to 17.0, 17.0 to 70.0,
        50.0 to 5.0, 5.0 to 50.0,
        30.0 to 3.0, 3.0 to 30.0,
        60.0 to 7.0, 7.0 to 60.0,
        80.0 to 8.0, 8.0 to 80.0,
        90.0 to 9.0, 9.0 to 90.0
    )

    fun parseUtterance(transcript: String, catalog: List<CatalogItem>): ParsedVoiceSale {
        val cleanTranscript = transcript
            .replace("।", " ")
            .replace(Regex("[.,?!\\-\\\\(\\)]"), " ")
            .trim()

        val matchResult = matcher.findBestMatch(cleanTranscript, catalog)
        val matchedItemFromCatalog = matchResult?.item

        val (rawQtyExtracted, rawUnit, spokenPrice, trailingQty, trailingUnit, cleanTranscriptWithoutTrailing, hasLeadingQty, hasAmbiguousPriceNumber) = extractQtyUnitAndPrice(cleanTranscript, matchedItemFromCatalog?.unitId ?: "PACKET", matchedItemFromCatalog?.name)

        var explicitUnit = normalizeUnit(rawUnit)
        if (explicitUnit == "PACKET" && matchedItemFromCatalog != null && matchedItemFromCatalog.unitId.isNotBlank()) {
            explicitUnit = matchedItemFromCatalog.unitId
        }

        val catalogPrice = matchedItemFromCatalog?.price ?: 0.0
        val extractedQty = disambiguateQuantityWithPriceSanity(rawQtyExtracted, catalogPrice, spokenPrice)

        val priceIntent: PriceIntent = when {
            hasAmbiguousPriceNumber -> PriceIntent.AMBIGUOUS_UNTRUSTED
            spokenPrice != null && spokenPrice > 0.0 && !hasLeadingQty -> PriceIntent.RATE_UPDATE
            spokenPrice != null && spokenPrice > 0.0 && hasLeadingQty -> PriceIntent.BULK_SALE_TOTAL
            else -> PriceIntent.NONE
        }

        var priceOverridden = false
        var updatedUnitPrice = targetItemPriceOrZero(matchedItemFromCatalog)
        var total = 0.0
        var isPendingPrice = false

        when (priceIntent) {
            PriceIntent.RATE_UPDATE -> {
                updatedUnitPrice = spokenPrice!!
                total = 0.0
                priceOverridden = true
                isPendingPrice = false
            }
            PriceIntent.BULK_SALE_TOTAL -> {
                total = spokenPrice!!
                priceOverridden = true
                updatedUnitPrice = if (extractedQty > 0.0) total / extractedQty else total
                isPendingPrice = false
            }
            PriceIntent.AMBIGUOUS_UNTRUSTED -> {
                total = 0.0
                priceOverridden = false
                updatedUnitPrice = 0.0
                isPendingPrice = true
            }
            PriceIntent.NONE -> {
                if (matchedItemFromCatalog != null && matchedItemFromCatalog.price > 0.0) {
                    total = calculateTotalForUnits(extractedQty, explicitUnit, matchedItemFromCatalog.price, matchedItemFromCatalog.unitId)
                    updatedUnitPrice = matchedItemFromCatalog.price
                } else {
                    isPendingPrice = true
                    total = 0.0
                    updatedUnitPrice = 0.0
                }
            }
        }

        val isSanityFlagged = (explicitUnit == "KG" && extractedQty > 45.0) ||
                (explicitUnit == "PACKET" && extractedQty > 50.0) ||
                (explicitUnit == "LITRE" && extractedQty > 30.0) ||
                ((priceIntent == PriceIntent.RATE_UPDATE || priceIntent == PriceIntent.BULK_SALE_TOTAL) &&
                        catalogPrice > 0.0 &&
                        Math.abs(updatedUnitPrice - catalogPrice) / catalogPrice > 0.5)

        val productName = matchedItemFromCatalog?.name ?: extractRawProductName(cleanTranscriptWithoutTrailing)

        val targetItem: CatalogItem? = if (matchedItemFromCatalog != null) {
            matchedItemFromCatalog
        } else if (!FuzzyCatalogMatcher.isBlacklistedItemName(productName) && productName.isNotBlank() && productName != "Item" && productName != "Unrecognized Item") {
            CatalogItem(
                name = capitalizeWords(productName),
                unitId = explicitUnit,
                price = if (priceIntent == PriceIntent.RATE_UPDATE || priceIntent == PriceIntent.BULK_SALE_TOTAL) updatedUnitPrice else 0.0
            )
        } else {
            null
        }

        return ParsedVoiceSale(
            matchedItem = targetItem?.copy(price = if (priceOverridden) updatedUnitPrice else targetItem.price),
            rawTranscript = cleanTranscriptWithoutTrailing,
            quantity = extractedQty,
            unit = explicitUnit,
            estimatedTotal = total,
            confidence = matchResult?.confidence ?: if (targetItem != null) 0.80f else 0.0f,
            priceOverridden = priceOverridden,
            updatedUnitPrice = updatedUnitPrice,
            isPendingPrice = isPendingPrice,
            isSanityFlagged = isSanityFlagged,
            trailingQty = trailingQty,
            trailingUnit = trailingUnit,
            hasLeadingQty = hasLeadingQty,
            priceIntent = priceIntent
        )
    }

    private fun targetItemPriceOrZero(item: CatalogItem?): Double = item?.price ?: 0.0

    private fun disambiguateQuantityWithPriceSanity(rawQty: Double, catalogPrice: Double, spokenPrice: Double?): Double {
        if (spokenPrice == null || spokenPrice <= 0.0 || catalogPrice <= 0.0) return rawQty

        val candidateAltQty = phonemeConfusionPairs[rawQty] ?: return rawQty
        val expectedQtyForRaw = spokenPrice / catalogPrice

        val rawDiff = Math.abs(rawQty - expectedQtyForRaw)
        val altDiff = Math.abs(candidateAltQty - expectedQtyForRaw)

        return if (altDiff < rawDiff && altDiff <= (expectedQtyForRaw * 0.35)) {
            candidateAltQty
        } else {
            rawQty
        }
    }

    private fun normalizeUnit(unitStr: String): String {
        val upper = unitStr.uppercase().trim()
        return when {
            upper.contains("KILOMETER") || upper.contains("KM") || upper.contains("किलोमीटर") || upper.contains("KILO") || upper.contains("KG") -> "KG"
            upper.contains("GRAM") || upper.contains("GM") || upper.contains("ग्राम") || upper == "G" -> "GRAM"
            upper.contains("LITRE") || upper.contains("LITER") || upper.contains("लीटर") -> "LITRE"
            upper.contains("ML") || upper.contains("एमएल") -> "ML"
            upper.contains("PACKET") || upper.contains("पैकेट") || upper.contains("PKT") -> "PACKET"
            upper.contains("DOZEN") || upper.contains("दर्जन") -> "DOZEN"
            upper.contains("METER") || upper.contains("मीटर") || upper.contains("GAZ") || upper.contains("गज") -> "METER"
            else -> upper
        }
    }

    private fun extractRawProductName(cleanTranscript: String): String {
        val nameWords = cleanTranscript.split(Regex("\\s+")).filter { token ->
            val lower = token.lowercase()
            token.toDoubleOrNull() == null &&
                    !hindiNumberMap.containsKey(lower) &&
                    !rupeeWords.contains(lower) &&
                    !lower.contains("kilometer") && !lower.contains("किलोमीटर") &&
                    !lower.contains("kg") && !lower.contains("kilo") && !lower.contains("किलो") &&
                    !lower.contains("gram") && !lower.contains("ग्राम") && !lower.contains("gm") &&
                    !lower.contains("litre") && !lower.contains("liter") && !lower.contains("लीटर") && !lower.contains("ml") &&
                    !lower.contains("packet") && !lower.contains("पैकेट") && !lower.contains("pkt") &&
                    !lower.contains("dozen") && !lower.contains("दर्जन") &&
                    !lower.contains("paao") && !lower.contains("पाव") && !lower.contains("paao") && !lower.contains("पाओ") &&
                    !lower.contains("aadha") && !lower.contains("aadhi") && !lower.contains("aadi") && !lower.contains("adi") &&
                    !lower.contains("आधा") && !lower.contains("आधी") && !lower.contains("आदि") && !lower.contains("अदि") &&
                    !lower.contains("adha") && !lower.contains("adhi") && !lower.contains("अधा") && !lower.contains("अधी") &&
                    !lower.contains("half") && !lower.contains("हाफ") &&
                    !lower.contains("sawa") && !lower.contains("सवा") &&
                    !lower.contains("dhai") && !lower.contains("ढाई") &&
                    !lower.contains("dedh") && !lower.contains("डेढ़") && !lower.contains("डेढ")
        }

        val result = nameWords.joinToString(" ").trim()
        val finalClean = result.split(Regex("\\s+")).filter { token ->
            val lower = token.lowercase()
            !lower.contains("kilometer") && !lower.contains("किलोमीटर") && !lower.contains("kilo") && !lower.contains("kg") && !lower.contains("किलो")
        }.joinToString(" ").trim()

        return if (finalClean.isNotBlank() && !FuzzyCatalogMatcher.isBlacklistedItemName(finalClean)) finalClean else "Unrecognized Item"
    }

    private fun capitalizeWords(str: String): String {
        return str.split(" ").joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
    }

    private fun calculateTotalForUnits(qty: Double, unit: String, unitPrice: Double, defaultCatalogUnit: String): Double {
        val weightBase = mapOf("GRAM" to 1.0, "KG" to 1000.0)
        val volumeBase = mapOf("ML" to 1.0, "LITRE" to 1000.0)
        val s = unit.uppercase()
        val c = defaultCatalogUnit.uppercase()
        val factor = when {
            s == c -> 1.0
            weightBase.containsKey(s) && weightBase.containsKey(c) -> weightBase.getValue(s) / weightBase.getValue(c)
            volumeBase.containsKey(s) && volumeBase.containsKey(c) -> volumeBase.getValue(s) / volumeBase.getValue(c)
            else -> 1.0
        }
        return qty * factor * unitPrice
    }

    private fun extractQtyUnitAndPrice(
        cleanTranscript: String,
        defaultCatalogUnit: String,
        itemName: String? = null
    ): ExtractedQtyTuple {
        val lower = cleanTranscript.lowercase()
        val tokens = lower.split(Regex("\\s+"))

        var activeUnit = defaultCatalogUnit
        var unitMultiplier = 1.0
        var hasExplicitModifier = false

        // 1. Multiplier Detection
        if (lower.contains("pao") || lower.contains("पाव") || lower.contains("paao") || lower.contains("पाओ")) {
            unitMultiplier = 0.25
            hasExplicitModifier = true
        } else if (lower.contains("aadha") || lower.contains("aadhi") || lower.contains("aadi") || lower.contains("adi") ||
            lower.contains("आधा") || lower.contains("आधी") || lower.contains("आदि") || lower.contains("अदि") ||
            lower.contains("adha") || lower.contains("adhi") || lower.contains("अधा") || lower.contains("अधी") ||
            lower.contains("half") || lower.contains("हाफ")) {
            unitMultiplier = 0.5
            hasExplicitModifier = true
        } else if (lower.contains("sawa") || lower.contains("सवा")) {
            unitMultiplier = 1.25
            hasExplicitModifier = true
        } else if (lower.contains("dhai") || lower.contains("ढाई")) {
            unitMultiplier = 2.5
            hasExplicitModifier = true
        } else if (lower.contains("dedh") || lower.contains("डेढ़") || lower.contains("डेढ")) {
            unitMultiplier = 1.5
            hasExplicitModifier = true
        }

        // 2. Unit Detection
        if (lower.contains("kg") || lower.contains("kilo") || lower.contains("किलो") || lower.contains("kilometer") || lower.contains("किलोमीटर")) {
            activeUnit = "KG"
            hasExplicitModifier = true
        } else if (lower.contains("gram") || lower.contains("ग्राम") || lower.contains("gm") || tokens.contains("g")) {
            activeUnit = "GRAM"
        } else if (lower.contains("litre") || lower.contains("liter") || lower.contains("लीटर")) {
            activeUnit = "LITRE"
        } else if (lower.contains("ml") || lower.contains("एमएल")) {
            activeUnit = "ML"
        } else if (lower.contains("packet") || lower.contains("पैकेट") || lower.contains("pkt")) {
            activeUnit = "PACKET"
        } else if (lower.contains("dozen") || lower.contains("दर्जन")) {
            activeUnit = "DOZEN"
        } else if (hasExplicitModifier) {
            activeUnit = if (defaultCatalogUnit == "LITRE" || defaultCatalogUnit == "ML") "LITRE" else "KG"
        }

        // Extract Spoken Price with Rupee-Word Gate
        var spokenPrice: Double? = null
        var spokenPriceIndex = -1
        var rupeeWordIndex = -1
        var hasAmbiguousPriceNumber = false

        val confirmedCandidates = mutableListOf<Triple<Int, Double, Int>>() // <priceIndex, priceValue, rupeeWordIndex>

        for (i in tokens.indices) {
            val token = tokens[i]
            val num = token.toDoubleOrNull() ?: hindiNumberMap[token]
            if (num != null && num > 0.0) {
                val prevToken = tokens.getOrNull(i - 1)?.lowercase()
                val nextToken = tokens.getOrNull(i + 1)?.lowercase()
                val prevMatch = prevToken != null && rupeeWords.contains(prevToken)
                val nextMatch = nextToken != null && rupeeWords.contains(nextToken)

                if (prevMatch || nextMatch) {
                    val rIdx = if (prevMatch) i - 1 else i + 1
                    confirmedCandidates.add(Triple(i, num, rIdx))
                }
            }
        }

        if (confirmedCandidates.isNotEmpty()) {
            val lastCandidate = confirmedCandidates.last()
            spokenPriceIndex = lastCandidate.first
            spokenPrice = lastCandidate.second
            rupeeWordIndex = lastCandidate.third
        } else if (tokens.size >= 2) {
            val lastIdx = tokens.size - 1
            val lastToken = tokens[lastIdx]
            val parsedLastNum = lastToken.toDoubleOrNull() ?: hindiNumberMap[lastToken]
            if (parsedLastNum != null && parsedLastNum > 0.0) {
                val firstToken = tokens.first()
                val parsedFirstNum = firstToken.toDoubleOrNull() ?: hindiNumberMap[firstToken]
                if (parsedFirstNum != null && tokens.size >= 3) {
                    hasAmbiguousPriceNumber = true
                } else if (tokens.size >= 2 && parsedLastNum >= 10.0) {
                    hasAmbiguousPriceNumber = true
                }
            }
        }

        // Find token index of Item Name if matched
        var itemTokenIndex = -1
        if (itemName != null) {
            val itemLower = itemName.lowercase()
            for (i in tokens.indices) {
                if (itemLower.contains(tokens[i])) {
                    itemTokenIndex = i
                    break
                }
            }
        }

        // Separate Leading vs Trailing Quantities (excluding spoken price & rupee-word tokens)
        var leadingQty: Double? = null
        var trailingQty: Double? = null
        var trailingQtyTokenStartIdx = -1

        for (i in tokens.indices) {
            if (i == spokenPriceIndex || i == rupeeWordIndex) continue
            val token = tokens[i]
            val num = token.toDoubleOrNull() ?: hindiNumberMap[token]
            if (num != null) {
                if (itemTokenIndex != -1 && i > itemTokenIndex) {
                    if (trailingQty == null) {
                        trailingQty = num
                        trailingQtyTokenStartIdx = i
                    }
                } else {
                    if (leadingQty == null) leadingQty = num
                }
            }
        }

        val hasLeadingQty = leadingQty != null
        val explicitQty = leadingQty ?: if (itemTokenIndex == -1) trailingQty else null

        // Build clean transcript with trailing quantity detached
        val cleanTranscriptWithoutTrailing = if (trailingQtyTokenStartIdx != -1 && leadingQty != null) {
            tokens.subList(0, trailingQtyTokenStartIdx).joinToString(" ")
        } else {
            cleanTranscript
        }

        val baseQty = explicitQty ?: 1.0
        val finalQty = if (explicitQty != null && hasExplicitModifier && unitMultiplier != 1.0 && explicitQty == 1.0) {
            unitMultiplier
        } else if (explicitQty == null) {
            unitMultiplier
        } else {
            baseQty * unitMultiplier
        }

        return ExtractedQtyTuple(
            qty = finalQty,
            unit = activeUnit,
            spokenPrice = spokenPrice,
            trailingQty = trailingQty,
            trailingUnit = activeUnit,
            cleanTranscriptWithoutTrailing = cleanTranscriptWithoutTrailing,
            hasLeadingQty = hasLeadingQty,
            hasAmbiguousPriceNumber = hasAmbiguousPriceNumber
        )
    }

    private data class ExtractedQtyTuple(
        val qty: Double,
        val unit: String,
        val spokenPrice: Double?,
        val trailingQty: Double?,
        val trailingUnit: String?,
        val cleanTranscriptWithoutTrailing: String,
        val hasLeadingQty: Boolean,
        val hasAmbiguousPriceNumber: Boolean = false
    )
}
