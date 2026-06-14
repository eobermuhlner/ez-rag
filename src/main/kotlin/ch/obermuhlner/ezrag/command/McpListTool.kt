package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import org.springframework.ai.tool.annotation.Tool
import java.nio.file.Files
import java.nio.file.Paths

/**
 * MCP tool that returns a per-document inventory with path, chunk count, and staleness status.
 * Opens a repository per-request via [StoreConfig] so the write lock is not held between calls.
 */
class McpListTool(
    private val storeConfig: StoreConfig,
    private val filesystemProbe: (String) -> Long? = { path ->
        try {
            Files.getLastModifiedTime(Paths.get(path)).toMillis()
        } catch (_: Exception) {
            null
        }
    },
    private val urlFreshnessThresholdMs: Long = 24 * 3_600_000L,
) {

    data class DocumentInfo(val path: String, val chunkCount: Int, val status: String)

    @Tool(description = "List all ingested documents with their chunk count and freshness status. A document is stale when its source file has been modified since last ingest. URL-based entries within the freshness window appear as FRESH; those outside it appear as STALE. Use `reingest` to refresh stale documents.")
    fun list(): List<DocumentInfo> {
        LuceneRepository.openWithRetry(
            storeConfig.embeddingModel,
            storeConfig.storeDir,
            storeConfig.analyzerName,
            storeConfig.lockTimeoutSeconds,
        ).use { repository ->
            val metadata = repository.getMetadata(
                filesystemProbe = filesystemProbe,
                urlFreshnessThresholdMs = urlFreshnessThresholdMs,
            )
            return metadata.documents.map { doc ->
                DocumentInfo(path = doc.path, chunkCount = doc.chunkCount, status = doc.status)
            }
        }
    }
}
