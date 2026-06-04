package ch.obermuhlner.ezrag.ingestion

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import picocli.CommandLine
import ch.obermuhlner.ezrag.EzRagCommand
import ch.obermuhlner.ezrag.command.IngestCommand
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.time.Instant

class IngestIntegrationTest {

    private fun createCommandLine(storeDirPath: Path, embeddingModel: EmbeddingModel): CommandLine {
        val ingestCommand = IngestCommand(embeddingModel)
        val ezRagCommand = EzRagCommand()
        val cmdLine = CommandLine(ezRagCommand)
        cmdLine.addSubcommand("ingest", ingestCommand)
        return cmdLine
    }

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
    fun `ingest a txt file exits 0 and store directory exists`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world. This is a test document for ingestion.")

        val storeDir = tempDir.resolve("store")
        val luceneDir = storeDir.resolve("lucene")

        val ingestCommand = IngestCommand(fakeEmbeddingModel, storeDir)
        val exitCode = ingestCommand.call(listOf(sampleFile.toFile()))

        assertThat(exitCode).isEqualTo(0)
        assertThat(luceneDir.toFile()).exists()
        assertThat(luceneDir.toFile()).isDirectory()
    }

    @Test
    fun `ingest a txt file creates a readable Lucene index`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world. This is a test document for ingestion.")

        val ingestCommand = IngestCommand(fakeEmbeddingModel, tempDir)
        ingestCommand.call(listOf(sampleFile.toFile()))

        assertThat(LuceneRepository.storeExists(tempDir)).isTrue()
    }

    @Test
    fun `ingest summary contains correct counts`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world. This is a test document for ingestion.")

        val out = StringWriter()
        val ingestCommand = IngestCommand(fakeEmbeddingModel, tempDir, PrintWriter(out))
        ingestCommand.call(listOf(sampleFile.toFile()))

        val output = out.toString()
        assertThat(output).contains("1 files ingested")
        assertThat(output).contains("0 skipped")
        // chunk count should be at least 1
        assertThat(output).containsPattern("\\d+ chunks created")
    }

    @Test
    fun `ingest is idempotent when store directory already exists`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world. This is a test document.")

        val storeDir = tempDir.resolve("store")
        storeDir.toFile().mkdirs() // directory already exists

        val ingestCommand = IngestCommand(fakeEmbeddingModel, storeDir)

        // First ingest
        val exitCode1 = ingestCommand.call(listOf(sampleFile.toFile()))
        // Second ingest with new command instance (new store state)
        val ingestCommand2 = IngestCommand(fakeEmbeddingModel, storeDir)
        val exitCode2 = ingestCommand2.call(listOf(sampleFile.toFile()))

        assertThat(exitCode1).isEqualTo(0)
        assertThat(exitCode2).isEqualTo(0)
    }

    @Test
    fun `ingest a mixed directory produces 3 files ingested and warning for unsupported file`(@TempDir tempDir: Path) {
        // Create a temp directory with supported and unsupported files
        val docsDir = tempDir.resolve("docs")
        docsDir.toFile().mkdirs()
        docsDir.resolve("file.txt").toFile().writeText("Text content for ingestion testing.")
        docsDir.resolve("file.md").toFile().writeText("# Markdown\n\nSome markdown content here.")

        // Use a real PDF from test resources
        val pdfSource = Paths.get(javaClass.getResource("/documents/sample.pdf")!!.toURI())
        docsDir.resolve("file.pdf").toFile().writeBytes(pdfSource.toFile().readBytes())

        docsDir.resolve("file.xyz").toFile().writeText("unsupported content")

        val out = StringWriter()
        val warn = StringWriter()
        val ingestCommand = IngestCommand(fakeEmbeddingModel, tempDir, PrintWriter(out), PrintWriter(warn))
        val exitCode = ingestCommand.call(listOf(docsDir.toFile()))

        assertThat(exitCode).isEqualTo(0)
        val summary = out.toString()
        assertThat(summary).contains("3 files ingested")

        val warnings = warn.toString()
        assertThat(warnings).contains("file.xyz")
    }

    @Test
    fun `second ingest of unchanged file prints 0 files ingested 0 chunks created 1 skipped`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world. This is a test document for deduplication.")

        // First ingest
        val out1 = StringWriter()
        val ingestCommand1 = IngestCommand(fakeEmbeddingModel, tempDir, PrintWriter(out1))
        val exitCode1 = ingestCommand1.call(listOf(sampleFile.toFile()))
        assertThat(exitCode1).isEqualTo(0)
        assertThat(out1.toString()).contains("1 files ingested")

        // Second ingest without modifying the file
        val out2 = StringWriter()
        val ingestCommand2 = IngestCommand(fakeEmbeddingModel, tempDir, PrintWriter(out2))
        val exitCode2 = ingestCommand2.call(listOf(sampleFile.toFile()))
        assertThat(exitCode2).isEqualTo(0)
        val summary2 = out2.toString()
        assertThat(summary2).contains("0 files ingested")
        assertThat(summary2).contains("0 chunks created")
        assertThat(summary2).contains("1 skipped")
    }

    @Test
    fun `store chunk count after two identical runs equals chunk count after first run`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world. This is a test document for deduplication.")

        // First ingest
        val out1 = StringWriter()
        val ingestCommand1 = IngestCommand(fakeEmbeddingModel, tempDir, PrintWriter(out1))
        ingestCommand1.call(listOf(sampleFile.toFile()))
        val summary1 = out1.toString()
        // Extract chunk count from first run
        val chunkPattern = Regex("(\\d+) chunks created")
        val chunksAfterFirst = chunkPattern.find(summary1)!!.groupValues[1].toInt()

        // Second ingest without modifying the file
        val out2 = StringWriter()
        val ingestCommand2 = IngestCommand(fakeEmbeddingModel, tempDir, PrintWriter(out2))
        ingestCommand2.call(listOf(sampleFile.toFile()))
        // File was skipped, so chunks created = 0
        assertThat(out2.toString()).contains("0 chunks created")

        // Verify the Lucene index still exists and has the same chunk count
        assertThat(LuceneRepository.storeExists(tempDir)).isTrue()
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repo ->
            val metadata = repo.getMetadata()
            assertThat(metadata.chunkCount).isEqualTo(chunksAfterFirst)
        }
    }

    @Test
    fun `after advancing mtime with unchanged content file is skipped`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world. This is a test document for mtime check.")

        // First ingest
        val out1 = StringWriter()
        val ingestCommand1 = IngestCommand(fakeEmbeddingModel, tempDir, PrintWriter(out1))
        ingestCommand1.call(listOf(sampleFile.toFile()))
        assertThat(out1.toString()).contains("1 files ingested")

        // Advance the mtime but do NOT change the content
        val futureTime = FileTime.from(Instant.now().plusSeconds(3600))
        Files.setLastModifiedTime(sampleFile, futureTime)

        // Second ingest — content hash unchanged, so the file is skipped despite mtime change
        val out2 = StringWriter()
        val ingestCommand2 = IngestCommand(fakeEmbeddingModel, tempDir, PrintWriter(out2))
        val exitCode2 = ingestCommand2.call(listOf(sampleFile.toFile()))
        assertThat(exitCode2).isEqualTo(0)
        assertThat(out2.toString()).contains("0 files ingested")
        assertThat(out2.toString()).contains("1 skipped")
    }

    @Test
    fun `after advancing mtime with changed content file is re-ingested`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world. This is a test document for mtime check.")

        // First ingest
        val out1 = StringWriter()
        val ingestCommand1 = IngestCommand(fakeEmbeddingModel, tempDir, PrintWriter(out1))
        ingestCommand1.call(listOf(sampleFile.toFile()))
        assertThat(out1.toString()).contains("1 files ingested")

        // Change the content AND advance the mtime
        sampleFile.toFile().writeText("Completely different content for re-ingest test.")
        val futureTime = FileTime.from(Instant.now().plusSeconds(3600))
        Files.setLastModifiedTime(sampleFile, futureTime)

        // Second ingest — content changed, so re-ingestion happens
        val out2 = StringWriter()
        val ingestCommand2 = IngestCommand(fakeEmbeddingModel, tempDir, PrintWriter(out2))
        val exitCode2 = ingestCommand2.call(listOf(sampleFile.toFile()))
        assertThat(exitCode2).isEqualTo(0)
        assertThat(out2.toString()).contains("1 files ingested")
    }

    @Test
    fun `small chunk size produces more chunks than default on same file`(@TempDir tempDir: Path) {
        // Create a multi-paragraph file that will produce multiple chunks with a small chunk size
        val multiParaContent = (1..20).joinToString("\n\n") { i ->
            "Paragraph $i: " + "word ".repeat(50)
        }
        val sampleFile = tempDir.resolve("multi-para.txt")
        sampleFile.toFile().writeText(multiParaContent)

        // Ingest with default settings (1000 tokens, 200 overlap)
        val storeDir1 = tempDir.resolve("store-default")
        val out1 = StringWriter()
        val ingestCommandDefault = IngestCommand(fakeEmbeddingModel, storeDir1, PrintWriter(out1))
        ingestCommandDefault.call(listOf(sampleFile.toFile()))
        val defaultChunkPattern = Regex("(\\d+) chunks created")
        val defaultChunks = defaultChunkPattern.find(out1.toString())!!.groupValues[1].toInt()

        // Ingest with small chunk size (200 tokens, 50 overlap)
        val storeDir2 = tempDir.resolve("store-small")
        val out2 = StringWriter()
        val ingestCommandSmall = IngestCommand(fakeEmbeddingModel, storeDir2, PrintWriter(out2),
            chunkSize = 200, chunkOverlap = 50)
        ingestCommandSmall.call(listOf(sampleFile.toFile()))
        val smallChunkPattern = Regex("(\\d+) chunks created")
        val smallChunks = smallChunkPattern.find(out2.toString())!!.groupValues[1].toInt()

        assertThat(smallChunks).isGreaterThan(defaultChunks)
    }

    @Test
    fun `custom store dir writes store to specified directory and not to default path`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world. This is a test document for custom store path.")

        val customStoreDir = tempDir.resolve("custom-store")
        val defaultStoreDir = tempDir.resolve(".ez-rag")

        val ingestCommand = IngestCommand(fakeEmbeddingModel, customStoreDir)
        val exitCode = ingestCommand.call(listOf(sampleFile.toFile()))

        assertThat(exitCode).isEqualTo(0)
        assertThat(LuceneRepository.storeExists(customStoreDir)).isTrue()
        assertThat(defaultStoreDir.resolve("lucene").toFile()).doesNotExist()
    }

    @Test
    fun `verbose ingest produces Ingesting and Chunk lines`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText(
            "Hello world. This is a test document for verbose output. " +
            "It has enough content to produce at least one chunk."
        )

        val out = StringWriter()
        val ingestCommand = IngestCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out),
            verbose = true
        )
        val exitCode = ingestCommand.call(listOf(sampleFile.toFile()))

        assertThat(exitCode).isEqualTo(0)
        val output = out.toString()
        assertThat(output).containsPattern("Ingesting:.*sample\\.txt")
        assertThat(output).containsPattern("Chunk \\d+ \\[\\d+ tokens\\]:")
    }

    @Test
    fun `missing embedding model exits non-zero with human-readable error and no store directory created`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world.")

        val storeDir = tempDir.resolve(".ez-rag")

        val out = StringWriter()
        // Pass null embedding model to simulate missing/misconfigured provider
        val ingestCommand = IngestCommand(
            embeddingModel = null,
            storeDirOverride = storeDir,
            outputWriter = PrintWriter(out)
        )
        val exitCode = ingestCommand.call(listOf(sampleFile.toFile()))

        assertThat(exitCode).isNotEqualTo(0)
        val output = out.toString()
        // Should contain a human-readable error, not start with a stack trace
        assertThat(output).contains("Error:")
        // Store directory must not have been created
        assertThat(storeDir.toFile()).doesNotExist()
    }

    @Test
    fun `preflight check fails before file IO when embedding model throws`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world.")

        val storeDir = tempDir.resolve(".ez-rag")

        // Simulate a misconfigured provider (e.g., missing API key) that throws on any embed call
        val brokenEmbeddingModel: EmbeddingModel = object : EmbeddingModel {
            override fun call(request: EmbeddingRequest): EmbeddingResponse =
                throw IllegalStateException("Missing API key")
            override fun embed(document: Document): FloatArray =
                throw IllegalStateException("Missing API key")
            override fun embed(text: String): FloatArray =
                throw IllegalStateException("Missing API key")
            override fun embedForResponse(texts: List<String>): EmbeddingResponse =
                throw IllegalStateException("Missing API key")
            override fun dimensions(): Int = 4
        }

        val out = StringWriter()
        val ingestCommand = IngestCommand(
            embeddingModel = brokenEmbeddingModel,
            storeDirOverride = storeDir,
            outputWriter = PrintWriter(out)
        )
        val exitCode = ingestCommand.call(listOf(sampleFile.toFile()))

        assertThat(exitCode).isNotEqualTo(0)
        val output = out.toString()
        // Should contain a human-readable error message
        assertThat(output).contains("Error:")
        // Error message must NOT start with a stack trace (first line should be the human-readable message)
        assertThat(output.trimStart()).doesNotStartWith("Exception")
        assertThat(output.trimStart()).doesNotStartWith("java.")
        // Store directory must not have been created (pre-flight ran before file I/O)
        assertThat(storeDir.toFile()).doesNotExist()
    }
}
