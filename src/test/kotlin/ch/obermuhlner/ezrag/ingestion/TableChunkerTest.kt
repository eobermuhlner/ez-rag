package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TableChunkerTest {

    // Use a simple word-count token counter for predictable unit tests
    private val wordCount: (String) -> Int = { text ->
        if (text.isBlank()) 0 else text.trim().split(Regex("\\s+")).size
    }

    private fun chunker(chunkSize: Int) = TableChunker(chunkSize = chunkSize, tokenCounter = wordCount)

    @Test
    fun `empty row list returns empty list`() {
        val chunker = chunker(100)
        val result = chunker.chunk(listOf("Name", "Value"), emptyList())
        assertThat(result).isEmpty()
    }

    @Test
    fun `single small batch produces one chunk containing the header`() {
        val chunker = chunker(100)
        val header = listOf("Name", "Age")
        val rows = listOf(
            listOf("Alice", "30"),
            listOf("Bob", "25"),
        )
        val result = chunker.chunk(header, rows)
        assertThat(result).hasSize(1)
        assertThat(result[0]).contains("Name")
        assertThat(result[0]).contains("Age")
        assertThat(result[0]).contains("Alice")
        assertThat(result[0]).contains("Bob")
    }

    @Test
    fun `large input produces multiple chunks each starting with header row`() {
        val chunker = chunker(10) // small budget forces splits
        val header = listOf("Name", "Value")
        // Each data row has ~5 words in Markdown format; header+separator ~8 words
        // So only 1 row per chunk fits under budget of 10
        val rows = (1..10).map { listOf("Item$it", "Val$it") }

        val result = chunker.chunk(header, rows)

        assertThat(result.size).isGreaterThan(1)
        result.forEach { chunk ->
            assertThat(chunk).contains("Name")
            assertThat(chunk).contains("Value")
        }
    }

    @Test
    fun `no data row appears in more than one chunk (strictly non-overlapping)`() {
        val chunker = chunker(20)
        val header = listOf("Name", "Value")
        // Use clearly unique identifiers that are not substrings of each other
        val rowNames = listOf("Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta", "Eta", "Theta")
        val rows = rowNames.map { listOf(it, "data") }

        val result = chunker.chunk(header, rows)
        assertThat(result.size).isGreaterThan(1)

        // Every row name must appear in exactly one chunk
        rowNames.forEach { name ->
            val chunksContaining = result.count { it.contains(name) }
            assertThat(chunksContaining)
                .withFailMessage("Row value '$name' appeared in $chunksContaining chunks (expected 1)")
                .isEqualTo(1)
        }
    }

    @Test
    fun `every chunk token count is within budget except single oversized row`() {
        // Use a budget large enough to fit the header+separator (about 9 words) plus some rows
        // Header line: | Name | Value | = 4 words; separator: | --- | --- | = 5 words; row: | AlphaRow | x | = 4 words
        val chunkSize = 20
        val chunker = chunker(chunkSize)
        val header = listOf("Name", "Value")
        val normalRows = listOf(
            listOf("AlphaRow", "one"),
            listOf("BetaRow", "two"),
            listOf("GammaRow", "three"),
            listOf("DeltaRow", "four"),
            listOf("EpsilonRow", "five"),
        )

        val result = chunker.chunk(header, normalRows)

        // Each chunk token count should be <= chunkSize (20)
        result.forEach { chunk ->
            val tokenCount = wordCount(chunk)
            assertThat(tokenCount)
                .withFailMessage("Chunk token count $tokenCount exceeds budget $chunkSize: '$chunk'")
                .isLessThanOrEqualTo(chunkSize)
        }
    }

    @Test
    fun `single oversized row is emitted as its own one-row chunk`() {
        val chunkSize = 5 // very small budget
        val chunker = chunker(chunkSize)
        val header = listOf("Col")
        // One row that is alone too large
        val rows = listOf(listOf("word1 word2 word3 word4 word5 word6 word7 word8 word9 word10"))

        val result = chunker.chunk(header, rows)

        // Even if oversized, it must be emitted
        assertThat(result).hasSize(1)
        assertThat(result[0]).contains("word1")
    }

    @Test
    fun `each chunk is a valid Markdown table with header and separator rows`() {
        val chunker = chunker(100)
        val header = listOf("Name", "Age")
        val rows = listOf(listOf("Alice", "30"))

        val result = chunker.chunk(header, rows)

        assertThat(result).hasSize(1)
        val lines = result[0].lines()
        // First line: header row  | Name | Age |
        assertThat(lines[0]).startsWith("|")
        assertThat(lines[0]).contains("Name")
        // Second line: separator  | --- | --- |
        assertThat(lines[1]).contains("---")
        // Third line: data row
        assertThat(lines[2]).contains("Alice")
    }
}
