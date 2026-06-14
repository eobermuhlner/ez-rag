package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import ch.obermuhlner.ezrag.rag.ChunkMatch
import ch.obermuhlner.ezrag.rag.HybridSearchPipeline
import ch.obermuhlner.ezrag.rag.SearchQuery
import ch.obermuhlner.ezrag.rag.SearchResult
import com.fasterxml.jackson.databind.ObjectMapper
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

class McpSearchToolTest {

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

    private fun makeStoreConfig(tempDir: Path) = StoreConfig(
        embeddingModel = fakeEmbeddingModel,
        storeDir = tempDir,
        analyzerName = "standard",
        lockTimeoutSeconds = 0,
    )

    /**
     * Creates a [McpSearchTool] backed by a [StoreConfig] that uses a stub [HybridSearchPipeline].
     * The stub is created via [searchPipelineFactory] which receives the opened repository.
     */
    private fun makeToolWithStubPipeline(
        tempDir: Path,
        capturedQueries: MutableList<SearchQuery> = mutableListOf(),
        resultToReturn: SearchResult = SearchResult(emptyList()),
        throwException: Exception? = null
    ): McpSearchTool {
        // Ensure the store exists so openWithRetry can open it
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { }

        return McpSearchTool(
            storeConfig = makeStoreConfig(tempDir),
            searchPipelineFactory = { repo ->
                object : HybridSearchPipeline(repo) {
                    override fun search(query: SearchQuery): SearchResult {
                        capturedQueries.add(query)
                        if (throwException != null) throw throwException
                        return resultToReturn
                    }
                }
            }
        )
    }

    @Test
    fun `search with only question uses default topK and minScore`(@TempDir tempDir: Path) {
        val capturedQueries = mutableListOf<SearchQuery>()
        val tool = makeToolWithStubPipeline(tempDir, capturedQueries)

        val result = tool.search("What is RAG?", null, null)

        assertThat(capturedQueries).hasSize(1)
        assertThat(capturedQueries[0].question).isEqualTo("What is RAG?")
        assertThat(capturedQueries[0].topK).isEqualTo(5)
        assertThat(capturedQueries[0].minScore).isEqualTo(0.0)
        assertThat(result.chunks).isEmpty()
    }

    @Test
    fun `search with explicit topK and minScore forwards those values`(@TempDir tempDir: Path) {
        val capturedQueries = mutableListOf<SearchQuery>()
        val chunk = ChunkMatch(path = "doc.txt", chunkIndex = 0, score = 0.9, text = "Relevant content")
        val tool = makeToolWithStubPipeline(tempDir, capturedQueries, SearchResult(listOf(chunk)))

        val result = tool.search("What is RAG?", 10, 0.75)

        assertThat(capturedQueries[0].topK).isEqualTo(10)
        assertThat(capturedQueries[0].minScore).isEqualTo(0.75)
        assertThat(result.chunks).hasSize(1)
        assertThat(result.chunks[0].path).isEqualTo("doc.txt")
        assertThat(result.chunks[0].score).isEqualTo(0.9)
        assertThat(result.chunks[0].text).isEqualTo("Relevant content")
    }

    @Test
    fun `search returns result with chunks field from SearchResult`(@TempDir tempDir: Path) {
        val chunks = listOf(
            ChunkMatch(path = "a.txt", chunkIndex = 1, score = 0.8, text = "Content A"),
            ChunkMatch(path = "b.txt", chunkIndex = 0, score = 0.7, text = "Content B"),
        )
        val tool = makeToolWithStubPipeline(tempDir, resultToReturn = SearchResult(chunks))

        val result = tool.search("question", null, null)

        assertThat(result.chunks).hasSize(2)
        assertThat(result.chunks[0].path).isEqualTo("a.txt")
        assertThat(result.chunks[1].path).isEqualTo("b.txt")
    }

    @Test
    fun `search throws exception when pipeline throws exception`(@TempDir tempDir: Path) {
        val tool = makeToolWithStubPipeline(tempDir, throwException = RuntimeException("Search failed catastrophically"))

        val ex = assertThrows<RuntimeException> { tool.search("question", null, null) }

        assertThat(ex.message).contains("Search failed catastrophically")
    }

    @Test
    fun `search headingPath is populated when stub returns chunk with headingPath`(@TempDir tempDir: Path) {
        val headings = listOf("Chapter 1", "Overview")
        val chunk = ChunkMatch(path = "guide.md", chunkIndex = 0, score = 0.9, text = "content", headingPath = headings)
        val tool = makeToolWithStubPipeline(tempDir, resultToReturn = SearchResult(listOf(chunk)))

        val result = tool.search("question", null, null)

        assertThat(result.chunks).hasSize(1)
        assertThat(result.chunks[0].headingPath).isEqualTo(headings)
    }

    @Test
    fun `search headingPath is null when stub returns chunk without headingPath`(@TempDir tempDir: Path) {
        val chunk = ChunkMatch(path = "report.pdf", chunkIndex = 0, score = 0.9, text = "content", headingPath = null)
        val tool = makeToolWithStubPipeline(tempDir, resultToReturn = SearchResult(listOf(chunk)))

        val result = tool.search("question", null, null)

        assertThat(result.chunks).hasSize(1)
        assertThat(result.chunks[0].headingPath).isNull()
    }

    @Test
    fun `successful SearchToolResult serialized to JSON does not contain error key`(@TempDir tempDir: Path) {
        val tool = makeToolWithStubPipeline(tempDir, resultToReturn = SearchResult(emptyList()))
        val result = tool.search("question", null, null)

        val mapper = ObjectMapper()
        val json = mapper.writeValueAsString(result)

        assertThat(json).doesNotContain("\"error\"")
    }

    @Test
    fun `search result chunk has text field not content`(@TempDir tempDir: Path) {
        val chunk = ChunkMatch(path = "doc.txt", chunkIndex = 0, score = 0.9, text = "Relevant content")
        val tool = makeToolWithStubPipeline(tempDir, resultToReturn = SearchResult(listOf(chunk)))

        val result = tool.search("question", null, null)

        assertThat(result.chunks[0].text).isEqualTo("Relevant content")
    }

}
