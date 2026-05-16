package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.VectorStoreRepository
import ch.obermuhlner.ezrag.rag.ChunkMatch
import ch.obermuhlner.ezrag.rag.EmbeddingSearchPipeline
import ch.obermuhlner.ezrag.rag.OutputFormatter
import ch.obermuhlner.ezrag.rag.RagPipeline
import ch.obermuhlner.ezrag.rag.RagQuery
import ch.obermuhlner.ezrag.rag.RagResult
import ch.obermuhlner.ezrag.rag.SearchQuery
import ch.obermuhlner.ezrag.rag.SearchResult
import ch.obermuhlner.ezrag.rag.SourceReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path

class ShellCommandTest {

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

    private val stubChatModel: ChatModel = ChatModel { _ ->
        ChatResponse(listOf(Generation(AssistantMessage("Stub answer"))))
    }

    private fun createStoreFile(storeFilePath: Path) {
        storeFilePath.parent?.toFile()?.mkdirs()
        storeFilePath.toFile().createNewFile()
    }

    private fun makeInput(vararg lines: String): InputStream =
        ByteArrayInputStream(lines.joinToString("\n").toByteArray())

    private fun makePipeline(
        storeFilePath: Path,
        calls: MutableList<RagQuery> = mutableListOf(),
        answer: String = "Stub answer",
    ): RagPipeline {
        val repo = VectorStoreRepository(fakeEmbeddingModel, storeFilePath)
        return object : RagPipeline(repo, stubChatModel) {
            override fun query(ragQuery: RagQuery): RagResult {
                calls.add(ragQuery)
                return RagResult(answer, emptyList())
            }
        }
    }

    private fun makeSearchPipeline(
        storeFilePath: Path,
        calls: MutableList<SearchQuery> = mutableListOf(),
    ): EmbeddingSearchPipeline {
        val repo = VectorStoreRepository(fakeEmbeddingModel, storeFilePath)
        return object : EmbeddingSearchPipeline(repo, fakeEmbeddingModel) {
            override fun search(query: SearchQuery): SearchResult {
                calls.add(query)
                return SearchResult(chunks = listOf(ChunkMatch("doc.txt", 0, 0.9, "Some content")))
            }
        }
    }

    private fun createShellCommand(
        storeFilePath: Path,
        out: StringWriter,
        err: StringWriter,
        inputStream: InputStream,
        pipeline: RagPipeline? = null,
        searchPipeline: EmbeddingSearchPipeline? = null,
        repository: VectorStoreRepository? = null,
    ): ShellCommand = ShellCommand(
        storeDirOverride = storeFilePath.parent,
        ragPipeline = pipeline,
        embeddingSearchPipeline = searchPipeline,
        vectorStoreRepository = repository,
        outputFormatter = OutputFormatter(),
        outputWriter = PrintWriter(out, true),
        errorWriter = PrintWriter(err, true),
        inputStream = inputStream,
    )

    // -----------------------------------------------------------------------
    // Test 1: three non-blank questions → query called exactly three times
    // -----------------------------------------------------------------------

    @Test
    fun `three non-blank questions cause query to be called exactly three times`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        createStoreFile(storeFilePath)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(storeFilePath, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(storeFilePath, out, err, makeInput("Q1", "Q2", "Q3"), pipeline)

        cmd.call()

        assertThat(calls).hasSize(3)
    }

    // -----------------------------------------------------------------------
    // Test 2: blank lines are skipped — query not called for them
    // -----------------------------------------------------------------------

    @Test
    fun `blank lines are skipped and do not cause query to be called`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        createStoreFile(storeFilePath)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(storeFilePath, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(storeFilePath, out, err, makeInput("Q1", "", "Q2"), pipeline)

        cmd.call()

        assertThat(calls).hasSize(2)
    }

    // -----------------------------------------------------------------------
    // Test 3: exit exits with return code 0 without calling query
    // -----------------------------------------------------------------------

    @Test
    fun `exit command causes loop to exit with return code 0 without calling query`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        createStoreFile(storeFilePath)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(storeFilePath, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(storeFilePath, out, err, makeInput("exit"), pipeline)

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(calls).isEmpty()
    }

    // -----------------------------------------------------------------------
    // Test 4: quit exits with return code 0
    // -----------------------------------------------------------------------

    @Test
    fun `quit command causes loop to exit with return code 0`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        createStoreFile(storeFilePath)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(storeFilePath, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(storeFilePath, out, err, makeInput("quit"), pipeline)

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(calls).isEmpty()
    }

    // -----------------------------------------------------------------------
    // Test 5: EOF exits with return code 0
    // -----------------------------------------------------------------------

    @Test
    fun `EOF causes loop to exit with return code 0`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        createStoreFile(storeFilePath)
        val pipeline = makePipeline(storeFilePath)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(storeFilePath, out, err, ByteArrayInputStream(ByteArray(0)), pipeline)

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
    }

    // -----------------------------------------------------------------------
    // Test 6: slash-prefixed lines silently skipped — query not called
    // -----------------------------------------------------------------------

    @Test
    fun `slash-prefixed lines are silently skipped and do not cause query to be called`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        createStoreFile(storeFilePath)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(storeFilePath, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(storeFilePath, out, err, makeInput("/somecommand"), pipeline)

        cmd.call()

        assertThat(calls).isEmpty()
    }

    // -----------------------------------------------------------------------
    // Test 7: missing store file → exit code 1 and stdout contains "ingest"
    // -----------------------------------------------------------------------

    @Test
    fun `missing vector store file returns exit code 1 and stdout contains ingest`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("nonexistent.json")
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(storeFilePath, out, err, ByteArrayInputStream(ByteArray(0)), null)

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(1)
        assertThat(out.toString()).containsIgnoringCase("ingest")
    }

    // -----------------------------------------------------------------------
    // Test 8: exception on first call doesn't stop second call from executing
    // -----------------------------------------------------------------------

    @Test
    fun `pipeline exception on first call does not prevent second call from being processed`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        createStoreFile(storeFilePath)
        val repo = VectorStoreRepository(fakeEmbeddingModel, storeFilePath)
        var callIndex = 0
        val pipeline = object : RagPipeline(repo, stubChatModel) {
            override fun query(ragQuery: RagQuery): RagResult {
                val idx = callIndex++
                if (idx == 0) throw RuntimeException("Simulated failure")
                return RagResult("SecondAnswer", emptyList())
            }
        }
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(storeFilePath, out, err, makeInput("Q1", "Q2"), pipeline)

        cmd.call()

        assertThat(out.toString()).contains("SecondAnswer")
    }

    // -----------------------------------------------------------------------
    // Test 9: --output json formats each answer as JSON
    // -----------------------------------------------------------------------

    @Test
    fun `output json formats each answer as JSON with answer field`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        createStoreFile(storeFilePath)
        val pipeline = makePipeline(storeFilePath)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(storeFilePath, out, err, makeInput("Q1"), pipeline)
        cmd.outputFormat = "json"

        cmd.call()

        val output = out.toString().trim()
        assertThat(output).startsWith("{")
        assertThat(output).contains("\"answer\"")
    }

    // -----------------------------------------------------------------------
    // Test 10: prompt written to stderr, not stdout
    // -----------------------------------------------------------------------

    @Test
    fun `prompt is written to stderr and does not appear on stdout`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        createStoreFile(storeFilePath)
        val pipeline = makePipeline(storeFilePath)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(storeFilePath, out, err, makeInput("exit"), pipeline)

        cmd.call()

        assertThat(err.toString()).contains("> ")
        assertThat(out.toString()).doesNotContain("> ")
    }

    // -----------------------------------------------------------------------
    // Test 11: /help prints all five command names
    // -----------------------------------------------------------------------

    @Test
    fun `slash help prints all five command names to stdout`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        createStoreFile(storeFilePath)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(storeFilePath, out, err, makeInput("/help"), makePipeline(storeFilePath))

        cmd.call()

        val output = out.toString()
        assertThat(output).contains("/help")
        assertThat(output).contains("/status")
        assertThat(output).contains("/search")
        assertThat(output).contains("/verbose")
        assertThat(output).contains("/exit")
    }

    // -----------------------------------------------------------------------
    // Test 12: /exit exits with return code 0 and query never called
    // -----------------------------------------------------------------------

    @Test
    fun `slash exit exits with return code 0 and RagPipeline query is never called`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        createStoreFile(storeFilePath)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(storeFilePath, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(storeFilePath, out, err, makeInput("/exit"), pipeline)

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(calls).isEmpty()
    }

    // -----------------------------------------------------------------------
    // Test 13: /status prints chunk count as a number
    // -----------------------------------------------------------------------

    @Test
    fun `slash status prints chunk count to stdout`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        val repo = VectorStoreRepository(fakeEmbeddingModel, storeFilePath)
        repo.load()
        repo.save()
        val pipeline = makePipeline(storeFilePath)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(storeFilePath, out, err, makeInput("/status"), pipeline, repository = repo)

        cmd.call()

        assertThat(out.toString()).matches("(?s).*\\d+.*")
    }

    // -----------------------------------------------------------------------
    // Test 14: /search calls EmbeddingSearchPipeline with correct question and topK
    // -----------------------------------------------------------------------

    @Test
    fun `slash search calls EmbeddingSearchPipeline with correct question and topK and not RagPipeline`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        createStoreFile(storeFilePath)
        val ragCalls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(storeFilePath, ragCalls)
        val searchCalls = mutableListOf<SearchQuery>()
        val searchPipeline = makeSearchPipeline(storeFilePath, searchCalls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(storeFilePath, out, err, makeInput("/search what is X"), pipeline, searchPipeline)
        cmd.topK = 7

        cmd.call()

        assertThat(ragCalls).isEmpty()
        assertThat(searchCalls).hasSize(1)
        assertThat(searchCalls[0].question).isEqualTo("what is X")
        assertThat(searchCalls[0].topK).isEqualTo(7)
    }

    // -----------------------------------------------------------------------
    // Test 15: /verbose toggles verbose on and off
    // -----------------------------------------------------------------------

    @Test
    fun `slash verbose prints verbose on first time and verbose off second time`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        createStoreFile(storeFilePath)
        val pipeline = makePipeline(storeFilePath)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(storeFilePath, out, err, makeInput("/verbose", "/verbose"), pipeline)

        cmd.call()

        val output = out.toString()
        assertThat(output).contains("verbose on")
        assertThat(output).contains("verbose off")
    }

    // -----------------------------------------------------------------------
    // Test 16: /verbose then question → source details on stderr
    // -----------------------------------------------------------------------

    @Test
    fun `slash verbose then question writes source details to stderr`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        createStoreFile(storeFilePath)
        val repo = VectorStoreRepository(fakeEmbeddingModel, storeFilePath)
        val source = SourceReference("doc.txt", 0, 0.9, "excerpt")
        val pipeline = object : RagPipeline(repo, stubChatModel) {
            override fun query(ragQuery: RagQuery): RagResult = RagResult("Answer", listOf(source))
        }
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(storeFilePath, out, err, makeInput("/verbose", "what is X"), pipeline)

        cmd.call()

        assertThat(err.toString()).contains("doc.txt")
    }

    // -----------------------------------------------------------------------
    // Test 17: /unknown prints error to stderr and loop continues
    // -----------------------------------------------------------------------

    @Test
    fun `slash unknown command writes error to stderr and loop continues without exiting`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        createStoreFile(storeFilePath)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(storeFilePath, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(storeFilePath, out, err, makeInput("/unknown", "real question"), pipeline)

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(err.toString()).isNotEmpty()
        assertThat(calls).hasSize(1)
        assertThat(calls[0].question).isEqualTo("real question")
    }

    // -----------------------------------------------------------------------
    // Test 18: slash-prefixed lines never passed to RagPipeline.query()
    // -----------------------------------------------------------------------

    @Test
    fun `slash prefixed lines are never passed to RagPipeline query`(@TempDir tempDir: Path) {
        val storeFilePath = tempDir.resolve("vector-store.json")
        createStoreFile(storeFilePath)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(storeFilePath, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(storeFilePath, out, err, makeInput("/help", "/status", "/verbose"), pipeline)

        cmd.call()

        assertThat(calls).isEmpty()
    }
}
