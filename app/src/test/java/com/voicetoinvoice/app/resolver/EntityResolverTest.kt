package com.voicetoinvoice.app.resolver

import com.voicetoinvoice.app.domain.parser.PhoneticKey
import com.voicetoinvoice.app.domain.resolver.Decision
import com.voicetoinvoice.app.domain.resolver.EntityResolver
import com.voicetoinvoice.app.domain.resolver.Resolvable
import org.junit.Assert.*
import org.junit.Test

data class TestCustomer(
    override val resolverId: String,
    override val primaryPhoneticKey: String,
    override val altPhoneticKeys: List<String> = emptyList(),
    override val exactTokens: List<String> = emptyList(),
    override val lastSeenMs: Long = 0L,
    override val useCount: Int = 0,
    val name: String = ""
) : Resolvable

class EntityResolverTest {

    private val resolver = EntityResolver<TestCustomer>()

    @Test
    fun testExactNameMatch() {
        val c1 = TestCustomer("1", PhoneticKey.of("Ramesh"), name = "Ramesh")
        val c2 = TestCustomer("2", PhoneticKey.of("Suresh"), name = "Suresh")
        val pool = listOf(c1, c2)

        val result = resolver.resolve("Ramesh", pool)
        assertEquals(2, result.candidates.size)
        assertEquals("1", result.candidates[0].item.resolverId)
        assertEquals(Decision.AUTO_ASSIGN, result.decision)
    }

    @Test
    fun testMisheardNameMatch() {
        // "रामेश" vs "रमेश"
        val c1 = TestCustomer("1", PhoneticKey.of("रमेश"), name = "Ramesh")
        val c2 = TestCustomer("2", PhoneticKey.of("दिनेश"), name = "Dinesh")
        val pool = listOf(c1, c2)

        val result = resolver.resolve("रामेश", pool)
        assertEquals(2, result.candidates.size)
        assertEquals("1", result.candidates[0].item.resolverId)
        assertTrue(result.candidates[0].score >= 0.40)
    }

    @Test
    fun testThreeSameNameCustomers() {
        // 3 customers named "Ramesh"
        val c1 = TestCustomer("1", PhoneticKey.of("Ramesh"), exactTokens = listOf("1"), name = "Ramesh 1", lastSeenMs = 1000L)
        val c2 = TestCustomer("2", PhoneticKey.of("Ramesh"), exactTokens = listOf("2"), name = "Ramesh 2", lastSeenMs = 2000L)
        val c3 = TestCustomer("3", PhoneticKey.of("Ramesh"), exactTokens = listOf("3"), name = "Ramesh 3", lastSeenMs = 3000L)
        val pool = listOf(c1, c2, c3)

        val result = resolver.resolve("Ramesh", pool)
        assertEquals(3, result.candidates.size)
        // Scores will be close so decision should be ASK (disambiguation needed)
        assertEquals(Decision.ASK, result.decision)
    }

    @Test
    fun testNameAndKeywordUnique() {
        val c1 = TestCustomer("1", PhoneticKey.of("Ramesh"), altPhoneticKeys = listOf(PhoneticKey.of("Dosa Wala")), name = "Ramesh Dosa")
        val c2 = TestCustomer("2", PhoneticKey.of("Ramesh"), altPhoneticKeys = listOf(PhoneticKey.of("Bijli Wala")), name = "Ramesh Bijli")
        val pool = listOf(c1, c2)

        val result = resolver.resolve("दोसे वाले", pool)
        assertEquals(2, result.candidates.size)
        assertEquals("1", result.candidates[0].item.resolverId)
    }

    @Test
    fun testCodeOnlyMatch() {
        val c1 = TestCustomer("1", PhoneticKey.of("Ramesh"), exactTokens = listOf("1", "#001"), name = "Ramesh")
        val c2 = TestCustomer("2", PhoneticKey.of("Suresh"), exactTokens = listOf("2", "#002"), name = "Suresh")
        val pool = listOf(c1, c2)

        val result = resolver.resolve("number 2", pool)
        assertEquals(2, result.candidates.size)
        assertEquals("2", result.candidates[0].item.resolverId)
        assertEquals(Decision.AUTO_ASSIGN, result.decision)
    }

    @Test
    fun testPhoneOnlyMatch() {
        val c1 = TestCustomer("1", PhoneticKey.of("Ramesh"), exactTokens = listOf("9876543210"), name = "Ramesh")
        val c2 = TestCustomer("2", PhoneticKey.of("Suresh"), exactTokens = listOf("9123456789"), name = "Suresh")
        val pool = listOf(c1, c2)

        val result = resolver.resolve("3210", pool)
        assertEquals(2, result.candidates.size)
        assertEquals("1", result.candidates[0].item.resolverId)
        assertEquals(Decision.AUTO_ASSIGN, result.decision)
    }

    @Test
    fun testNoSpeechRecencyOrdering() {
        val c1 = TestCustomer("1", PhoneticKey.of("Ramesh"), lastSeenMs = 1000L, name = "Old")
        val c2 = TestCustomer("2", PhoneticKey.of("Suresh"), lastSeenMs = 5000L, name = "Recent")
        val pool = listOf(c1, c2)

        val result = resolver.resolve("", pool)
        assertEquals(2, result.candidates.size)
        assertEquals("2", result.candidates[0].item.resolverId)
        assertEquals(Decision.ASK, result.decision)
    }

    @Test
    fun testEmptyPoolReturnsNoneAvailable() {
        val result = resolver.resolve("Ramesh", emptyList())
        assertTrue(result.candidates.isEmpty())
        assertEquals(Decision.NONE_AVAILABLE, result.decision)
    }

    @Test
    fun testNonEmptyPoolNeverReturnsZeroCandidates() {
        val c1 = TestCustomer("1", PhoneticKey.of("Ramesh"))
        val result = resolver.resolve("UnmatchedRandomWord123", listOf(c1))
        assertFalse("Candidate list must NEVER be empty when pool is non-empty", result.candidates.isEmpty())
    }
}
