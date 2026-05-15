package ch.obermuhlner.ezrag.rag

import ch.obermuhlner.ezrag.ingestion.VectorStoreRepository
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.vectorstore.SearchRequest

open class EmbeddingSearchPipeline(
    private val repository: VectorStoreRepository,
    private val embeddingModel: EmbeddingModel
) {

    open fun search(query: SearchQuery): SearchResult {
        val similarDocs = repository.getStore().similaritySearch(
            SearchRequest.builder()
                .query(query.question)
                .topK(query.topK)
                .similarityThreshold(query.minScore)
                .build()
        )

        if (similarDocs.isNullOrEmpty()) {
            return SearchResult(chunks = emptyList())
        }

        val chunks = similarDocs.map { doc ->
            val filePath = doc.metadata["source"] as? String ?: ""
            val chunkIndex = doc.metadata["chunk_index"]?.let { toInt(it) } ?: 0
            val score = doc.score ?: 0.0
            val content = doc.text ?: ""
            ChunkMatch(
                filePath = filePath,
                chunkIndex = chunkIndex,
                score = score,
                content = content
            )
        }.sortedByDescending { it.score }

        return SearchResult(chunks = chunks)
    }

    private fun toInt(value: Any): Int? = when (value) {
        is Int -> value
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
}
