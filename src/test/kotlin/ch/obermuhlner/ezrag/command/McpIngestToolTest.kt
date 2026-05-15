package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.IngestResult
import ch.obermuhlner.ezrag.ingestion.IngestService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import java.io.File
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

    private fun makeTool(
        tempDir: Path,
        capturedChunkSizes: MutableList<Int> = mutableListOf(),
        capturedChunkOverlaps: MutableList<Int> = mutableListOf(),
        capturedFiles: MutableList<List<File>> = mutableListOf(),
        resultToReturn: IngestResult = IngestResult(0, 0, 0),
        throwException: Exception? = null
    ): McpIngestTool {
        val storePath = tempDir.resolve("store.json")
        return McpIngestTool(
            embeddingModel = fakeEmbeddingModel,
            storePath = storePath,
            ingestServiceFactory = { chunkSize, chunkOverlap ->
                capturedChunkSizes.add(chunkSize)
                capturedChunkOverlaps.add(chunkOverlap)
                object : IngestService(fakeEmbeddingModel, storePath, chunkSize, chunkOverlap) {
                    override fun ingest(files: List<File>): IngestResult {
                        capturedFiles.add(files)
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
        val capturedFiles = mutableListOf<List<File>>()
        val tool = makeTool(tempDir, capturedChunkSizes, capturedChunkOverlaps, capturedFiles,
            resultToReturn = IngestResult(1, 3, 0))

        val targetPath = tempDir.resolve("docs").toString()
        tool.ingest(targetPath, null, null)

        assertThat(capturedChunkSizes).containsExactly(1000)
        assertThat(capturedChunkOverlaps).containsExactly(200)
        assertThat(capturedFiles[0][0]).isEqualTo(File(targetPath))
    }

    @Test
    fun `ingest with explicit chunkSize and chunkOverlap forwards those values`(@TempDir tempDir: Path) {
        val capturedChunkSizes = mutableListOf<Int>()
        val capturedChunkOverlaps = mutableListOf<Int>()
        val tool = makeTool(tempDir, capturedChunkSizes, capturedChunkOverlaps)

        tool.ingest(tempDir.toString(), 500, 100)

        assertThat(capturedChunkSizes).containsExactly(500)
        assertThat(capturedChunkOverlaps).containsExactly(100)
    }

    @Test
    fun `ingest returns result with filesIngested chunksCreated and skipped fields`(@TempDir tempDir: Path) {
        val tool = makeTool(tempDir, resultToReturn = IngestResult(filesIngested = 3, chunksCreated = 12, skipped = 2))

        val result = tool.ingest(tempDir.toString(), null, null)

        assertThat(result.filesIngested).isEqualTo(3)
        assertThat(result.chunksCreated).isEqualTo(12)
        assertThat(result.skipped).isEqualTo(2)
        assertThat(result.error).isNull()
    }

    @Test
    fun `after successful ingest the vector store file is updated on disk`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world for ingest disk persistence test.")
        val storePath = tempDir.resolve("store.json")

        val tool = McpIngestTool(fakeEmbeddingModel, storePath)
        tool.ingest(sampleFile.toString(), null, null)

        assertThat(storePath.toFile()).exists()
    }

    @Test
    fun `ingest returns structured error response when IngestService throws exception`(@TempDir tempDir: Path) {
        val tool = makeTool(tempDir, throwException = RuntimeException("Disk is full"))

        val result = tool.ingest(tempDir.toString(), null, null)

        assertThat(result.error).isNotNull()
        assertThat(result.error).contains("Disk is full")
        assertThat(result.filesIngested).isEqualTo(0)
        assertThat(result.chunksCreated).isEqualTo(0)
        assertThat(result.skipped).isEqualTo(0)
    }
}
