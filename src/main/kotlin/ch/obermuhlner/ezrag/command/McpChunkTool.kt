package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import java.nio.file.Path
import java.nio.file.Paths

class McpChunkTool(
    private val embeddingModel: EmbeddingModel,
    private val storeDir: Path,
) {

    data class ChunkResult(
        val chunkIndex: Int,
        val text: String,
        val headingTitle: String? = null,
        val headingLevel: Int? = null,
        val headingPath: List<String>? = null
    )

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class ChunkToolResult(
        val file: String,
        val chunks: List<ChunkResult>,
        val error: String? = null
    )

    @Tool(description = "Retrieve one or more chunks by file path and chunk index. " +
        "chunkIndex values come from prior search, embedding-search, or bm25-search tool results. " +
        "Use the optional window parameter to also retrieve chunks surrounding the target index.")
    fun chunk(
        @ToolParam(description = "Absolute or relative path to the file containing the chunk.") filePath: String,
        @ToolParam(description = "Index of the chunk to retrieve, as returned by search, embedding-search, or bm25-search.") chunkIndex: Int,
        @ToolParam(required = false, description = "Number of chunks before and after the target to include (default 0 when omitted).") window: Int? = null
    ): ChunkToolResult {
        val absolutePath = Paths.get(filePath).toAbsolutePath().normalize().toString()
        val w = window ?: 0
        val fromIndex = maxOf(0, chunkIndex - w)
        val toIndex = chunkIndex + w
        return try {
            LuceneRepository.open(embeddingModel, storeDir, "standard").use { repository ->
                val chunks = repository.getChunkRange(absolutePath, fromIndex, toIndex)
                if (chunks.isEmpty()) {
                    return ChunkToolResult(
                        file = absolutePath,
                        chunks = emptyList(),
                        error = "File not found in store: $absolutePath"
                    )
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
                ChunkToolResult(file = absolutePath, chunks = chunkResults)
            }
        } catch (e: Exception) {
            ChunkToolResult(
                file = absolutePath,
                chunks = emptyList(),
                error = "Chunk retrieval failed: ${e.message}"
            )
        }
    }
}
