package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.rag.ChunkMatch
import ch.obermuhlner.ezrag.rag.HybridSearchPipeline
import ch.obermuhlner.ezrag.rag.SearchQuery
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

/**
 * MCP tool that searches the vector store using hybrid search (BM25 + embedding via RRF)
 * and returns matching chunks.
 */
class McpSearchTool(private val pipeline: HybridSearchPipeline) {

    data class SearchToolResult(
        val chunks: List<ChunkMatch>
    )

    @Tool(description = "Search the vector store for chunks matching the question using hybrid search (BM25 + embedding). Returns matching chunks with their text, score, path, chunkIndex, and optional headingPath. Use the `chunk` tool with chunkIndex to retrieve surrounding context.")
    fun search(
        @ToolParam(description = "The search question or query text.") question: String,
        @ToolParam(required = false, description = "Maximum number of results to return (default: 5).") topK: Int?,
        @ToolParam(required = false, description = "Minimum score threshold, 0.0 to 1.0 (default: 0.0). Filters the final RRF-fused scores, which are normalized to 0–1.") minScore: Double?
    ): SearchToolResult {
        val query = SearchQuery(
            question = question,
            topK = topK ?: 5,
            minScore = minScore ?: 0.0,
            mode = "hybrid"
        )
        val result = pipeline.search(query)
        return SearchToolResult(chunks = result.chunks)
    }
}
