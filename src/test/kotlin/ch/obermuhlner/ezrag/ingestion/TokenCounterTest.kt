package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TokenCounterTest {

    @Test
    fun `countTokens hello world returns between 1 and 10`() {
        val count = TokenCounter.countTokens("hello world")
        assertThat(count).isBetween(1, 10)
    }

    @Test
    fun `countTokens empty string returns 0`() {
        assertThat(TokenCounter.countTokens("")).isEqualTo(0)
    }

    @Test
    fun `countTokens is callable as function reference`() {
        val counter: (String) -> Int = TokenCounter::countTokens
        assertThat(counter("hello world")).isBetween(1, 10)
    }
}
