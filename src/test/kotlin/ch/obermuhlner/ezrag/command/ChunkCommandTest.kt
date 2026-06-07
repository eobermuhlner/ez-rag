package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import picocli.CommandLine
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path

class ChunkCommandTest {

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

    private fun ingestChunks(
        storeDir: Path,
        absolutePath: String,
        texts: List<String>,
        mtime: Long = 1716000000000L
    ) {
        LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard").use { repo ->
            val docs = texts.mapIndexed { i, text ->
                Document.builder()
                    .text(text)
                    .metadata(mapOf("source" to absolutePath, "mtime" to mtime, "chunk_index" to i))
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
        headingPath: List<String>,
        mtime: Long = 1716000000000L
    ) {
        LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard").use { repo ->
            val doc = Document.builder()
                .text(text)
                .metadata(mapOf(
                    "source" to absolutePath,
                    "mtime" to mtime,
                    "chunk_index" to chunkIndex,
                    "heading_title" to headingTitle,
                    "heading_level" to headingLevel,
                    "heading_path" to headingPath
                ))
                .build()
            repo.add(listOf(doc))
        }
    }

    // -----------------------------------------------------------------------
    // Picocli-level flag tests: --output-format accepted, --output rejected
    // -----------------------------------------------------------------------

    @Test
    fun `--output-format json is accepted by picocli parser for ChunkCommand`(@TempDir tempDir: Path) {
        val filePath = tempDir.resolve("doc.txt").toAbsolutePath().toString()
        ingestChunks(tempDir, filePath, listOf("Alpha", "Beta"))

        val out = StringWriter()
        val err = StringWriter()
        val cmd = ChunkCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
        )
        val commandLine = CommandLine(cmd)
        val exitCode = commandLine.execute("--output-format", "json", filePath, "0")
        assertThat(exitCode).isNotEqualTo(CommandLine.ExitCode.USAGE)
    }

    @Test
    fun `--output json is rejected by picocli parser for ChunkCommand`(@TempDir tempDir: Path) {
        val filePath = tempDir.resolve("doc.txt").toAbsolutePath().toString()
        ingestChunks(tempDir, filePath, listOf("Alpha", "Beta"))

        val out = StringWriter()
        val err = StringWriter()
        val cmd = ChunkCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
        )
        val commandLine = CommandLine(cmd)
        val exitCode = commandLine.execute("--output", "json", filePath, "0")
        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.USAGE)
    }

    @Test
    fun `chunk command prints text of exact chunk and exits 0`(@TempDir tempDir: Path) {
        val filePath = tempDir.resolve("doc.txt").toAbsolutePath().toString()
        ingestChunks(tempDir, filePath, listOf("Chunk zero text", "Chunk one text", "Chunk two text"))

        val out = StringWriter()
        val err = StringWriter()
        val cmd = ChunkCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
        )
        cmd.filePath = filePath
        cmd.chunkIndex = 2
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString()).contains("Chunk 2")
        assertThat(out.toString()).contains("Chunk two text")
    }

    @Test
    fun `chunk command with window fetches adjacent chunks in ascending order`(@TempDir tempDir: Path) {
        val filePath = tempDir.resolve("doc.txt").toAbsolutePath().toString()
        ingestChunks(tempDir, filePath, listOf("Text 0", "Text 1", "Text 2", "Text 3", "Text 4"))

        val out = StringWriter()
        val err = StringWriter()
        val cmd = ChunkCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
        )
        cmd.filePath = filePath
        cmd.chunkIndex = 2
        cmd.window = 1
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        val output = out.toString()
        assertThat(output).contains("Chunk 1")
        assertThat(output).contains("Chunk 2")
        assertThat(output).contains("Chunk 3")
        // Verify ascending order: 1 before 2 before 3
        assertThat(output.indexOf("Chunk 1")).isLessThan(output.indexOf("Chunk 2"))
        assertThat(output.indexOf("Chunk 2")).isLessThan(output.indexOf("Chunk 3"))
    }

    @Test
    fun `chunk command with window at boundary clamps and does not error`(@TempDir tempDir: Path) {
        val filePath = tempDir.resolve("doc.txt").toAbsolutePath().toString()
        ingestChunks(tempDir, filePath, listOf("Text 0", "Text 1", "Text 2"))

        val out = StringWriter()
        val err = StringWriter()
        val cmd = ChunkCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
        )
        cmd.filePath = filePath
        cmd.chunkIndex = 0
        cmd.window = 1
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        val output = out.toString()
        // Only chunks 0 and 1 should appear, no chunk -1
        assertThat(output).contains("Chunk 0")
        assertThat(output).contains("Chunk 1")
        assertThat(output).doesNotContain("Chunk -1")
    }

    @Test
    fun `chunk command with --output json produces valid JSON with file and chunks array`(@TempDir tempDir: Path) {
        val filePath = tempDir.resolve("doc.txt").toAbsolutePath().toString()
        ingestChunks(tempDir, filePath, listOf("Alpha", "Beta", "Gamma"))

        val out = StringWriter()
        val err = StringWriter()
        val cmd = ChunkCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
        )
        cmd.filePath = filePath
        cmd.chunkIndex = 1
        cmd.outputFormat = "json"
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        val json = out.toString().trim()
        val node = ObjectMapper().readTree(json)
        assertThat(node.has("file")).isTrue()
        assertThat(node.get("file").asText()).isEqualTo(filePath)
        assertThat(node.has("chunks")).isTrue()
        assertThat(node.get("chunks").isArray).isTrue()
        assertThat(node.get("chunks").size()).isEqualTo(1)
        assertThat(node.get("chunks")[0].get("chunkIndex").asInt()).isEqualTo(1)
        assertThat(node.get("chunks")[0].get("text").asText()).isEqualTo("Beta")
    }

    @Test
    fun `chunk command JSON output includes heading fields when present and omits them when absent`(@TempDir tempDir: Path) {
        val filePath = tempDir.resolve("doc.md").toAbsolutePath().toString()
        ingestHeadingChunk(tempDir, filePath, "Section content", 0,
            headingTitle = "My Section", headingLevel = 2, headingPath = listOf("Top", "My Section"))

        val out = StringWriter()
        val err = StringWriter()
        val cmd = ChunkCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
        )
        cmd.filePath = filePath
        cmd.chunkIndex = 0
        cmd.outputFormat = "json"
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        val node = ObjectMapper().readTree(out.toString().trim())
        val chunk = node.get("chunks")[0]
        assertThat(chunk.has("headingTitle")).isTrue()
        assertThat(chunk.get("headingTitle").asText()).isEqualTo("My Section")
        assertThat(chunk.has("headingLevel")).isTrue()
        assertThat(chunk.get("headingLevel").asInt()).isEqualTo(2)
        assertThat(chunk.has("headingPath")).isTrue()
        assertThat(chunk.get("headingPath").isArray).isTrue()
    }

    @Test
    fun `chunk command JSON output omits heading fields when chunk has no heading metadata`(@TempDir tempDir: Path) {
        val filePath = tempDir.resolve("doc.txt").toAbsolutePath().toString()
        ingestChunks(tempDir, filePath, listOf("Plain text"))

        val out = StringWriter()
        val err = StringWriter()
        val cmd = ChunkCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
        )
        cmd.filePath = filePath
        cmd.chunkIndex = 0
        cmd.outputFormat = "json"
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        val node = ObjectMapper().readTree(out.toString().trim())
        val chunk = node.get("chunks")[0]
        assertThat(chunk.has("headingTitle")).isFalse()
        assertThat(chunk.has("headingLevel")).isFalse()
        assertThat(chunk.has("headingPath")).isFalse()
    }

    @Test
    fun `chunk command exits 1 with error message when file not in store`(@TempDir tempDir: Path) {
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { }

        val unknownPath = tempDir.resolve("nonexistent.txt").toAbsolutePath().toString()
        val out = StringWriter()
        val err = StringWriter()
        val cmd = ChunkCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
        )
        cmd.filePath = unknownPath
        cmd.chunkIndex = 0
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(1)
        assertThat(err.toString()).contains(unknownPath)
    }

    @Test
    fun `chunk command exits 1 when chunk index is out of range`(@TempDir tempDir: Path) {
        val filePath = tempDir.resolve("doc.txt").toAbsolutePath().toString()
        ingestChunks(tempDir, filePath, listOf("Only one chunk"))

        val out = StringWriter()
        val err = StringWriter()
        val cmd = ChunkCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
        )
        cmd.filePath = filePath
        cmd.chunkIndex = 999
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(1)
        assertThat(err.toString()).isNotBlank()
    }
}
