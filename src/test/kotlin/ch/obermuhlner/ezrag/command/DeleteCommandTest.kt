package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.VectorStoreRepository
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

class DeleteCommandTest {

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

    private fun createRepository(storeFilePath: Path): VectorStoreRepository {
        val repo = VectorStoreRepository(fakeEmbeddingModel, storeFilePath)
        repo.load()
        return repo
    }

    private fun ingestFile(repo: VectorStoreRepository, absolutePath: String, chunkCount: Int, mtime: Long = 1000L) {
        val docs = (0 until chunkCount).map { i ->
            Document.builder()
                .text("Chunk $i of ${absolutePath}")
                .metadata(mapOf("source" to absolutePath, "mtime" to mtime, "chunk_index" to i))
                .build()
        }
        repo.add(docs)
        repo.save()
    }

    @Test
    fun `delete removes ingested file from store`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repo = createRepository(storeFilePath)
        val fileToDelete = tempDir.resolve("file.txt").toAbsolutePath().toString()
        ingestFile(repo, fileToDelete, chunkCount = 3)

        val out = StringWriter()
        val cmd = DeleteCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
        )
        cmd.filePaths = listOf(fileToDelete)
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        // Reload and verify the file is gone
        val repo2 = createRepository(storeFilePath)
        assertThat(repo2.getMetadata().documents.find { it.path == fileToDelete }).isNull()
    }

    @Test
    fun `delete prints Deleted line with chunk count by default`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repo = createRepository(storeFilePath)
        val fileToDelete = tempDir.resolve("file.txt").toAbsolutePath().toString()
        ingestFile(repo, fileToDelete, chunkCount = 3)

        val out = StringWriter()
        val cmd = DeleteCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
        )
        cmd.filePaths = listOf(fileToDelete)
        cmd.call()

        assertThat(out.toString()).contains("Deleted: $fileToDelete (3 chunks)")
    }

    @Test
    fun `delete with --quiet produces no output on success`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repo = createRepository(storeFilePath)
        val fileToDelete = tempDir.resolve("file.txt").toAbsolutePath().toString()
        ingestFile(repo, fileToDelete, chunkCount = 2)

        val out = StringWriter()
        val cmd = DeleteCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
            quiet = true,
        )
        cmd.filePaths = listOf(fileToDelete)
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString()).isEmpty()
    }

    @Test
    fun `delete of unknown file prints warning and exits 0`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        createRepository(storeFilePath) // empty store

        val unknownPath = tempDir.resolve("unknown.txt").toAbsolutePath().toString()
        val out = StringWriter()
        val cmd = DeleteCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
        )
        cmd.filePaths = listOf(unknownPath)
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString()).contains("Warning: not found in store: $unknownPath")
    }

    @Test
    fun `delete multiple files deletes both and prints result for each`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repo = createRepository(storeFilePath)
        val file1 = tempDir.resolve("file1.txt").toAbsolutePath().toString()
        val file2 = tempDir.resolve("file2.txt").toAbsolutePath().toString()
        ingestFile(repo, file1, chunkCount = 2)
        ingestFile(repo, file2, chunkCount = 4)

        val out = StringWriter()
        val cmd = DeleteCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
        )
        cmd.filePaths = listOf(file1, file2)
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        val output = out.toString()
        assertThat(output).contains("Deleted: $file1 (2 chunks)")
        assertThat(output).contains("Deleted: $file2 (4 chunks)")

        val repo2 = createRepository(storeFilePath)
        assertThat(repo2.getMetadata().documents).isEmpty()
    }
}
