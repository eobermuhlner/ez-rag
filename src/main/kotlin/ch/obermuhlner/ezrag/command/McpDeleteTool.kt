package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import java.nio.file.Path
import java.nio.file.Paths

/**
 * MCP tool that deletes an ingested document from the unified Lucene index.
 */
class McpDeleteTool(
    private val embeddingModel: EmbeddingModel,
    private val storeDir: Path,
) {

    data class DeleteToolResult(
        val filePath: String,
        val chunksRemoved: Int,
        val error: String? = null
    )

    @Tool(description = "Delete an ingested document from the vector store by its file path.")
    fun delete(
        @ToolParam(description = "Absolute or relative path to the file to delete from the vector store.") filePath: String
    ): DeleteToolResult {
        val absolutePath = Paths.get(filePath).toAbsolutePath().normalize().toString()
        return try {
            LuceneRepository.open(embeddingModel, storeDir, "standard").use { repository ->
                val removed = repository.delete(absolutePath)
                DeleteToolResult(filePath = absolutePath, chunksRemoved = removed)
            }
        } catch (e: Exception) {
            DeleteToolResult(
                filePath = absolutePath,
                chunksRemoved = 0,
                error = "Delete failed: ${e.message}"
            )
        }
    }
}
