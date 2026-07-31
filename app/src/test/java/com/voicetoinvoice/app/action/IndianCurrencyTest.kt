package com.voicetoinvoice.app.action

import com.voicetoinvoice.app.domain.action.IndianCurrency
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Indian digit grouping. Western grouping (`124,500`) does not line up with the words a Hindi
 * speaker uses for the number, so it is measurably harder to read at a glance for this user base.
 */
class IndianCurrencyTest {

    @Test
    fun `groups in lakhs not thousands`() {
        assertEquals("1,24,500", IndianCurrency.format(124500.0))
        assertEquals("12,34,567", IndianCurrency.format(1234567.0))
        assertEquals("1,00,000", IndianCurrency.format(100000.0))
    }

    @Test
    fun `small numbers are ungrouped`() {
        assertEquals("0", IndianCurrency.format(0.0))
        assertEquals("50", IndianCurrency.format(50.0))
        assertEquals("999", IndianCurrency.format(999.0))
        assertEquals("1,000", IndianCurrency.format(1000.0))
    }

    @Test
    fun `paise shown only when non-zero`() {
        assertEquals("1,234", IndianCurrency.format(1234.0))
        assertEquals("1,234.50", IndianCurrency.format(1234.5))
        assertEquals("1,234.00", IndianCurrency.format(1234.0, alwaysShowPaise = true))
    }

    @Test
    fun `rounds half up to two places`() {
        assertEquals("10.13", IndianCurrency.format(10.125))
        assertEquals("10.12", IndianCurrency.format(10.124))
    }

    @Test
    fun `negative amounts keep the sign outside the digits`() {
        // Returns produce negative totals, and "-₹1,200" must not render as "₹-1,200" or "1,-200".
        assertEquals("-1,200", IndianCurrency.format(-1200.0))
        assertEquals("-1,24,500.50", IndianCurrency.format(-124500.5))
    }

    @Test
    fun `crore boundary keeps pairing above the last three digits`() {
        assertEquals("1,00,00,000", IndianCurrency.format(10000000.0))
    }

    @Test
    fun `symbol helper prefixes the rupee sign`() {
        assertEquals("₹1,24,500", IndianCurrency.withSymbol(124500.0))
    }
}
