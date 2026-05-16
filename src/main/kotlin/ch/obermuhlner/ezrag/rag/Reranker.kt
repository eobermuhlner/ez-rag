package ch.obermuhlner.ezrag.rag

interface Reranker {
    val name: String
    fun rerank(query: String, candidates: List<ChunkMatch>): List<ChunkMatch>
}
