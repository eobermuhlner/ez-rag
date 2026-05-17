package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.IngestService
import ch.obermuhlner.ezrag.ingestion.ReIngestResult
import ch.obermuhlner.ezrag.ingestion.ReIngestService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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

    private fun ingestFile(storeDir: Path, file: Path) {
        val service = IngestService(fakeEmbeddingModel, storeDir)
        service.ingest(listOf(file.toFile()))
    }

    private fun makeTool(
        tempDir: Path,
        resultToReturn: ReIngestResult? = null,
        throwException: Exception? = null
    ): McpReIngestTool {
        return McpReIngestTool(
            embeddingModel = fakeEmbeddingModel,
            storeDir = tempDir,
            reIngestServiceFactory = { cs: Int, co: Int ->
                if (resultToReturn != null || throwException != null) {
                    object : ReIngestService(fakeEmbeddingModel, tempDir, cs, co) {
                        override fun reIngest(forceAll: Boolean): ReIngestResult {
                            if (throwException != null) throw throwException
                            return resultToReturn!!
                        }
                    }
                } else {
                    ReIngestService(fakeEmbeddingModel, tempDir, cs, co)
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

        val tool = McpReIngestTool(fakeEmbeddingModel, storeDir)
        val result = tool.reingest(forceAll = null, chunkSize = null, chunkOverlap = null)

        assertThat(result.filesReIngested).isEqualTo(1)
        assertThat(result.chunksCreated).isGreaterThan(0)
        assertThat(result.filesSkipped).isEqualTo(0)
        assertThat(result.staleFound).isEqualTo(1)
        assertThat(result.error).isNull()
    }

    @Test
    fun `reingest with forceAll=true re-ingests all documents`(@TempDir tempDir: Path) {
        val capturedForceAll = mutableListOf<Boolean>()
        val tool = McpReIngestTool(
            embeddingModel = fakeEmbeddingModel,
            storeDir = tempDir,
            reIngestServiceFactory = { chunkSize, chunkOverlap ->
                object : ReIngestService(fakeEmbeddingModel, tempDir, chunkSize, chunkOverlap) {
                    override fun reIngest(forceAll: Boolean): ReIngestResult {
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
        assertThat(result.error).isNull()
    }

    @Test
    fun `reingest when service throws returns error field and zero counts`(@TempDir tempDir: Path) {
        val tool = makeTool(tempDir, throwException = RuntimeException("Store is corrupt"))

        val result = tool.reingest(forceAll = null, chunkSize = null, chunkOverlap = null)

        assertThat(result.error).isNotNull()
        assertThat(result.error).contains("Store is corrupt")
        assertThat(result.filesReIngested).isEqualTo(0)
        assertThat(result.chunksCreated).isEqualTo(0)
        assertThat(result.filesSkipped).isEqualTo(0)
        assertThat(result.staleFound).isNull()
    }

    @Test
    fun `reingest uses default chunkSize and chunkOverlap when not provided`(@TempDir tempDir: Path) {
        val capturedChunkSizes = mutableListOf<Int>()
        val capturedChunkOverlaps = mutableListOf<Int>()
        val tool = McpReIngestTool(
            embeddingModel = fakeEmbeddingModel,
            storeDir = tempDir,
            reIngestServiceFactory = { chunkSize, chunkOverlap ->
                capturedChunkSizes.add(chunkSize)
                capturedChunkOverlaps.add(chunkOverlap)
                object : ReIngestService(fakeEmbeddingModel, tempDir, chunkSize, chunkOverlap) {
                    override fun reIngest(forceAll: Boolean): ReIngestResult {
                        return ReIngestResult(staleFound = 0, filesReIngested = 0, chunksCreated = 0, filesSkipped = 0)
                    }
                }
            }
        )

        tool.reingest(forceAll = null, chunkSize = null, chunkOverlap = null)

        assertThat(capturedChunkSizes).containsExactly(1000)
        assertThat(capturedChunkOverlaps).containsExactly(200)
    }
}
