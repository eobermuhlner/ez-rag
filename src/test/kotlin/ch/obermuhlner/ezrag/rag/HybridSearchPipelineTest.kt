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
