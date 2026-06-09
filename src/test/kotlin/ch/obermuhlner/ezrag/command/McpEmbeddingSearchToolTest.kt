package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import ch.obermuhlner.ezrag.rag.ChunkMatch
import ch.obermuhlner.ezrag.rag.EmbeddingSearchPipeline
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

class McpEmbeddingSearchToolTest {

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

    private fun stubPipeline(
        tempDir: Path,
        capturedQueries: MutableList<SearchQuery> = mutableListOf(),
        resultToReturn: SearchResult = SearchResult(emptyList()),
        throwException: Exception? = null
    ): EmbeddingSearchPipeline {
        val repo = LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard")
        return object : EmbeddingSearchPipeline(repo) {
            override fun search(query: SearchQuery): SearchResult {
                capturedQueries.add(query)
                if (throwException != null) throw throwException
                return resultToReturn
            }
        }
    }

    @Test
    fun `tool declared name is search_embedding`(@TempDir tempDir: Path) {
        val pipeline = stubPipeline(tempDir)
        val tool = McpEmbeddingSearchTool(pipeline)
        val callbacks = org.springframework.ai.support.ToolCallbacks.from(tool)
        val names = callbacks.map { it.toolDefinition.name() }
        assertThat(names).contains("search_embedding")
    }

    @Test
    fun `search_embedding invocation with a query returns embedding stub results`(@TempDir tempDir: Path) {
        val capturedQueries = mutableListOf<SearchQuery>()
        val chunk = ChunkMatch(path = "embed.txt", chunkIndex = 0, score = 0.9, content = "Embedding content")
        val pipeline = stubPipeline(tempDir, capturedQueries, SearchResult(listOf(chunk)))

        val tool = McpEmbeddingSearchTool(pipeline)
        val result = tool.searchEmbedding("embedding query", null, null)

        assertThat(capturedQueries).hasSize(1)
        assertThat(capturedQueries[0].question).isEqualTo("embedding query")
        assertThat(result.chunks).hasSize(1)
        assertThat(result.chunks[0].path).isEqualTo("embed.txt")
        assertThat(result.error).isNull()
    }

    @Test
    fun `search_embedding with explicit topK and minScore forwards the values`(@TempDir tempDir: Path) {
        val capturedQueries = mutableListOf<SearchQuery>()
        val pipeline = stubPipeline(tempDir, capturedQueries)

        val tool = McpEmbeddingSearchTool(pipeline)
        tool.searchEmbedding("query", 10, 0.5)

        assertThat(capturedQueries[0].topK).isEqualTo(10)
        assertThat(capturedQueries[0].minScore).isEqualTo(0.5)
    }

    @Test
    fun `search_embedding returns structured error when pipeline throws`(@TempDir tempDir: Path) {
        val pipeline = stubPipeline(tempDir, throwException = RuntimeException("Embedding exploded"))

        val tool = McpEmbeddingSearchTool(pipeline)
        val result = tool.searchEmbedding("query", null, null)

        assertThat(result.error).isNotNull()
        assertThat(result.error).contains("Embedding exploded")
        assertThat(result.chunks).isEmpty()
    }

    @Test
    fun `search_embedding headingPath is populated when stub returns chunk with headingPath`(@TempDir tempDir: Path) {
        val headings = listOf("API Reference", "Methods")
        val chunk = ChunkMatch(path = "api.md", chunkIndex = 0, score = 0.95, content = "content", headingPath = headings)
        val pipeline = stubPipeline(tempDir, resultToReturn = SearchResult(listOf(chunk)))

        val tool = McpEmbeddingSearchTool(pipeline)
        val result = tool.searchEmbedding("query", null, null)

        assertThat(result.chunks).hasSize(1)
        assertThat(result.chunks[0].headingPath).isEqualTo(headings)
    }

    @Test
    fun `search_embedding headingPath is null when stub returns chunk without headingPath`(@TempDir tempDir: Path) {
        val chunk = ChunkMatch(path = "plain.txt", chunkIndex = 0, score = 0.95, content = "content", headingPath = null)
        val pipeline = stubPipeline(tempDir, resultToReturn = SearchResult(listOf(chunk)))

        val tool = McpEmbeddingSearchTool(pipeline)
        val result = tool.searchEmbedding("query", null, null)

        assertThat(result.chunks).hasSize(1)
        assertThat(result.chunks[0].headingPath).isNull()
    }
}
