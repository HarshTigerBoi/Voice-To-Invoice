package com.voicetoinvoice.app.domain.action

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Rupee formatting with Indian digit grouping (lakh/crore), e.g. `124500` -> `1,24,500`.
 *
 * Western grouping (`124,500`) is genuinely harder for the target user to read at a glance --
 * the grouping boundaries do not line up with the words a Hindi speaker uses for the number.
 * `java.text.NumberFormat` for `en-IN` is not dependable across the OEM ROMs this app runs on,
 * so the grouping is done explicitly.
 */
object IndianCurrency {

    /** `1234.5` -> `"1,234.50"`, `1234.0` -> `"1,234"`. */
    fun format(amount: Double, alwaysShowPaise: Boolean = false): String {
        val rounded = BigDecimal(amount).setScale(2, RoundingMode.HALF_UP)
        val negative = rounded.signum() < 0
        val abs = rounded.abs()

        val whole = abs.toBigInteger().toString()
        val paise = abs.subtract(BigDecimal(abs.toBigInteger())).movePointRight(2).toInt()

        val grouped = groupIndian(whole)
        val body = if (paise > 0 || alwaysShowPaise) {
            "$grouped.${paise.toString().padStart(2, '0')}"
        } else {
            grouped
        }
        return if (negative) "-$body" else body
    }

    /** With the ₹ symbol attached. */
    fun withSymbol(amount: Double): String = "₹${format(amount)}"

    /**
     * Last three digits stay together, everything before them groups in pairs:
     * `1234567` -> `12,34,567`.
     */
    private fun groupIndian(digits: String): String {
        if (digits.length <= 3) return digits
        val last3 = digits.takeLast(3)
        val rest = digits.dropLast(3)
        val sb = StringBuilder()
        var count = 0
        for (i in rest.indices.reversed()) {
            sb.append(rest[i])
            count++
            if (count % 2 == 0 && i != 0) sb.append(',')
        }
        return sb.reverse().toString() + "," + last3
    }
}
