package ch.obermuhlner.ezrag.command

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

class McpListToolTest {

    private val fakeEmbeddingModel: EmbeddingModel = object : EmbeddingModel {
        override fun call(request: EmbeddingRequest): EmbeddingResponse {
            val embeddings = request.instructions.mapIndexed { idx, _ ->
                Embedding(FloatArray(4) { 0.1f * (idx + 1) }, idx)
            }
            return EmbeddingResponse(embeddings)
        }
        override fun embed(document: Document): FloatArray = FloatArray(4) { 0.1f }
        override fun embed(text: String): FloatArray = FloatArray(4) { 0.1f }
        override fun embedForResponse(texts: List<String>): EmbeddingResponse {
            val embeddings = texts.mapIndexed { idx, _ ->
                Embedding(FloatArray(4) { 0.1f * (idx + 1) }, idx)
            }
            return EmbeddingResponse(embeddings)
        }
        override fun dimensions(): Int = 4
    }

    @Test
    fun `list returns per-document path, chunkCount, and stale false for fresh documents`(@TempDir tempDir: Path) {
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repository ->
            repository.add(listOf(
                Document.builder().text("doc1 chunk 1")
                    .metadata(mapOf("source" to "doc1.txt", "mtime" to 1000L, "chunk_index" to 0)).build(),
                Document.builder().text("doc1 chunk 2")
                    .metadata(mapOf("source" to "doc1.txt", "mtime" to 1000L, "chunk_index" to 1)).build(),
                Document.builder().text("doc2 content")
                    .metadata(mapOf("source" to "doc2.txt", "mtime" to 2000L, "chunk_index" to 0)).build(),
            ))

            // Inject probe that returns the same mtime — all documents are fresh
            val tool = McpListTool(repository, filesystemProbe = { path ->
                when (path) {
                    "doc1.txt" -> 1000L
                    "doc2.txt" -> 2000L
                    else -> null
                }
            })
            val result = tool.list()

            assertThat(result).hasSize(2)
            val doc1 = result.first { it.path == "doc1.txt" }
            assertThat(doc1.chunkCount).isEqualTo(2)
            assertThat(doc1.status).isEqualTo("FRESH")
            val doc2 = result.first { it.path == "doc2.txt" }
            assertThat(doc2.chunkCount).isEqualTo(1)
            assertThat(doc2.status).isEqualTo("FRESH")
        }
    }

    @Test
    fun `list marks document as stale when filesystem probe returns different mtime`(@TempDir tempDir: Path) {
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repository ->
            repository.add(listOf(
                Document.builder().text("fresh content")
                    .metadata(mapOf("source" to "fresh.txt", "mtime" to 1000L, "chunk_index" to 0)).build(),
                Document.builder().text("stale content")
                    .metadata(mapOf("source" to "stale.txt", "mtime" to 2000L, "chunk_index" to 0)).build(),
            ))

            // fresh.txt probe returns same mtime; stale.txt probe returns null (missing file)
            val tool = McpListTool(repository, filesystemProbe = { path ->
                when (path) {
                    "fresh.txt" -> 1000L
                    else -> null
                }
            })
            val result = tool.list()

            assertThat(result).hasSize(2)
            val fresh = result.first { it.path == "fresh.txt" }
            assertThat(fresh.status).isEqualTo("FRESH")
            val stale = result.first { it.path == "stale.txt" }
            assertThat(stale.status).isEqualTo("STALE")
        }
    }

    @Test
    fun `list returns empty list for empty store`(@TempDir tempDir: Path) {
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repository ->
            val tool = McpListTool(repository, filesystemProbe = { _ -> null })
            val result = tool.list()

            assertThat(result).isEmpty()
        }
    }

    @Test
    fun `list returns status FRESH for recently-ingested URL`(@TempDir tempDir: Path) {
        val url = "https://example.com/page"
        val now = System.currentTimeMillis()
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repository ->
            repository.add(listOf(
                Document.builder().text("URL content")
                    .metadata(mapOf("source" to url, "mtime" to 0L, "chunk_index" to 0, "ingest_time" to now))
                    .build(),
            ))

            val tool = McpListTool(repository, filesystemProbe = { _ -> null })
            val result = tool.list()

            assertThat(result).hasSize(1)
            assertThat(result[0].status).isEqualTo("FRESH")
        }
    }

    @Test
    fun `list returns status STALE for URL beyond freshness threshold`(@TempDir tempDir: Path) {
        val url = "https://example.com/page"
        val oldIngestTime = System.currentTimeMillis() - 25 * 3_600_000L // 25 hours ago
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repository ->
            repository.add(listOf(
                Document.builder().text("URL content")
                    .metadata(mapOf("source" to url, "mtime" to 0L, "chunk_index" to 0, "ingest_time" to oldIngestTime))
                    .build(),
            ))

            val tool = McpListTool(repository, filesystemProbe = { _ -> null })
            val result = tool.list()

            assertThat(result).hasSize(1)
            assertThat(result[0].status).isEqualTo("STALE")
        }
    }

    @Test
    fun `list file source status unaffected by URL freshness threshold`(@TempDir tempDir: Path) {
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repository ->
            repository.add(listOf(
                Document.builder().text("File content")
                    .metadata(mapOf("source" to "/abs/file.txt", "mtime" to 1000L, "chunk_index" to 0))
                    .build(),
            ))

            val tool = McpListTool(repository, filesystemProbe = { path ->
                if (path == "/abs/file.txt") 1000L else null
            })
            val result = tool.list()

            assertThat(result).hasSize(1)
            assertThat(result[0].status).isEqualTo("FRESH")
        }
    }
}
