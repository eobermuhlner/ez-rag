package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import org.springframework.ai.tool.annotation.Tool
import java.nio.file.Files
import java.nio.file.Paths

/**
 * MCP tool that returns a per-document inventory with path, chunk count, and staleness status.
 * Delegates staleness detection to LuceneRepository.getMetadata(filesystemProbe).
 */
class McpListTool(
    private val repository: LuceneRepository,
    private val filesystemProbe: (String) -> Long? = { path ->
        try {
            Files.getLastModifiedTime(Paths.get(path)).toMillis()
        } catch (_: Exception) {
            null
        }
    }
) {

    data class DocumentInfo(val path: String, val chunkCount: Int, val stale: Boolean)

    @Tool(description = "List all ingested documents with their chunk count and staleness status. A document is stale when its source file has been modified since last ingest. URL-based entries cannot be checked for staleness and always appear as not stale. Use `reingest` to refresh stale documents.")
    fun list(): List<DocumentInfo> {
        val metadata = repository.getMetadata(filesystemProbe)
        return metadata.documents.map { doc ->
            DocumentInfo(path = doc.path, chunkCount = doc.chunkCount, stale = doc.stale)
        }
    }
}
