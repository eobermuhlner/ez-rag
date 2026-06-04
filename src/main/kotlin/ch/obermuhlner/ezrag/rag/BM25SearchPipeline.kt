package ch.obermuhlner.ezrag.rag

import ch.obermuhlner.ezrag.ingestion.LuceneRepository

open class BM25SearchPipeline(
    private val luceneRepository: LuceneRepository
) {
    open fun search(query: SearchQuery): SearchResult {
        val docs = luceneRepository.bm25Search(query.question, query.topK)
        val chunks = docs.map { doc ->
            val filePath = doc.metadata["source"] as? String ?: ""
            val chunkIndex = (doc.metadata["chunk_index"] as? Number)?.toInt() ?: 0
            val score = (doc.metadata["score"] as? Double) ?: (doc.score ?: 0.0)
            val content = doc.text ?: ""
            ChunkMatch(filePath = filePath, chunkIndex = chunkIndex, score = score, content = content)
        }
        return SearchResult(chunks = chunks.filter { it.score >= query.minScore }, mode = "bm25")
    }
}
