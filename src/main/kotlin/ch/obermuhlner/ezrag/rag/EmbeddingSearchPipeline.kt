package ch.obermuhlner.ezrag.rag

import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import java.io.PrintWriter

open class EmbeddingSearchPipeline(
    private val luceneRepository: LuceneRepository,
    private val reranker: Reranker? = null,
    private val errWriter: PrintWriter = PrintWriter(System.err, true),
) {

    open fun search(query: SearchQuery): SearchResult {
        val fetchK = if (reranker != null && query.rerankCandidates != null) {
            query.rerankCandidates
        } else {
            query.topK
        }

        val docs = luceneRepository.semanticSearch(query.question, fetchK)
        if (docs.isEmpty()) return SearchResult(chunks = emptyList())
        val candidates = docs.map { doc ->
            val filePath = doc.metadata["source"] as? String ?: ""
            val chunkIndex = doc.metadata["chunk_index"]?.let { toInt(it) } ?: 0
            val score = (doc.metadata["score"] as? Double) ?: (doc.score ?: 0.0)
            val content = doc.text ?: ""
            @Suppress("UNCHECKED_CAST")
            val headingPath = doc.metadata["heading_path"] as? List<String>
            ChunkMatch(path = filePath, chunkIndex = chunkIndex, score = score, text = content, headingPath = headingPath)
        }.filter { it.score >= query.minScore }.sortedByDescending { it.score }

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
