package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import java.nio.file.Paths

/**
 * MCP tool that retrieves chunks by file path and index with optional surrounding context.
 * Opens a repository per-request via [StoreConfig] so the write lock is not held between calls.
 */
class McpChunkTool(private val storeConfig: StoreConfig) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class ChunkResult(
        val chunkIndex: Int,
        val text: String,
        val headingTitle: String? = null,
        val headingLevel: Int? = null,
        val headingPath: List<String>? = null
    )

    data class ChunkToolResult(
        val file: String,
        val chunks: List<ChunkResult>
    )

    @Tool(description = "Retrieve a chunk by file path and chunk index, with optional surrounding context. " +
        "chunkIndex values come from prior `search` tool results. " +
        "Use the window parameter to include chunks before and after the target index. " +
        "Returns chunk text with document structure (headings). For document metadata (chunk count, staleness), use the `list` tool.")
    fun chunk(
        @ToolParam(description = "Absolute or relative path to the file containing the chunk.") filePath: String,
        @ToolParam(description = "Index of the chunk to retrieve, as returned by the `search` tool.") chunkIndex: Int,
        @ToolParam(required = false, description = "Number of chunks before and after the target to include (default 0 when omitted).") window: Int? = null
    ): ChunkToolResult {
        val absolutePath = Paths.get(filePath).toAbsolutePath().normalize().toString()
        val w = window ?: 0
        val fromIndex = maxOf(0, chunkIndex - w)
        val toIndex = chunkIndex + w
        LuceneRepository.openWithRetry(
            storeConfig.embeddingModel,
            storeConfig.storeDir,
            storeConfig.analyzerName,
            storeConfig.lockTimeoutSeconds,
        ).use { repository ->
            val chunks = repository.getChunkRange(absolutePath, fromIndex, toIndex)
            if (chunks.isEmpty()) {
                throw IllegalArgumentException("File not found in store: $absolutePath")
            }
            val chunkResults = chunks.map { chunk ->
                ChunkResult(
                    chunkIndex = chunk.chunkIndex,
                    text = chunk.text,
                    headingTitle = chunk.headingTitle,
                    headingLevel = chunk.headingLevel,
                    headingPath = chunk.headingPath
                )
            }
            return ChunkToolResult(file = absolutePath, chunks = chunkResults)
        }
    }
}
