package com.voicetoinvoice.app.domain.lexicon

/**
 * MIRROR of supabase/functions/process-voice-job/lexicon.ts. Keep identical. See ISSUE-107.
 */
data class LexiconEntry(val canonical: String, val surfaces: List<String> = emptyList())

enum class QualifierKind { BRAND, VARIETY }

data class QualifierEntry(
    val canonical: String,
    val kind: QualifierKind,
    val surfaces: List<String>
)

object ItemLexicon {
    val QUALIFIERS: List<QualifierEntry> = listOf(
        // Brands
        QualifierEntry("Amul", QualifierKind.BRAND, listOf("अमूल", "amul")),
        QualifierEntry("Saras", QualifierKind.BRAND, listOf("सरस", "saras")),
        QualifierEntry("Madhur", QualifierKind.BRAND, listOf("मधुर", "madhur")),
        QualifierEntry("Tata", QualifierKind.BRAND, listOf("टाटा", "tata")),
        QualifierEntry("Aashirvaad", QualifierKind.BRAND, listOf("आशीर्वाद", "aashirvaad")),
        QualifierEntry("Fortune", QualifierKind.BRAND, listOf("फॉर्च्यून", "fortune")),
        QualifierEntry("Mother Dairy", QualifierKind.BRAND, listOf("मदर डेयरी", "mother dairy")),
        QualifierEntry("Nestle", QualifierKind.BRAND, listOf("नेस्ले", "nestle")),
        QualifierEntry("Britannia", QualifierKind.BRAND, listOf("ब्रिटानिया", "britannia")),
        QualifierEntry("Parle", QualifierKind.BRAND, listOf("पारले", "parle")),

        // Varieties
        QualifierEntry("Desi", QualifierKind.VARIETY, listOf("देसी", "desi")),
        QualifierEntry("Green", QualifierKind.VARIETY, listOf("हरा", "हरी", "हरे", "green")),
        QualifierEntry("Red", QualifierKind.VARIETY, listOf("लाल", "red")),
        QualifierEntry("Black", QualifierKind.VARIETY, listOf("काला", "काली", "black")),
        QualifierEntry("White", QualifierKind.VARIETY, listOf("सफेद", "सफ़ेद", "white")),
        QualifierEntry("Fresh", QualifierKind.VARIETY, listOf("ताज़ा", "ताजा", "fresh")),
        QualifierEntry("Full Cream", QualifierKind.VARIETY, listOf("फुल क्रीम", "full cream")),
        QualifierEntry("Toned", QualifierKind.VARIETY, listOf("टोंड", "toned"))
    )

    val ENTRIES: List<LexiconEntry> = listOf(
        // Produce & Vegetables
        LexiconEntry("Pyaz", listOf("प्याज", "प्याज़", "pyaz", "pyaaz", "onion", "onions")),
        LexiconEntry("Tamatar", listOf("टमाटर", "tmatar", "tamatar", "tomato", "tomatoes")),
        LexiconEntry("Aaloo", listOf("आलू", "अलू", "alu", "aaloo", "aalu", "potato", "potatoes")),
        LexiconEntry("Adrak", listOf("अदरक", "adrak", "ginger")),
        LexiconEntry("Bhindi", listOf("भिंडी", "bhindi", "bindi", "okra", "ladyfinger")),
        LexiconEntry("Dhaniya", listOf("धनिया", "dhaniya", "coriander")),
        LexiconEntry("Mirch", listOf("मिर्च", "हरी मिर्च", "mirch", "mirchi", "chilli", "chili")),
        LexiconEntry("Gobhi", listOf("गोभी", "gobhi", "cauliflower", "cabbage")),
        LexiconEntry("Baingan", listOf("बैंगन", "baingan", "brinjal", "eggplant")),
        LexiconEntry("Gajar", listOf("गाजर", "gajar", "carrot")),
        LexiconEntry("Matar", listOf("मटर", "matar", "peas")),
        LexiconEntry("Kheera", listOf("खीरा", "kheera", "cucumber")),
        LexiconEntry("Palak", listOf("पालक", "palak", "spinach")),
        LexiconEntry("Lahsun", listOf("लहसुन", "lahsun", "garlic")),
        LexiconEntry("Broccoli", listOf("ब्रोकली", "broccoli")),
        LexiconEntry("Dragon Fruit", listOf("ड्रैगन फ्रूट", "dragon fruit", "dragonfruit")),
        LexiconEntry("Seb", listOf("सेब")),
        LexiconEntry("Kela", listOf("केला")),
        LexiconEntry("Nimbu", listOf("नींबू")),
        LexiconEntry("Shimla Mirch", listOf("शिमला मिर्च")),
        LexiconEntry("Lauki", listOf("लौकी")),
        LexiconEntry("Torai", listOf("तोरई")),
        LexiconEntry("Karela", listOf("करेला")),
        LexiconEntry("Kaddu", listOf("कद्दू")),
        LexiconEntry("Mooli", listOf("मूली")),
        LexiconEntry("Chukandar", listOf("चुकंदर")),
        LexiconEntry("Angoor", listOf("अंगूर")),
        LexiconEntry("Aam", listOf("आम")),
        LexiconEntry("Santra", listOf("संतरा")),
        LexiconEntry("Papita", listOf("पपीता")),
        LexiconEntry("Anar", listOf("अनार")),
        LexiconEntry("Tarbooj", listOf("तरबूज")),
        LexiconEntry("Amrood", listOf("अमरूद")),

        // Dairy & Eggs
        // "गोल्ड"/"gold" alone is a DIFFERENT brand from Amul Gold — both are milk, but a shop
        // stocks and prices them separately. Only the Amul-qualified surfaces belong here.
        LexiconEntry("Amul Gold Milk", listOf("अमूल गोल्ड", "amul gold", "अमूल gold", "gold milk", "गोल्ड दूध")),
        LexiconEntry("Gold", listOf("गोल्ड", "gold")),
        LexiconEntry("Amul Taaza Milk", listOf("अमूल ताज़ा", "ताज़ा", "taaza milk", "amul taaza", "taaza")),
        LexiconEntry("Saras Milk", listOf("सरस दूध", "saras milk")),
        LexiconEntry("Curd (Dahi)", listOf("दही", "curd", "dahi")),
        LexiconEntry("Chaas (Buttermilk)", listOf("छाछ", "मट्ठा", "chaas", "chaach", "buttermilk")),
        LexiconEntry("Paneer", listOf("पनीर", "paneer")),
        // Plain "घी"/"ghee" is a different product from branded "Desi Ghee" and is priced
        // separately (₹1200/KG vs ₹650/KG in the reference shop). Do not collapse them.
        LexiconEntry("Ghee", listOf("घी", "ghee", "gi")),
        LexiconEntry("Desi Ghee", listOf("desi ghee", "देसी घी")),
        LexiconEntry("Butter", listOf("मक्खन", "बटर", "butter", "amul butter")),
        LexiconEntry("Eggs", listOf("अंडे", "अंडा", "anda", "egg", "eggs")),
        LexiconEntry("Doodh", listOf("दूध", "doodh", "milk", "dudh")),
        LexiconEntry("Malai", listOf("मलाई")),

        // FMCG, Biscuits & Staples
        LexiconEntry("Good Day Biscuit", listOf("गुड डे", "good day", "good day biscuit")),
        LexiconEntry("Good Knight Mosquito Coil", listOf("गुड नाइट", "गुड नाइट कॉइल", "good knight", "good knight coil")),
        LexiconEntry("Yippee Noodles", listOf("इप्पी", "इप्पी नूडल्स", "yippee", "yippee noodles")),
        LexiconEntry("Maggi", listOf("मैगी", "मैगी नूडल्स", "maggi")),
        LexiconEntry("Surf Excel", listOf("सर्फ", "सर्फ एक्सेल", "surf", "surf excel")),
        LexiconEntry("Fortune Refined Oil", listOf("फॉर्च्यून तेल", "fortune oil")),
        // "आटा" is flour, not a brand. A shop stocks generic atta AND Aashirvaad atta at
        // different prices — only the brand-qualified surfaces may claim the branded identity.
        LexiconEntry("Atta", listOf("आटा", "atta")),
        LexiconEntry("Atta (Aashirvaad)", listOf("आशीर्वाद आटा", "aashirvaad atta")),
        LexiconEntry("Bourbon Biscuit", listOf("बर्बन", "bourbon")),
        LexiconEntry("Parle-G Biscuit", listOf("पारले जी", "parle-g", "parleg")),
        LexiconEntry("Ponds Cream", listOf("पॉन्ड्स", "पॉन्ड्स क्रीम", "ponds", "ponds cream")),
        // "चीनी"/"sugar" is the commodity, not Madhur's brand. Madhur, Tata and loose sugar
        // are separate sellable items with separate prices.
        LexiconEntry("Sugar", listOf("चीनी", "शक्कर", "sugar", "chini")),
        LexiconEntry("Sugar (Madhur)", listOf("madhur sugar", "मधुर चीनी")),
        LexiconEntry("Chawal", listOf("चावल")),
        LexiconEntry("Namak", listOf("नमक")),
        LexiconEntry("Tel", listOf("तेल")),
        LexiconEntry("Maida", listOf("मैदा")),
        LexiconEntry("Sooji", listOf("सूजी")),
        LexiconEntry("Besan", listOf("बेसन")),
        LexiconEntry("Poha", listOf("पोहा")),
        LexiconEntry("Sewai", listOf("सेवई")),
        LexiconEntry("Sabudana", listOf("साबूदाना")),
        LexiconEntry("Gud", listOf("गुड़")),

        // Dals & Pulses
        LexiconEntry("Chana", listOf("चना")),
        LexiconEntry("Rajma", listOf("राजमा")),
        LexiconEntry("Moong", listOf("मूंग")),
        LexiconEntry("Masoor", listOf("मसूर")),
        LexiconEntry("Arhar", listOf("अरहर")),
        LexiconEntry("Toor", listOf("तूर")),
        LexiconEntry("Urad", listOf("उड़द")),
        LexiconEntry("Chole", listOf("छोले")),

        // Spices & Condiments
        LexiconEntry("Amchoor", listOf("अमचूर")),
        LexiconEntry("Haldi", listOf("हल्दी")),
        LexiconEntry("Jeera", listOf("जीरा")),
        LexiconEntry("Rai", listOf("राई")),
        LexiconEntry("Methi", listOf("मेथी")),
        LexiconEntry("Saunf", listOf("सौंफ")),
        LexiconEntry("Elaichi", listOf("इलायची")),
        LexiconEntry("Dalchini", listOf("दालचीनी")),
        LexiconEntry("Laung", listOf("लौंग")),
        LexiconEntry("Kali Mirch", listOf("काली मिर्च")),
        LexiconEntry("Tejpatta", listOf("तेजपत्ता")),
        LexiconEntry("Hing", listOf("हींग")),
        LexiconEntry("Ajwain", listOf("अजवाइन")),
        LexiconEntry("Garam Masala", listOf("गरम मसाला")),
        LexiconEntry("Kasuri Methi", listOf("कसूरी मेथी")),
        LexiconEntry("Imli", listOf("इमली")),
        LexiconEntry("Khatai", listOf("खटाई")),

        // Dry Fruits & Nuts
        LexiconEntry("Kaju", listOf("काजू")),
        LexiconEntry("Badam", listOf("बादाम")),
        LexiconEntry("Moongphali", listOf("मूंगफली")),
        LexiconEntry("Kishmish", listOf("किशमिश")),
        LexiconEntry("Akhrot", listOf("अखरोट")),
        LexiconEntry("Pista", listOf("पिस्ता")),
        LexiconEntry("Khajoor", listOf("खजूर")),
        LexiconEntry("Nariyal", listOf("नारियल")),

        // Household & Beverages
        LexiconEntry("Chaipatti", listOf("चायपत्ती")),
        LexiconEntry("Coffee", listOf("कॉफी")),
        LexiconEntry("Sabun", listOf("साबुन")),
        LexiconEntry("Shampoo", listOf("शैम्पू")),
        LexiconEntry("Agarbatti", listOf("अगरबत्ती")),
        LexiconEntry("Machis", listOf("माचिस")),
        LexiconEntry("Biscuit", listOf("बिस्कुट")),
        LexiconEntry("Bread", listOf("ब्रेड")),
        LexiconEntry("Namkeen", listOf("नमकीन")),
        LexiconEntry("Sona", listOf("सोना")),
        LexiconEntry("Chaandi", listOf("चांदी")),

        // Pooja Items
        LexiconEntry("Chandan", listOf("चंदन")),
        LexiconEntry("Kumkum", listOf("कुमकुम")),
        LexiconEntry("Roli", listOf("रोली")),
        LexiconEntry("Mouli", listOf("मौली")),
        LexiconEntry("Akshat", listOf("अक्षत")),
        LexiconEntry("Kapoor", listOf("कपूर")),
        LexiconEntry("Dhoop", listOf("धूप")),
        LexiconEntry("Diya", listOf("दीया")),
        LexiconEntry("Rooi", listOf("रुई")),
        LexiconEntry("Havan Samagri", listOf("हवन सामग्री")),
        LexiconEntry("Gangajal", listOf("गंगाजल")),
        LexiconEntry("Karonja", listOf("करोंजा")),
        LexiconEntry("Karonda", listOf("करौंदा")),
        LexiconEntry("Soyabean", listOf("सोयाबीन"))
    )

    private val surfaceToCanonical: Map<String, String> = buildMap {
        for (e in ENTRIES) {
            put(e.canonical.trim().lowercase(), e.canonical)
            for (s in e.surfaces) put(s.trim().lowercase(), e.canonical)
        }
    }

    private val qualifierBySurface: Map<String, QualifierEntry> = buildMap {
        for (q in QUALIFIERS) {
            put(q.canonical.trim().lowercase(), q)
            for (s in q.surfaces) put(s.trim().lowercase(), q)
        }
    }

    init {
        for ((surface, _) in qualifierBySurface) {
            if (surfaceToCanonical.containsKey(surface)) {
                error("Qualifier surface collision: '$surface' exists as both a qualifier and an item surface")
            }
        }
    }

    fun canonicalize(surface: String?): String? =
        surface?.trim()?.lowercase()?.let { surfaceToCanonical[it] }

    fun composeIdentity(phrase: String): String? {
        val tokens = phrase.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size < 2) return null

        val brands = mutableListOf<String>()
        val varieties = mutableListOf<String>()
        val rest = mutableListOf<String>()

        var i = 0
        while (i < tokens.size) {
            var matched = false
            for (span in minOf(3, tokens.size - i) downTo 1) {
                if (matched) break
                val probe = tokens.subList(i, i + span).joinToString(" ")
                val q = qualifierBySurface[probe]
                if (q != null) {
                    if (q.kind == QualifierKind.BRAND) {
                        if (!brands.contains(q.canonical)) brands.add(q.canonical)
                    } else {
                        if (!varieties.contains(q.canonical)) varieties.add(q.canonical)
                    }
                    i += span
                    matched = true
                }
            }
            if (!matched) {
                rest.add(tokens[i])
                i++
            }
        }

        if (rest.isEmpty()) return null
        if (brands.isEmpty() && varieties.isEmpty()) return null

        val base = canonicalize(rest.joinToString(" ")) ?: return null

        return (brands + varieties + listOf(base)).joinToString(" ")
    }

    fun canonicalOf(name: String?): String {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""

        val explicit = canonicalize(trimmed)
        if (explicit != null) return explicit

        val composed = composeIdentity(trimmed)
        if (composed != null) return composed

        return trimmed.lowercase().replace(Regex("\\s+"), " ")
    }

    fun surfaceMapForMatcher(): Map<String, String> = surfaceToCanonical

    fun baseUnitOf(unitId: String): String {
        return when (unitId.trim().uppercase()) {
            "KG", "GRAM", "PAO", "AADHA", "DHAI", "SAWA" -> "KG"
            "PIECE", "DOZEN" -> "PIECE"
            "PACKET" -> "PACKET"
            "LITRE", "ML" -> "LITRE"
            "BOX" -> "BOX"
            else -> unitId.trim().uppercase().ifEmpty { "PACKET" }
        }
    }

    fun canonicalQualifierOf(surface: String): String =
        surface.trim().lowercase().let { qualifierBySurface[it]?.canonical ?: surface }

    /**
     * Qualifier surfaces, flat. These go into their OWN segmenter vocabulary, never into
     * ALL_SURFACES: a modifier in the item vocabulary competes as a product and collapses the
     * ambiguity margin of real products ("हरा" sits 0.167 from "आलू"). But it must still be
     * recognisable, or the lattice fuzzy-matches it to the nearest product instead ("अमूल" -> अंगूर).
     * See ISSUE-109.
     */
    val ALL_QUALIFIER_SURFACES: List<String> =
        QUALIFIERS.flatMap { listOf(it.canonical) + it.surfaces }

    /**
     * Every ITEM surface. Qualifier surfaces are deliberately NOT included — they are modifiers,
     * not products, and in the item vocabulary they compete as item candidates in `matchVocab`:
     * "हरा"/"हरी" (Green) sit 0.167 from "आलू", which collapsed the ambiguity margin and flagged
     * a clean "पाँच किलो आलू" as AMBIGUOUS — the ISSUE-107 defect, reintroduced. An unrecognised
     * leading token is still emitted as ITEM at ITEM_BASELINE_COST and joined into the same
     * segment, so "अमूल घी" survives as one phrase for `canonicalOf` to compose. See ISSUE-109.
     */
    // Surfaces BEFORE canonical, deliberately. Every spelling of one item usually shares a
    // phonetic key, and normalizedLiteralDistance is transliteration-aware, so they tie at 0 on
    // both scores — the tie is settled by whichever the vocabulary lists first. Canonical-first
    // made the segmenter report "Aaloo" for a shopkeeper who said "आलू", changing what shows in
    // the ledger and the trace. The native spelling leads each `surfaces` list, so listing
    // surfaces first restores the pre-ISSUE-107 behaviour. ISSUE-109.
    val ALL_SURFACES: List<String> =
        ENTRIES.flatMap { it.surfaces + it.canonical }
}
