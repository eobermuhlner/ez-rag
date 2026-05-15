package ch.obermuhlner.ezrag.rag

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

class EmbeddingSearchPipelineTest {

    /**
     * An embedding model that returns distinct vectors per document text,
     * so cosine similarity scores differ when the query matches one text more closely.
     *
     * Documents are embedded as a unit vector in the direction of [1,0,0,0] or [0,1,0,0] etc.,
     * and the query vector is fixed to [1,0,0,0] so it most closely matches doc1.
     */
    private val distinctVectorEmbeddingModel: EmbeddingModel = object : EmbeddingModel {
        // Map well-known texts to unit vectors so scores differ
        private val vectorMap = mapOf(
            "High score document content." to floatArrayOf(1f, 0f, 0f, 0f),
            "Low score document content." to floatArrayOf(0f, 1f, 0f, 0f),
            // query text maps to the same direction as "high score"
            "test query" to floatArrayOf(1f, 0f, 0f, 0f),
        )

        private fun vectorFor(text: String): FloatArray =
            vectorMap[text] ?: floatArrayOf(0.5f, 0.5f, 0f, 0f)

        override fun call(request: EmbeddingRequest): EmbeddingResponse {
            val embeddings = request.instructions.mapIndexed { idx, text ->
                Embedding(vectorFor(text), idx)
            }
            return EmbeddingResponse(embeddings)
        }

        override fun embed(document: Document): FloatArray = vectorFor(document.text ?: "")
        override fun embed(text: String): FloatArray = vectorFor(text)

        override fun embedForResponse(texts: List<String>): EmbeddingResponse {
            val embeddings = texts.mapIndexed { idx, text ->
                Embedding(vectorFor(text), idx)
            }
            return EmbeddingResponse(embeddings)
        }

        override fun dimensions(): Int = 4
    }

    private fun createRepository(tempDir: Path, embeddingModel: EmbeddingModel = distinctVectorEmbeddingModel): VectorStoreRepository {
        val storePath = tempDir.resolve("vector-store.json")
        val repo = VectorStoreRepository(embeddingModel, storePath)
        repo.load()
        return repo
    }

    @Test
    fun `search returns chunks sorted by score descending when docs have distinct scores`(@TempDir tempDir: Path) {
        val repository = createRepository(tempDir)

        val doc1 = Document.builder()
            .text("High score document content.")
            .metadata(mapOf("source" to "high.txt", "chunk_index" to 0))
            .build()
        val doc2 = Document.builder()
            .text("Low score document content.")
            .metadata(mapOf("source" to "low.txt", "chunk_index" to 0))
            .build()
        repository.add(listOf(doc1, doc2))

        val pipeline = EmbeddingSearchPipeline(repository, distinctVectorEmbeddingModel)
        val result = pipeline.search(SearchQuery(question = "test query", topK = 5, minScore = 0.0))

        assertThat(result.chunks).isNotEmpty()
        // Scores must be sorted descending
        val scores = result.chunks.map { it.score }
        assertThat(scores).isSortedAccordingTo(compareByDescending { it })
        // High score document should come first
        assertThat(result.chunks.first().filePath).isEqualTo("high.txt")
    }

    @Test
    fun `search with minScore above all document scores returns empty SearchResult`(@TempDir tempDir: Path) {
        val repository = createRepository(tempDir)

        val doc = Document.builder()
            .text("Some content.")
            .metadata(mapOf("source" to "doc.txt", "chunk_index" to 0))
            .build()
        repository.add(listOf(doc))

        val pipeline = EmbeddingSearchPipeline(repository, distinctVectorEmbeddingModel)
        // minScore=1.0 means only exact matches pass; cosine scores will be < 1.0 unless identical
        // Use a very high threshold to ensure no results pass
        val result = pipeline.search(SearchQuery(question = "test query", topK = 5, minScore = 1.0))

        assertThat(result.chunks).isEmpty()
    }

    @Test
    fun `search with topK=1 returns exactly one chunk even when store holds more`(@TempDir tempDir: Path) {
        val repository = createRepository(tempDir)

        val doc1 = Document.builder()
            .text("High score document content.")
            .metadata(mapOf("source" to "high.txt", "chunk_index" to 0))
            .build()
        val doc2 = Document.builder()
            .text("Low score document content.")
            .metadata(mapOf("source" to "low.txt", "chunk_index" to 0))
            .build()
        repository.add(listOf(doc1, doc2))

        val pipeline = EmbeddingSearchPipeline(repository, distinctVectorEmbeddingModel)
        val result = pipeline.search(SearchQuery(question = "test query", topK = 1, minScore = 0.0))

        assertThat(result.chunks).hasSize(1)
    }

    @Test
    fun `ChunkMatch content contains full chunk text with no length truncation`(@TempDir tempDir: Path) {
        val repository = createRepository(tempDir)

        val longText = "Word ".repeat(200) // 1000 chars, well over any 200-char limit
        val doc = Document.builder()
            .text(longText)
            .metadata(mapOf("source" to "long.txt", "chunk_index" to 0))
            .build()
        repository.add(listOf(doc))

        val pipeline = EmbeddingSearchPipeline(repository, distinctVectorEmbeddingModel)
        val result = pipeline.search(SearchQuery(question = "test query", topK = 5, minScore = 0.0))

        assertThat(result.chunks).isNotEmpty()
        assertThat(result.chunks.first().content).isEqualTo(longText)
    }

    @Test
    fun `EmbeddingSearchPipeline can be constructed and search invoked without any ChatModel on the call stack`(@TempDir tempDir: Path) {
        // This test verifies the no-ChatModel requirement by simply running search
        // in a context where no ChatModel is imported or referenced.
        val repository = createRepository(tempDir)

        val doc = Document.builder()
            .text("Content without chat model.")
            .metadata(mapOf("source" to "nochat.txt", "chunk_index" to 0))
            .build()
        repository.add(listOf(doc))

        // Constructing EmbeddingSearchPipeline does not require ChatModel at all.
        val pipeline = EmbeddingSearchPipeline(repository, distinctVectorEmbeddingModel)
        val result = pipeline.search(SearchQuery(question = "test query", topK = 5, minScore = 0.0))

        // Should return results without throwing
        assertThat(result.chunks).isNotEmpty
    }
}
