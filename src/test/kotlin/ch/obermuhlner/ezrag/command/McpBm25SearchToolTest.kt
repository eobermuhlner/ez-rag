package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import ch.obermuhlner.ezrag.rag.BM25SearchPipeline
import ch.obermuhlner.ezrag.rag.ChunkMatch
import ch.obermuhlner.ezrag.rag.SearchQuery
import ch.obermuhlner.ezrag.rag.SearchResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import java.nio.file.Path

class McpBm25SearchToolTest {

    private val fakeEmbeddingModel: EmbeddingModel = object : EmbeddingModel {
        override fun call(request: EmbeddingRequest): EmbeddingResponse =
            EmbeddingResponse(request.instructions.mapIndexed { i, _ -> Embedding(FloatArray(4) { 0.25f }, i) })
        override fun embed(document: Document): FloatArray = FloatArray(4) { 0.25f }
        override fun embed(text: String): FloatArray = FloatArray(4) { 0.25f }
        override fun embedForResponse(texts: List<String>): EmbeddingResponse =
            EmbeddingResponse(texts.mapIndexed { i, _ -> Embedding(FloatArray(4) { 0.25f }, i) })
        override fun dimensions(): Int = 4
    }

    private fun stubPipeline(
        storeDir: Path,
        capturedQueries: MutableList<SearchQuery> = mutableListOf(),
        resultToReturn: SearchResult = SearchResult(emptyList(), mode = "bm25"),
        throwException: Exception? = null
    ): BM25SearchPipeline {
        val repo = LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard")
        return object : BM25SearchPipeline(repo) {
            override fun search(query: SearchQuery): SearchResult {
                capturedQueries.add(query)
                if (throwException != null) throw throwException
                return resultToReturn
            }
        }
    }

    @Test
    fun `tool declared name is search_bm25`(@TempDir tempDir: Path) {
        val pipeline = stubPipeline(tempDir)
        val tool = McpBm25SearchTool(pipeline)
        // Use reflection to get the @Tool annotation name
        val method = tool.javaClass.declaredMethods.first { it.name == "searchBm25" }
        val toolAnnotation = method.getAnnotation(org.springframework.ai.tool.annotation.Tool::class.java)
        // Tool name defaults to method name converted to snake_case, but let's verify via ToolCallbacks
        val callbacks = org.springframework.ai.support.ToolCallbacks.from(tool)
        val names = callbacks.map { it.toolDefinition.name() }
        assertThat(names).contains("search_bm25")
    }

    @Test
    fun `search_bm25 invocation with a query returns BM25 stub results`(@TempDir tempDir: Path) {
        val capturedQueries = mutableListOf<SearchQuery>()
        val chunk = ChunkMatch(filePath = "keyword.txt", chunkIndex = 0, score = 1.5, content = "Keyword content")
        val pipeline = stubPipeline(tempDir, capturedQueries, SearchResult(listOf(chunk), mode = "bm25"))

        val tool = McpBm25SearchTool(pipeline)
        val result = tool.searchBm25("keyword query", null)

        assertThat(capturedQueries).hasSize(1)
        assertThat(capturedQueries[0].question).isEqualTo("keyword query")
        assertThat(result.chunks).hasSize(1)
        assertThat(result.chunks[0].filePath).isEqualTo("keyword.txt")
        assertThat(result.error).isNull()
    }

    @Test
    fun `search_bm25 with explicit topK forwards the value`(@TempDir tempDir: Path) {
        val capturedQueries = mutableListOf<SearchQuery>()
        val pipeline = stubPipeline(tempDir, capturedQueries)

        val tool = McpBm25SearchTool(pipeline)
        tool.searchBm25("query", 10)

        assertThat(capturedQueries[0].topK).isEqualTo(10)
    }

    @Test
    fun `search_bm25 returns structured error when pipeline throws`(@TempDir tempDir: Path) {
        val pipeline = stubPipeline(tempDir, throwException = RuntimeException("BM25 exploded"))

        val tool = McpBm25SearchTool(pipeline)
        val result = tool.searchBm25("query", null)

        assertThat(result.error).isNotNull()
        assertThat(result.error).contains("BM25 exploded")
        assertThat(result.chunks).isEmpty()
    }
}
