package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.document.Document

class DocumentChunkerTest {

    @Test
    fun `a document larger than chunk size produces more than one chunk`() {
        val longText = "The quick brown fox jumps over the lazy dog. ".repeat(100)
        val document = Document.builder()
            .text(longText)
            .metadata(mapOf("source" to "/some/file.txt"))
            .build()

        val chunker = DocumentChunker(chunkSize = 50, chunkOverlap = 10)
        val chunks = chunker.split(listOf(document))

        assertThat(chunks).hasSizeGreaterThan(1)
    }

    @Test
    fun `each chunk carries the source metadata from the original document`() {
        val longText = "The quick brown fox jumps over the lazy dog. ".repeat(100)
        val document = Document.builder()
            .text(longText)
            .metadata(mapOf("source" to "/some/file.txt"))
            .build()

        val chunker = DocumentChunker(chunkSize = 50, chunkOverlap = 10)
        val chunks = chunker.split(listOf(document))

        assertThat(chunks).isNotEmpty()
        chunks.forEach { chunk ->
            assertThat(chunk.metadata["source"] as String).isEqualTo("/some/file.txt")
        }
    }
}
