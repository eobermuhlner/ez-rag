package ch.obermuhlner.ezrag.command

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import org.springframework.ai.transformers.TransformersEmbeddingModel
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
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

        val storePath = tempDir.resolve("vector-store.json")
        val absentCacheDir = tempDir.resolve("absent-models") // does not exist

        val out = StringWriter()
        val ingestCommand = IngestCommand(
            embeddingModel = fakeTransformersModel,
            storePathOverride = storePath,
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

        val storePath = tempDir.resolve("vector-store.json")
        val absentCacheDir = tempDir.resolve("absent-models") // does not exist

        val out = StringWriter()
        val ingestCommand = IngestCommand(
            embeddingModel = fakeTransformersModel,
            storePathOverride = storePath,
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

        val storePath = tempDir.resolve("vector-store.json")
        val populatedCacheDir = tempDir.resolve("models")
        populatedCacheDir.toFile().mkdirs()
        populatedCacheDir.resolve("some-model-file.onnx").toFile().writeText("fake model data")

        val out = StringWriter()
        val ingestCommand = IngestCommand(
            embeddingModel = fakeTransformersModel,
            storePathOverride = storePath,
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

        val storePath = tempDir.resolve("vector-store.json")
        val absentCacheDir = tempDir.resolve("absent-models") // does not exist

        val out = StringWriter()
        val ingestCommand = IngestCommand(
            embeddingModel = fakeEmbeddingModel,
            storePathOverride = storePath,
            outputWriter = PrintWriter(out, true),
            modelCachePath = absentCacheDir
        )
        ingestCommand.call(listOf(sampleFile.toFile()))

        val output = out.toString()
        assertThat(output).doesNotContain("Downloading embedding model")
    }
}
