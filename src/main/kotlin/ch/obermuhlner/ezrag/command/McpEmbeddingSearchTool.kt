package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.rag.ChunkMatch
import ch.obermuhlner.ezrag.rag.EmbeddingSearchPipeline
import ch.obermuhlner.ezrag.rag.SearchQuery
import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

/**
 * MCP tool that searches the vector store using embedding (semantic) search and returns matching chunks.
 */
class McpEmbeddingSearchTool(private val pipeline: EmbeddingSearchPipeline) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class SearchToolResult(
        val chunks: List<ChunkMatch>,
        val error: String? = null
    )

    @Tool(name = "search_embedding", description = "Search the vector store using embedding (semantic) search. Returns matching chunks ranked by similarity score.")
    fun searchEmbedding(
        @ToolParam(description = "The search question or query text.") question: String,
        @ToolParam(required = false, description = "Maximum number of results to return (default: 5).") topK: Int?,
        @ToolParam(required = false, description = "Minimum similarity score threshold, 0.0 to 1.0 (default: 0.0).") minScore: Double?
    ): SearchToolResult {
        return try {
            val query = SearchQuery(
                question = question,
                topK = topK ?: 5,
                minScore = minScore ?: 0.0,
                mode = "embedding"
            )
            val result = pipeline.search(query)
            SearchToolResult(chunks = result.chunks)
        } catch (e: Exception) {
            SearchToolResult(
                chunks = emptyList(),
                error = "Search failed: ${e.message}"
            )
        }
    }
}
