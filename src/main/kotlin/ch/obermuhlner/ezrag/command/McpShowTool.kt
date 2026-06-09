package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import java.nio.file.Path
import java.nio.file.Paths

/**
 * MCP tool that returns per-chunk metadata (and optionally raw text) for an ingested file.
 */
class McpShowTool(
    private val embeddingModel: EmbeddingModel,
    private val storeDir: Path,
) {

    data class ChunkResult(
        val chunkIndex: Int,
        val charCount: Int,
        val mtime: Long,
        val text: String? = null
    )

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class ShowToolResult(
        val file: String,
        val chunks: List<ChunkResult>,
        val error: String? = null
    )

    @Tool(description = "Show per-chunk metadata for an ingested file. Optionally includes raw chunk text.")
    fun show(
        @ToolParam(description = "Absolute or relative path to the file to inspect.") filePath: String,
        @ToolParam(required = false, description = "If true, include raw chunk text in results. Defaults to false when omitted.") includeChunks: Boolean? = null
    ): ShowToolResult {
        val absolutePath = Paths.get(filePath).toAbsolutePath().normalize().toString()
        return try {
            LuceneRepository.open(embeddingModel, storeDir, "standard").use { repository ->
                val chunks = repository.getChunksForFile(absolutePath)
                if (chunks.isEmpty()) {
                    return ShowToolResult(
                        file = absolutePath,
                        chunks = emptyList(),
                        error = "File not found in store: $absolutePath"
                    )
                }
                val chunkResults = chunks.map { chunk ->
                    ChunkResult(
                        chunkIndex = chunk.chunkIndex,
                        charCount = chunk.charCount,
                        mtime = chunk.mtime,
                        text = if (includeChunks == true) chunk.text else null
                    )
                }
                ShowToolResult(file = absolutePath, chunks = chunkResults)
            }
        } catch (e: Exception) {
            ShowToolResult(
                file = absolutePath,
                chunks = emptyList(),
                error = "Show failed: ${e.message}"
            )
        }
    }
}
