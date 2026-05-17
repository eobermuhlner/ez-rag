package ch.obermuhlner.ezrag.eval

class EvalMetricsCalculator {

    /**
     * Calculates Recall@k, MRR, and Hit Rate@k for the given list of question results.
     *
     * @param results the list of question results to evaluate
     * @param k the cutoff rank (top-k results considered)
     * @param hardNegativeSourceFilenames the set of filenames of hard-negative documents (for subset metrics)
     * @return EvalMetrics with recall@k, mrr, hitRate@k, and optional hardNegativeMetrics
     */
    fun calculate(
        results: List<EvalQuestionResult>,
        k: Int,
        hardNegativeSourceFilenames: Set<String> = emptySet()
    ): EvalMetrics {
        if (results.isEmpty()) {
            return EvalMetrics(recallAtK = 0.0, mrr = 0.0, hitRateAtK = 0.0)
        }

        val mainMetrics = computeMetrics(results, k)

        val hardNegativeResults = if (hardNegativeSourceFilenames.isNotEmpty()) {
            results.filter { result ->
                result.expectedSources.any { src -> src in hardNegativeSourceFilenames }
            }
        } else {
            emptyList()
        }

        val hardNegativeMetrics = if (hardNegativeResults.isNotEmpty()) {
            computeMetrics(hardNegativeResults, k)
        } else {
            null
        }

        return mainMetrics.copy(hardNegativeMetrics = hardNegativeMetrics)
    }

    private fun isHit(result: EvalQuestionResult, chunk: EvalRetrievedChunk): Boolean =
        if (result.expectedChunkContains.isNotEmpty()) {
            result.expectedChunkContains.any { phrase ->
                chunk.content.contains(phrase, ignoreCase = true)
            }
        } else {
            chunk.source in result.expectedSources
        }

    private fun computeMetrics(results: List<EvalQuestionResult>, k: Int): EvalMetrics {
        if (results.isEmpty()) {
            return EvalMetrics(recallAtK = 0.0, mrr = 0.0, hitRateAtK = 0.0)
        }

        var hitCount = 0
        var reciprocalRankSum = 0.0

        for (result in results) {
            val topK = result.retrievedChunks.take(k)
            val firstHitRank = topK.indexOfFirst { isHit(result, it) }
            if (firstHitRank >= 0) {
                hitCount++
                reciprocalRankSum += 1.0 / (firstHitRank + 1)
            }
        }

        val recall = hitCount.toDouble() / results.size
        val mrr = reciprocalRankSum / results.size

        return EvalMetrics(
            recallAtK = recall,
            mrr = mrr,
            hitRateAtK = recall  // Hit Rate@k is synonymous with Recall@k per the PRD
        )
    }
}
