package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.FileSource
import ch.obermuhlner.ezrag.ingestion.IngestService
import ch.obermuhlner.ezrag.ingestion.IngestSource
import ch.obermuhlner.ezrag.ingestion.JsoupUrlFetcher
import ch.obermuhlner.ezrag.ingestion.UrlFetcher
import ch.obermuhlner.ezrag.ingestion.UrlSource
import com.fasterxml.jackson.annotation.JsonInclude
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
    private val storeDir: Path,
    private val urlFetcher: UrlFetcher = JsoupUrlFetcher(),
    private val ingestServiceFactory: (Int, Int, UrlFetcher) -> IngestService = { chunkSize, chunkOverlap, fetcher ->
        IngestService(embeddingModel, storeDir, chunkSize, chunkOverlap, urlFetcher = fetcher)
    }
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class IngestToolResult(
        val filesIngested: Int,
        val chunksCreated: Int,
        val skipped: Int,
        val error: String? = null
    )

    @Tool(description = "Ingest documents from a file, directory path, or HTTP/HTTPS URL into the vector store. Persists the updated store to disk after each call.")
    fun ingest(
        @ToolParam(description = "Path to a file or directory to ingest, or an HTTP/HTTPS URL to fetch and ingest.") path: String,
        @ToolParam(required = false, description = "Chunk size in characters (default: 1000).") chunkSize: Int?,
        @ToolParam(required = false, description = "Chunk overlap in characters (default: 200).") chunkOverlap: Int?
    ): IngestToolResult {
        val cs = chunkSize ?: 1000
        val co = chunkOverlap ?: 200
        return try {
            val service = ingestServiceFactory(cs, co, urlFetcher)
            val source: IngestSource = if (path.startsWith("http://") || path.startsWith("https://")) {
                UrlSource(path)
            } else {
                FileSource(File(path))
            }
            val result = service.ingest(listOf(source))
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
