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

class BM25SearchPipelineTest {

    private val fakeEmbeddingModel: EmbeddingModel = object : EmbeddingModel {
        override fun call(request: EmbeddingRequest): EmbeddingResponse =
            EmbeddingResponse(request.instructions.mapIndexed { i, _ -> Embedding(FloatArray(4) { 0.25f }, i) })
        override fun embed(document: Document): FloatArray = FloatArray(4) { 0.25f }
        override fun embed(text: String): FloatArray = FloatArray(4) { 0.25f }
        override fun embedForResponse(texts: List<String>): EmbeddingResponse =
            EmbeddingResponse(texts.mapIndexed { i, _ -> Embedding(FloatArray(4) { 0.25f }, i) })
        override fun dimensions(): Int = 4
    }

    @Test
    fun `search returns chunks from LuceneRepository for matching keyword`(@TempDir storeDir: Path) {
        val repo = LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard")
        val doc = Document.builder()
            .text("The quick brown fox jumps over the lazy dog")
            .metadata(mapOf("source" to "fox.txt", "chunk_index" to 0, "mtime" to 1000L))
            .build()
        repo.add(listOf(doc))

        val pipeline = BM25SearchPipeline(repo)
        val result = pipeline.search(SearchQuery(question = "fox", topK = 5, minScore = 0.0, mode = "bm25"))
        repo.close()

        assertThat(result.chunks).isNotEmpty()
        assertThat(result.chunks[0].filePath).isEqualTo("fox.txt")
        assertThat(result.mode).isEqualTo("bm25")
    }

    @Test
    fun `search with minScore 1_0 returns only the top-ranked chunk`(@TempDir storeDir: Path) {
        val repo = LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard")
        // Both docs contain "fox"; doc1 is a stronger BM25 match (shorter doc = higher score)
        repo.add(listOf(
            Document.builder().text("fox")
                .metadata(mapOf("source" to "best.txt", "chunk_index" to 0, "mtime" to 1000L)).build(),
            Document.builder().text("the quick brown fox jumps over the lazy dog and then the fox ran away")
                .metadata(mapOf("source" to "weaker.txt", "chunk_index" to 0, "mtime" to 1000L)).build(),
        ))

        val pipeline = BM25SearchPipeline(repo)
        val result = pipeline.search(SearchQuery(question = "fox", topK = 5, minScore = 1.0, mode = "bm25"))
        repo.close()

        assertThat(result.chunks).hasSize(1)
        assertThat(result.chunks[0].filePath).isEqualTo("best.txt")
        assertThat(result.chunks[0].score).isEqualTo(1.0)
    }

    @Test
    fun `search returns empty list when no documents match query`(@TempDir storeDir: Path) {
        val repo = LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard")
        val doc = Document.builder()
            .text("Some unrelated content about programming")
            .metadata(mapOf("source" to "prog.txt", "chunk_index" to 0, "mtime" to 1000L))
            .build()
        repo.add(listOf(doc))

        val pipeline = BM25SearchPipeline(repo)
        val result = pipeline.search(SearchQuery(question = "zzznomatch", topK = 5, minScore = 0.0, mode = "bm25"))
        repo.close()

        assertThat(result.chunks).isEmpty()
        assertThat(result.mode).isEqualTo("bm25")
    }
}
