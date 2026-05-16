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

class McpShowToolTest {

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
    fun `calling show tool with valid file path returns chunk metadata`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repo = VectorStoreRepository(fakeEmbeddingModel, storeFilePath)
        repo.load()

        val absolutePath = tempDir.resolve("doc.txt").toAbsolutePath().toString()
        val docs = (0..1).map { i ->
            Document.builder()
                .text("Chunk $i content")
                .metadata(mapOf("source" to absolutePath, "mtime" to 1716000000000L, "chunk_index" to i))
                .build()
        }
        repo.add(docs)
        repo.save()

        val tool = McpShowTool(fakeEmbeddingModel, storeFilePath)
        val result = tool.show(absolutePath, false)

        assertThat(result.error).isNull()
        assertThat(result.file).isEqualTo(absolutePath)
        assertThat(result.chunks).hasSize(2)
        assertThat(result.chunks[0].chunkIndex).isEqualTo(0)
        assertThat(result.chunks[1].chunkIndex).isEqualTo(1)
        // Text should be absent when includeChunks = false
        assertThat(result.chunks[0].text).isNull()
    }

    @Test
    fun `calling show tool with includeChunks true returns text`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repo = VectorStoreRepository(fakeEmbeddingModel, storeFilePath)
        repo.load()

        val absolutePath = tempDir.resolve("doc.txt").toAbsolutePath().toString()
        val doc = Document.builder()
            .text("Hello world content")
            .metadata(mapOf("source" to absolutePath, "mtime" to 1000L, "chunk_index" to 0))
            .build()
        repo.add(listOf(doc))
        repo.save()

        val tool = McpShowTool(fakeEmbeddingModel, storeFilePath)
        val result = tool.show(absolutePath, true)

        assertThat(result.error).isNull()
        assertThat(result.chunks[0].text).isEqualTo("Hello world content")
    }

    @Test
    fun `calling show tool with unknown file path returns error`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repo = VectorStoreRepository(fakeEmbeddingModel, storeFilePath)
        repo.load()
        repo.save()

        val unknownPath = tempDir.resolve("unknown.txt").toAbsolutePath().toString()
        val tool = McpShowTool(fakeEmbeddingModel, storeFilePath)
        val result = tool.show(unknownPath, false)

        assertThat(result.error).isNotNull()
        assertThat(result.chunks).isEmpty()
    }
}
