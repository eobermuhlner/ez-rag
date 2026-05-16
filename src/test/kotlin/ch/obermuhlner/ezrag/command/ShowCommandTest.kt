package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.VectorStoreRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path

class ShowCommandTest {

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

    private fun createRepository(storeFilePath: Path): VectorStoreRepository {
        val repo = VectorStoreRepository(fakeEmbeddingModel, storeFilePath)
        repo.load()
        return repo
    }

    private fun ingestChunks(
        repo: VectorStoreRepository,
        absolutePath: String,
        texts: List<String>,
        mtime: Long = 1716000000000L
    ) {
        val docs = texts.mapIndexed { i, text ->
            Document.builder()
                .text(text)
                .metadata(mapOf("source" to absolutePath, "mtime" to mtime, "chunk_index" to i))
                .build()
        }
        repo.add(docs)
        repo.save()
    }

    @Test
    fun `show displays header and per-chunk metadata without raw text`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repo = createRepository(storeFilePath)
        val filePath = tempDir.resolve("doc.txt").toAbsolutePath().toString()
        ingestChunks(repo, filePath, listOf("Hello world", "Second chunk", "Third one here"))

        val out = StringWriter()
        val err = StringWriter()
        val cmd = ShowCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
        )
        cmd.filePath = filePath
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        val output = out.toString()
        assertThat(output).contains("File: $filePath")
        assertThat(output).contains("Chunks: 3")
        assertThat(output).contains("Chunk 1")
        assertThat(output).contains("Chunk 2")
        assertThat(output).contains("Chunk 3")
        assertThat(output).contains("${" Hello world".length} chars".trimStart())
        // Raw text should NOT appear
        assertThat(output).doesNotContain("Hello world")
        assertThat(output).doesNotContain("Second chunk")
    }

    @Test
    fun `show with --chunks includes raw text of each chunk`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repo = createRepository(storeFilePath)
        val filePath = tempDir.resolve("doc.txt").toAbsolutePath().toString()
        ingestChunks(repo, filePath, listOf("Hello world", "Second chunk text"))

        val out = StringWriter()
        val err = StringWriter()
        val cmd = ShowCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
            includeChunks = true,
        )
        cmd.filePath = filePath
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        val output = out.toString()
        assertThat(output).contains("Hello world")
        assertThat(output).contains("Second chunk text")
    }

    @Test
    fun `show with --output json produces valid JSON with expected fields`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repo = createRepository(storeFilePath)
        val filePath = tempDir.resolve("doc.txt").toAbsolutePath().toString()
        ingestChunks(repo, filePath, listOf("Hello world", "Second chunk"), mtime = 1716000000000L)

        val out = StringWriter()
        val err = StringWriter()
        val cmd = ShowCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
        )
        cmd.filePath = filePath
        cmd.outputFormat = "json"
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        val json = out.toString().trim()
        val mapper = ObjectMapper()
        val node = mapper.readTree(json)
        assertThat(node.has("file")).isTrue()
        assertThat(node.get("file").asText()).isEqualTo(filePath)
        assertThat(node.has("chunks")).isTrue()
        val chunks = node.get("chunks")
        assertThat(chunks.isArray).isTrue()
        assertThat(chunks.size()).isEqualTo(2)
        val firstChunk = chunks[0]
        assertThat(firstChunk.has("chunkIndex")).isTrue()
        assertThat(firstChunk.has("charCount")).isTrue()
        assertThat(firstChunk.has("mtime")).isTrue()
        // text field should be absent when --chunks not set
        assertThat(firstChunk.has("text")).isFalse()
    }

    @Test
    fun `show with --output json and --chunks includes text field`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repo = createRepository(storeFilePath)
        val filePath = tempDir.resolve("doc.txt").toAbsolutePath().toString()
        ingestChunks(repo, filePath, listOf("Hello world"))

        val out = StringWriter()
        val err = StringWriter()
        val cmd = ShowCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
            includeChunks = true,
        )
        cmd.filePath = filePath
        cmd.outputFormat = "json"
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        val json = out.toString().trim()
        val mapper = ObjectMapper()
        val node = mapper.readTree(json)
        val chunks = node.get("chunks")
        assertThat(chunks[0].has("text")).isTrue()
        assertThat(chunks[0].get("text").asText()).isEqualTo("Hello world")
    }

    @Test
    fun `show of unknown file exits non-zero with error message`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        createRepository(storeFilePath) // empty store

        val unknownPath = tempDir.resolve("unknown.txt").toAbsolutePath().toString()
        val out = StringWriter()
        val err = StringWriter()
        val cmd = ShowCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
        )
        cmd.filePath = unknownPath
        val exitCode = cmd.call()

        assertThat(exitCode).isNotEqualTo(0)
        // Error message should be present (in err or out)
        assertThat(err.toString() + out.toString()).isNotBlank()
    }
}
