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

        val service = IngestService(fakeEmbeddingModel, tempDir)

        val result = service.ingest(listOf(sampleFile.toFile()))

        assertThat(result.filesIngested).isEqualTo(1)
        assertThat(result.chunksCreated).isGreaterThanOrEqualTo(1)
        assertThat(result.skipped).isEqualTo(0)
    }

    @Test
    fun `ingest skips unchanged file on second call`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world. This is a test document for deduplication.")

        val service1 = IngestService(fakeEmbeddingModel, tempDir)
        val result1 = service1.ingest(listOf(sampleFile.toFile()))
        assertThat(result1.filesIngested).isEqualTo(1)
        assertThat(result1.skipped).isEqualTo(0)

        val service2 = IngestService(fakeEmbeddingModel, tempDir)
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
        val warnings = StringWriter()
        val service = IngestService(fakeEmbeddingModel, tempDir, warningWriter = PrintWriter(warnings, true))

        val result = service.ingest(listOf(nonExistent))

        assertThat(result.filesIngested).isEqualTo(0)
        assertThat(result.chunksCreated).isEqualTo(0)
        assertThat(warnings.toString()).contains("does-not-exist")
    }

    @Test
    fun `ingest warns and skips file with unsupported extension`(@TempDir tempDir: Path) {
        val unsupported = tempDir.resolve("data.csv")
        unsupported.toFile().writeText("col1,col2\nval1,val2")
        val warnings = StringWriter()
        val service = IngestService(fakeEmbeddingModel, tempDir, warningWriter = PrintWriter(warnings, true))

        val result = service.ingest(listOf(unsupported.toFile()))

        assertThat(result.filesIngested).isEqualTo(0)
        assertThat(result.chunksCreated).isEqualTo(0)
        assertThat(warnings.toString()).contains("data.csv")
    }

    @Test
    fun `ingest skips file that produces no chunks without throwing`(@TempDir tempDir: Path) {
        val emptyFile = tempDir.resolve("empty.txt")
        emptyFile.toFile().writeText("")
        val service = IngestService(fakeEmbeddingModel, tempDir)

        val result = service.ingest(listOf(emptyFile.toFile()))

        assertThat(result.filesIngested).isEqualTo(0)
        assertThat(result.chunksCreated).isEqualTo(0)
    }

    @Test
    fun `isAlreadyIngested returns true when called with absolute path after ingesting via relative path`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Content for already-ingested test.")

        val service = IngestService(fakeEmbeddingModel, tempDir)

        val cwdRelative = java.nio.file.Paths.get("").toAbsolutePath().relativize(sampleFile)
        val relativeFile = cwdRelative.toFile()
        service.ingest(listOf(relativeFile))

        val absolutePath = sampleFile.toAbsolutePath().normalize().toString()
        val mtime = sampleFile.toFile().lastModified()

        // Re-ingest using a new service that loads the saved store
        val service2 = IngestService(fakeEmbeddingModel, tempDir)
        val result2 = service2.ingest(listOf(sampleFile.toFile()))

        // Should be skipped (already ingested)
        assertThat(result2.skipped).isEqualTo(1)
        assertThat(result2.filesIngested).isEqualTo(0)
    }

    @Test
    fun `ingest via relative path stores absolute path in source metadata`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world. This is a test document for path normalisation.")

        val service = IngestService(fakeEmbeddingModel, tempDir)

        // Construct a relative File to simulate passing ./docs/file.md from the CWD
        val cwdRelative = java.nio.file.Paths.get("").toAbsolutePath().relativize(sampleFile)
        val relativeFile = cwdRelative.toFile()
        assertThat(relativeFile.isAbsolute).isFalse() // confirm it's actually relative

        service.ingest(listOf(relativeFile))

        val repository = VectorStoreRepository(fakeEmbeddingModel, tempDir)
        repository.load()
        val metadata = repository.getMetadata()

        val absolutePath = sampleFile.toAbsolutePath().normalize().toString()
        assertThat(metadata.documents).anyMatch { it.path == absolutePath }
        // Ensure no relative paths are stored
        assertThat(metadata.documents).allMatch { java.nio.file.Paths.get(it.path).isAbsolute }
    }

    @Test
    fun `ingest adds chunk_index to each chunk in ascending order starting from 0`(@TempDir tempDir: Path) {
        // Create a file large enough to produce multiple chunks
        val content = "Hello world. ".repeat(200)  // ~2600 chars, should produce multiple chunks
        val sampleFile = tempDir.resolve("multi-chunk.txt")
        sampleFile.toFile().writeText(content)

        val service = IngestService(fakeEmbeddingModel, tempDir, chunkSize = 500, chunkOverlap = 0)
        service.ingest(listOf(sampleFile.toFile()))

        // Read back stored chunk indices via repository reflection
        val repository = VectorStoreRepository(fakeEmbeddingModel, tempDir)
        repository.load()
        val absolutePath = sampleFile.toAbsolutePath().normalize().toString()

        // Collect chunk_index values for the file via reflection on the underlying store
        val chunkIndices = collectChunkIndicesForFile(repository, absolutePath)

        assertThat(chunkIndices).isNotEmpty
        assertThat(chunkIndices).hasSizeGreaterThanOrEqualTo(2)
        // chunk_index values should be 0, 1, 2, ... without gaps or duplicates
        val sorted = chunkIndices.sorted()
        assertThat(sorted.first()).isEqualTo(0)
        assertThat(sorted).isEqualTo((0 until sorted.size).toList())
    }

    /** Helper: uses reflection to extract chunk_index values for a given source path. */
    private fun collectChunkIndicesForFile(repository: VectorStoreRepository, absolutePath: String): List<Int> {
        val store = repository.getStore()
        val storeField = org.springframework.ai.vectorstore.SimpleVectorStore::class.java.getDeclaredField("store")
        storeField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val storeMap = storeField.get(store) as Map<String, Any>
        val indices = mutableListOf<Int>()
        for (entry in storeMap.values) {
            val metadataMethod = entry.javaClass.getMethod("getMetadata")
            @Suppress("UNCHECKED_CAST")
            val metadata = metadataMethod.invoke(entry) as? Map<String, Any> ?: continue
            val source = metadata["source"] as? String ?: continue
            if (source != absolutePath) continue
            val idx = metadata["chunk_index"]
            if (idx != null) {
                indices.add(when (idx) {
                    is Int -> idx
                    is Number -> idx.toInt()
                    is String -> idx.toIntOrNull() ?: continue
                    else -> continue
                })
            }
        }
        return indices
    }
}
