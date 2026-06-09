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

class McpStatusToolTest {

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
    fun `status returns StoreStatus mapped from LuceneRepository getMetadata`(@TempDir tempDir: Path) {
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repository ->
            repository.add(listOf(
                Document.builder().text("doc1 content")
                    .metadata(mapOf("source" to "doc1.txt", "mtime" to 1000L, "chunk_index" to 0)).build(),
                Document.builder().text("doc1 second chunk")
                    .metadata(mapOf("source" to "doc1.txt", "mtime" to 1000L, "chunk_index" to 1)).build(),
                Document.builder().text("doc2 content")
                    .metadata(mapOf("source" to "doc2.txt", "mtime" to 2000L, "chunk_index" to 0)).build(),
            ))

            val tool = McpStatusTool(repository)
            val result = tool.status()

            assertThat(result.storeDirPath).isNotBlank()
            assertThat(result.chunkCount).isEqualTo(3)
            assertThat(result.error).isNull()
        }
    }

    @Test
    fun `status returns StoreStatus with non-blank storeDirPath`(@TempDir tempDir: Path) {
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repository ->
            repository.add(listOf(
                Document.builder().text("Some content")
                    .metadata(mapOf("source" to "test.txt", "mtime" to 1000L, "chunk_index" to 0)).build()
            ))

            val tool = McpStatusTool(repository)
            val result = tool.status()

            assertThat(result.storeDirPath).contains("lucene")
            assertThat(result.error).isNull()
        }
    }
}
