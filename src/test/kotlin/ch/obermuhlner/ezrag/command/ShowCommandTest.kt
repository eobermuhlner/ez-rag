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

    private fun ingestHeadingChunk(
        repo: VectorStoreRepository,
        absolutePath: String,
        text: String,
        headingTitle: String,
        headingLevel: Int,
        headingPath: List<String>,
        mtime: Long = 1716000000000L
    ) {
        val doc = Document.builder()
            .text(text)
            .metadata(mapOf(
                "source" to absolutePath,
                "mtime" to mtime,
                "chunk_index" to 0,
                "heading_title" to headingTitle,
                "heading_level" to headingLevel,
                "heading_path" to headingPath
            ))
            .build()
        repo.add(listOf(doc))
        repo.save()
    }

    @Test
    fun `show text output for Markdown chunk with heading includes heading prefix in summary line`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repo = createRepository(storeFilePath)
        val filePath = tempDir.resolve("doc.md").toAbsolutePath().toString()
        ingestHeadingChunk(repo, filePath, "## Section Name\nSome content here",
            headingTitle = "Section Name", headingLevel = 2, headingPath = listOf("Section Name"))

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
        assertThat(output).contains("heading: ## Section Name")
    }

    @Test
    fun `show JSON output for Markdown chunk with heading includes headingTitle headingLevel headingPath keys`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repo = createRepository(storeFilePath)
        val filePath = tempDir.resolve("doc.md").toAbsolutePath().toString()
        ingestHeadingChunk(repo, filePath, "## Section Name\nSome content here",
            headingTitle = "Section Name", headingLevel = 2, headingPath = listOf("Top", "Section Name"))

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
        val chunk = node.get("chunks")[0]
        assertThat(chunk.has("headingTitle")).isTrue()
        assertThat(chunk.get("headingTitle").asText()).isEqualTo("Section Name")
        assertThat(chunk.has("headingLevel")).isTrue()
        assertThat(chunk.get("headingLevel").asInt()).isEqualTo(2)
        assertThat(chunk.has("headingPath")).isTrue()
        assertThat(chunk.get("headingPath").isArray).isTrue()
    }

    @Test
    fun `show text output for non-Markdown chunk contains no heading substring`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repo = createRepository(storeFilePath)
        val filePath = tempDir.resolve("doc.txt").toAbsolutePath().toString()
        ingestChunks(repo, filePath, listOf("Plain text content"))

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
        assertThat(out.toString()).doesNotContain("heading:")
    }

    @Test
    fun `show JSON output for non-Markdown chunk contains no headingTitle headingLevel headingPath keys`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repo = createRepository(storeFilePath)
        val filePath = tempDir.resolve("doc.txt").toAbsolutePath().toString()
        ingestChunks(repo, filePath, listOf("Plain text content"))

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
        val chunk = node.get("chunks")[0]
        assertThat(chunk.has("headingTitle")).isFalse()
        assertThat(chunk.has("headingLevel")).isFalse()
        assertThat(chunk.has("headingPath")).isFalse()
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
