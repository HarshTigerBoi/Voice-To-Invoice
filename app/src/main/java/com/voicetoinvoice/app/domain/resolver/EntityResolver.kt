package com.voicetoinvoice.app.domain.resolver

import com.voicetoinvoice.app.domain.parser.PhoneticKey
import kotlin.math.ln1p

interface Resolvable {
    val resolverId: String
    val primaryPhoneticKey: String
    val altPhoneticKeys: List<String>   // keyword, aliases
    val exactTokens: List<String>       // code as digits, phone, phone suffix
    val lastSeenMs: Long
    val useCount: Int
}

enum class Decision {
    AUTO_ASSIGN, ASK, NONE_AVAILABLE
}

data class Scored<T>(
    val item: T,
    val score: Double
)

data class ResolutionResult<T>(
    val candidates: List<Scored<T>>,    // ALWAYS non-empty when the pool is non-empty
    val decision: Decision               // AUTO_ASSIGN | ASK | NONE_AVAILABLE
)

class EntityResolver<T : Resolvable>(
    private val threshold: Double = THRESHOLD,
    private val margin: Double = MARGIN
) {

    companion object {
        const val THRESHOLD = 0.80
        const val MARGIN = 0.15
    }

    fun resolve(query: String, pool: List<T>): ResolutionResult<T> {
        if (pool.isEmpty()) {
            return ResolutionResult(emptyList(), Decision.NONE_AVAILABLE)
        }

        val trimmedQuery = query.trim()
        val queryPhoneticKey = if (trimmedQuery.isNotBlank()) PhoneticKey.of(trimmedQuery) else ""
        val digits = trimmedQuery.filter { it.isDigit() }

        val maxLastSeen = pool.maxOfOrNull { it.lastSeenMs } ?: 0L
        val minLastSeen = pool.minOfOrNull { it.lastSeenMs } ?: 0L
        val lastSeenRange = (maxLastSeen - minLastSeen).toDouble().coerceAtLeast(1.0)

        val scoredList = pool.map { item ->
            var score = 0.0

            if (trimmedQuery.isNotBlank()) {
                // 1. Phonetic primary name match
                if (queryPhoneticKey.isNotBlank() && item.primaryPhoneticKey.isNotBlank()) {
                    val dist = PhoneticKey.normalizedDistance(queryPhoneticKey, item.primaryPhoneticKey)
                    val nameMatchScore = (1.0 - dist).coerceAtLeast(0.0)
                    score += 0.80 * nameMatchScore
                }

                // 2. Phonetic alt/keyword match
                if (queryPhoneticKey.isNotBlank() && item.altPhoneticKeys.isNotEmpty()) {
                    val bestAltDist = item.altPhoneticKeys.minOfOrNull {
                        PhoneticKey.normalizedDistance(queryPhoneticKey, it)
                    } ?: 1.0
                    val altMatchScore = (1.0 - bestAltDist).coerceAtLeast(0.0)
                    score += 0.30 * altMatchScore
                }

                // 3. Exact code match
                if (digits.isNotBlank()) {
                    val matchesCode = item.exactTokens.any { token ->
                        val cleanToken = token.filter { it.isDigit() }
                        cleanToken.isNotBlank() && cleanToken == digits
                    }
                    if (matchesCode) {
                        score += 0.80
                    }
                }

                // 4. Exact phone match / suffix match
                if (digits.isNotBlank() && digits.length >= 3) {
                    val matchesPhone = item.exactTokens.any { token ->
                        val cleanPhone = token.filter { it.isDigit() }
                        cleanPhone.isNotBlank() && (cleanPhone.endsWith(digits) || digits.endsWith(cleanPhone))
                    }
                    if (matchesPhone) {
                        score += 0.80
                    }
                }
            }

            // 5. Recency prior (0.0 to 0.05)
            val recencyNorm = if (item.lastSeenMs > 0L) {
                (item.lastSeenMs - minLastSeen) / lastSeenRange
            } else 0.0
            score += 0.05 * recencyNorm

            // 6. Use count prior (0.0 to 0.05)
            val frequencyNorm = (ln1p(item.useCount.toDouble()) / ln1p(100.0)).coerceAtMost(1.0)
            score += 0.05 * frequencyNorm

            Scored(item, score.coerceAtMost(1.0))
        }.sortedByDescending { it.score }

        val top1 = scoredList.firstOrNull()?.score ?: 0.0
        val top2 = scoredList.getOrNull(1)?.score ?: 0.0

        val decision = if (top1 >= threshold && (top1 - top2) >= margin) {
            Decision.AUTO_ASSIGN
        } else {
            Decision.ASK
        }

        return ResolutionResult(scoredList, decision)
    }
}
