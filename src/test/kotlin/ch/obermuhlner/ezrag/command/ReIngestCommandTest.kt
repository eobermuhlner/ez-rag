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
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

class ReIngestCommandTest {

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

    private fun ingestDoc(storeDir: Path, file: Path) {
        val absolutePath = file.toAbsolutePath().normalize().toString()
        val mtime = file.toFile().lastModified()
        val doc = Document.builder()
            .text(file.toFile().readText())
            .metadata(mapOf("source" to absolutePath, "mtime" to mtime, "chunk_index" to 0))
            .build()
        LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard").use { repo ->
            repo.add(listOf(doc))
        }
    }

    @Test
    fun `output contains Stale documents line and summary when stale document exists`(@TempDir tempDir: Path) {
        val storeDir = tempDir.resolve("store")
        val sourceFile = tempDir.resolve("doc.txt")
        sourceFile.toFile().writeText("original content")
        ingestDoc(storeDir, sourceFile)

        // Make file stale
        val futureTime = FileTime.from(Instant.now().plusSeconds(3600))
        Files.setLastModifiedTime(sourceFile, futureTime)
        sourceFile.toFile().writeText("updated content")

        val out = StringWriter()
        val warn = StringWriter()
        val cmd = ReIngestCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = storeDir,
            outputWriter = PrintWriter(out, true),
            warningWriter = PrintWriter(warn, true),
        )
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        val output = out.toString()
        assertThat(output).contains("Stale documents: 1")
        assertThat(output).containsPattern("\\d+ files re-ingested, \\d+ chunks created, \\d+ skipped")
    }

    @Test
    fun `summary line matches expected format with correct counts`(@TempDir tempDir: Path) {
        val storeDir = tempDir.resolve("store")
        val sourceFile = tempDir.resolve("doc.txt")
        sourceFile.toFile().writeText("content to ingest")
        ingestDoc(storeDir, sourceFile)

        // Make file stale
        val futureTime = FileTime.from(Instant.now().plusSeconds(3600))
        Files.setLastModifiedTime(sourceFile, futureTime)
        sourceFile.toFile().writeText("updated content")

        val out = StringWriter()
        val cmd = ReIngestCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = storeDir,
            outputWriter = PrintWriter(out, true),
        )
        cmd.call()

        val output = out.toString()
        assertThat(output).contains("1 files re-ingested")
        assertThat(output).contains("0 skipped")
    }

    @Test
    fun `quiet suppresses per-file Re-ingesting lines but shows summary`(@TempDir tempDir: Path) {
        val storeDir = tempDir.resolve("store")
        val sourceFile = tempDir.resolve("doc.txt")
        sourceFile.toFile().writeText("some content")
        ingestDoc(storeDir, sourceFile)

        // Make file stale
        val futureTime = FileTime.from(Instant.now().plusSeconds(3600))
        Files.setLastModifiedTime(sourceFile, futureTime)
        sourceFile.toFile().writeText("new content")

        val out = StringWriter()
        val cmd = ReIngestCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = storeDir,
            outputWriter = PrintWriter(out, true),
            quiet = true,
        )
        cmd.call()

        val output = out.toString()
        assertThat(output).doesNotContain("Re-ingesting:")
        assertThat(output).contains("files re-ingested")
    }

    @Test
    fun `store-dir targets the specified store directory`(@TempDir tempDir: Path) {
        val customStoreDir = tempDir.resolve("custom-store")
        val defaultStoreDir = tempDir.resolve("default-store")

        val sourceFile = tempDir.resolve("doc.txt")
        sourceFile.toFile().writeText("some content")

        // Ingest into the custom store
        ingestDoc(customStoreDir, sourceFile)

        // Make file stale
        val futureTime = FileTime.from(Instant.now().plusSeconds(3600))
        Files.setLastModifiedTime(sourceFile, futureTime)
        sourceFile.toFile().writeText("updated content")

        val out = StringWriter()
        val cmd = ReIngestCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = customStoreDir,
            outputWriter = PrintWriter(out, true),
        )
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        // Custom store should be updated, default store should not exist
        assertThat(customStoreDir.resolve("lucene").toFile()).exists()
        assertThat(defaultStoreDir.resolve("lucene").toFile()).doesNotExist()
        val output = out.toString()
        assertThat(output).contains("1 files re-ingested")
    }

    @Test
    fun `unchanged document is not re-ingested and summary shows 0 re-ingested`(@TempDir tempDir: Path) {
        val storeDir = tempDir.resolve("store")
        val sourceFile = tempDir.resolve("doc.txt")
        sourceFile.toFile().writeText("unchanged content")
        ingestDoc(storeDir, sourceFile)

        val out = StringWriter()
        val cmd = ReIngestCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = storeDir,
            outputWriter = PrintWriter(out, true),
        )
        cmd.call()

        val output = out.toString()
        assertThat(output).contains("Stale documents: 0")
        assertThat(output).contains("0 files re-ingested")
    }

    @Test
    fun `reingest --all re-ingests all documents including unchanged ones`(@TempDir tempDir: Path) {
        val storeDir = tempDir.resolve("store")
        val file1 = tempDir.resolve("doc1.txt")
        val file2 = tempDir.resolve("doc2.txt")
        file1.toFile().writeText("first document content")
        file2.toFile().writeText("second document content")
        ingestDoc(storeDir, file1)
        ingestDoc(storeDir, file2)

        // Neither file is stale
        val out = StringWriter()
        val cmd = ReIngestCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = storeDir,
            outputWriter = PrintWriter(out, true),
        )
        cmd.forceAllOption = true
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        val output = out.toString()
        // Both files should be re-ingested even though neither is stale
        assertThat(output).contains("2 files re-ingested")
        assertThat(output).contains("0 skipped")
    }

    @Test
    fun `reingest --all output does not contain Stale documents line`(@TempDir tempDir: Path) {
        val storeDir = tempDir.resolve("store")
        val sourceFile = tempDir.resolve("doc.txt")
        sourceFile.toFile().writeText("some content")
        ingestDoc(storeDir, sourceFile)

        val out = StringWriter()
        val cmd = ReIngestCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = storeDir,
            outputWriter = PrintWriter(out, true),
        )
        cmd.forceAllOption = true
        cmd.call()

        val output = out.toString()
        assertThat(output).doesNotContain("Stale documents:")
        assertThat(output).contains("files re-ingested")
    }

    @Test
    fun `reingest without --all still shows Stale documents line`(@TempDir tempDir: Path) {
        val storeDir = tempDir.resolve("store")
        val sourceFile = tempDir.resolve("doc.txt")
        sourceFile.toFile().writeText("some content")
        ingestDoc(storeDir, sourceFile)

        val out = StringWriter()
        val cmd = ReIngestCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = storeDir,
            outputWriter = PrintWriter(out, true),
        )
        // forceAllOption is false by default
        cmd.call()

        val output = out.toString()
        assertThat(output).contains("Stale documents:")
    }
}
