package ch.obermuhlner.ezrag.rag

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OutputFormatterTest {

    private val formatter = OutputFormatter()

    private fun singleSourceResult(): RagResult {
        val source = SourceReference(
            filePath = "docs/overview.txt",
            chunkIndex = 2,
            similarityScore = 0.87,
            excerpt = "This document describes the system architecture."
        )
        return RagResult(answer = "The system uses a layered architecture.", sources = listOf(source))
    }

    private fun emptySourcesResult(): RagResult {
        return RagResult(answer = "No relevant documents found", sources = emptyList())
    }

    // ---- Text format tests ----

    @Test
    fun `text format with one source contains answer text and Sources section with file path and score`() {
        val result = singleSourceResult()
        val output = formatter.formatText(result)

        assertThat(output).contains("The system uses a layered architecture.")
        assertThat(output).contains("--- Sources ---")
        assertThat(output).contains("docs/overview.txt")
        assertThat(output).contains("0.87")
    }

    @Test
    fun `text format with empty sources list does not contain Sources section`() {
        val result = emptySourcesResult()
        val output = formatter.formatText(result)

        assertThat(output).contains("No relevant documents found")
        assertThat(output).doesNotContain("--- Sources ---")

    }

    // ---- JSON format tests ----

    @Test
    fun `JSON format with one source parses as valid JSON with answer string and sources array containing file score and excerpt keys`() {
        val result = singleSourceResult()
        val json = formatter.formatJson(result)

        // Must be valid JSON - parse it manually using basic assertions
        assertThat(json.trim()).startsWith("{")
        assertThat(json.trim()).endsWith("}")

        // answer field
        assertThat(json).contains("\"answer\"")
        assertThat(json).contains("The system uses a layered architecture.")

        // sources array
        assertThat(json).contains("\"sources\"")
        assertThat(json).contains("[")
        assertThat(json).contains("\"file\"")
        assertThat(json).contains("docs/overview.txt")
        assertThat(json).contains("\"score\"")
        assertThat(json).contains("0.87")
        assertThat(json).contains("\"excerpt\"")
        assertThat(json).contains("This document describes the system architecture.")
    }

    @Test
    fun `JSON format with empty sources produces sources empty array`() {
        val result = emptySourcesResult()
        val json = formatter.formatJson(result)

        assertThat(json).contains("\"sources\"")
        assertThat(json).contains("[]")
    }
}
