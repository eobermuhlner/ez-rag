package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.VectorStoreRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import java.nio.file.Path

class McpDeleteToolTest {

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
    fun `calling delete tool with valid file path removes document and returns confirmation`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repo = VectorStoreRepository(fakeEmbeddingModel, storeFilePath)
        repo.load()

        val absolutePath = tempDir.resolve("doc.txt").toAbsolutePath().toString()
        val docs = (0..2).map { i ->
            Document.builder()
                .text("Chunk $i content")
                .metadata(mapOf("source" to absolutePath, "mtime" to 1000L, "chunk_index" to i))
                .build()
        }
        repo.add(docs)
        repo.save()

        val tool = McpDeleteTool(fakeEmbeddingModel, storeFilePath)
        val result = tool.delete(absolutePath)

        assertThat(result.chunksRemoved).isEqualTo(3)
        assertThat(result.error).isNull()

        // Verify the store no longer contains the file
        val repo2 = VectorStoreRepository(fakeEmbeddingModel, storeFilePath)
        repo2.load()
        assertThat(repo2.getMetadata().documents.find { it.path == absolutePath }).isNull()
    }

    @Test
    fun `calling delete tool with unknown file path returns 0 chunks removed`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repo = VectorStoreRepository(fakeEmbeddingModel, storeFilePath)
        repo.load()
        repo.save()

        val unknownPath = tempDir.resolve("unknown.txt").toAbsolutePath().toString()
        val tool = McpDeleteTool(fakeEmbeddingModel, storeFilePath)
        val result = tool.delete(unknownPath)

        assertThat(result.chunksRemoved).isEqualTo(0)
        assertThat(result.error).isNull()
    }
}
