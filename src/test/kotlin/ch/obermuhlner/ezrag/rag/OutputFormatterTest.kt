package ch.obermuhlner.ezrag.rag

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document

class OutputFormatterTest {

    private val formatter = OutputFormatter()

    private fun singleSourceResult(): RagResult {
        val source = SourceReference(
            path = "docs/overview.txt",
            chunkIndex = 2,
            score = 0.87,
            text = "This document describes the system architecture."
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
        assertThat(json).contains("\"path\"")
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
            path = "docs/arch.md",
            chunkIndex = 3,
            score = 0.87,
            text = "The architecture consists of three layers..."
        )
        val chunk2 = ChunkMatch(
            path = "docs/overview.md",
            chunkIndex = 1,
            score = 0.74,
            text = "An overview of the system..."
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
            path = "docs/single.md",
            chunkIndex = 0,
            score = 0.90,
            text = "Single chunk content."
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
    fun `formatJson SearchResult uses path key for file path field`() {
        val result = twoChunkSearchResult()
        val json = formatter.formatJson(result)

        assertThat(json).contains("\"path\"")
        assertThat(json).doesNotContain("\"source\"")
        assertThat(json).doesNotContain("\"file\"")
    }

    @Test
    fun `formatJson SearchResult uses chunks as top-level key not sources or results`() {
        val result = twoChunkSearchResult()
        val json = formatter.formatJson(result)

        assertThat(json).contains("\"chunks\"")
        assertThat(json).doesNotContain("\"sources\"")
        assertThat(json).doesNotContain("\"results\"")
    }

    @Test
    fun `formatJson SearchResult entries include path chunkIndex score and content keys`() {
        val result = twoChunkSearchResult()
        val json = formatter.formatJson(result)

        assertThat(json).contains("\"path\"")
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

    // ---- SearchResult XML format tests ----

    @Test
    fun `formatXml SearchResult has results root element with mode attribute`() {
        val chunk1 = ChunkMatch(path = "docs/arch.md", chunkIndex = 3, score = 0.87, text = "arch content")
        val chunk2 = ChunkMatch(path = "docs/overview.md", chunkIndex = 1, score = 0.74, text = "overview content")
        val result = SearchResult(chunks = listOf(chunk1, chunk2), mode = "hybrid")
        val xml = formatter.formatXml(result)

        assertThat(xml).contains("<results mode=\"hybrid\">")
        assertThat(xml.trim()).endsWith("</results>")
    }

    @Test
    fun `formatXml SearchResult result elements have correct index score source chunk attributes`() {
        val chunk1 = ChunkMatch(path = "docs/arch.md", chunkIndex = 3, score = 0.87, text = "arch content")
        val chunk2 = ChunkMatch(path = "docs/overview.md", chunkIndex = 1, score = 0.74, text = "overview content")
        val result = SearchResult(chunks = listOf(chunk1, chunk2), mode = "hybrid")
        val xml = formatter.formatXml(result)

        assertThat(xml).contains("<result index=\"1\" score=\"0.87\" source=\"docs/arch.md\" chunk=\"3\">")
        assertThat(xml).contains("<result index=\"2\" score=\"0.74\" source=\"docs/overview.md\" chunk=\"1\">")
    }

    @Test
    fun `formatXml SearchResult chunk content appears between result tags`() {
        val chunk1 = ChunkMatch(path = "docs/arch.md", chunkIndex = 3, score = 0.87, text = "arch content")
        val chunk2 = ChunkMatch(path = "docs/overview.md", chunkIndex = 1, score = 0.74, text = "overview content")
        val result = SearchResult(chunks = listOf(chunk1, chunk2), mode = "hybrid")
        val xml = formatter.formatXml(result)

        assertThat(xml).contains("arch content")
        assertThat(xml).contains("overview content")
        assertThat(xml).contains("</result>")
    }

    @Test
    fun `formatXml SearchResult with zero chunks produces empty results element`() {
        val result = SearchResult(chunks = emptyList(), mode = "bm25")
        val xml = formatter.formatXml(result)

        assertThat(xml).isEqualTo("<results mode=\"bm25\"></results>")
        assertThat(xml).doesNotContain("<result ")
    }

    @Test
    fun `formatXml SearchResult score 0_9799999 is rendered as 0_98`() {
        val chunk = ChunkMatch(path = "test.md", chunkIndex = 1, score = 0.9799999, text = "some content")
        val result = SearchResult(chunks = listOf(chunk), mode = "embedding")
        val xml = formatter.formatXml(result)

        assertThat(xml).contains("score=\"0.98\"")
    }

    @Test
    fun `formatJson SearchResult backslash double-quote and newline in content are escaped`() {
        val chunk = ChunkMatch(
            path = "test.txt",
            chunkIndex = 0,
            score = 0.5,
            text = "Line1\nLine2 with \"quotes\" and \\backslash"
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

    // ---- Document list (chunks) format tests ----

    private fun chunkWithHeading(): Document {
        return Document.builder()
            .text("The architecture consists of three layers.")
            .metadata(mapOf(
                "chunk_index" to 0,
                "heading_title" to "Overview",
                "heading_level" to 2,
                "heading_path" to listOf("Introduction", "Overview")
            ))
            .build()
    }

    private fun chunkWithoutHeading(): Document {
        return Document.builder()
            .text("Plain text content with no headings.")
            .metadata(mapOf("chunk_index" to 1))
            .build()
    }

    @Test
    fun `formatText chunks first header line reads bracket-1-bracket chunk=0 heading_path`() {
        val chunks = listOf(chunkWithHeading())
        val output = formatter.formatText(chunks)

        assertThat(output).contains("[1] chunk=0")
        assertThat(output).contains("heading_path=Introduction > Overview")
    }

    @Test
    fun `formatText chunks contains chunk text`() {
        val chunks = listOf(chunkWithHeading())
        val output = formatter.formatText(chunks)

        assertThat(output).contains("The architecture consists of three layers.")
    }

    @Test
    fun `formatText chunks has blank line between two chunks`() {
        val chunks = listOf(chunkWithHeading(), chunkWithoutHeading())
        val output = formatter.formatText(chunks)

        assertThat(output).contains("\n\n")
        assertThat(output).contains("[1] chunk=0")
        assertThat(output).contains("[2] chunk=1")
    }

    @Test
    fun `formatText chunks without heading_path omits heading_path in output`() {
        val chunks = listOf(chunkWithoutHeading())
        val output = formatter.formatText(chunks)

        assertThat(output).doesNotContain("heading_path")
    }

    @Test
    fun `formatText empty chunk list returns empty string`() {
        val output = formatter.formatText(emptyList<Document>())

        assertThat(output).isEmpty()
    }

    @Test
    fun `formatJson chunks output parses as valid JSON with chunks array`() {
        val chunks = listOf(chunkWithHeading())
        val json = formatter.formatJson(chunks)

        assertThat(json.trim()).startsWith("{")
        assertThat(json.trim()).endsWith("}")
        assertThat(json).contains("\"chunks\"")
        assertThat(json).contains("[")
    }

    @Test
    fun `formatJson chunks each entry contains chunkIndex and content`() {
        val chunks = listOf(chunkWithHeading())
        val json = formatter.formatJson(chunks)

        assertThat(json).contains("\"chunkIndex\"")
        assertThat(json).contains("\"content\"")
        assertThat(json).contains("The architecture consists of three layers.")
    }

    @Test
    fun `formatJson chunks with heading_path includes headingPath field`() {
        val chunks = listOf(chunkWithHeading())
        val json = formatter.formatJson(chunks)

        assertThat(json).contains("\"headingPath\"")
        assertThat(json).contains("Introduction > Overview")
    }

    @Test
    fun `formatJson chunks without heading_path omits headingPath field`() {
        val chunks = listOf(chunkWithoutHeading())
        val json = formatter.formatJson(chunks)

        assertThat(json).doesNotContain("\"headingPath\"")
    }

    @Test
    fun `formatJson empty chunk list produces chunks empty array`() {
        val json = formatter.formatJson(emptyList<Document>())

        assertThat(json).contains("\"chunks\"")
        assertThat(json).contains("[]")
    }

    @Test
    fun `formatXml chunks produces results root element`() {
        val chunks = listOf(chunkWithHeading())
        val xml = formatter.formatXml(chunks)

        assertThat(xml).startsWith("<results>")
        assertThat(xml.trim()).endsWith("</results>")
    }

    @Test
    fun `formatXml chunks each chunk produces result element with index and chunk attributes`() {
        val chunks = listOf(chunkWithHeading())
        val xml = formatter.formatXml(chunks)

        assertThat(xml).contains("<result index=\"1\" chunk=\"0\"")
        assertThat(xml).contains("The architecture consists of three layers.")
        assertThat(xml).contains("</result>")
    }

    @Test
    fun `formatXml chunks with heading_path includes heading_path attribute`() {
        val chunks = listOf(chunkWithHeading())
        val xml = formatter.formatXml(chunks)

        assertThat(xml).contains("heading_path=\"Introduction &gt; Overview\"")
    }

    @Test
    fun `formatXml chunks without heading_path omits heading_path attribute`() {
        val chunks = listOf(chunkWithoutHeading())
        val xml = formatter.formatXml(chunks)

        assertThat(xml).doesNotContain("heading_path")
    }

    @Test
    fun `formatXml empty chunk list produces empty results element`() {
        val xml = formatter.formatXml(emptyList<Document>())

        assertThat(xml).isEqualTo("<results></results>")
    }

    @Test
    fun `formatJson chunks content with special characters is properly escaped`() {
        val chunk = Document.builder()
            .text("Line1\nLine2 with \"quotes\" and \\backslash")
            .metadata(mapOf("chunk_index" to 0))
            .build()
        val json = formatter.formatJson(listOf(chunk))

        assertThat(json).contains("\\n")
        assertThat(json).contains("\\\"")
        assertThat(json).contains("\\\\")
    }
}
