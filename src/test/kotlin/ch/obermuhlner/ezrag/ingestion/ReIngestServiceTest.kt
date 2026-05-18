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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

class ReIngestServiceTest {

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

    private fun ingestFile(storeDir: Path, file: Path, chunkSize: Int = 1000, chunkOverlap: Int = 200) {
        val service = IngestService(fakeEmbeddingModel, storeDir, chunkSize, chunkOverlap)
        service.ingest(listOf(file.toFile()))
    }

    private fun createReIngestService(
        storeDir: Path,
        warningWriter: PrintWriter = PrintWriter(System.err, true),
        chunkSize: Int = 1000,
        chunkOverlap: Int = 200
    ): ReIngestService {
        return ReIngestService(fakeEmbeddingModel, storeDir, chunkSize, chunkOverlap, warningWriter)
    }

    @Test
    fun `re-ingesting a stale document replaces its chunks`(@TempDir tempDir: Path) {
        val storeDir = tempDir.resolve("store")
        val sourceFile = tempDir.resolve("doc.txt")
        sourceFile.toFile().writeText("original content for testing re-ingest")

        // First ingest
        ingestFile(storeDir, sourceFile)

        // Advance mtime to make the file stale
        val futureTime = FileTime.from(Instant.now().plusSeconds(3600))
        Files.setLastModifiedTime(sourceFile, futureTime)
        // Update file content
        sourceFile.toFile().writeText("updated content after modification")

        val warnOut = StringWriter()
        val service = createReIngestService(storeDir, PrintWriter(warnOut, true))
        val result = service.reIngest(forceAll = false)

        assertThat(result.filesReIngested).isEqualTo(1)
        assertThat(result.chunksCreated).isGreaterThan(0)
        assertThat(result.filesSkipped).isEqualTo(0)
        assertThat(result.staleFound).isEqualTo(1)
    }

    @Test
    fun `after re-ingestion search returns new content and not old content`(@TempDir tempDir: Path) {
        val storeDir = tempDir.resolve("store")
        val sourceFile = tempDir.resolve("doc.txt")
        sourceFile.toFile().writeText("The capital of France is Paris")

        // First ingest
        ingestFile(storeDir, sourceFile)

        // Advance mtime and update content
        val futureTime = FileTime.from(Instant.now().plusSeconds(3600))
        Files.setLastModifiedTime(sourceFile, futureTime)
        sourceFile.toFile().writeText("The capital of Germany is Berlin")

        val service = createReIngestService(storeDir)
        service.reIngest(forceAll = false)

        // Verify store contents: new content should be present
        LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard").use { repo ->
            val chunks = repo.getChunksForFile(sourceFile.toAbsolutePath().normalize().toString())
            val allText = chunks.joinToString(" ") { it.text }
            assertThat(allText).contains("Berlin")
            assertThat(allText).doesNotContain("Paris")
        }
    }

    @Test
    fun `unchanged document is not re-ingested in stale-only mode`(@TempDir tempDir: Path) {
        val storeDir = tempDir.resolve("store")
        val sourceFile = tempDir.resolve("doc.txt")
        sourceFile.toFile().writeText("some content that will not change")

        // Ingest the file
        ingestFile(storeDir, sourceFile)

        val service = createReIngestService(storeDir)
        val result = service.reIngest(forceAll = false)

        assertThat(result.filesReIngested).isEqualTo(0)
        assertThat(result.filesSkipped).isEqualTo(0)
        assertThat(result.staleFound).isEqualTo(0)
    }

    @Test
    fun `missing source file produces warning on warningWriter and is counted as skipped`(@TempDir tempDir: Path) {
        val storeDir = tempDir.resolve("store")
        val sourceFile = tempDir.resolve("doc.txt")
        sourceFile.toFile().writeText("content that will be deleted from disk")

        // Ingest file
        ingestFile(storeDir, sourceFile)

        // Delete the file from disk so the store entry becomes orphaned
        Files.delete(sourceFile)

        val warnOut = StringWriter()
        val service = createReIngestService(storeDir, PrintWriter(warnOut, true))
        val result = service.reIngest(forceAll = false)

        assertThat(result.filesSkipped).isEqualTo(1)
        assertThat(result.filesReIngested).isEqualTo(0)
        val warnings = warnOut.toString()
        assertThat(warnings).contains("WARN:")
        assertThat(warnings).contains(sourceFile.toAbsolutePath().normalize().toString())
    }

    @Test
    fun `staleFound counts all stale documents including missing files`(@TempDir tempDir: Path) {
        val storeDir = tempDir.resolve("store")

        // Ingest two files
        val file1 = tempDir.resolve("doc1.txt")
        val file2 = tempDir.resolve("doc2.txt")
        val file3 = tempDir.resolve("doc3.txt")
        file1.toFile().writeText("content one")
        file2.toFile().writeText("content two")
        file3.toFile().writeText("content three")
        ingestFile(storeDir, file1)
        ingestFile(storeDir, file2)
        ingestFile(storeDir, file3)

        // Make file1 stale by advancing its mtime
        val futureTime = FileTime.from(Instant.now().plusSeconds(3600))
        Files.setLastModifiedTime(file1, futureTime)
        file1.toFile().writeText("updated content one")

        // Delete file2 from disk (orphaned)
        Files.delete(file2)

        // file3 remains unchanged

        val warnOut = StringWriter()
        val service = createReIngestService(storeDir, PrintWriter(warnOut, true))
        val result = service.reIngest(forceAll = false)

        // staleFound should be 2: file1 (changed mtime) + file2 (missing = stale)
        assertThat(result.staleFound).isEqualTo(2)
        // Only file1 should be re-ingested
        assertThat(result.filesReIngested).isEqualTo(1)
        // file2 is skipped (missing)
        assertThat(result.filesSkipped).isEqualTo(1)
    }

    @Test
    fun `forceAll=true re-ingests all documents including unchanged ones`(@TempDir tempDir: Path) {
        val storeDir = tempDir.resolve("store")
        val file1 = tempDir.resolve("doc1.txt")
        val file2 = tempDir.resolve("doc2.txt")
        file1.toFile().writeText("first document content")
        file2.toFile().writeText("second document content")

        // Ingest both files
        ingestFile(storeDir, file1)
        ingestFile(storeDir, file2)

        // Neither file is stale (mtimes match)
        val service = createReIngestService(storeDir)
        val result = service.reIngest(forceAll = true)

        // Both files should be re-ingested even though neither is stale
        assertThat(result.filesReIngested).isEqualTo(2)
        assertThat(result.chunksCreated).isGreaterThan(0)
        assertThat(result.filesSkipped).isEqualTo(0)
    }

    @Test
    fun `forceAll=true sets staleFound to null`(@TempDir tempDir: Path) {
        val storeDir = tempDir.resolve("store")
        val sourceFile = tempDir.resolve("doc.txt")
        sourceFile.toFile().writeText("some content")

        // Ingest the file
        ingestFile(storeDir, sourceFile)

        val service = createReIngestService(storeDir)
        val result = service.reIngest(forceAll = true)

        assertThat(result.staleFound).isNull()
    }
}
