package ch.obermuhlner.ezrag.rag

data class SearchQuery(
    val question: String,
    val topK: Int,
    val minScore: Double,
    val rerankCandidates: Int? = null,
    val verbose: Boolean = false
)

data class ChunkMatch(
    val filePath: String,
    val chunkIndex: Int,
    val score: Double,
    val content: String
)

data class SearchResult(
    val chunks: List<ChunkMatch>
)
