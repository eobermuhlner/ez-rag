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
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path

/**
 * A stub reranker that reverses the order of candidates, assigning scores so the
 * last candidate gets the highest score. Used to verify that reranking integration
 * in EmbeddingSearchPipeline works correctly.
 */
class StubReranker : Reranker {
    override val name: String = "StubReranker"

    override fun rerank(query: String, candidates: List<ChunkMatch>): List<ChunkMatch> {
        // Assigns highest score to last candidate, lowest score to first candidate,
        // so the reranked order is the reverse of the input order.
        return candidates.mapIndexed { index, chunk ->
            chunk.copy(score = (index + 1).toDouble())
        }.sortedByDescending { it.score }
    }
}

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

    private fun createRepository(tempDir: Path, embeddingModel: EmbeddingModel = distinctVectorEmbeddingModel): LuceneRepository {
        return LuceneRepository.open(embeddingModel, tempDir, "standard")
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

        val pipeline = EmbeddingSearchPipeline(repository)
        val result = pipeline.search(SearchQuery(question = "test query", topK = 5, minScore = 0.0))

        assertThat(result.chunks).isNotEmpty()
        // Scores must be sorted descending
        val scores = result.chunks.map { it.score }
        assertThat(scores).isSortedAccordingTo(compareByDescending { it })
        // High score document should come first
        assertThat(result.chunks.first().path).isEqualTo("high.txt")

        repository.close()
    }

    @Test
    fun `search with minScore above all document scores returns empty SearchResult`(@TempDir tempDir: Path) {
        val repository = createRepository(tempDir)

        val doc = Document.builder()
            .text("Some content.")
            .metadata(mapOf("source" to "doc.txt", "chunk_index" to 0))
            .build()
        repository.add(listOf(doc))

        val pipeline = EmbeddingSearchPipeline(repository)
        // minScore=1.0 means only exact matches pass; cosine scores will be < 1.0 unless identical
        // Use a very high threshold to ensure no results pass
        val result = pipeline.search(SearchQuery(question = "test query", topK = 5, minScore = 1.0))

        assertThat(result.chunks).isEmpty()
        repository.close()
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

        val pipeline = EmbeddingSearchPipeline(repository)
        val result = pipeline.search(SearchQuery(question = "test query", topK = 1, minScore = 0.0))

        assertThat(result.chunks).hasSize(1)
        repository.close()
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

        val pipeline = EmbeddingSearchPipeline(repository)
        val result = pipeline.search(SearchQuery(question = "test query", topK = 5, minScore = 0.0))

        assertThat(result.chunks).isNotEmpty()
        assertThat(result.chunks.first().text).isEqualTo(longText)
        repository.close()
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
        val pipeline = EmbeddingSearchPipeline(repository)
        val result = pipeline.search(SearchQuery(question = "test query", topK = 5, minScore = 0.0))

        // Should return results without throwing
        assertThat(result.chunks).isNotEmpty
        repository.close()
    }

    // ---------------------------------------------------------------------------
    // Reranking tests
    // ---------------------------------------------------------------------------

    @Test
    fun `StubReranker reranks so last embedding candidate becomes first result`(@TempDir tempDir: Path) {
        // Store: doc1 (high embedding score) and doc2 (low embedding score)
        // StubReranker reverses order, so doc2 must come first in reranked output
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

        val stub = StubReranker()
        val pipeline = EmbeddingSearchPipeline(repository, reranker = stub)
        val result = pipeline.search(
            SearchQuery(question = "test query", topK = 2, minScore = 0.0, rerankCandidates = 2)
        )

        // StubReranker assigns highest score to the last candidate from the store.
        // The store returns [high.txt, low.txt] by embedding score; stub reverses → low.txt first.
        assertThat(result.chunks).hasSize(2)
        assertThat(result.chunks.first().path).isEqualTo("low.txt")
        repository.close()
    }

    @Test
    fun `when rerankCandidates=6 and topK=2, result contains exactly 2 chunks`(@TempDir tempDir: Path) {
        val repository = createRepository(tempDir)

        // Insert 6 documents (4 "other" docs use generic vectors)
        val docs = listOf(
            Document.builder().text("High score document content.").metadata(mapOf("source" to "high.txt", "chunk_index" to 0)).build(),
            Document.builder().text("Low score document content.").metadata(mapOf("source" to "low.txt", "chunk_index" to 0)).build(),
            Document.builder().text("doc3").metadata(mapOf("source" to "doc3.txt", "chunk_index" to 0)).build(),
            Document.builder().text("doc4").metadata(mapOf("source" to "doc4.txt", "chunk_index" to 0)).build(),
            Document.builder().text("doc5").metadata(mapOf("source" to "doc5.txt", "chunk_index" to 0)).build(),
            Document.builder().text("doc6").metadata(mapOf("source" to "doc6.txt", "chunk_index" to 0)).build(),
        )
        repository.add(docs)

        val stub = StubReranker()
        val pipeline = EmbeddingSearchPipeline(repository, reranker = stub)
        val result = pipeline.search(
            SearchQuery(question = "test query", topK = 2, minScore = 0.0, rerankCandidates = 6)
        )

        assertThat(result.chunks).hasSize(2)
        repository.close()
    }

    @Test
    fun `reranked ChunkMatch scores equal stub scores, not original embedding scores`(@TempDir tempDir: Path) {
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

        val stub = StubReranker()
        val pipeline = EmbeddingSearchPipeline(repository, reranker = stub)
        val result = pipeline.search(
            SearchQuery(question = "test query", topK = 2, minScore = 0.0, rerankCandidates = 2)
        )

        // StubReranker assigns scores 2.0 and 1.0 (n - index for reversed list)
        assertThat(result.chunks).hasSize(2)
        assertThat(result.chunks[0].score).isEqualTo(2.0)
        assertThat(result.chunks[1].score).isEqualTo(1.0)
        repository.close()
    }

    @Test
    fun `null reranker with null rerankCandidates behaves identically to pre-reranking`(@TempDir tempDir: Path) {
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

        // No reranker: null (default)
        val pipeline = EmbeddingSearchPipeline(repository)
        val result = pipeline.search(SearchQuery(question = "test query", topK = 5, minScore = 0.0))

        // Original embedding ordering: high.txt first
        assertThat(result.chunks).isNotEmpty()
        assertThat(result.chunks.first().path).isEqualTo("high.txt")
        repository.close()
    }

    @Test
    fun `null reranker with non-null rerankCandidates still fetches topK, not rerankCandidates`(@TempDir tempDir: Path) {
        val repository = createRepository(tempDir)

        val docs = (1..4).map { i ->
            Document.builder()
                .text("doc$i content")
                .metadata(mapOf("source" to "doc$i.txt", "chunk_index" to 0))
                .build()
        }
        repository.add(docs)

        // reranker=null (default), rerankCandidates=4 set but reranker is null → must use topK
        val pipeline = EmbeddingSearchPipeline(repository)
        val result = pipeline.search(
            SearchQuery(question = "test query", topK = 1, minScore = 0.0, rerankCandidates = 4)
        )

        // Even though rerankCandidates=4, reranker is null, so pipeline fetches topK=1
        assertThat(result.chunks).hasSize(1)
        repository.close()
    }

    // ---------------------------------------------------------------------------
    // Verbose diagnostic tests
    // ---------------------------------------------------------------------------

    @Test
    fun `verbose=true with StubReranker writes Reranker and Reranking lines to errWriter`(@TempDir tempDir: Path) {
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

        val sw = StringWriter()
        val errWriter = PrintWriter(sw, true)
        val stub = StubReranker()
        val pipeline = EmbeddingSearchPipeline(repository, reranker = stub, errWriter = errWriter)

        pipeline.search(SearchQuery(question = "test query", topK = 2, minScore = 0.0, rerankCandidates = 2, verbose = true))

        val output = sw.toString()
        val lines = output.lines().filter { it.isNotEmpty() }
        assertThat(lines).anyMatch { it.startsWith("Reranker: ") }
        assertThat(lines).anyMatch { it.startsWith("Reranking: ") }
        repository.close()
    }

    @Test
    fun `Reranker diagnostic line contains the stub name`(@TempDir tempDir: Path) {
        val repository = createRepository(tempDir)

        val doc1 = Document.builder()
            .text("High score document content.")
            .metadata(mapOf("source" to "high.txt", "chunk_index" to 0))
            .build()
        repository.add(listOf(doc1))

        val sw = StringWriter()
        val errWriter = PrintWriter(sw, true)
        val stub = StubReranker()
        val pipeline = EmbeddingSearchPipeline(repository, reranker = stub, errWriter = errWriter)

        pipeline.search(SearchQuery(question = "test query", topK = 1, minScore = 0.0, rerankCandidates = 1, verbose = true))

        val output = sw.toString()
        val rerankerLine = output.lines().first { it.startsWith("Reranker: ") }
        assertThat(rerankerLine).contains(stub.name)
        repository.close()
    }

    @Test
    fun `Reranking diagnostic line matches pattern with rerankCandidates and topK`(@TempDir tempDir: Path) {
        val repository = createRepository(tempDir)

        val docs = (1..4).map { i ->
            Document.builder()
                .text("doc$i content")
                .metadata(mapOf("source" to "doc$i.txt", "chunk_index" to 0))
                .build()
        }
        repository.add(docs)

        val sw = StringWriter()
        val errWriter = PrintWriter(sw, true)
        val stub = StubReranker()
        val pipeline = EmbeddingSearchPipeline(repository, reranker = stub, errWriter = errWriter)

        pipeline.search(SearchQuery(question = "test query", topK = 2, minScore = 0.0, rerankCandidates = 4, verbose = true))

        val output = sw.toString()
        val rerankingLine = output.lines().first { it.startsWith("Reranking: ") }
        assertThat(rerankingLine).isEqualTo("Reranking: 4 candidates → top 2")
        repository.close()
    }

    @Test
    fun `verbose=false writes no reranker diagnostic lines even with active reranker`(@TempDir tempDir: Path) {
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

        val sw = StringWriter()
        val errWriter = PrintWriter(sw, true)
        val stub = StubReranker()
        val pipeline = EmbeddingSearchPipeline(repository, reranker = stub, errWriter = errWriter)

        pipeline.search(SearchQuery(question = "test query", topK = 2, minScore = 0.0, rerankCandidates = 2, verbose = false))

        val output = sw.toString()
        assertThat(output).doesNotContain("Reranker: ")
        assertThat(output).doesNotContain("Reranking: ")
        repository.close()
    }

    @Test
    fun `verbose=true with null reranker writes no reranker diagnostic lines`(@TempDir tempDir: Path) {
        val repository = createRepository(tempDir)

        val doc1 = Document.builder()
            .text("High score document content.")
            .metadata(mapOf("source" to "high.txt", "chunk_index" to 0))
            .build()
        repository.add(listOf(doc1))

        val sw = StringWriter()
        val errWriter = PrintWriter(sw, true)
        // No reranker (null)
        val pipeline = EmbeddingSearchPipeline(repository, reranker = null, errWriter = errWriter)

        pipeline.search(SearchQuery(question = "test query", topK = 1, minScore = 0.0, verbose = true))

        val output = sw.toString()
        assertThat(output).doesNotContain("Reranker: ")
        assertThat(output).doesNotContain("Reranking: ")
        repository.close()
    }
}
