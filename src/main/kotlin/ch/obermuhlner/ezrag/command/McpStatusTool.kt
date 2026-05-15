package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.VectorStoreRepository
import org.springframework.ai.tool.annotation.Tool

/**
 * MCP tool that returns structured vector store metadata.
 */
class McpStatusTool(private val repository: VectorStoreRepository) {

    data class DocumentInfo(val path: String, val chunkCount: Int)

    data class StoreStatus(
        val storePath: String,
        val chunkCount: Int,
        val documents: List<DocumentInfo>,
        val error: String? = null
    )

    @Tool(description = "Return metadata about the vector store: path, chunk count, and list of ingested documents.")
    fun status(): StoreStatus {
        return try {
            val metadata = repository.getMetadata()
            StoreStatus(
                storePath = metadata.storePath,
                chunkCount = metadata.chunkCount,
                documents = metadata.documents.map { DocumentInfo(path = it.path, chunkCount = it.chunkCount) }
            )
        } catch (e: Exception) {
            StoreStatus(
                storePath = "",
                chunkCount = 0,
                documents = emptyList(),
                error = "Failed to retrieve store status: ${e.message}"
            )
        }
    }
}
