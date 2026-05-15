package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.VectorStoreRepository
import ch.obermuhlner.ezrag.rag.ChunkMatch
import ch.obermuhlner.ezrag.rag.EmbeddingSearchPipeline
import ch.obermuhlner.ezrag.rag.OutputFormatter
import ch.obermuhlner.ezrag.rag.SearchQuery
import ch.obermuhlner.ezrag.rag.SearchResult
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

class SearchCommandTest {

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

    private fun createPopulatedRepository(storePath: Path): VectorStoreRepository {
        val repo = VectorStoreRepository(fakeEmbeddingModel, storePath)
        repo.load()
        val doc = Document.builder()
            .text("Test content for searching.")
            .metadata(mapOf("source" to "test.txt", "chunk_index" to 0, "mtime" to 1000L))
            .build()
        repo.add(listOf(doc))
        repo.save()
        return repo
    }

    private fun createSearchCommand(
        storePath: Path,
        out: StringWriter,
        err: StringWriter,
        inputStream: ByteArrayInputStream = ByteArrayInputStream(ByteArray(0)),
        pipeline: EmbeddingSearchPipeline? = null,
        formatter: OutputFormatter = OutputFormatter(),
    ): SearchCommand {
        val repo = VectorStoreRepository(fakeEmbeddingModel, storePath)
        repo.load()
        val searchPipeline = pipeline ?: EmbeddingSearchPipeline(repo, fakeEmbeddingModel)
        return SearchCommand(
            storePathOverride = storePath,
            searchPipeline = searchPipeline,
            repositoryForVerbose = repo,
            outputFormatter = formatter,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
            inputStream = inputStream,
        )
    }

    // -----------------------------------------------------------------------
    // Test 1: when --question is absent, reads from injected InputStream
    // -----------------------------------------------------------------------

    @Test
    fun `absence of --question reads stdin until EOF and uses as question`(@TempDir tempDir: Path) {
        val storePath = tempDir.resolve("vector-store.json")
        createPopulatedRepository(storePath)

        val stdinContent = "Query from stdin"
        val inputStream = ByteArrayInputStream(stdinContent.toByteArray())
        val capturedQueries = mutableListOf<SearchQuery>()
        val capturingPipeline = object : EmbeddingSearchPipeline(
            VectorStoreRepository(fakeEmbeddingModel, storePath).also { it.load() },
            fakeEmbeddingModel
        ) {
            override fun search(query: SearchQuery): SearchResult {
                capturedQueries.add(query)
                return SearchResult(emptyList())
            }
        }

        val out = StringWriter()
        val err = StringWriter()
        val cmd = SearchCommand(
            storePathOverride = storePath,
            searchPipeline = capturingPipeline,
            outputFormatter = OutputFormatter(),
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
            inputStream = inputStream,
        )

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(capturedQueries).hasSize(1)
        assertThat(capturedQueries[0].question).isEqualTo(stdinContent)
    }

    // -----------------------------------------------------------------------
    // Test 2: empty stdin exits code 1 with non-empty error message
    // -----------------------------------------------------------------------

    @Test
    fun `empty stdin exits code 1 with non-empty error message`(@TempDir tempDir: Path) {
        val storePath = tempDir.resolve("vector-store.json")
        createPopulatedRepository(storePath)

        val inputStream = ByteArrayInputStream(ByteArray(0))
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createSearchCommand(storePath, out, err, inputStream)
        // no question set

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(1)
        assertThat(out.toString()).isNotEmpty()
    }

    // -----------------------------------------------------------------------
    // Test 3: non-existent store exits 1 and error message contains store path
    // -----------------------------------------------------------------------

    @Test
    fun `non-existent store exits code 1 and error message contains store path`(@TempDir tempDir: Path) {
        val storePath = tempDir.resolve("nonexistent.json")
        val out = StringWriter()
        val err = StringWriter()

        // Create SearchCommand without a pipeline (since the store doesn't exist)
        val cmd = SearchCommand(
            storePathOverride = storePath,
            searchPipeline = null,
            outputFormatter = OutputFormatter(),
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
            inputStream = ByteArrayInputStream(ByteArray(0)),
        )
        cmd.question = "hello"

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(1)
        assertThat(out.toString()).contains(storePath.toAbsolutePath().toString())
    }
}
