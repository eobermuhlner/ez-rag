package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.rag.BM25SearchPipeline
import ch.obermuhlner.ezrag.rag.ChunkMatch
import ch.obermuhlner.ezrag.rag.SearchQuery
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

/**
 * MCP tool that searches the vector store using BM25 (keyword) search and returns matching chunks.
 */
class McpBm25SearchTool(private val pipeline: BM25SearchPipeline) {

    data class SearchToolResult(
        val chunks: List<ChunkMatch>,
        val error: String? = null
    )

    @Tool(name = "search_bm25", description = "Search the vector store using BM25 keyword search. Returns matching chunks ranked by term frequency.")
    fun searchBm25(
        @ToolParam(description = "The search question or keyword query text.") question: String,
        @ToolParam(required = false, description = "Maximum number of results to return (default: 5).") topK: Int?
    ): SearchToolResult {
        return try {
            val query = SearchQuery(
                question = question,
                topK = topK ?: 5,
                minScore = 0.0,
                mode = "bm25"
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
