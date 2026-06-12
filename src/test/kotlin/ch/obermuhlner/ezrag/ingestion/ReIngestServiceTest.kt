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

    private val fakeEmbeddingModel: EmbeddingModel = createFakeModel(4)

    private fun createFakeModel(dim: Int): EmbeddingModel = object : EmbeddingModel {
        override fun call(request: EmbeddingRequest): EmbeddingResponse {
            val embeddings = request.instructions.mapIndexed { idx, _ ->
                Embedding(FloatArray(dim) { 0.1f * (idx + 1) }, idx)
            }
            return EmbeddingResponse(embeddings)
        }
        override fun embed(document: Document): FloatArray = FloatArray(dim) { 0.1f }
        override fun embed(text: String): FloatArray = FloatArray(dim) { 0.1f }
        override fun embedForResponse(texts: List<String>): EmbeddingResponse {
            val embeddings = texts.mapIndexed { idx, _ ->
                Embedding(FloatArray(dim) { 0.1f * (idx + 1) }, idx)
            }
            return EmbeddingResponse(embeddings)
        }
        override fun dimensions(): Int = dim
    }

    private fun ingestFile(storeDir: Path, file: Path, model: EmbeddingModel = fakeEmbeddingModel, chunkSize: Int = 1000, chunkOverlap: Int = 200) {
        LuceneRepository.open(model, storeDir, "standard").use { repo ->
            val service = IngestService(repo, chunkSize, chunkOverlap)
            service.ingest(listOf(file.toFile()))
        }
    }

    /**
     * Opens a LuceneRepository, runs the given block with a ReIngestService, then closes the repository.
     */
    private fun <T> withReIngestService(
        storeDir: Path,
        model: EmbeddingModel = fakeEmbeddingModel,
        warningWriter: PrintWriter = PrintWriter(System.err, true),
        chunkSize: Int = 1000,
        chunkOverlap: Int = 200,
        block: (ReIngestService) -> T
    ): T {
        return LuceneRepository.open(model, storeDir, "standard").use { repo ->
            val service = ReIngestService(repo, chunkSize, chunkOverlap, warningWriter)
            block(service)
        }
    }

    @Test
    fun `ReIngestService can be constructed with an open LuceneRepository`(@TempDir tempDir: Path) {
        val storeDir = tempDir.resolve("store")
        LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard").use { repo ->
            val service = ReIngestService(repo)
            val result = service.reIngest(forceAll = false)
            assertThat(result.staleFound).isEqualTo(0)
            assertThat(result.filesReIngested).isEqualTo(0)
        }
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
        val result = withReIngestService(storeDir, warningWriter = PrintWriter(warnOut, true)) { service ->
            service.reIngest(forceAll = false)
        }

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

        withReIngestService(storeDir) { service ->
            service.reIngest(forceAll = false)
        }

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

        val result = withReIngestService(storeDir) { service ->
            service.reIngest(forceAll = false)
        }

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
        val result = withReIngestService(storeDir, warningWriter = PrintWriter(warnOut, true)) { service ->
            service.reIngest(forceAll = false)
        }

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
        val result = withReIngestService(storeDir, warningWriter = PrintWriter(warnOut, true)) { service ->
            service.reIngest(forceAll = false)
        }

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
        val result = withReIngestService(storeDir) { service ->
            service.reIngest(forceAll = true)
        }

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

        val result = withReIngestService(storeDir) { service ->
            service.reIngest(forceAll = true)
        }

        assertThat(result.staleFound).isNull()
    }

    @Test
    fun `forceAll=true succeeds when embedding dimension has changed`(@TempDir tempDir: Path) {
        val storeDir = tempDir.resolve("store")
        val sourceFile = tempDir.resolve("doc.txt")
        sourceFile.toFile().writeText("content to re-ingest with new model")

        // Ingest with dimension-4 model
        val smallModel = createFakeModel(4)
        ingestFile(storeDir, sourceFile, model = smallModel)

        // Reset stored dimension, then re-ingest with dimension-8 model — must not throw
        val largeModel = createFakeModel(8)
        LuceneRepository.resetStoredDimension(storeDir)
        val result = withReIngestService(storeDir, model = largeModel) { service ->
            service.reIngest(forceAll = true)
        }

        assertThat(result.filesReIngested).isEqualTo(1)
        assertThat(result.chunksCreated).isGreaterThan(0)
    }

    @Test
    fun `forceAll=true with dimension change replaces index with new dimension`(@TempDir tempDir: Path) {
        val storeDir = tempDir.resolve("store")
        val sourceFile = tempDir.resolve("doc.txt")
        sourceFile.toFile().writeText("dimension migration test content")

        val smallModel = createFakeModel(4)
        ingestFile(storeDir, sourceFile, model = smallModel)

        val largeModel = createFakeModel(8)
        LuceneRepository.resetStoredDimension(storeDir)
        withReIngestService(storeDir, model = largeModel) { service ->
            service.reIngest(forceAll = true)
        }

        // Searching with the new model must work without dimension errors
        LuceneRepository.open(largeModel, storeDir, "standard").use { repo ->
            val chunks = repo.getChunksForFile(sourceFile.toAbsolutePath().normalize().toString())
            assertThat(chunks).isNotEmpty()
        }
    }

    private fun makeFakeFetcher(responseRef: () -> ByteArray, contentType: String = "text/html"): UrlFetcher =
        object : UrlFetcher {
            override fun fetch(url: String) = FetchResult(
                bytes = responseRef(),
                contentType = contentType,
                lastModifiedEpochMs = 0L,
                statusCode = 200
            )
        }

    private fun ingestUrl(storeDir: Path, url: String, fetcher: UrlFetcher) {
        LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard").use { repo ->
            val service = IngestService(repo, urlFetcher = fetcher)
            service.ingest(listOf(UrlSource(url)))
        }
    }

    @Test
    fun `re-ingest re-fetches URL source and reports filesReIngested=1 when content changed`(@TempDir tempDir: Path) {
        val storeDir = tempDir.resolve("store")
        val fakeUrl = "https://example.com/page"

        var fetchBytes = "<html><head><title>Page</title></head><body><p>original content</p></body></html>".toByteArray()
        val fetcher = makeFakeFetcher({ fetchBytes })

        ingestUrl(storeDir, fakeUrl, fetcher)

        fetchBytes = "<html><head><title>Page</title></head><body><p>updated content</p></body></html>".toByteArray()

        val result = LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard").use { repo ->
            ReIngestService(repo, urlFetcher = fetcher).reIngest(forceAll = false)
        }

        assertThat(result.filesReIngested).isEqualTo(1)
        assertThat(result.filesSkipped).isEqualTo(0)
    }

    @Test
    fun `re-ingest skips URL source when content unchanged`(@TempDir tempDir: Path) {
        val storeDir = tempDir.resolve("store")
        val fakeUrl = "https://example.com/page"

        val fetchBytes = "<html><head><title>Page</title></head><body><p>some content</p></body></html>".toByteArray()
        val fetcher = makeFakeFetcher({ fetchBytes })

        ingestUrl(storeDir, fakeUrl, fetcher)

        val result = LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard").use { repo ->
            ReIngestService(repo, urlFetcher = fetcher).reIngest(forceAll = false)
        }

        assertThat(result.filesReIngested).isEqualTo(0)
        assertThat(result.filesSkipped).isEqualTo(1)
    }

    @Test
    fun `re-ingest warns and skips URL source when fetch fails leaving old chunks intact`(@TempDir tempDir: Path) {
        val storeDir = tempDir.resolve("store")
        val fakeUrl = "https://example.com/page"

        val goodFetcher = makeFakeFetcher({
            "<html><head><title>Page</title></head><body><p>some content</p></body></html>".toByteArray()
        })
        ingestUrl(storeDir, fakeUrl, goodFetcher)

        val failingFetcher = object : UrlFetcher {
            override fun fetch(url: String): FetchResult = throw RuntimeException("Network error")
        }

        val warnOut = StringWriter()
        val result = LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard").use { repo ->
            ReIngestService(
                repo,
                warningWriter = PrintWriter(warnOut, true),
                urlFetcher = failingFetcher
            ).reIngest(forceAll = false)
        }

        assertThat(result.filesReIngested).isEqualTo(0)
        assertThat(result.filesSkipped).isEqualTo(1)
        assertThat(warnOut.toString()).contains("WARN:")

        LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard").use { repo ->
            val chunks = repo.getChunksForFile(fakeUrl)
            assertThat(chunks).isNotEmpty()
        }
    }

    @Test
    fun `re-ingest file sources still work correctly alongside URL sources`(@TempDir tempDir: Path) {
        val storeDir = tempDir.resolve("store")
        val sourceFile = tempDir.resolve("doc.txt")
        sourceFile.toFile().writeText("original file content")

        ingestFile(storeDir, sourceFile)

        val futureTime = FileTime.from(Instant.now().plusSeconds(3600))
        Files.setLastModifiedTime(sourceFile, futureTime)
        sourceFile.toFile().writeText("updated file content")

        val result = withReIngestService(storeDir) { service ->
            service.reIngest(forceAll = false)
        }

        assertThat(result.filesReIngested).isEqualTo(1)
        assertThat(result.filesSkipped).isEqualTo(0)
    }
}
