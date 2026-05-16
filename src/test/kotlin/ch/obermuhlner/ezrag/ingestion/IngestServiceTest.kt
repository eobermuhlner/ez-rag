package ch.obermuhlner.ezrag.ingestion

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

class IngestServiceTest {

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
    fun `ingest returns IngestResult with correct file and chunk counts`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world. This is a test document for ingestion.")

        val storePath = tempDir.resolve("vector-store.json")
        val service = IngestService(fakeEmbeddingModel, storePath)

        val result = service.ingest(listOf(sampleFile.toFile()))

        assertThat(result.filesIngested).isEqualTo(1)
        assertThat(result.chunksCreated).isGreaterThanOrEqualTo(1)
        assertThat(result.skipped).isEqualTo(0)
    }

    @Test
    fun `ingest skips unchanged file on second call`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world. This is a test document for deduplication.")

        val storePath = tempDir.resolve("vector-store.json")

        val service1 = IngestService(fakeEmbeddingModel, storePath)
        val result1 = service1.ingest(listOf(sampleFile.toFile()))
        assertThat(result1.filesIngested).isEqualTo(1)
        assertThat(result1.skipped).isEqualTo(0)

        val service2 = IngestService(fakeEmbeddingModel, storePath)
        val result2 = service2.ingest(listOf(sampleFile.toFile()))
        assertThat(result2.filesIngested).isEqualTo(0)
        assertThat(result2.chunksCreated).isEqualTo(0)
        assertThat(result2.skipped).isEqualTo(1)
    }

    @Test
    fun `ingest has no dependency on picocli`() {
        // Verify IngestService has no picocli dependency - it is a standalone service
        // not tied to the CLI framework
        val constructors = IngestService::class.java.constructors
        val paramTypes = constructors.flatMap { it.parameterTypes.toList() }.map { it.name }
        assertThat(paramTypes).doesNotContain("picocli.CommandLine")
    }

    @Test
    fun `IngestResult data class has filesIngested chunksCreated and skipped fields`() {
        val result = IngestResult(filesIngested = 3, chunksCreated = 10, skipped = 2)
        assertThat(result.filesIngested).isEqualTo(3)
        assertThat(result.chunksCreated).isEqualTo(10)
        assertThat(result.skipped).isEqualTo(2)
    }

    @Test
    fun `ingest warns and skips non-existent path`(@TempDir tempDir: Path) {
        val nonExistent = tempDir.resolve("does-not-exist").toFile()
        val storePath = tempDir.resolve("vector-store.json")
        val warnings = StringWriter()
        val service = IngestService(fakeEmbeddingModel, storePath, warningWriter = PrintWriter(warnings, true))

        val result = service.ingest(listOf(nonExistent))

        assertThat(result.filesIngested).isEqualTo(0)
        assertThat(result.chunksCreated).isEqualTo(0)
        assertThat(warnings.toString()).contains("does-not-exist")
    }

    @Test
    fun `ingest warns and skips file with unsupported extension`(@TempDir tempDir: Path) {
        val unsupported = tempDir.resolve("data.csv")
        unsupported.toFile().writeText("col1,col2\nval1,val2")
        val storePath = tempDir.resolve("vector-store.json")
        val warnings = StringWriter()
        val service = IngestService(fakeEmbeddingModel, storePath, warningWriter = PrintWriter(warnings, true))

        val result = service.ingest(listOf(unsupported.toFile()))

        assertThat(result.filesIngested).isEqualTo(0)
        assertThat(result.chunksCreated).isEqualTo(0)
        assertThat(warnings.toString()).contains("data.csv")
    }

    @Test
    fun `ingest skips file that produces no chunks without throwing`(@TempDir tempDir: Path) {
        val emptyFile = tempDir.resolve("empty.txt")
        emptyFile.toFile().writeText("")
        val storePath = tempDir.resolve("vector-store.json")
        val service = IngestService(fakeEmbeddingModel, storePath)

        val result = service.ingest(listOf(emptyFile.toFile()))

        assertThat(result.filesIngested).isEqualTo(0)
        assertThat(result.chunksCreated).isEqualTo(0)
    }
}
