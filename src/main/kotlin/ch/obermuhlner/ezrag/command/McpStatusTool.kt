package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.ai.tool.annotation.Tool

/**
 * MCP tool that returns structured store metadata (health-check only, no document inventory).
 * Use the `list` tool to obtain a per-document inventory with staleness flags.
 */
class McpStatusTool(private val repository: LuceneRepository) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class StoreStatus(
        val storeDirPath: String,
        val chunkCount: Int,
        val error: String? = null
    )

    @Tool(description = "Return store health metadata: store path and total chunk count. For a per-document inventory with staleness flags, use the `list` tool.")
    fun status(): StoreStatus {
        return try {
            val metadata = repository.getMetadata()
            StoreStatus(
                storeDirPath = metadata.storeDirPath,
                chunkCount = metadata.chunkCount
            )
        } catch (e: Exception) {
            StoreStatus(
                storeDirPath = "",
                chunkCount = 0,
                error = "Failed to retrieve store status: ${e.message}"
            )
        }
    }
}
