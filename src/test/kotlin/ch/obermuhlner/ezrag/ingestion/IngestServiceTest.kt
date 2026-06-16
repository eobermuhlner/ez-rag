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
    fun `ingest ingests file with unknown extension containing plain text`(@TempDir tempDir: Path) {
        val unknownExt = tempDir.resolve("data.odt")
        unknownExt.toFile().writeText("This is plain text content in an unknown extension file.")
        withIngestService(tempDir) { service ->
            val result = service.ingest(listOf(unknownExt.toFile()))
            assertThat(result.filesIngested).isEqualTo(1)
            assertThat(result.chunksCreated).isGreaterThanOrEqualTo(1)
            assertThat(result.skipped).isEqualTo(0)
        }
    }

    @Test
    fun `ingest yaml file produces ingested greater than zero and skipped equals zero`(@TempDir tempDir: Path) {
        val yamlFile = tempDir.resolve("config.yaml")
        yamlFile.toFile().writeText("key: value\nother: data\nmore: stuff here for chunking\n")
        withIngestService(tempDir) { service ->
            val result = service.ingest(listOf(yamlFile.toFile()))
            assertThat(result.filesIngested).isEqualTo(1)
            assertThat(result.chunksCreated).isGreaterThanOrEqualTo(1)
            assertThat(result.skipped).isEqualTo(0)
        }
    }

    @Test
    fun `ingest no-extension Makefile produces ingested greater than zero`(@TempDir tempDir: Path) {
        val makefile = tempDir.resolve("Makefile")
        makefile.toFile().writeText("all:\n\techo hello\nbuild:\n\techo building\n")
        withIngestService(tempDir) { service ->
            val result = service.ingest(listOf(makefile.toFile()))
            assertThat(result.filesIngested).isEqualTo(1)
            assertThat(result.chunksCreated).isGreaterThanOrEqualTo(1)
        }
    }

    @Test
    fun `ingest binary file with unknown extension produces skipped equals 1 and ingested equals 0`(@TempDir tempDir: Path) {
        val binaryFile = tempDir.resolve("compiled.obj")
        binaryFile.toFile().writeBytes(byteArrayOf(0x4d, 0x5a, 0x00, 0x01, 0x02, 0x03))
        val warnings = StringWriter()
        withIngestService(tempDir, warningWriter = PrintWriter(warnings, true)) { service ->
            val result = service.ingest(listOf(binaryFile.toFile()))
            assertThat(result.filesIngested).isEqualTo(0)
            assertThat(result.skipped).isEqualTo(1)
            val w = warnings.toString().lowercase()
            assertThat(w).satisfiesAnyOf(
                { s -> assertThat(s as String).contains("binary") },
                { s -> assertThat(s as String).contains("skipping") }
            )
        }
    }

    @Test
    fun `ingest directory with one text yaml and one binary file produces ingested 1 and skipped 1`(@TempDir tempDir: Path, @TempDir storeDir: Path) {
        tempDir.resolve("config.yaml").toFile().writeText("key: value\nother: data\n")
        tempDir.resolve("compiled.bin").toFile().writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        withIngestService(storeDir) { service ->
            val result = service.ingest(listOf(tempDir.toFile()))
            assertThat(result.filesIngested).isEqualTo(1)
            assertThat(result.skipped).isEqualTo(1)
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
    fun `unsupported content type with binary body emits warning and increments skipped`(@TempDir tempDir: Path) {
        val url = "https://example.com/image.png"
        // Body contains a null byte so binary detection fires → skipped
        val binaryBody = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x00, 0x0d, 0x0a, 0x1a)
        val fakeUrlFetcher = makeFakeUrlFetcher(url, contentType = "image/png", bytes = binaryBody)

        val warnings = StringWriter()
        withIngestService(tempDir, warningWriter = PrintWriter(warnings, true), urlFetcher = fakeUrlFetcher) { service ->
            val result = service.ingest(listOf(UrlSource(url)))
            assertThat(result.filesIngested).isEqualTo(0)
            assertThat(result.skipped).isEqualTo(1)
            val w = warnings.toString().lowercase()
            assertThat(w).satisfiesAnyOf(
                { s -> assertThat(s as String).contains("binary") },
                { s -> assertThat(s as String).contains("skipping") }
            )
        }
    }

    @Test
    fun `URL with application_json content type and plain text body produces ingested greater than zero`(@TempDir tempDir: Path) {
        val url = "https://api.example.com/data.json"
        val jsonBody = """{"items":["alpha","beta","gamma","delta","epsilon","zeta","eta","theta"]}""".toByteArray()
        val fakeUrlFetcher = makeFakeUrlFetcher(url, contentType = "application/json", bytes = jsonBody)
        withIngestService(tempDir, urlFetcher = fakeUrlFetcher) { service ->
            val result = service.ingest(listOf(UrlSource(url)))
            assertThat(result.filesIngested).isEqualTo(1)
            assertThat(result.chunksCreated).isGreaterThanOrEqualTo(1)
        }
    }

    @Test
    fun `URL with unrecognised content type and binary body produces skipped equals 1 and warning`(@TempDir tempDir: Path) {
        val url = "https://example.com/data.bin"
        // Body starts with a null byte → binary
        val binaryBody = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04)
        val fakeUrlFetcher = makeFakeUrlFetcher(url, contentType = "application/octet-stream", bytes = binaryBody)

        val warnings = StringWriter()
        withIngestService(tempDir, warningWriter = PrintWriter(warnings, true), urlFetcher = fakeUrlFetcher) { service ->
            val result = service.ingest(listOf(UrlSource(url)))
            assertThat(result.filesIngested).isEqualTo(0)
            assertThat(result.skipped).isEqualTo(1)
            val w = warnings.toString().lowercase()
            assertThat(w).satisfiesAnyOf(
                { s -> assertThat(s as String).contains("binary") },
                { s -> assertThat(s as String).contains("skipping") }
            )
        }
    }

    @Test
    fun `URL text fallback leaves no temp files in injected temp dir after ingest`(@TempDir tempDir: Path, @TempDir urlTempDir: Path) {
        val url = "https://api.example.com/data.json"
        val jsonBody = """{"items":["alpha","beta","gamma","delta","epsilon","zeta","eta","theta"]}""".toByteArray()
        val fakeUrlFetcher = makeFakeUrlFetcher(url, contentType = "application/json", bytes = jsonBody)
        withIngestService(tempDir, urlFetcher = fakeUrlFetcher, tempDirProvider = { urlTempDir }) { service ->
            service.ingest(listOf(UrlSource(url)))
        }

        assertThat(urlTempDir.toFile().listFiles()).isEmpty()
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

    @Test
    fun `URL ingestion results in StoreDocumentInfo with status FRESH within freshness window`(@TempDir tempDir: Path) {
        val url = "https://example.com/page.html"
        val fakeUrlFetcher = makeFakeUrlFetcher(url)
        withIngestService(tempDir, urlFetcher = fakeUrlFetcher) { service ->
            service.ingest(listOf(UrlSource(url)))
        }

        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repo ->
            val metadata = repo.getMetadata(urlFreshnessThresholdMs = 24 * 3_600_000L)
            val doc = metadata.documents.first { it.path == url }
            assertThat(doc.status).isEqualTo("FRESH")
        }
    }

    @Test
    fun `file ingestion results in StoreDocumentInfo status determined by mtime not timer`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("File content for status test.")
        withIngestService(tempDir) { service ->
            service.ingest(listOf(sampleFile.toFile()))
        }

        val absolutePath = sampleFile.toAbsolutePath().normalize().toString()
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repo ->
            val metadata = repo.getMetadata(filesystemProbe = { _ -> sampleFile.toFile().lastModified() })
            val doc = metadata.documents.first { it.path == absolutePath }
            assertThat(doc.status).isEqualTo("FRESH")
        }
    }

    @Test
    fun `single pptx file passed to IngestService is ingested and not skipped`(@TempDir tempDir: Path) {
        ch.obermuhlner.ezrag.ingestion.office.PowerPointFixtureGenerator.createPptxFixture(
            ch.obermuhlner.ezrag.ingestion.office.PowerPointFixtureGenerator.pptxFile
        )
        val pptxFile = ch.obermuhlner.ezrag.ingestion.office.PowerPointFixtureGenerator.pptxFile

        withIngestService(tempDir) { service ->
            val result = service.ingest(listOf(pptxFile))
            assertThat(result.filesIngested).isEqualTo(1)
            assertThat(result.chunksCreated).isGreaterThanOrEqualTo(1)
            assertThat(result.skipped).isEqualTo(0)
        }
    }

    @Test
    fun `directory containing pptx files causes IngestService to discover and ingest them`(@TempDir pptxDir: Path, @TempDir storeDir: Path) {
        ch.obermuhlner.ezrag.ingestion.office.PowerPointFixtureGenerator.createPptxFixture(
            ch.obermuhlner.ezrag.ingestion.office.PowerPointFixtureGenerator.pptxFile
        )
        val sourcePptx = ch.obermuhlner.ezrag.ingestion.office.PowerPointFixtureGenerator.pptxFile

        val pptxInDir = pptxDir.resolve("test.pptx").toFile()
        sourcePptx.copyTo(pptxInDir, overwrite = true)
        pptxDir.resolve("test.txt").toFile().writeText("Some plain text content.")

        withIngestService(storeDir) { service ->
            val result = service.ingest(listOf(pptxDir.toFile()))
            assertThat(result.filesIngested).isEqualTo(2)
            assertThat(result.chunksCreated).isGreaterThanOrEqualTo(2)
        }
    }

    @Test
    fun `single xlsx file passed to IngestService is ingested and not skipped`(@TempDir tempDir: Path) {
        ch.obermuhlner.ezrag.ingestion.office.ExcelFixtureGenerator.createXlsxFixture(
            ch.obermuhlner.ezrag.ingestion.office.ExcelFixtureGenerator.xlsxFile
        )
        val xlsxFile = ch.obermuhlner.ezrag.ingestion.office.ExcelFixtureGenerator.xlsxFile

        withIngestService(tempDir) { service ->
            val result = service.ingest(listOf(xlsxFile))
            assertThat(result.filesIngested).isEqualTo(1)
            assertThat(result.chunksCreated).isGreaterThanOrEqualTo(1)
            assertThat(result.skipped).isEqualTo(0)
        }
    }

    @Test
    fun `directory containing xlsx files causes IngestService to discover and ingest them`(@TempDir xlsxDir: Path, @TempDir storeDir: Path) {
        ch.obermuhlner.ezrag.ingestion.office.ExcelFixtureGenerator.createXlsxFixture(
            ch.obermuhlner.ezrag.ingestion.office.ExcelFixtureGenerator.xlsxFile
        )
        val sourceXlsx = ch.obermuhlner.ezrag.ingestion.office.ExcelFixtureGenerator.xlsxFile

        val xlsxInDir = xlsxDir.resolve("test.xlsx").toFile()
        sourceXlsx.copyTo(xlsxInDir, overwrite = true)
        xlsxDir.resolve("test.txt").toFile().writeText("Some plain text content.")

        withIngestService(storeDir) { service ->
            val result = service.ingest(listOf(xlsxDir.toFile()))
            assertThat(result.filesIngested).isEqualTo(2)
            assertThat(result.chunksCreated).isGreaterThanOrEqualTo(2)
        }
    }

    @Test
    fun `single docx file passed to IngestService is ingested and not skipped`(@TempDir tempDir: Path) {
        // Ensure DOCX fixture exists
        ch.obermuhlner.ezrag.ingestion.office.WordFixtureGenerator.createDocxFixture(
            ch.obermuhlner.ezrag.ingestion.office.WordFixtureGenerator.docxFile
        )
        val docxFile = ch.obermuhlner.ezrag.ingestion.office.WordFixtureGenerator.docxFile

        withIngestService(tempDir) { service ->
            val result = service.ingest(listOf(docxFile))
            assertThat(result.filesIngested).isEqualTo(1)
            assertThat(result.chunksCreated).isGreaterThanOrEqualTo(1)
            assertThat(result.skipped).isEqualTo(0)
        }
    }

    @Test
    fun `directory containing docx files causes IngestService to discover and ingest them`(@TempDir docxDir: Path, @TempDir storeDir: Path) {
        // Ensure DOCX fixture exists
        ch.obermuhlner.ezrag.ingestion.office.WordFixtureGenerator.createDocxFixture(
            ch.obermuhlner.ezrag.ingestion.office.WordFixtureGenerator.docxFile
        )
        val sourceDocx = ch.obermuhlner.ezrag.ingestion.office.WordFixtureGenerator.docxFile

        // Copy the docx fixture into a temp directory
        val docxInDir = docxDir.resolve("test.docx").toFile()
        sourceDocx.copyTo(docxInDir, overwrite = true)
        // Also add a .txt file to confirm mixed-type directories work
        docxDir.resolve("test.txt").toFile().writeText("Some plain text content.")

        withIngestService(storeDir) { service ->
            val result = service.ingest(listOf(docxDir.toFile()))
            // Both the docx and the txt should be ingested
            assertThat(result.filesIngested).isEqualTo(2)
            assertThat(result.chunksCreated).isGreaterThanOrEqualTo(2)
        }
    }

    @Test
    fun `encrypted docx is skipped with a warning when no password is supplied`(@TempDir tempDir: Path) {
        val encryptedFixture = ch.obermuhlner.ezrag.ingestion.office.EncryptedWordFixtureGenerator.encryptedDocxFile
        ch.obermuhlner.ezrag.ingestion.office.EncryptedWordFixtureGenerator.createEncryptedDocxFixture(encryptedFixture)
        val normalFile = tempDir.resolve("normal.txt")
        normalFile.toFile().writeText("This is normal plain text content for the batch test.")

        val warnings = StringWriter()
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repo ->
            // No passwords supplied — encrypted file should be skipped
            val service = IngestService(repo, 1000, 200, PrintWriter(warnings, true))
            val result = service.ingest(listOf(encryptedFixture, normalFile.toFile()))
            // Normal file ingested, encrypted file skipped
            assertThat(result.filesIngested).isEqualTo(1)
            assertThat(result.skipped).isGreaterThanOrEqualTo(1)
            assertThat(warnings.toString()).contains("WARN:")
            assertThat(warnings.toString()).contains("encrypted")
        }
    }

    @Test
    fun `encrypted docx is ingested when correct password is supplied to IngestService`(@TempDir tempDir: Path) {
        val encryptedFixture = ch.obermuhlner.ezrag.ingestion.office.EncryptedWordFixtureGenerator.encryptedDocxFile
        ch.obermuhlner.ezrag.ingestion.office.EncryptedWordFixtureGenerator.createEncryptedDocxFixture(encryptedFixture)

        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repo ->
            val service = IngestService(
                repository = repo,
                passwords = listOf(ch.obermuhlner.ezrag.ingestion.office.EncryptedWordFixtureGenerator.CORRECT_PASSWORD)
            )
            val result = service.ingest(listOf(encryptedFixture))
            assertThat(result.filesIngested).isEqualTo(1)
            assertThat(result.chunksCreated).isGreaterThanOrEqualTo(1)
        }
    }
}
