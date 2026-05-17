package ch.obermuhlner.ezrag.rag

import ch.obermuhlner.ezrag.ingestion.BM25Repository
import ch.obermuhlner.ezrag.ingestion.VectorStoreRepository
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
        // Index "alpha content" so it is top in both embedding and BM25 for "alpha"
        val bm25Repo = BM25Repository(storeDir, "standard")
        val alphaDoc = Document.builder()
            .text("alpha content")
            .metadata(mapOf("source" to "alpha.txt", "chunk_index" to 0, "mtime" to 1L))
            .build()
        val betaDoc = Document.builder()
            .text("beta content")
            .metadata(mapOf("source" to "beta.txt", "chunk_index" to 0, "mtime" to 2L))
            .build()
        bm25Repo.index(listOf(alphaDoc, betaDoc))
        bm25Repo.close()

        val vectorRepo = VectorStoreRepository(fakeEmbeddingModel, storeDir)
        vectorRepo.load()
        vectorRepo.add(listOf(alphaDoc, betaDoc))

        val bm25Repo2 = BM25Repository(storeDir, "standard")
        val pipeline = HybridSearchPipeline(vectorRepo, fakeEmbeddingModel, bm25Repo2)

        val result = pipeline.search(SearchQuery(question = "alpha content", topK = 2, minScore = 0.0, mode = "hybrid"))
        bm25Repo2.close()

        assertThat(result.chunks).isNotEmpty()
        assertThat(result.chunks.first().filePath).isEqualTo("alpha.txt")
    }

    // -----------------------------------------------------------------------
    // Test 2: result mode is "hybrid"
    // -----------------------------------------------------------------------

    @Test
    fun `search result has mode set to hybrid`(@TempDir storeDir: Path) {
        val bm25Repo = BM25Repository(storeDir, "standard")
        val doc = Document.builder()
            .text("alpha content")
            .metadata(mapOf("source" to "alpha.txt", "chunk_index" to 0, "mtime" to 1L))
            .build()
        bm25Repo.index(listOf(doc))
        bm25Repo.close()

        val vectorRepo = VectorStoreRepository(fakeEmbeddingModel, storeDir)
        vectorRepo.load()
        vectorRepo.add(listOf(doc))

        val bm25Repo2 = BM25Repository(storeDir, "standard")
        val pipeline = HybridSearchPipeline(vectorRepo, fakeEmbeddingModel, bm25Repo2)

        val result = pipeline.search(SearchQuery(question = "alpha content", topK = 5, minScore = 0.0, mode = "hybrid"))
        bm25Repo2.close()

        assertThat(result.mode).isEqualTo("hybrid")
    }

    // -----------------------------------------------------------------------
    // Test 3: topK is respected
    // -----------------------------------------------------------------------

    @Test
    fun `result size does not exceed topK`(@TempDir storeDir: Path) {
        val bm25Repo = BM25Repository(storeDir, "standard")
        val docs = (1..5).map { i ->
            Document.builder()
                .text("content $i")
                .metadata(mapOf("source" to "doc$i.txt", "chunk_index" to 0, "mtime" to i.toLong()))
                .build()
        }
        bm25Repo.index(docs)
        bm25Repo.close()

        val vectorRepo = VectorStoreRepository(fakeEmbeddingModel, storeDir)
        vectorRepo.load()
        vectorRepo.add(docs)

        val bm25Repo2 = BM25Repository(storeDir, "standard")
        val pipeline = HybridSearchPipeline(vectorRepo, fakeEmbeddingModel, bm25Repo2)

        val result = pipeline.search(SearchQuery(question = "content", topK = 2, minScore = 0.0, mode = "hybrid"))
        bm25Repo2.close()

        assertThat(result.chunks.size).isLessThanOrEqualTo(2)
    }
}
