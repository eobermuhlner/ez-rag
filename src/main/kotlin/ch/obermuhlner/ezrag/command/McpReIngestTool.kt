package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.ReIngestService
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import java.nio.file.Path

/**
 * MCP tool that re-ingests stale (or all) documents into the vector store via [ReIngestService].
 */
class McpReIngestTool(
    private val embeddingModel: EmbeddingModel,
    private val storeDir: Path,
    private val reIngestServiceFactory: (Int, Int) -> ReIngestService = { chunkSize, chunkOverlap ->
        ReIngestService(embeddingModel, storeDir, chunkSize, chunkOverlap)
    }
) {

    data class ReIngestToolResult(
        val staleFound: Int?,
        val filesReIngested: Int,
        val chunksCreated: Int,
        val filesSkipped: Int,
        val error: String? = null
    )

    @Tool(description = "Re-ingest stale documents in the vector store. By default re-ingests only stale documents (those whose file mtime has changed). Pass forceAll=true to re-ingest every document regardless of staleness.")
    fun reingest(
        @ToolParam(required = false, description = "If true, re-ingest all documents regardless of staleness. Default false (stale only).") forceAll: Boolean?,
        @ToolParam(required = false, description = "Chunk size in characters (default: 1000).") chunkSize: Int?,
        @ToolParam(required = false, description = "Chunk overlap in characters (default: 200).") chunkOverlap: Int?
    ): ReIngestToolResult {
        val cs = chunkSize ?: 1000
        val co = chunkOverlap ?: 200
        val force = forceAll ?: false
        return try {
            val service = reIngestServiceFactory(cs, co)
            val result = service.reIngest(forceAll = force)
            ReIngestToolResult(
                staleFound = result.staleFound,
                filesReIngested = result.filesReIngested,
                chunksCreated = result.chunksCreated,
                filesSkipped = result.filesSkipped
            )
        } catch (e: Exception) {
            ReIngestToolResult(
                staleFound = null,
                filesReIngested = 0,
                chunksCreated = 0,
                filesSkipped = 0,
                error = "ReIngest failed: ${e.message}"
            )
        }
    }
}
