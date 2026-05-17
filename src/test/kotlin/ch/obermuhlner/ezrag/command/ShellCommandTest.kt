package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.BM25Repository
import ch.obermuhlner.ezrag.ingestion.VectorStoreRepository
import ch.obermuhlner.ezrag.rag.BM25SearchPipeline
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

    private fun createStoreFile(storeDir: Path) {
        storeDir.toFile().mkdirs()
        // Create a minimal vector-store.json so ShellCommand considers the store to exist
        storeDir.resolve("vector-store.json").toFile().createNewFile()
    }

    private fun makeInput(vararg lines: String): InputStream =
        ByteArrayInputStream(lines.joinToString("\n").toByteArray())

    private fun makePipeline(
        storeDir: Path,
        calls: MutableList<RagQuery> = mutableListOf(),
        answer: String = "Stub answer",
    ): RagPipeline {
        val repo = VectorStoreRepository(fakeEmbeddingModel, storeDir)
        val searchPipeline = EmbeddingSearchPipeline(repo, fakeEmbeddingModel)
        return object : RagPipeline(searchPipeline, stubChatModel) {
            override fun query(ragQuery: RagQuery): RagResult {
                calls.add(ragQuery)
                return RagResult(answer, emptyList())
            }
        }
    }

    private fun makeSearchPipeline(
        storeDir: Path,
        calls: MutableList<SearchQuery> = mutableListOf(),
    ): EmbeddingSearchPipeline {
        val repo = VectorStoreRepository(fakeEmbeddingModel, storeDir)
        return object : EmbeddingSearchPipeline(repo, fakeEmbeddingModel) {
            override fun search(query: SearchQuery): SearchResult {
                calls.add(query)
                return SearchResult(chunks = listOf(ChunkMatch("doc.txt", 0, 0.9, "Some content")))
            }
        }
    }

    private fun makeBM25SearchPipeline(
        storeDir: Path,
        calls: MutableList<SearchQuery> = mutableListOf(),
    ): BM25SearchPipeline {
        val repo = BM25Repository(storeDir, "standard")
        return object : BM25SearchPipeline(repo) {
            override fun search(query: SearchQuery): SearchResult {
                calls.add(query)
                return SearchResult(chunks = listOf(ChunkMatch("bm25doc.txt", 0, 1.0, "BM25 content")), mode = "bm25")
            }
        }
    }

    private fun createShellCommand(
        storeDir: Path,
        out: StringWriter,
        err: StringWriter,
        inputStream: InputStream,
        pipeline: RagPipeline? = null,
        searchPipeline: EmbeddingSearchPipeline? = null,
        bm25SearchPipeline: BM25SearchPipeline? = null,
        repository: VectorStoreRepository? = null,
    ): ShellCommand = ShellCommand(
        storeDirOverride = storeDir,
        ragPipeline = pipeline,
        embeddingSearchPipeline = searchPipeline,
        bm25SearchPipeline = bm25SearchPipeline,
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
        createStoreFile(tempDir)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(tempDir, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("Q1", "Q2", "Q3"), pipeline)

        cmd.call()

        assertThat(calls).hasSize(3)
    }

    // -----------------------------------------------------------------------
    // Test 2: blank lines are skipped — query not called for them
    // -----------------------------------------------------------------------

    @Test
    fun `blank lines are skipped and do not cause query to be called`(@TempDir tempDir: Path) {
        createStoreFile(tempDir)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(tempDir, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("Q1", "", "Q2"), pipeline)

        cmd.call()

        assertThat(calls).hasSize(2)
    }

    // -----------------------------------------------------------------------
    // Test 3: exit exits with return code 0 without calling query
    // -----------------------------------------------------------------------

    @Test
    fun `exit command causes loop to exit with return code 0 without calling query`(@TempDir tempDir: Path) {
        createStoreFile(tempDir)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(tempDir, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("exit"), pipeline)

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(calls).isEmpty()
    }

    // -----------------------------------------------------------------------
    // Test 4: quit exits with return code 0
    // -----------------------------------------------------------------------

    @Test
    fun `quit command causes loop to exit with return code 0`(@TempDir tempDir: Path) {
        createStoreFile(tempDir)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(tempDir, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("quit"), pipeline)

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(calls).isEmpty()
    }

    // -----------------------------------------------------------------------
    // Test 5: EOF exits with return code 0
    // -----------------------------------------------------------------------

    @Test
    fun `EOF causes loop to exit with return code 0`(@TempDir tempDir: Path) {
        createStoreFile(tempDir)
        val pipeline = makePipeline(tempDir)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, ByteArrayInputStream(ByteArray(0)), pipeline)

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
    }

    // -----------------------------------------------------------------------
    // Test 6: slash-prefixed lines silently skipped — query not called
    // -----------------------------------------------------------------------

    @Test
    fun `slash-prefixed lines are silently skipped and do not cause query to be called`(@TempDir tempDir: Path) {
        createStoreFile(tempDir)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(tempDir, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/somecommand"), pipeline)

        cmd.call()

        assertThat(calls).isEmpty()
    }

    // -----------------------------------------------------------------------
    // Test 7: missing store file → exit code 1 and stdout contains "ingest"
    // -----------------------------------------------------------------------

    @Test
    fun `missing vector store file returns exit code 1 and stdout contains ingest`(@TempDir tempDir: Path) {
        val nonExistentDir = tempDir.resolve("nonexistent-dir")
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(nonExistentDir, out, err, ByteArrayInputStream(ByteArray(0)), null)

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(1)
        assertThat(out.toString()).containsIgnoringCase("ingest")
    }

    // -----------------------------------------------------------------------
    // Test 8: exception on first call doesn't stop second call from executing
    // -----------------------------------------------------------------------

    @Test
    fun `pipeline exception on first call does not prevent second call from being processed`(@TempDir tempDir: Path) {
        createStoreFile(tempDir)
        val repo = VectorStoreRepository(fakeEmbeddingModel, tempDir)
        val searchPipeline0 = EmbeddingSearchPipeline(repo, fakeEmbeddingModel)
        var callIndex = 0
        val pipeline = object : RagPipeline(searchPipeline0, stubChatModel) {
            override fun query(ragQuery: RagQuery): RagResult {
                val idx = callIndex++
                if (idx == 0) throw RuntimeException("Simulated failure")
                return RagResult("SecondAnswer", emptyList())
            }
        }
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("Q1", "Q2"), pipeline)

        cmd.call()

        assertThat(out.toString()).contains("SecondAnswer")
    }

    // -----------------------------------------------------------------------
    // Test 9: --output json formats each answer as JSON
    // -----------------------------------------------------------------------

    @Test
    fun `output json formats each answer as JSON with answer field`(@TempDir tempDir: Path) {
        createStoreFile(tempDir)
        val pipeline = makePipeline(tempDir)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("Q1"), pipeline)
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
        createStoreFile(tempDir)
        val pipeline = makePipeline(tempDir)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("exit"), pipeline)

        cmd.call()

        assertThat(err.toString()).contains("> ")
        assertThat(out.toString()).doesNotContain("> ")
    }

    // -----------------------------------------------------------------------
    // Test 11: /help prints all five command names
    // -----------------------------------------------------------------------

    @Test
    fun `slash help prints all five command names to stdout`(@TempDir tempDir: Path) {
        createStoreFile(tempDir)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/help"), makePipeline(tempDir))

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
        createStoreFile(tempDir)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(tempDir, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/exit"), pipeline)

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(calls).isEmpty()
    }

    // -----------------------------------------------------------------------
    // Test 13: /status prints chunk count as a number
    // -----------------------------------------------------------------------

    @Test
    fun `slash status prints chunk count to stdout`(@TempDir tempDir: Path) {
        val repo = VectorStoreRepository(fakeEmbeddingModel, tempDir)
        repo.load()
        repo.save()
        val pipeline = makePipeline(tempDir)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/status"), pipeline, repository = repo)

        cmd.call()

        assertThat(out.toString()).matches("(?s).*\\d+.*")
    }

    // -----------------------------------------------------------------------
    // Test 14: /search calls EmbeddingSearchPipeline with correct question and topK
    // -----------------------------------------------------------------------

    @Test
    fun `slash search calls EmbeddingSearchPipeline with correct question and topK and not RagPipeline`(@TempDir tempDir: Path) {
        createStoreFile(tempDir)
        val ragCalls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(tempDir, ragCalls)
        val searchCalls = mutableListOf<SearchQuery>()
        val searchPipeline = makeSearchPipeline(tempDir, searchCalls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/search what is X"), pipeline, searchPipeline)
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
        createStoreFile(tempDir)
        val pipeline = makePipeline(tempDir)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/verbose", "/verbose"), pipeline)

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
        createStoreFile(tempDir)
        val repo = VectorStoreRepository(fakeEmbeddingModel, tempDir)
        val searchPipelineForVerbose = EmbeddingSearchPipeline(repo, fakeEmbeddingModel)
        val source = SourceReference("doc.txt", 0, 0.9, "excerpt")
        val pipeline = object : RagPipeline(searchPipelineForVerbose, stubChatModel) {
            override fun query(ragQuery: RagQuery): RagResult = RagResult("Answer", listOf(source))
        }
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/verbose", "what is X"), pipeline)

        cmd.call()

        assertThat(err.toString()).contains("doc.txt")
    }

    // -----------------------------------------------------------------------
    // Test 17: /unknown prints error to stderr and loop continues
    // -----------------------------------------------------------------------

    @Test
    fun `slash unknown command writes error to stderr and loop continues without exiting`(@TempDir tempDir: Path) {
        createStoreFile(tempDir)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(tempDir, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/unknown", "real question"), pipeline)

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
        createStoreFile(tempDir)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(tempDir, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/help", "/status", "/verbose"), pipeline)

        cmd.call()

        assertThat(calls).isEmpty()
    }

    // -----------------------------------------------------------------------
    // Test 19: /search-bm25 uses BM25SearchPipeline and outputs chunk content
    // -----------------------------------------------------------------------

    @Test
    fun `slash search-bm25 calls BM25SearchPipeline and outputs chunk content`(@TempDir tempDir: Path) {
        createStoreFile(tempDir)
        val bm25Calls = mutableListOf<SearchQuery>()
        val bm25Pipeline = makeBM25SearchPipeline(tempDir, bm25Calls)
        val ragCalls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(tempDir, ragCalls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/search-bm25 foxterm", "/exit"), pipeline, bm25SearchPipeline = bm25Pipeline)

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(ragCalls).isEmpty()
        assertThat(bm25Calls).hasSize(1)
        assertThat(bm25Calls[0].question).isEqualTo("foxterm")
        assertThat(out.toString()).contains("BM25 content")
    }

    // -----------------------------------------------------------------------
    // Test 20: /search-embedding uses EmbeddingSearchPipeline and outputs chunk content
    // -----------------------------------------------------------------------

    @Test
    fun `slash search-embedding calls EmbeddingSearchPipeline and outputs chunk content`(@TempDir tempDir: Path) {
        createStoreFile(tempDir)
        val searchCalls = mutableListOf<SearchQuery>()
        val searchPipeline = makeSearchPipeline(tempDir, searchCalls)
        val ragCalls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(tempDir, ragCalls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/search-embedding embterm", "/exit"), pipeline, searchPipeline)

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(ragCalls).isEmpty()
        assertThat(searchCalls).hasSize(1)
        assertThat(searchCalls[0].question).isEqualTo("embterm")
        assertThat(out.toString()).contains("Some content")
    }

    // -----------------------------------------------------------------------
    // Test 21: /help output contains search-bm25 and search-embedding
    // -----------------------------------------------------------------------

    @Test
    fun `slash help output contains search-bm25 and search-embedding`(@TempDir tempDir: Path) {
        createStoreFile(tempDir)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/help"), makePipeline(tempDir))

        cmd.call()

        val output = out.toString()
        assertThat(output).contains("search-bm25")
        assertThat(output).contains("search-embedding")
    }
}
