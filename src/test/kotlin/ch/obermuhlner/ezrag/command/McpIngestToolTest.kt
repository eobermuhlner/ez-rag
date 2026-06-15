package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.FetchResult
import ch.obermuhlner.ezrag.ingestion.FileSource
import ch.obermuhlner.ezrag.ingestion.IngestResult
import ch.obermuhlner.ezrag.ingestion.IngestService
import ch.obermuhlner.ezrag.ingestion.IngestSource
import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import ch.obermuhlner.ezrag.ingestion.UrlFetcher
import ch.obermuhlner.ezrag.ingestion.UrlSource
import ch.obermuhlner.ezrag.ingestion.office.EncryptedWordFixtureGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import java.nio.file.Path

class McpIngestToolTest {

    private val fakeEmbeddingModel: EmbeddingModel = object : EmbeddingModel {
        override fun call(request: EmbeddingRequest): EmbeddingResponse {
            val embeddings = request.instructions.mapIndexed { idx, _ ->
                Embedding(FloatArray(4) { 0.25f }, idx)
            }
            return EmbeddingResponse(embeddings)
        }

        override fun embed(document: Document): FloatArray = FloatArray(4) { 0.25f }
        override fun embed(text: String): FloatArray = FloatArray(4) { 0.25f }

        override fun embedForResponse(texts: List<String>): EmbeddingResponse {
            val embeddings = texts.mapIndexed { idx, _ ->
                Embedding(FloatArray(4) { 0.25f }, idx)
            }
            return EmbeddingResponse(embeddings)
        }

        override fun dimensions(): Int = 4
    }

    private fun makeStoreConfig(storeDir: Path) = StoreConfig(
        embeddingModel = fakeEmbeddingModel,
        storeDir = storeDir,
        analyzerName = "standard",
        lockTimeoutSeconds = 0,
    )

    private fun makeTool(
        tempDir: Path,
        capturedChunkSizes: MutableList<Int> = mutableListOf(),
        capturedChunkOverlaps: MutableList<Int> = mutableListOf(),
        capturedSources: MutableList<List<IngestSource>> = mutableListOf(),
        capturedPasswords: MutableList<List<String>> = mutableListOf(),
        resultToReturn: IngestResult = IngestResult(0, 0, 0),
        throwException: Exception? = null
    ): McpIngestTool {
        // Ensure the store exists for openWithRetry
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { }
        return McpIngestTool(
            storeConfig = makeStoreConfig(tempDir),
            ingestServiceFactory = { repo, chunkSize, chunkOverlap, _, passwords ->
                capturedChunkSizes.add(chunkSize)
                capturedChunkOverlaps.add(chunkOverlap)
                capturedPasswords.add(passwords)
                object : IngestService(repo, chunkSize, chunkOverlap) {
                    override fun ingest(sources: Iterable<IngestSource>): IngestResult {
                        capturedSources.add(sources.toList())
                        if (throwException != null) throw throwException
                        return resultToReturn
                    }
                }
            }
        )
    }

    @Test
    fun `ingest with only path uses default chunkSize and chunkOverlap`(@TempDir tempDir: Path) {
        val capturedChunkSizes = mutableListOf<Int>()
        val capturedChunkOverlaps = mutableListOf<Int>()
        val capturedSources = mutableListOf<List<IngestSource>>()
        val tool = makeTool(tempDir, capturedChunkSizes, capturedChunkOverlaps, capturedSources,
            resultToReturn = IngestResult(1, 3, 0))

        val targetPath = tempDir.resolve("docs").toString()
        tool.ingest(targetPath, null, null, null)

        assertThat(capturedChunkSizes).containsExactly(1000)
        assertThat(capturedChunkOverlaps).containsExactly(200)
        assertThat(capturedSources[0][0]).isEqualTo(FileSource(java.io.File(targetPath)))
    }

    @Test
    fun `ingest with explicit chunkSize and chunkOverlap forwards those values`(@TempDir tempDir: Path) {
        val capturedChunkSizes = mutableListOf<Int>()
        val capturedChunkOverlaps = mutableListOf<Int>()
        val tool = makeTool(tempDir, capturedChunkSizes, capturedChunkOverlaps)

        tool.ingest(tempDir.toString(), 500, 100, null)

        assertThat(capturedChunkSizes).containsExactly(500)
        assertThat(capturedChunkOverlaps).containsExactly(100)
    }

    @Test
    fun `ingest returns result with filesIngested chunksCreated and filesSkipped fields`(@TempDir tempDir: Path) {
        val tool = makeTool(tempDir, resultToReturn = IngestResult(filesIngested = 3, chunksCreated = 12, skipped = 2))

        val result = tool.ingest(tempDir.toString(), null, null, null)

        assertThat(result.filesIngested).isEqualTo(3)
        assertThat(result.chunksCreated).isEqualTo(12)
        assertThat(result.filesSkipped).isEqualTo(2)
    }

    @Test
    fun `after successful ingest the Lucene index is created on disk`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world for ingest disk persistence test.")
        val storeDir = tempDir.resolve("store-dir")
        storeDir.toFile().mkdirs()

        val tool = McpIngestTool(makeStoreConfig(storeDir))
        tool.ingest(sampleFile.toString(), null, null, null)

        assertThat(LuceneRepository.storeExists(storeDir)).isTrue()
    }

    @Test
    fun `ingest throws exception when IngestService throws exception`(@TempDir tempDir: Path) {
        val tool = makeTool(tempDir, throwException = RuntimeException("Disk is full"))

        val ex = assertThrows<RuntimeException> { tool.ingest(tempDir.toString(), null, null, null) }

        assertThat(ex.message).contains("Disk is full")
    }

    // --- Task 05: URL ingestion ---

    @Test
    fun `ingest URL with fake UrlFetcher returns filesIngested 1 and at least one chunk`(@TempDir tempDir: Path) {
        val html = "<html><head><title>Test Page</title></head><body><p>Hello world content to ingest for MCP URL test.</p></body></html>"
        val fakeUrlFetcher = object : UrlFetcher {
            override fun fetch(url: String) = FetchResult(html.toByteArray(), "text/html", 0L, 200)
        }
        val storeDir = tempDir.resolve("store")
        storeDir.toFile().mkdirs()

        val tool = McpIngestTool(makeStoreConfig(storeDir), urlFetcher = fakeUrlFetcher)
        val result = tool.ingest("https://example.com/page.html", null, null, null)
        assertThat(result.filesIngested).isEqualTo(1)
        assertThat(result.chunksCreated).isGreaterThanOrEqualTo(1)
        assertThat(result.filesSkipped).isEqualTo(0)
    }

    @Test
    fun `ingest URL second call with unchanged bytes returns filesSkipped 1`(@TempDir tempDir: Path) {
        val html = "<html><head><title>Test Page</title></head><body><p>Content for unchanged skip test in MCP tool.</p></body></html>"
        val fakeUrlFetcher = object : UrlFetcher {
            override fun fetch(url: String) = FetchResult(html.toByteArray(), "text/html", 0L, 200)
        }
        val storeDir = tempDir.resolve("store")
        storeDir.toFile().mkdirs()

        val config = makeStoreConfig(storeDir)
        McpIngestTool(config, urlFetcher = fakeUrlFetcher)
            .ingest("https://example.com/page.html", null, null, null)

        val result = McpIngestTool(config, urlFetcher = fakeUrlFetcher)
            .ingest("https://example.com/page.html", null, null, null)
        assertThat(result.filesIngested).isEqualTo(0)
        assertThat(result.filesSkipped).isEqualTo(1)
    }

    @Test
    fun `ingest URL when IngestService throws propagates exception`(@TempDir tempDir: Path) {
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { }
        val tool = McpIngestTool(
            storeConfig = makeStoreConfig(tempDir),
            ingestServiceFactory = { repo, cs, co, _, _ ->
                object : IngestService(repo, cs, co) {
                    override fun ingest(sources: Iterable<IngestSource>): IngestResult {
                        throw RuntimeException("Connection refused")
                    }
                }
            }
        )

        val ex = assertThrows<RuntimeException> { tool.ingest("https://unreachable.example.com/", null, null, null) }

        assertThat(ex.message).contains("Connection refused")
    }

    @Test
    fun `ingest file path still works after URL detection was added`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world for file regression test after URL support added.")
        val storeDir = tempDir.resolve("store")
        storeDir.toFile().mkdirs()

        val tool = McpIngestTool(makeStoreConfig(storeDir))
        val result = tool.ingest(sampleFile.toString(), null, null, null)
        assertThat(result.filesIngested).isEqualTo(1)
    }

    @Test
    fun `ingest URL path is passed as UrlSource to IngestService`(@TempDir tempDir: Path) {
        val capturedSources = mutableListOf<List<IngestSource>>()
        val tool = makeTool(tempDir, capturedSources = capturedSources)

        tool.ingest("https://example.com/page.html", null, null, null)

        assertThat(capturedSources[0][0]).isEqualTo(UrlSource("https://example.com/page.html"))
    }

    @Test
    fun `ingest file path is passed as FileSource to IngestService`(@TempDir tempDir: Path) {
        val capturedSources = mutableListOf<List<IngestSource>>()
        val tool = makeTool(tempDir, capturedSources = capturedSources)

        val targetPath = tempDir.resolve("doc.txt").toString()
        tool.ingest(targetPath, null, null, null)

        assertThat(capturedSources[0][0]).isEqualTo(FileSource(java.io.File(targetPath)))
    }

    // --- write-lock regression test ---

    @Test
    fun `ingest with StoreConfig opens and closes repository per call — chunk is retrievable`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("regression.txt")
        sampleFile.toFile().writeText("Write-lock regression test content for StoreConfig ingest.")
        val storeDir = tempDir.resolve("store")
        storeDir.toFile().mkdirs()

        val config = makeStoreConfig(storeDir)
        val tool = McpIngestTool(config)

        val result = tool.ingest(sampleFile.toString(), null, null, null)

        assertThat(result.filesIngested).isEqualTo(1)
        assertThat(result.chunksCreated).isGreaterThanOrEqualTo(1)

        // After tool call, repository is closed — we can open a new one to verify the chunk is there
        LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard").use { repo ->
            val chunks = repo.getChunksForFile(sampleFile.toAbsolutePath().toString())
            assertThat(chunks).isNotEmpty()
        }
    }

    // --- Task 06: passwords parameter ---

    @Test
    fun `ingest with passwords list forwards passwords to IngestService via factory`(@TempDir tempDir: Path) {
        val capturedPasswords = mutableListOf<List<String>>()
        val tool = makeTool(tempDir, capturedPasswords = capturedPasswords)

        tool.ingest(tempDir.toString(), null, null, listOf("secret1", "secret2"))

        assertThat(capturedPasswords).hasSize(1)
        assertThat(capturedPasswords[0]).containsExactly("secret1", "secret2")
    }

    @Test
    fun `ingest without passwords passes empty list to IngestService via factory`(@TempDir tempDir: Path) {
        val capturedPasswords = mutableListOf<List<String>>()
        val tool = makeTool(tempDir, capturedPasswords = capturedPasswords)

        tool.ingest(tempDir.toString(), null, null, null)

        assertThat(capturedPasswords).hasSize(1)
        assertThat(capturedPasswords[0]).isEmpty()
    }

    @Test
    fun `ingest with correct password successfully indexes password-protected Office file`(@TempDir tempDir: Path) {
        EncryptedWordFixtureGenerator.createEncryptedDocxFixture(EncryptedWordFixtureGenerator.encryptedDocxFile)
        val storeDir = tempDir.resolve("store")
        storeDir.toFile().mkdirs()

        val tool = McpIngestTool(makeStoreConfig(storeDir))
        val result = tool.ingest(
            EncryptedWordFixtureGenerator.encryptedDocxFile.absolutePath,
            null,
            null,
            listOf(EncryptedWordFixtureGenerator.CORRECT_PASSWORD)
        )

        assertThat(result.filesIngested).isEqualTo(1)
        assertThat(result.chunksCreated).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `ingest without password on protected file skips file with warning and returns filesSkipped 1`(@TempDir tempDir: Path) {
        EncryptedWordFixtureGenerator.createEncryptedDocxFixture(EncryptedWordFixtureGenerator.encryptedDocxFile)
        val storeDir = tempDir.resolve("store")
        storeDir.toFile().mkdirs()

        val tool = McpIngestTool(makeStoreConfig(storeDir))
        val result = tool.ingest(
            EncryptedWordFixtureGenerator.encryptedDocxFile.absolutePath,
            null,
            null,
            null
        )

        assertThat(result.filesIngested).isEqualTo(0)
        assertThat(result.filesSkipped).isEqualTo(1)
    }
}
