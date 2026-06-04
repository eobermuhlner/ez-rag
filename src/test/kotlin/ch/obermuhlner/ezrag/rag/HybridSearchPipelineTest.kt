package ch.obermuhlner.ezrag.rag

import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import java.nio.file.Path

class HybridSearchPipelineTest {

    /**
     * Embedding model that returns distinct vectors per known text so cosine similarity
     * differs. Query text maps to "alpha" direction so alpha.txt scores highest.
     */
    private val fakeEmbeddingModel: EmbeddingModel = object : EmbeddingModel {
        private val vectors = mapOf(
            "alpha content" to floatArrayOf(1f, 0f, 0f, 0f),
            "beta content"  to floatArrayOf(0f, 1f, 0f, 0f),
            "hybrid query"  to floatArrayOf(1f, 0f, 0f, 0f),
        )
        private fun vec(t: String) = vectors[t] ?: floatArrayOf(0.5f, 0.5f, 0f, 0f)

        override fun call(request: EmbeddingRequest): EmbeddingResponse =
            EmbeddingResponse(request.instructions.mapIndexed { i, t -> Embedding(vec(t), i) })
        override fun embed(document: Document): FloatArray = vec(document.text ?: "")
        override fun embed(text: String): FloatArray = vec(text)
        override fun embedForResponse(texts: List<String>): EmbeddingResponse =
            EmbeddingResponse(texts.mapIndexed { i, t -> Embedding(vec(t), i) })
        override fun dimensions(): Int = 4
    }

    // -----------------------------------------------------------------------
    // Test 1: chunk ranked 1st by both fakes appears as top result
    // -----------------------------------------------------------------------

    @Test
    fun `chunk ranked first by both bm25 and embedding appears as top result`(@TempDir storeDir: Path) {
        val repo = LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard")
        val alphaDoc = Document.builder()
            .text("alpha content")
            .metadata(mapOf("source" to "alpha.txt", "chunk_index" to 0, "mtime" to 1L))
            .build()
        val betaDoc = Document.builder()
            .text("beta content")
            .metadata(mapOf("source" to "beta.txt", "chunk_index" to 0, "mtime" to 2L))
            .build()
        repo.add(listOf(alphaDoc, betaDoc))

        val pipeline = HybridSearchPipeline(repo)
        val result = pipeline.search(SearchQuery(question = "alpha content", topK = 2, minScore = 0.0, mode = "hybrid"))
        repo.close()

        assertThat(result.chunks).isNotEmpty()
        assertThat(result.chunks.first().filePath).isEqualTo("alpha.txt")
    }

    // -----------------------------------------------------------------------
    // Test 2: result mode is "hybrid"
    // -----------------------------------------------------------------------

    @Test
    fun `search result has mode set to hybrid`(@TempDir storeDir: Path) {
        val repo = LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard")
        val doc = Document.builder()
            .text("alpha content")
            .metadata(mapOf("source" to "alpha.txt", "chunk_index" to 0, "mtime" to 1L))
            .build()
        repo.add(listOf(doc))

        val pipeline = HybridSearchPipeline(repo)
        val result = pipeline.search(SearchQuery(question = "alpha content", topK = 5, minScore = 0.0, mode = "hybrid"))
        repo.close()

        assertThat(result.mode).isEqualTo("hybrid")
    }

    // -----------------------------------------------------------------------
    // Test 3: topK is respected
    // -----------------------------------------------------------------------

    @Test
    fun `minScore filters out chunks whose normalized RRF score is below threshold`(@TempDir storeDir: Path) {
        val repo = LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard")
        // alpha.txt: vector [1,0,0,0] — top embedding match for "alpha content"
        // beta.txt:  vector [0,1,0,0] — ranked second in both BM25 and embedding
        val alphaDoc = Document.builder()
            .text("alpha content")
            .metadata(mapOf("source" to "alpha.txt", "chunk_index" to 0, "mtime" to 1L))
            .build()
        val betaDoc = Document.builder()
            .text("beta content")
            .metadata(mapOf("source" to "beta.txt", "chunk_index" to 0, "mtime" to 2L))
            .build()
        repo.add(listOf(alphaDoc, betaDoc))

        val pipeline = HybridSearchPipeline(repo)

        // minScore = 0.0 → both chunks returned
        val all = pipeline.search(SearchQuery(question = "alpha content", topK = 5, minScore = 0.0, mode = "hybrid"))
        assertThat(all.chunks).hasSize(2)

        // alpha.txt is rank 1 in both lists (normalized score = 1.0);
        // beta.txt is rank 2 in both lists (normalized score = 61/62 ≈ 0.984).
        // minScore = 0.99 keeps alpha.txt but filters beta.txt.
        val filtered = pipeline.search(SearchQuery(question = "alpha content", topK = 5, minScore = 0.99, mode = "hybrid"))
        assertThat(filtered.chunks).hasSize(1)
        assertThat(filtered.chunks[0].filePath).isEqualTo("alpha.txt")

        repo.close()
    }

    @Test
    fun `result size does not exceed topK`(@TempDir storeDir: Path) {
        val repo = LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard")
        val docs = (1..5).map { i ->
            Document.builder()
                .text("content $i")
                .metadata(mapOf("source" to "doc$i.txt", "chunk_index" to 0, "mtime" to i.toLong()))
                .build()
        }
        repo.add(docs)

        val pipeline = HybridSearchPipeline(repo)
        val result = pipeline.search(SearchQuery(question = "content", topK = 2, minScore = 0.0, mode = "hybrid"))
        repo.close()

        assertThat(result.chunks.size).isLessThanOrEqualTo(2)
    }
}
