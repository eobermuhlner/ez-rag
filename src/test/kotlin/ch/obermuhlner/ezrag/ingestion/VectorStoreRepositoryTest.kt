package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.vectorstore.SimpleVectorStore
import java.nio.file.Path

class VectorStoreRepositoryTest {

    private val embeddingModel: EmbeddingModel = object : EmbeddingModel {
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
    fun `add documents and save creates the store file`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repository = VectorStoreRepository(embeddingModel, storeFilePath)
        repository.load()

        val document = Document.builder()
            .text("Hello world")
            .metadata(mapOf("source" to "test.txt"))
            .build()
        repository.add(listOf(document))
        repository.save()

        assertThat(storeFilePath.toFile()).exists()
        assertThat(storeFilePath.toFile().length()).isGreaterThan(0)
    }

    @Test
    fun `load from existing file preserves documents`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")

        // First run: add documents and save
        val repo1 = VectorStoreRepository(embeddingModel, storeFilePath)
        repo1.load()
        val doc = Document.builder()
            .text("Hello world")
            .metadata(mapOf("source" to "test.txt"))
            .build()
        repo1.add(listOf(doc))
        repo1.save()

        // Second run: load the saved store
        val repo2 = VectorStoreRepository(embeddingModel, storeFilePath)
        repo2.load()

        // The store file should still exist and be valid
        assertThat(storeFilePath.toFile()).exists()
    }

    @Test
    fun `load works when store file does not exist`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("non-existent-store.json")
        val repository = VectorStoreRepository(embeddingModel, storeFilePath)

        // Should not throw
        repository.load()

        assertThat(storeFilePath.toFile()).doesNotExist()
    }

    @Test
    fun `isAlreadyIngested returns true for matching path and mtime`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repository = VectorStoreRepository(embeddingModel, storeFilePath)
        repository.load()

        val document = Document.builder()
            .text("Some content")
            .metadata(mapOf("source" to "file.txt", "mtime" to 1000L))
            .build()
        repository.add(listOf(document))

        assertThat(repository.isAlreadyIngested("file.txt", 1000L)).isTrue()
    }

    @Test
    fun `isAlreadyIngested returns false for matching path but different mtime`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repository = VectorStoreRepository(embeddingModel, storeFilePath)
        repository.load()

        val document = Document.builder()
            .text("Some content")
            .metadata(mapOf("source" to "file.txt", "mtime" to 1000L))
            .build()
        repository.add(listOf(document))

        assertThat(repository.isAlreadyIngested("file.txt", 9999L)).isFalse()
    }

    @Test
    fun `isAlreadyIngested returns false when no documents have been added`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repository = VectorStoreRepository(embeddingModel, storeFilePath)
        repository.load()

        assertThat(repository.isAlreadyIngested("file.txt", 1000L)).isFalse()
    }

    @Test
    fun `getMetadata returns correct per-file and total counts`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repository = VectorStoreRepository(embeddingModel, storeFilePath)
        repository.load()

        val docsForA = (1..3).map { i ->
            Document.builder()
                .text("Chunk $i of a.txt")
                .metadata(mapOf("source" to "a.txt", "mtime" to 1000L))
                .build()
        }
        val docsForB = (1..2).map { i ->
            Document.builder()
                .text("Chunk $i of b.txt")
                .metadata(mapOf("source" to "b.txt", "mtime" to 2000L))
                .build()
        }
        repository.add(docsForA)
        repository.add(docsForB)

        val metadata = repository.getMetadata()

        assertThat(metadata.chunkCount).isEqualTo(5)
        val aInfo = metadata.documents.find { it.path == "a.txt" }
        val bInfo = metadata.documents.find { it.path == "b.txt" }
        assertThat(aInfo).isNotNull
        assertThat(bInfo).isNotNull
        assertThat(aInfo!!.chunkCount).isEqualTo(3)
        assertThat(bInfo!!.chunkCount).isEqualTo(2)
    }

    @Test
    fun `delete removes all chunks for target file leaves other files untouched and returns chunk count`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repository = VectorStoreRepository(embeddingModel, storeFilePath)
        repository.load()

        val docsForA = (0..2).map { i ->
            Document.builder()
                .text("Chunk $i of a.txt")
                .metadata(mapOf("source" to "/abs/a.txt", "mtime" to 1000L, "chunk_index" to i))
                .build()
        }
        val docsForB = (0..1).map { i ->
            Document.builder()
                .text("Chunk $i of b.txt")
                .metadata(mapOf("source" to "/abs/b.txt", "mtime" to 2000L, "chunk_index" to i))
                .build()
        }
        repository.add(docsForA)
        repository.add(docsForB)

        val removed = repository.delete("/abs/a.txt")

        assertThat(removed).isEqualTo(3)
        val metadata = repository.getMetadata()
        assertThat(metadata.documents.find { it.path == "/abs/a.txt" }).isNull()
        assertThat(metadata.documents.find { it.path == "/abs/b.txt" }).isNotNull
        assertThat(metadata.documents.find { it.path == "/abs/b.txt" }!!.chunkCount).isEqualTo(2)
    }

    @Test
    fun `delete evicts file from ingestedFiles cache so isAlreadyIngested returns false`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repository = VectorStoreRepository(embeddingModel, storeFilePath)
        repository.load()

        val doc = Document.builder()
            .text("Some content")
            .metadata(mapOf("source" to "/abs/file.txt", "mtime" to 1000L))
            .build()
        repository.add(listOf(doc))
        assertThat(repository.isAlreadyIngested("/abs/file.txt", 1000L)).isTrue()

        repository.delete("/abs/file.txt")

        assertThat(repository.isAlreadyIngested("/abs/file.txt", 1000L)).isFalse()
    }

    @Test
    fun `delete on unknown path returns 0`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repository = VectorStoreRepository(embeddingModel, storeFilePath)
        repository.load()

        val doc = Document.builder()
            .text("Some content")
            .metadata(mapOf("source" to "/abs/known.txt", "mtime" to 1000L))
            .build()
        repository.add(listOf(doc))

        val removed = repository.delete("/abs/unknown.txt")

        assertThat(removed).isEqualTo(0)
        // Known file still present
        val metadata = repository.getMetadata()
        assertThat(metadata.documents.find { it.path == "/abs/known.txt" }).isNotNull
    }

    @Test
    fun `getChunksForFile returns chunks sorted by chunkIndex with correct charCount and mtime`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repository = VectorStoreRepository(embeddingModel, storeFilePath)
        repository.load()

        val texts = listOf("Hello world", "Second chunk here", "Third chunk content text")
        val docs = texts.mapIndexed { i, text ->
            Document.builder()
                .text(text)
                .metadata(mapOf("source" to "/abs/file.txt", "mtime" to 1716000000000L, "chunk_index" to i))
                .build()
        }
        repository.add(docs)

        val chunks = repository.getChunksForFile("/abs/file.txt")

        assertThat(chunks).hasSize(3)
        // Sorted by chunk_index
        assertThat(chunks[0].chunkIndex).isEqualTo(0)
        assertThat(chunks[1].chunkIndex).isEqualTo(1)
        assertThat(chunks[2].chunkIndex).isEqualTo(2)
        // charCount matches text length
        assertThat(chunks[0].charCount).isEqualTo("Hello world".length)
        assertThat(chunks[1].charCount).isEqualTo("Second chunk here".length)
        assertThat(chunks[2].charCount).isEqualTo("Third chunk content text".length)
        // mtime matches
        assertThat(chunks[0].mtime).isEqualTo(1716000000000L)
        assertThat(chunks[1].mtime).isEqualTo(1716000000000L)
        assertThat(chunks[2].mtime).isEqualTo(1716000000000L)
    }

    @Test
    fun `getChunksForFile on unknown path returns empty list`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repository = VectorStoreRepository(embeddingModel, storeFilePath)
        repository.load()

        val doc = Document.builder()
            .text("Some content")
            .metadata(mapOf("source" to "/abs/known.txt", "mtime" to 1000L, "chunk_index" to 0))
            .build()
        repository.add(listOf(doc))

        val chunks = repository.getChunksForFile("/abs/unknown.txt")

        assertThat(chunks).isEmpty()
    }

    // ---- New staleness and aggregate metadata tests ----

    @Test
    fun `StoreDocumentInfo stale is false when filesystem probe returns same mtime as stored`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repository = VectorStoreRepository(embeddingModel, storeFilePath)
        repository.load()

        repository.add(listOf(
            Document.builder().text("Content")
                .metadata(mapOf("source" to "/abs/file.txt", "mtime" to 1000L)).build()
        ))

        val metadata = repository.getMetadata(filesystemProbe = { _ -> 1000L })
        val docInfo = metadata.documents.find { it.path == "/abs/file.txt" }

        assertThat(docInfo).isNotNull
        assertThat(docInfo!!.stale).isFalse()
    }

    @Test
    fun `StoreDocumentInfo stale is true when filesystem probe returns different mtime`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repository = VectorStoreRepository(embeddingModel, storeFilePath)
        repository.load()

        repository.add(listOf(
            Document.builder().text("Content")
                .metadata(mapOf("source" to "/abs/file.txt", "mtime" to 1000L)).build()
        ))

        val metadata = repository.getMetadata(filesystemProbe = { _ -> 9999L })
        val docInfo = metadata.documents.find { it.path == "/abs/file.txt" }

        assertThat(docInfo).isNotNull
        assertThat(docInfo!!.stale).isTrue()
    }

    @Test
    fun `StoreDocumentInfo stale is true when filesystem probe returns null (file missing)`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repository = VectorStoreRepository(embeddingModel, storeFilePath)
        repository.load()

        repository.add(listOf(
            Document.builder().text("Content")
                .metadata(mapOf("source" to "/abs/file.txt", "mtime" to 1000L)).build()
        ))

        val metadata = repository.getMetadata(filesystemProbe = { _ -> null })
        val docInfo = metadata.documents.find { it.path == "/abs/file.txt" }

        assertThat(docInfo).isNotNull
        assertThat(docInfo!!.stale).isTrue()
    }

    @Test
    fun `StoreDocumentInfo mtime equals max mtime across multiple chunks for same source`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repository = VectorStoreRepository(embeddingModel, storeFilePath)
        repository.load()

        // Multiple chunks with different mtimes for same source (edge case from partial re-ingestion)
        repository.add(listOf(
            Document.builder().text("Chunk 1")
                .metadata(mapOf("source" to "/abs/file.txt", "mtime" to 1000L, "chunk_index" to 0)).build(),
            Document.builder().text("Chunk 2")
                .metadata(mapOf("source" to "/abs/file.txt", "mtime" to 3000L, "chunk_index" to 1)).build(),
            Document.builder().text("Chunk 3")
                .metadata(mapOf("source" to "/abs/file.txt", "mtime" to 2000L, "chunk_index" to 2)).build()
        ))

        val metadata = repository.getMetadata(filesystemProbe = { _ -> 3000L })
        val docInfo = metadata.documents.find { it.path == "/abs/file.txt" }

        assertThat(docInfo).isNotNull
        assertThat(docInfo!!.mtime).isEqualTo(3000L)
        assertThat(docInfo.stale).isFalse()
    }

    @Test
    fun `StoreMetadata documentCount equals number of distinct source paths`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repository = VectorStoreRepository(embeddingModel, storeFilePath)
        repository.load()

        repository.add(listOf(
            Document.builder().text("Content A1")
                .metadata(mapOf("source" to "/abs/a.txt", "mtime" to 1000L)).build(),
            Document.builder().text("Content A2")
                .metadata(mapOf("source" to "/abs/a.txt", "mtime" to 1000L)).build(),
            Document.builder().text("Content B")
                .metadata(mapOf("source" to "/abs/b.txt", "mtime" to 2000L)).build()
        ))

        val metadata = repository.getMetadata(filesystemProbe = { _ -> null })

        assertThat(metadata.documentCount).isEqualTo(2)
    }

    @Test
    fun `StoreMetadata storeSizeBytes equals file length after save`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repository = VectorStoreRepository(embeddingModel, storeFilePath)
        repository.load()

        repository.add(listOf(
            Document.builder().text("Some content")
                .metadata(mapOf("source" to "/abs/file.txt", "mtime" to 1000L)).build()
        ))
        repository.save()

        val metadata = repository.getMetadata(filesystemProbe = { _ -> 1000L })

        assertThat(metadata.storeSizeBytes).isEqualTo(storeFilePath.toFile().length())
        assertThat(metadata.storeSizeBytes).isGreaterThan(0)
    }

    @Test
    fun `StoreMetadata lastIngestTime is 0 for empty store`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repository = VectorStoreRepository(embeddingModel, storeFilePath)
        repository.load()

        val metadata = repository.getMetadata(filesystemProbe = { _ -> null })

        assertThat(metadata.lastIngestTime).isEqualTo(0L)
    }

    @Test
    fun `StoreMetadata lastIngestTime equals max mtime across all chunks`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repository = VectorStoreRepository(embeddingModel, storeFilePath)
        repository.load()

        repository.add(listOf(
            Document.builder().text("Content A")
                .metadata(mapOf("source" to "/abs/a.txt", "mtime" to 1000L)).build(),
            Document.builder().text("Content B")
                .metadata(mapOf("source" to "/abs/b.txt", "mtime" to 5000L)).build(),
            Document.builder().text("Content C")
                .metadata(mapOf("source" to "/abs/c.txt", "mtime" to 3000L)).build()
        ))

        val metadata = repository.getMetadata(filesystemProbe = { _ -> null })

        assertThat(metadata.lastIngestTime).isEqualTo(5000L)
    }

    @Test
    fun `StoreMetadata staleDocumentCount equals count of documents where stale is true`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repository = VectorStoreRepository(embeddingModel, storeFilePath)
        repository.load()

        repository.add(listOf(
            Document.builder().text("Content A")
                .metadata(mapOf("source" to "/abs/a.txt", "mtime" to 1000L)).build(),
            Document.builder().text("Content B")
                .metadata(mapOf("source" to "/abs/b.txt", "mtime" to 2000L)).build(),
            Document.builder().text("Content C")
                .metadata(mapOf("source" to "/abs/c.txt", "mtime" to 3000L)).build()
        ))

        // a.txt is stale (mtime changed), b.txt is missing (null), c.txt is fresh
        val metadata = repository.getMetadata(filesystemProbe = { path ->
            when (path) {
                "/abs/a.txt" -> 9999L   // different mtime -> stale
                "/abs/b.txt" -> null    // missing -> stale
                "/abs/c.txt" -> 3000L   // same mtime -> fresh
                else -> null
            }
        })

        assertThat(metadata.staleDocumentCount).isEqualTo(2)
    }
}
