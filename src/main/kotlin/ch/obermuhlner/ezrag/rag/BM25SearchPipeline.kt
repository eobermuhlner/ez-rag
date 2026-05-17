package ch.obermuhlner.ezrag.rag

import ch.obermuhlner.ezrag.ingestion.BM25Repository

open class BM25SearchPipeline(
    private val bm25Repository: BM25Repository
) {
    open fun search(query: SearchQuery): SearchResult {
        val chunks = bm25Repository.search(query.question, query.topK)
        return SearchResult(chunks = chunks, mode = "bm25")
    }
}
