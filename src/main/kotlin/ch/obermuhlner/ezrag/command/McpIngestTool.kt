package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.FileSource
import ch.obermuhlner.ezrag.ingestion.IngestService
import ch.obermuhlner.ezrag.ingestion.IngestSource
import ch.obermuhlner.ezrag.ingestion.JsoupUrlFetcher
import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import ch.obermuhlner.ezrag.ingestion.UrlFetcher
import ch.obermuhlner.ezrag.ingestion.UrlSource
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import java.io.File

/**
 * MCP tool that ingests documents into the vector store via [IngestService].
 * Opens a repository per-request via [StoreConfig] so the write lock is not held between calls.
 */
class McpIngestTool(
    private val storeConfig: StoreConfig,
    private val urlFetcher: UrlFetcher = JsoupUrlFetcher(),
    private val ingestServiceFactory: (LuceneRepository, Int, Int, UrlFetcher, List<String>) -> IngestService = { repo, chunkSize, chunkOverlap, fetcher, passwords ->
        IngestService(repo, chunkSize, chunkOverlap, urlFetcher = fetcher, passwords = passwords)
    }
) {

    data class IngestToolResult(
        val filesIngested: Int,
        val chunksCreated: Int,
        val filesSkipped: Int
    )

    @Tool(description = "Ingest documents from a file, directory path, or HTTP/HTTPS URL into the vector store. filesSkipped counts files already up-to-date (content unchanged), unsupported formats, or failed URL fetches. Supply passwords to unlock encrypted Office files.")
    fun ingest(
        @ToolParam(description = "Path to ingest: an absolute or relative filesystem path (file or directory), or an HTTP/HTTPS URL.") path: String,
        @ToolParam(required = false, description = "Chunk size in characters (default: 1000).") chunkSize: Int?,
        @ToolParam(required = false, description = "Chunk overlap in characters (default: 200).") chunkOverlap: Int?,
        @ToolParam(required = false, description = "Passwords to try when opening encrypted Office files. Each entry is tried in order until one succeeds.") passwords: List<String>? = null
    ): IngestToolResult {
        val cs = chunkSize ?: 1000
        val co = chunkOverlap ?: 200
        val pwds = passwords ?: emptyList()
        val source: IngestSource = if (path.startsWith("http://") || path.startsWith("https://")) {
            UrlSource(path)
        } else {
            FileSource(File(path))
        }
        LuceneRepository.openWithRetry(
            storeConfig.embeddingModel,
            storeConfig.storeDir,
            storeConfig.analyzerName,
            storeConfig.lockTimeoutSeconds,
        ).use { repository ->
            val service = ingestServiceFactory(repository, cs, co, urlFetcher, pwds)
            val result = service.ingest(listOf(source))
            return IngestToolResult(
                filesIngested = result.filesIngested,
                chunksCreated = result.chunksCreated,
                filesSkipped = result.skipped
            )
        }
    }
}
