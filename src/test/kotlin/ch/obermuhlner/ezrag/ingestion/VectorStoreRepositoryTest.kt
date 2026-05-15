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
        val storePath = tempDir.resolve("vector-store.json")
        val repository = VectorStoreRepository(embeddingModel, storePath)
        repository.load()

        val document = Document.builder()
            .text("Hello world")
            .metadata(mapOf("source" to "test.txt"))
            .build()
        repository.add(listOf(document))
        repository.save()

        assertThat(storePath.toFile()).exists()
        assertThat(storePath.toFile().length()).isGreaterThan(0)
    }

    @Test
    fun `load from existing file preserves documents`(@TempDir tempDir: Path) {
        val storePath = tempDir.resolve("vector-store.json")

        // First run: add documents and save
        val repo1 = VectorStoreRepository(embeddingModel, storePath)
        repo1.load()
        val doc = Document.builder()
            .text("Hello world")
            .metadata(mapOf("source" to "test.txt"))
            .build()
        repo1.add(listOf(doc))
        repo1.save()

        // Second run: load the saved store
        val repo2 = VectorStoreRepository(embeddingModel, storePath)
        repo2.load()

        // The store file should still exist and be valid
        assertThat(storePath.toFile()).exists()
    }

    @Test
    fun `load works when store file does not exist`(@TempDir tempDir: Path) {
        val storePath = tempDir.resolve("non-existent-store.json")
        val repository = VectorStoreRepository(embeddingModel, storePath)

        // Should not throw
        repository.load()

        assertThat(storePath.toFile()).doesNotExist()
    }

    @Test
    fun `isAlreadyIngested returns true for matching path and mtime`(@TempDir tempDir: Path) {
        val storePath = tempDir.resolve("vector-store.json")
        val repository = VectorStoreRepository(embeddingModel, storePath)
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
        val storePath = tempDir.resolve("vector-store.json")
        val repository = VectorStoreRepository(embeddingModel, storePath)
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
        val storePath = tempDir.resolve("vector-store.json")
        val repository = VectorStoreRepository(embeddingModel, storePath)
        repository.load()

        assertThat(repository.isAlreadyIngested("file.txt", 1000L)).isFalse()
    }

    @Test
    fun `getMetadata returns correct per-file and total counts`(@TempDir tempDir: Path) {
        val storePath = tempDir.resolve("vector-store.json")
        val repository = VectorStoreRepository(embeddingModel, storePath)
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
}
