package ch.obermuhlner.ezrag

import ch.obermuhlner.ezrag.command.IngestCommand
import ch.obermuhlner.ezrag.command.SearchCommand
import ch.obermuhlner.ezrag.ingestion.VectorStoreRepository
import ch.obermuhlner.ezrag.rag.EmbeddingSearchPipeline
import ch.obermuhlner.ezrag.rag.OutputFormatter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import java.io.ByteArrayInputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path

/**
 * End-to-end integration tests for the embedding search pipeline.
 *
 * Uses a fake EmbeddingModel (returns fixed vectors) to exercise real disk I/O.
 * No Spring context, no live LLM, no live embedding API.
 */
class EmbeddingSearchIntegrationTest {

    private val fakeEmbeddingModel: EmbeddingModel = object : EmbeddingModel {
        override fun call(request: EmbeddingRequest): EmbeddingResponse {
            val embeddings = request.instructions.mapIndexed { idx, _ ->
                Embedding(FloatArray(4) { 0.25f }, idx)
            }
            return EmbeddingResponse(embeddings)
        }

        override fun embed(document: Document): FloatArray = FloatArray(4) { 0.25f }
        override fun embed(text: String): FloatArray = FloatArray(4) { 0.25f }

        override fun embedForResponse(texts: List<String>): EmbeddingResponse {
            val embeddings = texts.mapIndexed { idx, _ ->
                Embedding(FloatArray(4) { 0.25f }, idx)
            }
            return EmbeddingResponse(embeddings)
        }

        override fun dimensions(): Int = 4
    }

    private fun ingest(files: List<java.io.File>, storeDir: Path): Int {
        val cmd = IngestCommand(embeddingModel = fakeEmbeddingModel, storeDirOverride = storeDir)
        return cmd.call(files)
    }

    private fun buildSearchCommand(
        storeDir: Path,
        out: StringWriter,
        err: StringWriter,
        inputStream: ByteArrayInputStream = ByteArrayInputStream(ByteArray(0)),
    ): SearchCommand {
        val repo = VectorStoreRepository(fakeEmbeddingModel, storeDir)
        repo.load()
        val pipeline = EmbeddingSearchPipeline(repo, fakeEmbeddingModel)
        return SearchCommand(
            storeDirOverride = storeDir,
            searchPipeline = pipeline,
            repositoryForVerbose = repo,
            outputFormatter = OutputFormatter(),
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
            inputStream = inputStream,
        )
    }

    // -----------------------------------------------------------------------
    // Test 1: ingest a small .txt file, then search for a term in the file;
    //         assert at least one ChunkMatch returned whose filePath matches
    //         the ingested file's path string.
    // -----------------------------------------------------------------------

    @Test
    fun `after ingesting a text file searching returns chunks whose filePath matches the ingested file`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("knowledge.txt").toFile()
        sampleFile.writeText(
            "The project architecture uses microservices deployed on Kubernetes. " +
            "Each service communicates via REST APIs and message queues."
        )

        assertThat(ingest(listOf(sampleFile), tempDir)).isEqualTo(0)

        val out = StringWriter()
        val err = StringWriter()
        val cmd = buildSearchCommand(tempDir, out, err)
        cmd.questionArgs = listOf("architecture")

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        val output = out.toString()
        // The output in text format should contain at least one result
        assertThat(output.trim()).isNotEmpty()
        // The source should contain the ingested file path
        assertThat(output).contains(sampleFile.absolutePath)
    }

    // -----------------------------------------------------------------------
    // Test 2: --output json returns a top-level "chunks" key
    // -----------------------------------------------------------------------

    @Test
    fun `search with --output json returns valid JSON with chunks key`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("data.txt").toFile()
        sampleFile.writeText("Machine learning models process embeddings efficiently.")

        ingest(listOf(sampleFile), tempDir)

        val out = StringWriter()
        val err = StringWriter()
        val cmd = buildSearchCommand(tempDir, out, err)
        cmd.questionArgs = listOf("embeddings")
        cmd.outputFormat = "json"

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        val jsonText = out.toString().trim()
        assertThat(jsonText).startsWith("{")
        assertThat(jsonText).contains("\"chunks\"")
    }
}
