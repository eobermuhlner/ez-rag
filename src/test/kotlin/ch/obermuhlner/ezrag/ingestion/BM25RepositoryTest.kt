package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.document.Document
import java.nio.file.Path

class BM25RepositoryTest {

    private fun makeDocument(text: String, source: String, chunkIndex: Int = 0, mtime: Long = 1000L): Document =
        Document.builder()
            .text(text)
            .metadata(mapOf("source" to source, "chunk_index" to chunkIndex, "mtime" to mtime))
            .build()

    @Test
    fun `search returns only document containing unique term`(@TempDir storeDir: Path) {
        val repo = BM25Repository(storeDir, "standard")
        val doc1 = makeDocument("The quick brown fox jumps over the lazy dog", "doc1.txt")
        val doc2 = makeDocument("Pack my box with five dozen liquor jugs", "doc2.txt")
        repo.index(listOf(doc1, doc2))
        repo.close()

        val repo2 = BM25Repository(storeDir, "standard")
        val results1 = repo2.search("fox", 5)
        val results2 = repo2.search("jugs", 5)
        repo2.close()

        assertThat(results1).hasSize(1)
        assertThat(results1[0].filePath).isEqualTo("doc1.txt")
        assertThat(results2).hasSize(1)
        assertThat(results2[0].filePath).isEqualTo("doc2.txt")
    }

    @Test
    fun `deleteBySource and re-index updates search results`(@TempDir storeDir: Path) {
        val repo = BM25Repository(storeDir, "standard")
        val original = makeDocument("The quick brown fox", "update.txt")
        repo.index(listOf(original))
        repo.close()

        val repo2 = BM25Repository(storeDir, "standard")
        repo2.deleteBySource("update.txt")
        val updated = makeDocument("Completely different content about robots", "update.txt", mtime = 2000L)
        repo2.index(listOf(updated))
        repo2.close()

        val repo3 = BM25Repository(storeDir, "standard")
        val foxResults = repo3.search("fox", 5)
        val robotResults = repo3.search("robots", 5)
        repo3.close()

        assertThat(foxResults).isEmpty()
        assertThat(robotResults).hasSize(1)
        assertThat(robotResults[0].filePath).isEqualTo("update.txt")
    }

    @Test
    fun `isAlreadyIndexed returns true for known source and mtime`(@TempDir storeDir: Path) {
        val repo = BM25Repository(storeDir, "standard")
        val doc = makeDocument("Some content here", "tracked.txt", mtime = 5000L)
        repo.index(listOf(doc))
        repo.close()

        val repo2 = BM25Repository(storeDir, "standard")
        assertThat(repo2.isAlreadyIndexed("tracked.txt", 5000L)).isTrue()
        assertThat(repo2.isAlreadyIndexed("tracked.txt", 9999L)).isFalse()
        assertThat(repo2.isAlreadyIndexed("unknown.txt", 5000L)).isFalse()
        repo2.close()
    }

    @Test
    fun `getMetadata returns documentCount and positive indexSizeBytes after indexing`(@TempDir storeDir: Path) {
        val repo = BM25Repository(storeDir, "standard")
        repo.index(listOf(
            makeDocument("First chunk content", "a.txt", chunkIndex = 0),
            makeDocument("Second chunk content", "a.txt", chunkIndex = 1),
            makeDocument("Third chunk content", "b.txt", chunkIndex = 0),
        ))
        repo.close()

        val repo2 = BM25Repository(storeDir, "standard")
        val meta = repo2.getMetadata()
        repo2.close()

        assertThat(meta.documentCount).isEqualTo(3)
        assertThat(meta.indexSizeBytes).isGreaterThan(0)
    }
}
