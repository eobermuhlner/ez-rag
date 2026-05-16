package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.IngestIntegrationTest
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

class StatusCommandTest {

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

    private fun ingestFile(file: Path, storePath: Path) {
        val repo = VectorStoreRepository(fakeEmbeddingModel, storePath)
        repo.load()
        val doc = Document.builder()
            .text(file.toFile().readText())
            .metadata(mapOf("source" to file.toString(), "mtime" to file.toFile().lastModified()))
            .build()
        repo.add(listOf(doc))
        repo.save()
    }

    @Test
    fun `status text output contains store path, chunk count, and filenames`(@TempDir tempDir: Path) {
        val fileA = tempDir.resolve("a.txt")
        val fileB = tempDir.resolve("b.txt")
        fileA.toFile().writeText("Content for file A. More text to ensure a chunk is created.")
        fileB.toFile().writeText("Content for file B. Different content for second file.")

        val storePath = tempDir.resolve("vector-store.json")

        // Ingest both files via VectorStoreRepository directly
        val repo = VectorStoreRepository(fakeEmbeddingModel, storePath)
        repo.load()
        repo.add(listOf(
            Document.builder().text(fileA.toFile().readText())
                .metadata(mapOf("source" to fileA.toString(), "mtime" to 1000L)).build()
        ))
        repo.add(listOf(
            Document.builder().text(fileB.toFile().readText())
                .metadata(mapOf("source" to fileB.toString(), "mtime" to 2000L)).build()
        ))
        repo.save()

        val out = StringWriter()
        val statusCommand = StatusCommand(fakeEmbeddingModel, storePath, PrintWriter(out))
        val exitCode = statusCommand.call()

        assertThat(exitCode).isEqualTo(0)
        val output = out.toString()
        assertThat(output).contains("Store:")
        assertThat(output).contains(storePath.toAbsolutePath().toString())
        assertThat(output).contains("Chunks:")
        assertThat(output).contains(fileA.toString())
        assertThat(output).contains(fileB.toString())
    }

    @Test
    fun `status JSON output is valid JSON with correct keys`(@TempDir tempDir: Path) {
        val fileA = tempDir.resolve("a.txt")
        fileA.toFile().writeText("Content for file A. More text here.")

        val storePath = tempDir.resolve("vector-store.json")
        val repo = VectorStoreRepository(fakeEmbeddingModel, storePath)
        repo.load()
        repo.add(listOf(
            Document.builder().text(fileA.toFile().readText())
                .metadata(mapOf("source" to fileA.toString(), "mtime" to 1000L)).build()
        ))
        repo.save()

        val out = StringWriter()
        val statusCommand = StatusCommand(fakeEmbeddingModel, storePath, PrintWriter(out))
        statusCommand.outputFormat = "json"
        val exitCode = statusCommand.call()

        assertThat(exitCode).isEqualTo(0)
        val json = out.toString().trim()
        val mapper = ObjectMapper()
        val node = mapper.readTree(json)
        assertThat(node.has("storePath")).isTrue()
        assertThat(node.get("storePath").asText()).isNotBlank()
        assertThat(node.has("chunkCount")).isTrue()
        assertThat(node.get("chunkCount").asInt()).isGreaterThanOrEqualTo(1)
        assertThat(node.has("documents")).isTrue()
        assertThat(node.get("documents").isArray).isTrue()
        val firstDoc = node.get("documents").first()
        assertThat(firstDoc.has("path")).isTrue()
        assertThat(firstDoc.has("chunkCount")).isTrue()
    }

    @Test
    fun `status exits non-zero and shows error when no store exists`(@TempDir tempDir: Path) {
        val storePath = tempDir.resolve("nonexistent-vector-store.json")

        val out = StringWriter()
        val statusCommand = StatusCommand(fakeEmbeddingModel, storePath, PrintWriter(out))
        val exitCode = statusCommand.call()

        assertThat(exitCode).isNotEqualTo(0)
        val output = out.toString()
        assertThat(output).contains(storePath.toAbsolutePath().toString())
        assertThat(output).contains("ez-rag ingest")
    }

    @Test
    fun `status works without embedding model`(@TempDir tempDir: Path) {
        val storePath = tempDir.resolve("vector-store.json")
        val repo = VectorStoreRepository(fakeEmbeddingModel, storePath)
        repo.load()
        repo.add(listOf(
            Document.builder().text("Hello world content")
                .metadata(mapOf("source" to "test.txt", "mtime" to 1000L)).build()
        ))
        repo.save()

        val out = StringWriter()
        val statusCommand = StatusCommand(embeddingModel = null, storePathOverride = storePath, outputWriter = PrintWriter(out))
        val exitCode = statusCommand.call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString()).contains("test.txt")
    }

    @Test
    fun `status text output lists files alphabetically`(@TempDir tempDir: Path) {
        val storePath = tempDir.resolve("vector-store.json")
        val repo = VectorStoreRepository(fakeEmbeddingModel, storePath)
        repo.load()
        // Add files out of alphabetical order
        repo.add(listOf(
            Document.builder().text("Content Z")
                .metadata(mapOf("source" to "z-file.txt", "mtime" to 1000L)).build()
        ))
        repo.add(listOf(
            Document.builder().text("Content A")
                .metadata(mapOf("source" to "a-file.txt", "mtime" to 2000L)).build()
        ))
        repo.save()

        val out = StringWriter()
        val statusCommand = StatusCommand(fakeEmbeddingModel, storePath, PrintWriter(out))
        statusCommand.call()

        val output = out.toString()
        val posA = output.indexOf("a-file.txt")
        val posZ = output.indexOf("z-file.txt")
        assertThat(posA).isLessThan(posZ)
    }
}
