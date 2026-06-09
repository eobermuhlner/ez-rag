package ch.obermuhlner.ezrag.rag

import com.fasterxml.jackson.annotation.JsonInclude

data class SearchQuery(
    val question: String,
    val topK: Int,
    val minScore: Double,
    val rerankCandidates: Int? = null,
    val verbose: Boolean = false,
    val mode: String = "embedding"
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChunkMatch(
    val path: String,
    val chunkIndex: Int,
    val score: Double,
    val content: String,
    val headingPath: List<String>? = null
)

data class SearchResult(
    val chunks: List<ChunkMatch>,
    val mode: String = "embedding"
)
