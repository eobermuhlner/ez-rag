package ch.obermuhlner.ezrag.rag

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RrfFusionTest {

    private fun chunk(id: String, index: Int = 0, score: Double = 1.0) =
        ChunkMatch(filePath = id, chunkIndex = index, score = score, content = "content-$id")

    // -----------------------------------------------------------------------
    // Test 1: chunk ranked 1st in both lists gets highest score
    // -----------------------------------------------------------------------

    @Test
    fun `chunk ranked first in both lists has strictly higher score than chunk ranked first in only one list`() {
        val bm25 = listOf(chunk("A"), chunk("B"))
        val emb  = listOf(chunk("A"), chunk("C"))
        // A is rank 1 in both; B is rank 2 in bm25 only; C is rank 2 in emb only
        val result = RrfFusion.fuse(bm25Results = bm25, embeddingResults = emb, topK = 3)

        val scoreA = result.first { it.filePath == "A" }.score
        val scoreB = result.first { it.filePath == "B" }.score
        val scoreC = result.first { it.filePath == "C" }.score

        assertThat(scoreA).isGreaterThan(scoreB)
        assertThat(scoreA).isGreaterThan(scoreC)
    }

    // -----------------------------------------------------------------------
    // Test 2: chunk in both lists appears exactly once in output
    // -----------------------------------------------------------------------

    @Test
    fun `chunk appearing in both input lists appears exactly once in the output`() {
        val bm25 = listOf(chunk("A"), chunk("B"))
        val emb  = listOf(chunk("A"), chunk("C"))
        val result = RrfFusion.fuse(bm25Results = bm25, embeddingResults = emb, topK = 10)

        val aCount = result.count { it.filePath == "A" }
        assertThat(aCount).isEqualTo(1)
    }

    // -----------------------------------------------------------------------
    // Test 3: empty BM25 list → output contains only embedding chunks (with worst-rank BM25 penalty)
    // -----------------------------------------------------------------------

    @Test
    fun `when bm25 list is empty output contains only chunks from embedding list`() {
        val emb = listOf(chunk("E1"), chunk("E2"), chunk("E3"))
        val result = RrfFusion.fuse(bm25Results = emptyList(), embeddingResults = emb, topK = 5)

        assertThat(result.map { it.filePath }).containsExactlyInAnyOrder("E1", "E2", "E3")
    }

    @Test
    fun `when bm25 list is empty each chunk score uses worst-rank bm25 penalty`() {
        val emb = listOf(chunk("E1"), chunk("E2"))
        // worst rank = emb.size + 1
        val worstRank = emb.size + 1
        val k = 60

        val result = RrfFusion.fuse(bm25Results = emptyList(), embeddingResults = emb, k = k, topK = 5)

        // E1 is rank 1 in embedding, worst rank in bm25
        val expectedE1 = 1.0 / (k + 1) + 1.0 / (k + worstRank)
        val actualE1 = result.first { it.filePath == "E1" }.score
        assertThat(actualE1).isEqualTo(expectedE1)
    }

    // -----------------------------------------------------------------------
    // Test 4: empty embedding list → symmetric to empty bm25 case
    // -----------------------------------------------------------------------

    @Test
    fun `when embedding list is empty output contains only chunks from bm25 list`() {
        val bm25 = listOf(chunk("B1"), chunk("B2"))
        val result = RrfFusion.fuse(bm25Results = bm25, embeddingResults = emptyList(), topK = 5)

        assertThat(result.map { it.filePath }).containsExactlyInAnyOrder("B1", "B2")
    }

    @Test
    fun `when embedding list is empty each chunk score uses worst-rank embedding penalty`() {
        val bm25 = listOf(chunk("B1"), chunk("B2"))
        val worstRank = bm25.size + 1
        val k = 60

        val result = RrfFusion.fuse(bm25Results = bm25, embeddingResults = emptyList(), k = k, topK = 5)

        val expectedB1 = 1.0 / (k + 1) + 1.0 / (k + worstRank)
        val actualB1 = result.first { it.filePath == "B1" }.score
        assertThat(actualB1).isEqualTo(expectedB1)
    }

    // -----------------------------------------------------------------------
    // Test 5: output length never exceeds topK
    // -----------------------------------------------------------------------

    @Test
    fun `output list length never exceeds topK regardless of input size`() {
        val bm25 = (1..10).map { chunk("B$it") }
        val emb  = (1..10).map { chunk("E$it") }
        val result = RrfFusion.fuse(bm25Results = bm25, embeddingResults = emb, topK = 3)

        assertThat(result).hasSize(3)
    }

    // -----------------------------------------------------------------------
    // Test 6: output is sorted by score descending
    // -----------------------------------------------------------------------

    @Test
    fun `output is sorted by RRF score descending`() {
        val bm25 = listOf(chunk("A"), chunk("B"), chunk("C"))
        val emb  = listOf(chunk("A"), chunk("B"), chunk("C"))
        val result = RrfFusion.fuse(bm25Results = bm25, embeddingResults = emb, topK = 3)

        val scores = result.map { it.score }
        assertThat(scores).isSortedAccordingTo(compareByDescending { it })
    }

    // -----------------------------------------------------------------------
    // Test 7: deduplication by (filePath, chunkIndex)
    // -----------------------------------------------------------------------

    @Test
    fun `deduplication is based on filePath and chunkIndex pair`() {
        // Same file, two different chunk indices — should both appear
        val bm25 = listOf(ChunkMatch("file.txt", 0, 1.0, "chunk 0"), ChunkMatch("file.txt", 1, 0.9, "chunk 1"))
        val emb  = listOf(ChunkMatch("file.txt", 0, 1.0, "chunk 0"), ChunkMatch("file.txt", 2, 0.8, "chunk 2"))

        val result = RrfFusion.fuse(bm25Results = bm25, embeddingResults = emb, topK = 10)

        // chunk 0 should appear once; chunk 1 and chunk 2 should each appear once
        val keys = result.map { Pair(it.filePath, it.chunkIndex) }
        assertThat(keys).doesNotHaveDuplicates()
        assertThat(keys).contains(Pair("file.txt", 0))
        assertThat(keys).contains(Pair("file.txt", 1))
        assertThat(keys).contains(Pair("file.txt", 2))
    }
}
