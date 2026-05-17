package ch.obermuhlner.ezrag.rag

import ch.obermuhlner.ezrag.ingestion.BM25Repository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.document.Document
import java.nio.file.Path

class BM25SearchPipelineTest {

    @Test
    fun `search returns chunks from BM25Repository for matching keyword`(@TempDir storeDir: Path) {
        val repo = BM25Repository(storeDir, "standard")
        val doc = Document.builder()
            .text("The quick brown fox jumps over the lazy dog")
            .metadata(mapOf("source" to "fox.txt", "chunk_index" to 0, "mtime" to 1000L))
            .build()
        repo.index(listOf(doc))
        repo.close()

        val repo2 = BM25Repository(storeDir, "standard")
        val pipeline = BM25SearchPipeline(repo2)
        val result = pipeline.search(SearchQuery(question = "fox", topK = 5, minScore = 0.0, mode = "bm25"))
        repo2.close()

        assertThat(result.chunks).isNotEmpty()
        assertThat(result.chunks[0].filePath).isEqualTo("fox.txt")
        assertThat(result.mode).isEqualTo("bm25")
    }

    @Test
    fun `search returns empty list when no documents match query`(@TempDir storeDir: Path) {
        val repo = BM25Repository(storeDir, "standard")
        val doc = Document.builder()
            .text("Some unrelated content about programming")
            .metadata(mapOf("source" to "prog.txt", "chunk_index" to 0, "mtime" to 1000L))
            .build()
        repo.index(listOf(doc))
        repo.close()

        val repo2 = BM25Repository(storeDir, "standard")
        val pipeline = BM25SearchPipeline(repo2)
        val result = pipeline.search(SearchQuery(question = "zzznomatch", topK = 5, minScore = 0.0, mode = "bm25"))
        repo2.close()

        assertThat(result.chunks).isEmpty()
        assertThat(result.mode).isEqualTo("bm25")
    }
}
