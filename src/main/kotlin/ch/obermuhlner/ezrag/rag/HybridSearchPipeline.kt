package ch.obermuhlner.ezrag.rag

import ch.obermuhlner.ezrag.ingestion.LuceneRepository

/**
 * Hybrid search pipeline that combines BM25 keyword search with embedding (vector) search
 * using Reciprocal Rank Fusion (RRF).
 *
 * Fetches [topK * 2] candidates from each source, fuses them via [RrfFusion], and returns
 * the top [topK] results with normalized scores in [0, 1]. [SearchQuery.minScore] is applied
 * to the fused results after normalization.
 */
open class HybridSearchPipeline(
    private val luceneRepository: LuceneRepository
) {
    open fun search(query: SearchQuery): SearchResult {
        val candidateK = query.topK * 2

        // Fetch candidates from embedding search (ignore minScore for hybrid)
        val embeddingPipeline = EmbeddingSearchPipeline(luceneRepository)
        val embeddingResult = embeddingPipeline.search(
            query.copy(topK = candidateK, minScore = 0.0)
        )

        // Fetch candidates from BM25 search
        val bm25Pipeline = BM25SearchPipeline(luceneRepository)
        val bm25Result = bm25Pipeline.search(query.copy(topK = candidateK))
        val bm25Chunks = bm25Result.chunks

        // Fuse, apply minScore filter, and return top topK
        val fused = RrfFusion.fuse(
            bm25Results = bm25Chunks,
            embeddingResults = embeddingResult.chunks,
            topK = query.topK
        ).filter { it.score >= query.minScore }

        return SearchResult(chunks = fused, mode = "hybrid")
    }
}
