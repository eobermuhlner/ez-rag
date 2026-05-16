package ch.obermuhlner.ezrag.rag

import ch.obermuhlner.ezrag.ingestion.VectorStoreRepository
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.vectorstore.SearchRequest
import java.io.PrintWriter

open class EmbeddingSearchPipeline(
    private val repository: VectorStoreRepository,
    private val embeddingModel: EmbeddingModel,
    private val reranker: Reranker? = null,
    private val errWriter: PrintWriter = PrintWriter(System.err, true)
) {

    open fun search(query: SearchQuery): SearchResult {
        val fetchK = if (reranker != null && query.rerankCandidates != null) {
            query.rerankCandidates
        } else {
            query.topK
        }

        val similarDocs = repository.getStore().similaritySearch(
            SearchRequest.builder()
                .query(query.question)
                .topK(fetchK)
                .similarityThreshold(query.minScore)
                .build()
        )

        if (similarDocs.isNullOrEmpty()) {
            return SearchResult(chunks = emptyList())
        }

        val candidates = similarDocs.map { doc ->
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

        if (reranker != null && query.rerankCandidates != null) {
            if (query.verbose) {
                errWriter.println("Reranker: ${reranker.name}")
                errWriter.println("Reranking: ${query.rerankCandidates} candidates → top ${query.topK}")
            }
            val reranked = reranker.rerank(query.question, candidates)
            return SearchResult(chunks = reranked.take(query.topK))
        }

        return SearchResult(chunks = candidates)
    }

    private fun toInt(value: Any): Int? = when (value) {
        is Int -> value
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
}
