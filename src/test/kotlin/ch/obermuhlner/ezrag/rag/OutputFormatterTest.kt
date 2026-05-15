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

    // ---- SearchResult text format tests ----

    private fun twoChunkSearchResult(): SearchResult {
        val chunk1 = ChunkMatch(
            filePath = "docs/arch.md",
            chunkIndex = 3,
            score = 0.87,
            content = "The architecture consists of three layers..."
        )
        val chunk2 = ChunkMatch(
            filePath = "docs/overview.md",
            chunkIndex = 1,
            score = 0.74,
            content = "An overview of the system..."
        )
        return SearchResult(chunks = listOf(chunk1, chunk2))
    }

    @Test
    fun `formatText SearchResult with two chunks contains exact header format for first chunk`() {
        val result = twoChunkSearchResult()
        val output = formatter.formatText(result)

        assertThat(output).contains("[1] score=0.87  source=docs/arch.md  chunk=3")
    }

    @Test
    fun `formatText SearchResult with two chunks contains exact header format for second chunk`() {
        val result = twoChunkSearchResult()
        val output = formatter.formatText(result)

        assertThat(output).contains("[2] score=0.74  source=docs/overview.md  chunk=1")
    }

    @Test
    fun `formatText SearchResult with two chunks has blank line between them`() {
        val result = twoChunkSearchResult()
        val output = formatter.formatText(result)

        // There must be a blank line (double newline) between the two blocks
        assertThat(output).contains("\n\n")
    }

    @Test
    fun `formatText SearchResult with single chunk has no trailing blank line`() {
        val chunk = ChunkMatch(
            filePath = "docs/single.md",
            chunkIndex = 0,
            score = 0.90,
            content = "Single chunk content."
        )
        val result = SearchResult(chunks = listOf(chunk))
        val output = formatter.formatText(result)

        assertThat(output.trimEnd()).doesNotEndWith("\n\n")
    }

    @Test
    fun `formatText SearchResult with zero chunks returns empty string`() {
        val result = SearchResult(chunks = emptyList())
        val output = formatter.formatText(result)

        assertThat(output).isEmpty()
    }

    // ---- SearchResult JSON format tests ----

    @Test
    fun `formatJson SearchResult uses chunks as top-level key not sources or results`() {
        val result = twoChunkSearchResult()
        val json = formatter.formatJson(result)

        assertThat(json).contains("\"chunks\"")
        assertThat(json).doesNotContain("\"sources\"")
        assertThat(json).doesNotContain("\"results\"")
    }

    @Test
    fun `formatJson SearchResult entries include file chunkIndex score and content keys`() {
        val result = twoChunkSearchResult()
        val json = formatter.formatJson(result)

        assertThat(json).contains("\"file\"")
        assertThat(json).contains("\"chunkIndex\"")
        assertThat(json).contains("\"score\"")
        assertThat(json).contains("\"content\"")
    }

    @Test
    fun `formatJson SearchResult with zero chunks produces chunks empty array`() {
        val result = SearchResult(chunks = emptyList())
        val json = formatter.formatJson(result)

        assertThat(json).contains("\"chunks\"")
        assertThat(json).contains("[]")
    }

    @Test
    fun `formatJson SearchResult backslash double-quote and newline in content are escaped`() {
        val chunk = ChunkMatch(
            filePath = "test.txt",
            chunkIndex = 0,
            score = 0.5,
            content = "Line1\nLine2 with \"quotes\" and \\backslash"
        )
        val result = SearchResult(chunks = listOf(chunk))
        val json = formatter.formatJson(result)

        assertThat(json).contains("\\n")
        assertThat(json).contains("\\\"")
        assertThat(json).contains("\\\\")
    }

    @Test
    fun `formatJson SearchResult is parseable as valid JSON`() {
        val result = twoChunkSearchResult()
        val json = formatter.formatJson(result)

        assertThat(json.trim()).startsWith("{")
        assertThat(json.trim()).endsWith("}")
        // The key and values should be present
        assertThat(json).contains("docs/arch.md")
        assertThat(json).contains("docs/overview.md")
    }
}
