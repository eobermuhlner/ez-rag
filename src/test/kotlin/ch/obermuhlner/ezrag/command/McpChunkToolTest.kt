package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import java.nio.file.Path

class McpChunkToolTest {

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

    private fun makeStoreConfig(storeDir: Path) = StoreConfig(
        embeddingModel = fakeEmbeddingModel,
        storeDir = storeDir,
        analyzerName = "standard",
        lockTimeoutSeconds = 0,
    )

    private fun ingestChunks(storeDir: Path, absolutePath: String, texts: List<String>) {
        LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard").use { repo ->
            val docs = texts.mapIndexed { i, text ->
                Document.builder()
                    .text(text)
                    .metadata(mapOf("source" to absolutePath, "mtime" to 1716000000000L, "chunk_index" to i))
                    .build()
            }
            repo.add(docs)
        }
    }

    private fun ingestHeadingChunk(
        storeDir: Path,
        absolutePath: String,
        text: String,
        chunkIndex: Int,
        headingTitle: String,
        headingLevel: Int,
        headingPath: List<String>
    ) {
        LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard").use { repo ->
            val doc = Document.builder()
                .text(text)
                .metadata(mapOf(
                    "source" to absolutePath,
                    "mtime" to 1716000000000L,
                    "chunk_index" to chunkIndex,
                    "heading_title" to headingTitle,
                    "heading_level" to headingLevel,
                    "heading_path" to headingPath
                ))
                .build()
            repo.add(listOf(doc))
        }
    }

    @Test
    fun `chunk tool returns single chunk with no error`(@TempDir tempDir: Path) {
        val filePath = tempDir.resolve("doc.txt").toAbsolutePath().toString()
        ingestChunks(tempDir, filePath, listOf("Alpha", "Beta", "Gamma"))

        val tool = McpChunkTool(makeStoreConfig(tempDir))
        val result = tool.chunk(filePath, 1, null)

        assertThat(result.file).isEqualTo(filePath)
        assertThat(result.chunks).hasSize(1)
        assertThat(result.chunks[0].chunkIndex).isEqualTo(1)
        assertThat(result.chunks[0].text).isEqualTo("Beta")
    }

    @Test
    fun `chunk tool with window returns adjacent chunks in ascending order`(@TempDir tempDir: Path) {
        val filePath = tempDir.resolve("doc.txt").toAbsolutePath().toString()
        ingestChunks(tempDir, filePath, listOf("A", "B", "C", "D", "E"))

        val tool = McpChunkTool(makeStoreConfig(tempDir))
        val result = tool.chunk(filePath, 2, 1)

        assertThat(result.chunks).hasSize(3)
        assertThat(result.chunks.map { it.chunkIndex }).containsExactly(1, 2, 3)
    }

    @Test
    fun `chunk tool with window clamps at file boundaries`(@TempDir tempDir: Path) {
        val filePath = tempDir.resolve("doc.txt").toAbsolutePath().toString()
        ingestChunks(tempDir, filePath, listOf("A", "B", "C"))

        val tool = McpChunkTool(makeStoreConfig(tempDir))
        val result = tool.chunk(filePath, 0, 1)

        assertThat(result.chunks.map { it.chunkIndex }).containsExactly(0, 1)
    }

    @Test
    fun `chunk tool propagates heading metadata when present`(@TempDir tempDir: Path) {
        val filePath = tempDir.resolve("doc.md").toAbsolutePath().toString()
        ingestHeadingChunk(tempDir, filePath, "Section content", 0,
            headingTitle = "My Section", headingLevel = 2, headingPath = listOf("Top", "My Section"))

        val tool = McpChunkTool(makeStoreConfig(tempDir))
        val result = tool.chunk(filePath, 0, null)

        val chunk = result.chunks[0]
        assertThat(chunk.headingTitle).isEqualTo("My Section")
        assertThat(chunk.headingLevel).isEqualTo(2)
        assertThat(chunk.headingPath).containsExactly("Top", "My Section")
    }

    @Test
    fun `chunk tool returns null heading fields when chunk has no heading metadata`(@TempDir tempDir: Path) {
        val filePath = tempDir.resolve("doc.txt").toAbsolutePath().toString()
        ingestChunks(tempDir, filePath, listOf("Plain text"))

        val tool = McpChunkTool(makeStoreConfig(tempDir))
        val result = tool.chunk(filePath, 0, null)

        val chunk = result.chunks[0]
        assertThat(chunk.headingTitle).isNull()
        assertThat(chunk.headingLevel).isNull()
        assertThat(chunk.headingPath).isNull()
    }

    @Test
    fun `chunk tool with window null returns single target chunk`(@TempDir tempDir: Path) {
        val filePath = tempDir.resolve("doc.txt").toAbsolutePath().toString()
        ingestChunks(tempDir, filePath, listOf("X", "Y", "Z"))

        val tool = McpChunkTool(makeStoreConfig(tempDir))
        val result = tool.chunk(filePath, 1, null)

        assertThat(result.chunks).hasSize(1)
        assertThat(result.chunks[0].chunkIndex).isEqualTo(1)
        assertThat(result.chunks[0].text).isEqualTo("Y")
    }

    @Test
    fun `chunk tool returns error and empty chunks for unknown file`(@TempDir tempDir: Path) {
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { }

        val unknownPath = tempDir.resolve("nonexistent.txt").toAbsolutePath().toString()
        val tool = McpChunkTool(makeStoreConfig(tempDir))
        val exception = assertThrows<IllegalArgumentException> {
            tool.chunk(unknownPath, 0, null)
        }
        assertThat(exception.message).contains("File not found in store")
    }

    @Test
    fun `chunk tool opens and closes repository per call — write lock is released after each call`(@TempDir tempDir: Path) {
        val filePath = tempDir.resolve("doc.txt").toAbsolutePath().toString()
        ingestChunks(tempDir, filePath, listOf("Concurrent chunk"))

        val tool = McpChunkTool(makeStoreConfig(tempDir))
        val result = tool.chunk(filePath, 0, null)
        assertThat(result.file).isEqualTo(filePath)
        assertThat(result.chunks).hasSize(1)

        // After the tool call the write lock is released — another repo can be opened
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repo ->
            assertThat(repo.getChunksForFile(filePath)).isNotEmpty()
        }
    }
}
