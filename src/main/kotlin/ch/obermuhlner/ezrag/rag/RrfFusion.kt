package ch.obermuhlner.ezrag.rag

/**
 * Stateless Reciprocal Rank Fusion (RRF) implementation.
 *
 * Combines two ranked lists of [ChunkMatch] results into a single fused list
 * using the formula: score(chunk) = 1/(k + rank_bm25) + 1/(k + rank_embedding)
 *
 * Chunks appearing in only one list receive the worst rank (list size + 1) for
 * the list they are absent from.
 */
object RrfFusion {

    /**
     * Fuse BM25 and embedding result lists using RRF scoring.
     *
     * @param bm25Results Ranked list of BM25 results (index 0 = rank 1)
     * @param embeddingResults Ranked list of embedding results (index 0 = rank 1)
     * @param k RRF constant (default 60)
     * @param topK Maximum number of results to return
     * @return Fused, deduplicated, sorted-descending list of up to [topK] results
     */
    fun fuse(
        bm25Results: List<ChunkMatch>,
        embeddingResults: List<ChunkMatch>,
        k: Int = 60,
        topK: Int
    ): List<ChunkMatch> {
        // Build rank maps: key = (path, chunkIndex) → 1-based rank
        val bm25Ranks = bm25Results.mapIndexed { idx, chunk ->
            Pair(chunk.path, chunk.chunkIndex) to (idx + 1)
        }.toMap()
        val embRanks = embeddingResults.mapIndexed { idx, chunk ->
            Pair(chunk.path, chunk.chunkIndex) to (idx + 1)
        }.toMap()

        // Collect all unique chunk keys across both lists, keeping a representative ChunkMatch
        val allChunks: MutableMap<Pair<String, Int>, ChunkMatch> = mutableMapOf()
        for (chunk in bm25Results) {
            allChunks.putIfAbsent(Pair(chunk.path, chunk.chunkIndex), chunk)
        }
        for (chunk in embeddingResults) {
            allChunks.putIfAbsent(Pair(chunk.path, chunk.chunkIndex), chunk)
        }

        // worst rank for each list when a chunk is absent: total unique chunks + 1
        val totalUnique = allChunks.size
        val bm25WorstRank = totalUnique + 1
        val embWorstRank = totalUnique + 1

        // Compute RRF score for each unique chunk, then normalize to [0, 1]
        // Maximum possible score is when ranked #1 in both lists: 2/(k+1)
        val maxPossibleScore = 2.0 / (k + 1)
        val scored = allChunks.entries.map { (key, chunk) ->
            val rankBm25 = bm25Ranks[key] ?: bm25WorstRank
            val rankEmb  = embRanks[key]  ?: embWorstRank
            val rrfScore = 1.0 / (k + rankBm25) + 1.0 / (k + rankEmb)
            chunk.copy(score = rrfScore / maxPossibleScore)
        }

        return scored
            .sortedByDescending { it.score }
            .take(topK)
    }
}
