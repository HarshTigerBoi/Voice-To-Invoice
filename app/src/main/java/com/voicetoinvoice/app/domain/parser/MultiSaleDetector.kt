package com.voicetoinvoice.app.domain.parser

import com.voicetoinvoice.app.data.local.entity.CatalogItem

class MultiSaleDetector(
    private val voiceParser: VoiceParser = VoiceParser(),
    private val orderingSegmenter: OrderingSegmenter = OrderingSegmenter()
) {

    fun detectMultipleSales(transcript: String, catalog: List<CatalogItem>, pendingCarryoverQty: Double? = null): List<ParsedVoiceSale> {
        val cleanTranscript = transcript.trim()
        if (cleanTranscript.isBlank()) return emptyList()

        val segmentResult = orderingSegmenter.segmentTranscript(cleanTranscript, pendingCarryoverQty)
        if (segmentResult.segments.isEmpty()) {
            val single = voiceParser.parseUtterance(cleanTranscript, catalog)
            return if (single.matchedItem != null || single.quantity > 0) listOf(single) else emptyList()
        }

        val parsedSales = mutableListOf<ParsedVoiceSale>()
        for (seg in segmentResult.segments) {
            val parsed = voiceParser.parseUtterance(seg.rawSegmentText, catalog)
            val updated = parsed.copy(
                quantity = if (parsed.quantity > 0 && parsed.hasLeadingQty) parsed.quantity else seg.quantity,
                unit = if (parsed.unit != "PACKET") parsed.unit else (seg.unit ?: parsed.unit),
                isSanityFlagged = parsed.isSanityFlagged || seg.isSanityFlagged
            )
            if (updated.matchedItem != null || updated.quantity > 0) {
                parsedSales.add(updated)
            }
        }

        return if (parsedSales.isNotEmpty()) parsedSales else listOf(voiceParser.parseUtterance(cleanTranscript, catalog))
    }
}
