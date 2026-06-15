package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.IngestService
import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import ch.obermuhlner.ezrag.ingestion.ReIngestResult
import ch.obermuhlner.ezrag.ingestion.ReIngestService
import ch.obermuhlner.ezrag.ingestion.UrlSource
import ch.obermuhlner.ezrag.ingestion.UrlFetcher
import ch.obermuhlner.ezrag.ingestion.FetchResult
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
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

class McpReIngestToolTest {

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

    private fun makeStoreConfig(storeDir: Path) = StoreConfig(
        embeddingModel = fakeEmbeddingModel,
        storeDir = storeDir,
        analyzerName = "standard",
        lockTimeoutSeconds = 0,
    )

    private fun ingestFile(storeDir: Path, file: Path) {
        LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard").use { repo ->
            val service = IngestService(repo)
            service.ingest(listOf(file.toFile()))
        }
    }

    private fun makeTool(
        storeDir: Path,
        resultToReturn: ReIngestResult? = null,
        throwException: Exception? = null,
        capturedPasswords: MutableList<List<String>> = mutableListOf()
    ): McpReIngestTool {
        return McpReIngestTool(
            storeConfig = makeStoreConfig(storeDir),
            reIngestServiceFactory = { repo, cs: Int, co: Int, passwords: List<String> ->
                capturedPasswords.add(passwords)
                if (resultToReturn != null || throwException != null) {
                    object : ReIngestService(repo, cs, co) {
                        override fun reIngest(forceAll: Boolean, urlFreshnessThresholdMs: Long): ReIngestResult {
                            if (throwException != null) throw throwException
                            return resultToReturn!!
                        }
                    }
                } else {
                    ReIngestService(repo, cs, co, passwords = passwords)
                }
            }
        )
    }

    @Test
    fun `reingest with stale document returns correct counts`(@TempDir tempDir: Path) {
        val storeDir = tempDir.resolve("store")
        val sourceFile = tempDir.resolve("doc.txt")
        sourceFile.toFile().writeText("original content for testing reingest MCP tool")

        ingestFile(storeDir, sourceFile)

        // Make file stale by advancing mtime and changing content
        val futureTime = FileTime.from(Instant.now().plusSeconds(3600))
        Files.setLastModifiedTime(sourceFile, futureTime)
        sourceFile.toFile().writeText("updated content after modification")

        val tool = makeTool(storeDir)
        val result = tool.reingest(forceAll = null, chunkSize = null, chunkOverlap = null)

        assertThat(result.filesReIngested).isEqualTo(1)
        assertThat(result.chunksCreated).isGreaterThan(0)
        assertThat(result.filesSkipped).isEqualTo(0)
        assertThat(result.staleFound).isEqualTo(1)
    }

    @Test
    fun `reingest with forceAll=true re-ingests all documents`(@TempDir tempDir: Path) {
        val capturedForceAll = mutableListOf<Boolean>()
        // Ensure store is initialized so openWithRetry can open it
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { }
        val tool = McpReIngestTool(
            storeConfig = makeStoreConfig(tempDir),
            reIngestServiceFactory = { repo, chunkSize, chunkOverlap, _ ->
                object : ReIngestService(repo, chunkSize, chunkOverlap) {
                    override fun reIngest(forceAll: Boolean, urlFreshnessThresholdMs: Long): ReIngestResult {
                        capturedForceAll.add(forceAll)
                        return ReIngestResult(staleFound = null, filesReIngested = 2, chunksCreated = 5, filesSkipped = 0)
                    }
                }
            }
        )

        val result = tool.reingest(forceAll = true, chunkSize = null, chunkOverlap = null)

        assertThat(capturedForceAll).containsExactly(true)
        assertThat(result.filesReIngested).isEqualTo(2)
        assertThat(result.staleFound).isNull()
    }

    @Test
    fun `reingest throws exception when service throws`(@TempDir tempDir: Path) {
        val storeDir = tempDir.resolve("store")
        LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard").use { }
        val tool = makeTool(storeDir, throwException = RuntimeException("Store is corrupt"))

        val ex = assertThrows<RuntimeException> {
            tool.reingest(forceAll = null, chunkSize = null, chunkOverlap = null)
        }

        assertThat(ex.message).contains("Store is corrupt")
    }

    @Test
    fun `reingest uses default chunkSize and chunkOverlap when not provided`(@TempDir tempDir: Path) {
        val capturedChunkSizes = mutableListOf<Int>()
        val capturedChunkOverlaps = mutableListOf<Int>()
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { }
        val tool = McpReIngestTool(
            storeConfig = makeStoreConfig(tempDir),
            reIngestServiceFactory = { repo, chunkSize, chunkOverlap, _ ->
                capturedChunkSizes.add(chunkSize)
                capturedChunkOverlaps.add(chunkOverlap)
                object : ReIngestService(repo, chunkSize, chunkOverlap) {
                    override fun reIngest(forceAll: Boolean, urlFreshnessThresholdMs: Long): ReIngestResult {
                        return ReIngestResult(staleFound = 0, filesReIngested = 0, chunksCreated = 0, filesSkipped = 0)
                    }
                }
            }
        )

        tool.reingest(forceAll = null, chunkSize = null, chunkOverlap = null)

        assertThat(capturedChunkSizes).containsExactly(1000)
        assertThat(capturedChunkOverlaps).containsExactly(200)
    }

    // --- write-lock regression test ---

    @Test
    fun `reingest with StoreConfig opens and closes repository per call`(@TempDir tempDir: Path) {
        val storeDir = tempDir.resolve("store")
        val sourceFile = tempDir.resolve("regression.txt")
        sourceFile.toFile().writeText("Original content for write-lock reingest regression test.")

        ingestFile(storeDir, sourceFile)

        // Modify the file to make it stale
        val futureTime = java.nio.file.attribute.FileTime.from(java.time.Instant.now().plusSeconds(3600))
        Files.setLastModifiedTime(sourceFile, futureTime)
        sourceFile.toFile().writeText("Updated content after modification for reingest regression test.")

        val tool = McpReIngestTool(makeStoreConfig(storeDir))
        val result = tool.reingest(forceAll = null, chunkSize = null, chunkOverlap = null)

        assertThat(result.filesReIngested).isEqualTo(1)
        assertThat(result.staleFound).isEqualTo(1)

        // After tool call, repository is released — we can open a new one
        LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard").use { repo ->
            assertThat(repo.getChunksForFile(sourceFile.toAbsolutePath().toString())).isNotEmpty()
        }
    }

    private fun makeFakeFetcher(responseRef: () -> ByteArray): UrlFetcher =
        object : UrlFetcher {
            override fun fetch(url: String) = FetchResult(
                bytes = responseRef(),
                contentType = "text/html",
                lastModifiedEpochMs = 0L,
                statusCode = 200
            )
        }

    @Test
    fun `FRESH URL is not re-fetched when urlFreshnessThresholdMs is large`(@TempDir tempDir: Path) {
        val storeDir = tempDir.resolve("store")
        val fakeUrl = "https://example.com/page"

        val fetcher = makeFakeFetcher({ "<html><body>content</body></html>".toByteArray() })
        LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard").use { repo ->
            IngestService(repo, urlFetcher = fetcher).ingest(listOf(UrlSource(fakeUrl)))
        }

        val tool = McpReIngestTool(
            storeConfig = makeStoreConfig(storeDir),
            urlFreshnessThresholdMs = 24 * 3_600_000L,
            reIngestServiceFactory = { repo, cs, co, _ ->
                ReIngestService(repo, cs, co, urlFetcher = fetcher)
            }
        )
        val result = tool.reingest(forceAll = null, chunkSize = null, chunkOverlap = null)

        assertThat(result.filesReIngested).isEqualTo(0)
        assertThat(result.staleFound).isEqualTo(0)
    }

    // --- Task 06: passwords parameter ---

    @Test
    fun `reingest with passwords list forwards passwords to ReIngestService via factory`(@TempDir tempDir: Path) {
        val capturedPasswords = mutableListOf<List<String>>()
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { }
        val tool = makeTool(
            storeDir = tempDir,
            resultToReturn = ReIngestResult(staleFound = 0, filesReIngested = 0, chunksCreated = 0, filesSkipped = 0),
            capturedPasswords = capturedPasswords
        )

        tool.reingest(forceAll = null, chunkSize = null, chunkOverlap = null, passwords = listOf("pass1", "pass2"))

        assertThat(capturedPasswords).hasSize(1)
        assertThat(capturedPasswords[0]).containsExactly("pass1", "pass2")
    }

    @Test
    fun `reingest without passwords passes empty list to ReIngestService via factory`(@TempDir tempDir: Path) {
        val capturedPasswords = mutableListOf<List<String>>()
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { }
        val tool = makeTool(
            storeDir = tempDir,
            resultToReturn = ReIngestResult(staleFound = 0, filesReIngested = 0, chunksCreated = 0, filesSkipped = 0),
            capturedPasswords = capturedPasswords
        )

        tool.reingest(forceAll = null, chunkSize = null, chunkOverlap = null, passwords = null)

        assertThat(capturedPasswords).hasSize(1)
        assertThat(capturedPasswords[0]).isEmpty()
    }

    @Test
    fun `reingest with correct password successfully re-ingests password-protected Office file`(@TempDir tempDir: Path) {
        EncryptedWordFixtureGenerator.createEncryptedDocxFixture(EncryptedWordFixtureGenerator.encryptedDocxFile)
        val storeDir = tempDir.resolve("store")

        // First ingest with password so it exists in the store
        LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard").use { repo ->
            IngestService(repo, passwords = listOf(EncryptedWordFixtureGenerator.CORRECT_PASSWORD))
                .ingest(listOf(EncryptedWordFixtureGenerator.encryptedDocxFile))
        }

        // Make the file stale by updating its modification time
        val futureTime = java.nio.file.attribute.FileTime.from(java.time.Instant.now().plusSeconds(3600))
        java.nio.file.Files.setLastModifiedTime(EncryptedWordFixtureGenerator.encryptedDocxFile.toPath(), futureTime)

        val tool = McpReIngestTool(
            storeConfig = makeStoreConfig(storeDir),
            reIngestServiceFactory = { repo, cs, co, passwords ->
                ReIngestService(repo, cs, co, passwords = passwords)
            }
        )
        val result = tool.reingest(forceAll = null, chunkSize = null, chunkOverlap = null, passwords = listOf(EncryptedWordFixtureGenerator.CORRECT_PASSWORD))

        assertThat(result.filesReIngested).isEqualTo(1)
        assertThat(result.chunksCreated).isGreaterThanOrEqualTo(1)
    }
}
