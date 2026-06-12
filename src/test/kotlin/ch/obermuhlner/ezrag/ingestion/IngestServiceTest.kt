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



class IngestServiceTest {

    private val simpleHtml = """
        <html><head><title>Fake Page</title></head>
        <body>
          <h2>Installation</h2>
          <p>Install by running the command below.</p>
        </body></html>
    """.trimIndent()

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

    /** Helper: open a LuceneRepository and run a block with a fresh IngestService. */
    private fun withIngestService(
        storeDir: Path,
        chunkSize: Int = 1000,
        chunkOverlap: Int = 200,
        warningWriter: PrintWriter = PrintWriter(System.err, true),
        urlFetcher: UrlFetcher = JsoupUrlFetcher(),
        tempDirProvider: () -> Path = { Files.createTempDirectory("ez-rag-url-") },
        block: (IngestService) -> Unit,
    ) {
        LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard").use { repo ->
            val service = IngestService(repo, chunkSize, chunkOverlap, warningWriter, urlFetcher, tempDirProvider)
            block(service)
        }
    }

    @Test
    fun `IngestService can be constructed with an open LuceneRepository`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world. Test document for repository-injection constructor.")

        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repo ->
            val service = IngestService(repo, 1000, 200)
            val result = service.ingest(listOf(sampleFile.toFile()))
            assertThat(result.filesIngested).isEqualTo(1)
            assertThat(result.chunksCreated).isGreaterThanOrEqualTo(1)
        }
    }

    @Test
    fun `ingest returns IngestResult with correct file and chunk counts`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world. This is a test document for ingestion.")

        withIngestService(tempDir) { service ->
            val result = service.ingest(listOf(sampleFile.toFile()))
            assertThat(result.filesIngested).isEqualTo(1)
            assertThat(result.chunksCreated).isGreaterThanOrEqualTo(1)
            assertThat(result.skipped).isEqualTo(0)
        }
    }

    @Test
    fun `ingest skips unchanged file on second call`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world. This is a test document for deduplication.")

        withIngestService(tempDir) { service ->
            val result1 = service.ingest(listOf(sampleFile.toFile()))
            assertThat(result1.filesIngested).isEqualTo(1)
            assertThat(result1.skipped).isEqualTo(0)
        }

        withIngestService(tempDir) { service ->
            val result2 = service.ingest(listOf(sampleFile.toFile()))
            assertThat(result2.filesIngested).isEqualTo(0)
            assertThat(result2.chunksCreated).isEqualTo(0)
            assertThat(result2.skipped).isEqualTo(1)
        }
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
        withIngestService(tempDir, warningWriter = PrintWriter(warnings, true)) { service ->
            val result = service.ingest(listOf(nonExistent))
            assertThat(result.filesIngested).isEqualTo(0)
            assertThat(result.chunksCreated).isEqualTo(0)
            assertThat(warnings.toString()).contains("does-not-exist")
        }
    }

    @Test
    fun `ingest warns and skips file with unsupported extension`(@TempDir tempDir: Path) {
        val unsupported = tempDir.resolve("data.csv")
        unsupported.toFile().writeText("col1,col2\nval1,val2")
        val warnings = StringWriter()
        withIngestService(tempDir, warningWriter = PrintWriter(warnings, true)) { service ->
            val result = service.ingest(listOf(unsupported.toFile()))
            assertThat(result.filesIngested).isEqualTo(0)
            assertThat(result.chunksCreated).isEqualTo(0)
            assertThat(warnings.toString()).contains("data.csv")
        }
    }

    @Test
    fun `ingest skips file that produces no chunks without throwing`(@TempDir tempDir: Path) {
        val emptyFile = tempDir.resolve("empty.txt")
        emptyFile.toFile().writeText("")
        withIngestService(tempDir) { service ->
            val result = service.ingest(listOf(emptyFile.toFile()))
            assertThat(result.filesIngested).isEqualTo(0)
            assertThat(result.chunksCreated).isEqualTo(0)
        }
    }

    @Test
    fun `isAlreadyIngested returns true when called with absolute path after ingesting via relative path`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Content for already-ingested test.")

        withIngestService(tempDir) { service ->
            val cwdRelative = java.nio.file.Paths.get("").toAbsolutePath().relativize(sampleFile)
            val relativeFile = cwdRelative.toFile()
            service.ingest(listOf(relativeFile))
        }

        // Re-ingest using a new service that loads the saved store
        withIngestService(tempDir) { service ->
            val result2 = service.ingest(listOf(sampleFile.toFile()))
            // Should be skipped (already ingested)
            assertThat(result2.skipped).isEqualTo(1)
            assertThat(result2.filesIngested).isEqualTo(0)
        }
    }

    @Test
    fun `ingest via relative path stores absolute path in source metadata`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world. This is a test document for path normalisation.")

        withIngestService(tempDir) { service ->
            // Construct a relative File to simulate passing ./docs/file.md from the CWD
            val cwdRelative = java.nio.file.Paths.get("").toAbsolutePath().relativize(sampleFile)
            val relativeFile = cwdRelative.toFile()
            assertThat(relativeFile.isAbsolute).isFalse() // confirm it's actually relative
            service.ingest(listOf(relativeFile))
        }

        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repository ->
            val metadata = repository.getMetadata()

            val absolutePath = sampleFile.toAbsolutePath().normalize().toString()
            assertThat(metadata.documents).anyMatch { it.path == absolutePath }
            // Ensure no relative paths are stored
            assertThat(metadata.documents).allMatch { java.nio.file.Paths.get(it.path).isAbsolute }
        }
    }

    @Test
    fun `ingest skips file when mtime changed but content is unchanged`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("unchanged content for content-hash test")

        withIngestService(tempDir) { service ->
            val result1 = service.ingest(listOf(sampleFile.toFile()))
            assertThat(result1.filesIngested).isEqualTo(1)
        }

        // Change only mtime, not content
        Files.setLastModifiedTime(sampleFile, FileTime.from(Instant.now().plusSeconds(3600)))

        withIngestService(tempDir) { service ->
            val result2 = service.ingest(listOf(sampleFile.toFile()))
            assertThat(result2.filesIngested).isEqualTo(0)
            assertThat(result2.skipped).isEqualTo(1)
        }
    }

    @Test
    fun `ingest re-ingests file when mtime and content both changed`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("original content for change detection")

        withIngestService(tempDir) { service ->
            val result1 = service.ingest(listOf(sampleFile.toFile()))
            assertThat(result1.filesIngested).isEqualTo(1)
        }

        // Change content, then set mtime to a future time to ensure it differs
        sampleFile.toFile().writeText("completely different content that has changed")
        Files.setLastModifiedTime(sampleFile, FileTime.from(Instant.now().plusSeconds(3600)))

        withIngestService(tempDir) { service ->
            val result2 = service.ingest(listOf(sampleFile.toFile()))
            assertThat(result2.filesIngested).isEqualTo(1)
            assertThat(result2.skipped).isEqualTo(0)
        }
    }

    private fun makeFakeUrlFetcher(
        url: String,
        html: String = simpleHtml,
        contentType: String = "text/html",
        lastModifiedEpochMs: Long = 0L,
        statusCode: Int = 200,
        bytes: ByteArray? = null,
    ): UrlFetcher = object : UrlFetcher {
        override fun fetch(fetchUrl: String): FetchResult {
            val responseBytes = bytes ?: html.toByteArray()
            if (fetchUrl == url) return FetchResult(responseBytes, contentType, lastModifiedEpochMs, statusCode)
            throw IllegalArgumentException("Unexpected URL: $fetchUrl")
        }
    }

    @Test
    fun `URL source ingested produces chunk with heading_title matching HTML h2`(@TempDir tempDir: Path) {
        val url = "https://example.com/page.html"
        val fakeUrlFetcher = makeFakeUrlFetcher(url)
        withIngestService(tempDir, urlFetcher = fakeUrlFetcher) { service ->
            val result = service.ingest(listOf(UrlSource(url)))
            assertThat(result.filesIngested).isEqualTo(1)
            assertThat(result.chunksCreated).isGreaterThanOrEqualTo(1)
            assertThat(result.skipped).isEqualTo(0)
        }

        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repo ->
            val chunks = repo.getChunksForFile(url)
            assertThat(chunks).anyMatch { it.headingTitle == "Installation" }
        }
    }

    @Test
    fun `URL source chunks all carry page_title metadata`() {
        // Verify at the HtmlDocumentReader level (used internally by IngestService for URLs)
        val docs = HtmlDocumentReader(simpleHtml).read()
        assertThat(docs).isNotEmpty()
        docs.forEach { doc ->
            assertThat(doc.metadata["page_title"]).isEqualTo("Fake Page")
        }
    }

    @Test
    fun `URL source skipped when raw bytes are unchanged on second ingest`(@TempDir tempDir: Path) {
        val url = "https://example.com/page.html"
        val fakeUrlFetcher = makeFakeUrlFetcher(url)

        withIngestService(tempDir, urlFetcher = fakeUrlFetcher) { service ->
            val result1 = service.ingest(listOf(UrlSource(url)))
            assertThat(result1.filesIngested).isEqualTo(1)
        }

        withIngestService(tempDir, urlFetcher = fakeUrlFetcher) { service ->
            val result2 = service.ingest(listOf(UrlSource(url)))
            assertThat(result2.filesIngested).isEqualTo(0)
            assertThat(result2.skipped).isEqualTo(1)
        }
    }

    @Test
    fun `URL source re-ingested when raw bytes change`(@TempDir tempDir: Path) {
        val url = "https://example.com/page.html"
        val firstFetcher = makeFakeUrlFetcher(url, html = simpleHtml)
        withIngestService(tempDir, urlFetcher = firstFetcher) { service ->
            val result1 = service.ingest(listOf(UrlSource(url)))
            assertThat(result1.filesIngested).isEqualTo(1)
        }

        val changedHtml = simpleHtml.replace("Installation", "Getting Started")
        val secondFetcher = makeFakeUrlFetcher(url, html = changedHtml)
        withIngestService(tempDir, urlFetcher = secondFetcher) { service ->
            val result2 = service.ingest(listOf(UrlSource(url)))
            assertThat(result2.filesIngested).isEqualTo(1)
            assertThat(result2.skipped).isEqualTo(0)
        }
    }

    @Test
    fun `mixed FileSource and UrlSource reports filesIngested = 2`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world. File content for mixed ingest test.")

        val url = "https://example.com/page.html"
        val fakeUrlFetcher = makeFakeUrlFetcher(url)
        withIngestService(tempDir, urlFetcher = fakeUrlFetcher) { service ->
            val result = service.ingest(listOf(FileSource(sampleFile.toFile()), UrlSource(url)))
            assertThat(result.filesIngested).isEqualTo(2)
        }
    }

    @Test
    fun `URL returning HTTP 404 emits warning and increments skipped`(@TempDir tempDir: Path) {
        val url = "https://example.com/missing.html"
        val fakeUrlFetcher = makeFakeUrlFetcher(url, statusCode = 404)

        val warnings = StringWriter()
        withIngestService(tempDir, warningWriter = PrintWriter(warnings, true), urlFetcher = fakeUrlFetcher) { service ->
            val result = service.ingest(listOf(UrlSource(url)))
            assertThat(result.filesIngested).isEqualTo(0)
            assertThat(result.skipped).isEqualTo(1)
            assertThat(warnings.toString()).contains("404")
        }
    }

    @Test
    fun `URL fetch failure emits warning and increments skipped`(@TempDir tempDir: Path) {
        val url = "https://unreachable.example.com/"
        val throwingFetcher = object : UrlFetcher {
            override fun fetch(url: String) = throw java.io.IOException("Connection refused")
        }

        val warnings = StringWriter()
        withIngestService(tempDir, warningWriter = PrintWriter(warnings, true), urlFetcher = throwingFetcher) { service ->
            val result = service.ingest(listOf(UrlSource(url)))
            assertThat(result.filesIngested).isEqualTo(0)
            assertThat(result.skipped).isEqualTo(1)
            assertThat(warnings.toString()).contains("Connection refused")
        }
    }

    @Test
    fun `unsupported content type emits warning and increments skipped`(@TempDir tempDir: Path) {
        val url = "https://example.com/image.png"
        val fakeUrlFetcher = makeFakeUrlFetcher(url, html = "PNG_DATA", contentType = "image/png")

        val warnings = StringWriter()
        withIngestService(tempDir, warningWriter = PrintWriter(warnings, true), urlFetcher = fakeUrlFetcher) { service ->
            val result = service.ingest(listOf(UrlSource(url)))
            assertThat(result.filesIngested).isEqualTo(0)
            assertThat(result.skipped).isEqualTo(1)
            assertThat(warnings.toString()).contains("image/png")
        }
    }

    @Test
    fun `URL with application_pdf content type produces chunks and filesIngested = 1`(@TempDir tempDir: Path, @TempDir pdfTempDir: Path) {
        val url = "https://example.com/doc.pdf"
        val pdfBytes = javaClass.getResourceAsStream("/documents/sample.pdf")!!.readBytes()
        val fakeUrlFetcher = makeFakeUrlFetcher(url, contentType = "application/pdf", bytes = pdfBytes)
        withIngestService(tempDir, urlFetcher = fakeUrlFetcher, tempDirProvider = { pdfTempDir }) { service ->
            val result = service.ingest(listOf(UrlSource(url)))
            assertThat(result.filesIngested).isEqualTo(1)
            assertThat(result.chunksCreated).isGreaterThanOrEqualTo(1)
        }
    }

    @Test
    fun `URL with text_plain content type produces chunks and filesIngested = 1`(@TempDir tempDir: Path) {
        val url = "https://example.com/doc.txt"
        val plainBytes = "This is plain text content for ingestion testing. It has enough words to form a chunk.".toByteArray()
        val fakeUrlFetcher = makeFakeUrlFetcher(url, contentType = "text/plain", bytes = plainBytes)
        withIngestService(tempDir, urlFetcher = fakeUrlFetcher) { service ->
            val result = service.ingest(listOf(UrlSource(url)))
            assertThat(result.filesIngested).isEqualTo(1)
            assertThat(result.chunksCreated).isGreaterThanOrEqualTo(1)
        }
    }

    @Test
    fun `PDF URL leaves no temp files in injected temp dir after ingest`(@TempDir tempDir: Path, @TempDir pdfTempDir: Path) {
        val url = "https://example.com/doc.pdf"
        val pdfBytes = javaClass.getResourceAsStream("/documents/sample.pdf")!!.readBytes()
        val fakeUrlFetcher = makeFakeUrlFetcher(url, contentType = "application/pdf", bytes = pdfBytes)
        withIngestService(tempDir, urlFetcher = fakeUrlFetcher, tempDirProvider = { pdfTempDir }) { service ->
            service.ingest(listOf(UrlSource(url)))
        }

        assertThat(pdfTempDir.toFile().listFiles()).isEmpty()
    }

    @Test
    fun `ingest adds chunk_index to each chunk in ascending order starting from 0`(@TempDir tempDir: Path) {
        // Create a file large enough to produce multiple chunks
        val content = "Hello world. ".repeat(200)  // ~2600 chars, should produce multiple chunks
        val sampleFile = tempDir.resolve("multi-chunk.txt")
        sampleFile.toFile().writeText(content)

        withIngestService(tempDir, chunkSize = 500, chunkOverlap = 0) { service ->
            service.ingest(listOf(sampleFile.toFile()))
        }

        val absolutePath = sampleFile.toAbsolutePath().normalize().toString()

        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repository ->
            val chunks = repository.getChunksForFile(absolutePath)
            val chunkIndices = chunks.map { it.chunkIndex }

            assertThat(chunkIndices).isNotEmpty
            assertThat(chunkIndices).hasSizeGreaterThanOrEqualTo(2)
            // chunk_index values should be 0, 1, 2, ... without gaps or duplicates
            val sorted = chunkIndices.sorted()
            assertThat(sorted.first()).isEqualTo(0)
            assertThat(sorted).isEqualTo((0 until sorted.size).toList())
        }
    }
}
