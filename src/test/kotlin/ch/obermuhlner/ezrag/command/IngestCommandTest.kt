package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.config.ConfigService
import ch.obermuhlner.ezrag.config.EzRagConfig
import ch.obermuhlner.ezrag.ingestion.FetchResult
import ch.obermuhlner.ezrag.ingestion.UrlFetcher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import org.springframework.ai.transformers.TransformersEmbeddingModel
import picocli.CommandLine
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path

/**
 * Unit tests for IngestCommand's first-run detection logic.
 *
 * TransformersEmbeddingModel is stubbed (subclassed) to avoid triggering a real ONNX model download.
 * A temp directory is used as the model cache path.
 */
class IngestCommandTest {

    /**
     * A safe stub for TransformersEmbeddingModel that overrides call() to return fake embeddings
     * without initializing the ONNX session or downloading any model.
     */
    private val fakeTransformersModel: TransformersEmbeddingModel = object : TransformersEmbeddingModel() {
        override fun call(request: EmbeddingRequest): EmbeddingResponse {
            val embeddings = request.instructions.mapIndexed { idx, _ ->
                Embedding(FloatArray(4) { 0.25f }, idx)
            }
            return EmbeddingResponse(embeddings)
        }

        override fun embed(text: String): FloatArray = FloatArray(4) { 0.25f }
        override fun embed(document: Document): FloatArray = FloatArray(4) { 0.25f }
        override fun embedForResponse(texts: List<String>): EmbeddingResponse {
            val embeddings = texts.mapIndexed { idx, _ ->
                Embedding(FloatArray(4) { 0.25f }, idx)
            }
            return EmbeddingResponse(embeddings)
        }
        override fun dimensions(): Int = 4
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
    fun `download message appears when TransformersEmbeddingModel and cache dir is absent`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world. This is a test document.")

        val absentCacheDir = tempDir.resolve("absent-models") // does not exist

        val out = StringWriter()
        val ingestCommand = IngestCommand(
            embeddingModel = fakeTransformersModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
            modelCachePath = absentCacheDir
        )
        ingestCommand.call(listOf(sampleFile.toFile()))

        val output = out.toString()
        assertThat(output).contains("Downloading embedding model all-MiniLM-L6-v2 (first run, this may take a moment)…")
    }

    @Test
    fun `download message appears before files ingested line`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world. This is a test document.")

        val absentCacheDir = tempDir.resolve("absent-models") // does not exist

        val out = StringWriter()
        val ingestCommand = IngestCommand(
            embeddingModel = fakeTransformersModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
            modelCachePath = absentCacheDir
        )
        ingestCommand.call(listOf(sampleFile.toFile()))

        val output = out.toString()
        val downloadIdx = output.indexOf("Downloading embedding model")
        val ingestedIdx = output.indexOf("files ingested")
        assertThat(downloadIdx).isGreaterThanOrEqualTo(0)
        assertThat(ingestedIdx).isGreaterThanOrEqualTo(0)
        assertThat(downloadIdx).isLessThan(ingestedIdx)
    }

    @Test
    fun `download message not present when cache dir exists with a file`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world. This is a test document.")

        val populatedCacheDir = tempDir.resolve("models")
        populatedCacheDir.toFile().mkdirs()
        populatedCacheDir.resolve("some-model-file.onnx").toFile().writeText("fake model data")

        val out = StringWriter()
        val ingestCommand = IngestCommand(
            embeddingModel = fakeTransformersModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
            modelCachePath = populatedCacheDir
        )
        ingestCommand.call(listOf(sampleFile.toFile()))

        val output = out.toString()
        assertThat(output).doesNotContain("Downloading embedding model")
    }

    @Test
    fun `download message not present when embedding model is not TransformersEmbeddingModel`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt")
        sampleFile.toFile().writeText("Hello world. This is a test document.")

        val absentCacheDir = tempDir.resolve("absent-models") // does not exist

        val out = StringWriter()
        val ingestCommand = IngestCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
            modelCachePath = absentCacheDir
        )
        ingestCommand.call(listOf(sampleFile.toFile()))

        val output = out.toString()
        assertThat(output).doesNotContain("Downloading embedding model")
    }

    @Test
    fun `default mode prints Ingesting line for each file`(@TempDir tempDir: Path) {
        val file1 = tempDir.resolve("file1.txt").also { it.toFile().writeText("Hello world.") }
        val file2 = tempDir.resolve("file2.txt").also { it.toFile().writeText("Another document.") }
        val out = StringWriter()

        val cmd = IngestCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
        )
        cmd.call(listOf(file1.toFile(), file2.toFile()))

        val output = out.toString()
        assertThat(output).contains("Ingesting: $file1")
        assertThat(output).contains("Ingesting: $file2")
    }

    @Test
    fun `default mode prints Ingesting before summary line`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("file.txt").also { it.toFile().writeText("Hello world.") }
        val out = StringWriter()

        val cmd = IngestCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
        )
        cmd.call(listOf(file.toFile()))

        val output = out.toString()
        val ingestingIdx = output.indexOf("Ingesting:")
        val summaryIdx = output.indexOf("files ingested")
        assertThat(ingestingIdx).isGreaterThanOrEqualTo(0)
        assertThat(ingestingIdx).isLessThan(summaryIdx)
    }

    @Test
    fun `default mode prints Skipping for already-ingested files`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("file.txt").also { it.toFile().writeText("Hello world.") }
        val out = StringWriter()

        val cmd = IngestCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
        )
        cmd.call(listOf(file.toFile()))
        out.buffer.setLength(0)
        cmd.call(listOf(file.toFile()))

        val output = out.toString()
        assertThat(output).contains("Skipping: $file")
        assertThat(output).contains("already ingested")
    }

    @Test
    fun `quiet mode suppresses per-file lines`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("file.txt").also { it.toFile().writeText("Hello world.") }
        val out = StringWriter()

        val cmd = IngestCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
            quiet = true,
        )
        cmd.call(listOf(file.toFile()))

        val output = out.toString()
        assertThat(output).doesNotContain("Ingesting:")
        assertThat(output).contains("files ingested")
    }

    @Test
    fun `verbose mode prints chunk details`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("file.txt").also { it.toFile().writeText("Hello world. This is a test.") }
        val out = StringWriter()

        val cmd = IngestCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
            verbose = true,
        )
        cmd.call(listOf(file.toFile()))

        val output = out.toString()
        assertThat(output).contains("Chunk 0")
        assertThat(output).containsPattern(Regex("\\[\\d+ tokens\\]").toPattern())
    }

    @Test
    fun `--details flag causes picocli UnmatchedArgumentException`(@TempDir tempDir: Path) {
        val cmd = IngestCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(StringWriter(), true),
        )
        val commandLine = CommandLine(cmd)
        val exitCode = commandLine.execute("--details", "somefile.txt")
        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.USAGE)
    }

    @Test
    fun `STORE_DIR env var bypasses parent directory walk`(@TempDir tempDir: Path) {
        // Set up a parent directory with .ez-rag/ that would be found by the walk
        val parentEzRagDir = tempDir.resolve(".ez-rag")
        parentEzRagDir.toFile().mkdirs()

        // Set up an explicit store dir via ConfigService (simulating STORE_DIR env var)
        val explicitStoreDir = tempDir.resolve("explicit-store")
        explicitStoreDir.toFile().mkdirs()

        val subDir = tempDir.resolve("sub")
        subDir.toFile().mkdirs()

        val file = subDir.resolve("doc.txt")
        file.toFile().writeText("Content for explicit store.")

        // Simulate STORE_DIR env var via ConfigService
        val configService = ConfigService(
            configFileSource = { null },
            envVars = mapOf("STORE_DIR" to explicitStoreDir.toString())
        )

        val out = StringWriter()
        val cmd = IngestCommand(
            embeddingModel = fakeEmbeddingModel,
            startDirOverride = subDir,
            outputWriter = PrintWriter(out, true),
            configServiceOverride = configService,
        )
        val exitCode = cmd.call(listOf(file.toFile()))

        assertThat(exitCode).isEqualTo(0)
        // Store should be in the explicit store dir, not the parent .ez-rag/
        val explicitStoreDir2 = explicitStoreDir.resolve("lucene")
        assertThat(explicitStoreDir2.toFile()).exists()
        val parentStoreDir = parentEzRagDir.resolve("lucene")
        assertThat(parentStoreDir.toFile()).doesNotExist()
    }

    @Test
    fun `storeDir config file entry bypasses parent directory walk`(@TempDir tempDir: Path) {
        // Set up a parent directory with .ez-rag/ that would be found by the walk
        val parentEzRagDir = tempDir.resolve(".ez-rag")
        parentEzRagDir.toFile().mkdirs()

        // Set up an explicit store dir via ConfigService (simulating storeDir config entry)
        val explicitStoreDir = tempDir.resolve("config-store")
        explicitStoreDir.toFile().mkdirs()

        val subDir = tempDir.resolve("sub")
        subDir.toFile().mkdirs()

        val file = subDir.resolve("doc.txt")
        file.toFile().writeText("Content for config store.")

        // Simulate storeDir in config file via ConfigService
        val configService = ConfigService(
            configFileSource = { EzRagConfig(storeDir = explicitStoreDir.toString()) },
            envVars = emptyMap()
        )

        val out = StringWriter()
        val cmd = IngestCommand(
            embeddingModel = fakeEmbeddingModel,
            startDirOverride = subDir,
            outputWriter = PrintWriter(out, true),
            configServiceOverride = configService,
        )
        val exitCode = cmd.call(listOf(file.toFile()))

        assertThat(exitCode).isEqualTo(0)
        // Store should be in the config-specified dir, not the parent .ez-rag/
        val explicitLuceneDir = explicitStoreDir.resolve("lucene")
        assertThat(explicitLuceneDir.toFile()).exists()
        val parentLuceneDir = parentEzRagDir.resolve("lucene")
        assertThat(parentLuceneDir.toFile()).doesNotExist()
    }

    @Test
    fun `ingest invoked from subdirectory writes to the store in the parent directory`(@TempDir tempDir: Path) {
        // Set up: .ez-rag/ exists in tempDir (the "project root")
        val ezRagDir = tempDir.resolve(".ez-rag")
        ezRagDir.toFile().mkdirs()

        // Create a subdirectory to simulate running from a child directory
        val subDir = tempDir.resolve("sub")
        subDir.toFile().mkdirs()

        // Create a file in the subdirectory to ingest
        val file = subDir.resolve("doc.txt")
        file.toFile().writeText("Hello from subdirectory.")

        val out = StringWriter()
        val cmd = IngestCommand(
            embeddingModel = fakeEmbeddingModel,
            // No storeDirOverride — let resolver walk up from subDir
            startDirOverride = subDir,
            outputWriter = PrintWriter(out, true),
        )
        val exitCode = cmd.call(listOf(file.toFile()))

        assertThat(exitCode).isEqualTo(0)
        // The Lucene store should be in the parent's .ez-rag/, not in subDir/.ez-rag/
        val parentLuceneDir = ezRagDir.resolve("lucene")
        assertThat(parentLuceneDir.toFile()).exists()
        // No .ez-rag/ should be created in subDir
        val subLuceneDir = subDir.resolve(".ez-rag").resolve("lucene")
        assertThat(subLuceneDir.toFile()).doesNotExist()
    }

    @Test
    fun `http-prefixed argument is routed as URL source not file path`(@TempDir tempDir: Path) {
        val url = "https://example.com/page.html"
        val html = "<html><head><title>Test</title></head><body><p>Content.</p></body></html>"
        val fakeUrlFetcher = object : UrlFetcher {
            override fun fetch(fetchUrl: String) =
                FetchResult(html.toByteArray(), "text/html", 0L, 200)
        }

        val out = StringWriter()
        val warnings = StringWriter()
        val cmd = IngestCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
            warningWriter = PrintWriter(warnings, true),
            urlFetcher = fakeUrlFetcher,
        )
        cmd.paths = listOf(url)
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        // File-path handling would produce "Path does not exist" for an http:// string
        assertThat(warnings.toString()).doesNotContain("Path does not exist")
        assertThat(out.toString()).contains("1 files ingested")
    }
}
