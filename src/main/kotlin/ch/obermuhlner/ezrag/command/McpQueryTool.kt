package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.rag.RagPipeline
import ch.obermuhlner.ezrag.rag.RagQuery
import ch.obermuhlner.ezrag.rag.SourceReference
import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam

/**
 * MCP tool that delegates to [RagPipeline] and returns a RAG answer with source references.
 */
class McpQueryTool(private val pipeline: RagPipeline) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class QueryToolResult(
        val answer: String,
        val sources: List<SourceReference>,
        val error: String? = null
    )

    @Tool(description = "Query the vector store using RAG. Returns an LLM-generated answer together with source references.")
    fun query(
        @ToolParam(description = "The question to ask.") question: String,
        @ToolParam(required = false, description = "Maximum number of chunks to retrieve (default: 5).") topK: Int?,
        @ToolParam(required = false, description = "Override the LLM provider for this query.") provider: String?,
        @ToolParam(required = false, description = "Override the chat model for this query.") model: String?
    ): QueryToolResult {
        return try {
            val ragQuery = RagQuery(
                question = question,
                topK = topK ?: 5,
                systemPrompt = "",
                modelOverride = model
            )
            val result = pipeline.query(ragQuery)
            QueryToolResult(answer = result.answer, sources = result.sources)
        } catch (e: Exception) {
            QueryToolResult(
                answer = "",
                sources = emptyList(),
                error = "Query failed: ${e.message}"
            )
        }
    }
}
