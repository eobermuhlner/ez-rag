package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.IngestService
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import java.io.File
import java.nio.file.Path

/**
 * MCP tool that ingests documents into the vector store via [IngestService].
 */
class McpIngestTool(
    private val embeddingModel: EmbeddingModel,
    private val storePath: Path,
    private val ingestServiceFactory: (Int, Int) -> IngestService = { chunkSize, chunkOverlap ->
        IngestService(embeddingModel, storePath, chunkSize, chunkOverlap)
    }
) {

    data class IngestToolResult(
        val filesIngested: Int,
        val chunksCreated: Int,
        val skipped: Int,
        val error: String? = null
    )

    @Tool(description = "Ingest documents from a file or directory path into the vector store. Persists the updated store to disk after each call.")
    fun ingest(
        @ToolParam(description = "Path to a file or directory to ingest.") path: String,
        @ToolParam(required = false, description = "Chunk size in characters (default: 1000).") chunkSize: Int?,
        @ToolParam(required = false, description = "Chunk overlap in characters (default: 200).") chunkOverlap: Int?
    ): IngestToolResult {
        val cs = chunkSize ?: 1000
        val co = chunkOverlap ?: 200
        return try {
            val service = ingestServiceFactory(cs, co)
            val result = service.ingest(listOf(File(path)))
            IngestToolResult(
                filesIngested = result.filesIngested,
                chunksCreated = result.chunksCreated,
                skipped = result.skipped
            )
        } catch (e: Exception) {
            IngestToolResult(
                filesIngested = 0,
                chunksCreated = 0,
                skipped = 0,
                error = "Ingest failed: ${e.message}"
            )
        }
    }
}
