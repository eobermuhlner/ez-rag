package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import java.nio.file.Path

class McpListToolTest {

    private val fakeEmbeddingModel: EmbeddingModel = object : EmbeddingModel {
        override fun call(request: EmbeddingRequest): EmbeddingResponse {
            val embeddings = request.instructions.mapIndexed { idx, _ ->
                Embedding(FloatArray(4) { 0.1f * (idx + 1) }, idx)
            }
            return EmbeddingResponse(embeddings)
        }
        override fun embed(document: Document): FloatArray = FloatArray(4) { 0.1f }
        override fun embed(text: String): FloatArray = FloatArray(4) { 0.1f }
        override fun embedForResponse(texts: List<String>): EmbeddingResponse {
            val embeddings = texts.mapIndexed { idx, _ ->
                Embedding(FloatArray(4) { 0.1f * (idx + 1) }, idx)
            }
            return EmbeddingResponse(embeddings)
        }
        override fun dimensions(): Int = 4
    }

    @Test
    fun `list returns per-document path, chunkCount, and stale false for fresh documents`(@TempDir tempDir: Path) {
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repository ->
            repository.add(listOf(
                Document.builder().text("doc1 chunk 1")
                    .metadata(mapOf("source" to "doc1.txt", "mtime" to 1000L, "chunk_index" to 0)).build(),
                Document.builder().text("doc1 chunk 2")
                    .metadata(mapOf("source" to "doc1.txt", "mtime" to 1000L, "chunk_index" to 1)).build(),
                Document.builder().text("doc2 content")
                    .metadata(mapOf("source" to "doc2.txt", "mtime" to 2000L, "chunk_index" to 0)).build(),
            ))

            // Inject probe that returns the same mtime — all documents are fresh
            val tool = McpListTool(repository) { path ->
                when (path) {
                    "doc1.txt" -> 1000L
                    "doc2.txt" -> 2000L
                    else -> null
                }
            }
            val result = tool.list()

            assertThat(result).hasSize(2)
            val doc1 = result.first { it.path == "doc1.txt" }
            assertThat(doc1.chunkCount).isEqualTo(2)
            assertThat(doc1.stale).isFalse()
            val doc2 = result.first { it.path == "doc2.txt" }
            assertThat(doc2.chunkCount).isEqualTo(1)
            assertThat(doc2.stale).isFalse()
        }
    }

    @Test
    fun `list marks document as stale when filesystem probe returns different mtime`(@TempDir tempDir: Path) {
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repository ->
            repository.add(listOf(
                Document.builder().text("fresh content")
                    .metadata(mapOf("source" to "fresh.txt", "mtime" to 1000L, "chunk_index" to 0)).build(),
                Document.builder().text("stale content")
                    .metadata(mapOf("source" to "stale.txt", "mtime" to 2000L, "chunk_index" to 0)).build(),
            ))

            // fresh.txt probe returns same mtime; stale.txt probe returns null (missing file)
            val tool = McpListTool(repository) { path ->
                when (path) {
                    "fresh.txt" -> 1000L
                    else -> null
                }
            }
            val result = tool.list()

            assertThat(result).hasSize(2)
            val fresh = result.first { it.path == "fresh.txt" }
            assertThat(fresh.stale).isFalse()
            val stale = result.first { it.path == "stale.txt" }
            assertThat(stale.stale).isTrue()
        }
    }

    @Test
    fun `list returns empty list for empty store`(@TempDir tempDir: Path) {
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repository ->
            val tool = McpListTool(repository) { _ -> null }
            val result = tool.list()

            assertThat(result).isEmpty()
        }
    }
}
