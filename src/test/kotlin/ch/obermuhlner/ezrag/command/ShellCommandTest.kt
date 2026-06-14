package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import ch.obermuhlner.ezrag.rag.BM25SearchPipeline
import ch.obermuhlner.ezrag.rag.ChunkMatch
import ch.obermuhlner.ezrag.rag.ConversationTurn
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
import picocli.CommandLine
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

    /**
     * Populates a Lucene index with a sample document so ShellCommand considers the store to exist.
     * Closes the repository after writing so the caller may re-open with openRepo().
     */
    private fun createStore(storeDir: Path) {
        LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard").use { repo ->
            val doc = Document.builder()
                .text("Sample content")
                .metadata(mapOf("source" to "sample.txt", "chunk_index" to 0, "mtime" to 1000L))
                .build()
            repo.add(listOf(doc))
        }
    }

    private fun makeInput(vararg lines: String): InputStream =
        ByteArrayInputStream(lines.joinToString("\n").toByteArray())

    private fun openRepo(storeDir: Path): LuceneRepository =
        LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard")

    private fun makePipeline(
        repo: LuceneRepository,
        calls: MutableList<RagQuery> = mutableListOf(),
        answer: String = "Stub answer",
    ): RagPipeline {
        val searchPipeline = EmbeddingSearchPipeline(repo)
        return object : RagPipeline(searchPipeline, stubChatModel) {
            override fun query(ragQuery: RagQuery): RagResult {
                calls.add(ragQuery)
                return RagResult(answer, emptyList())
            }
        }
    }

    private fun makeSearchPipeline(
        repo: LuceneRepository,
        calls: MutableList<SearchQuery> = mutableListOf(),
    ): EmbeddingSearchPipeline {
        return object : EmbeddingSearchPipeline(repo) {
            override fun search(query: SearchQuery): SearchResult {
                calls.add(query)
                return SearchResult(chunks = listOf(ChunkMatch("doc.txt", 0, 0.9, "Some content")))
            }
        }
    }

    private fun makeBM25SearchPipeline(
        repo: LuceneRepository,
        calls: MutableList<SearchQuery> = mutableListOf(),
    ): BM25SearchPipeline {
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
        repository: LuceneRepository? = null,
    ): ShellCommand = ShellCommand(
        storeDirOverride = storeDir,
        ragPipeline = pipeline,
        embeddingSearchPipeline = searchPipeline,
        bm25SearchPipeline = bm25SearchPipeline,
        luceneRepository = repository,
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
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(repo, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("Q1", "Q2", "Q3"), pipeline)

        cmd.call()
        repo.close()

        assertThat(calls).hasSize(3)
    }

    // -----------------------------------------------------------------------
    // Test 2: blank lines are skipped — query not called for them
    // -----------------------------------------------------------------------

    @Test
    fun `blank lines are skipped and do not cause query to be called`(@TempDir tempDir: Path) {
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(repo, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("Q1", "", "Q2"), pipeline)

        cmd.call()
        repo.close()

        assertThat(calls).hasSize(2)
    }

    // -----------------------------------------------------------------------
    // Test 3: exit exits with return code 0 without calling query
    // -----------------------------------------------------------------------

    @Test
    fun `exit command causes loop to exit with return code 0 without calling query`(@TempDir tempDir: Path) {
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(repo, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("exit"), pipeline)

        val exitCode = cmd.call()
        repo.close()

        assertThat(exitCode).isEqualTo(0)
        assertThat(calls).isEmpty()
    }

    // -----------------------------------------------------------------------
    // Test 4: quit exits with return code 0
    // -----------------------------------------------------------------------

    @Test
    fun `quit command causes loop to exit with return code 0`(@TempDir tempDir: Path) {
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(repo, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("quit"), pipeline)

        val exitCode = cmd.call()
        repo.close()

        assertThat(exitCode).isEqualTo(0)
        assertThat(calls).isEmpty()
    }

    // -----------------------------------------------------------------------
    // Test 5: EOF exits with return code 0
    // -----------------------------------------------------------------------

    @Test
    fun `EOF causes loop to exit with return code 0`(@TempDir tempDir: Path) {
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val pipeline = makePipeline(repo)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, ByteArrayInputStream(ByteArray(0)), pipeline)

        val exitCode = cmd.call()
        repo.close()

        assertThat(exitCode).isEqualTo(0)
    }

    // -----------------------------------------------------------------------
    // Test 6: slash-prefixed lines silently skipped — query not called
    // -----------------------------------------------------------------------

    @Test
    fun `slash-prefixed lines are silently skipped and do not cause query to be called`(@TempDir tempDir: Path) {
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(repo, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/somecommand"), pipeline)

        cmd.call()
        repo.close()

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
        createStore(tempDir)
        val luceneRepo = openRepo(tempDir)
        val searchPipeline0 = EmbeddingSearchPipeline(luceneRepo)
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
        luceneRepo.close()

        assertThat(out.toString()).contains("SecondAnswer")
    }

    // -----------------------------------------------------------------------
    // Test 9: --output json formats each answer as JSON
    // -----------------------------------------------------------------------

    @Test
    fun `output json formats each answer as JSON with answer field`(@TempDir tempDir: Path) {
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val pipeline = makePipeline(repo)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("Q1"), pipeline)
        cmd.outputFormat = "json"

        cmd.call()
        repo.close()

        val output = out.toString().trim()
        assertThat(output).startsWith("{")
        assertThat(output).contains("\"answer\"")
    }

    // -----------------------------------------------------------------------
    // Test 10: prompt written to stderr, not stdout
    // -----------------------------------------------------------------------

    @Test
    fun `prompt is written to stderr and does not appear on stdout`(@TempDir tempDir: Path) {
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val pipeline = makePipeline(repo)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("exit"), pipeline)

        cmd.call()
        repo.close()

        assertThat(err.toString()).contains("> ")
        assertThat(out.toString()).doesNotContain("> ")
    }

    // -----------------------------------------------------------------------
    // Test 11: /help prints all six command names
    // -----------------------------------------------------------------------

    @Test
    fun `slash help prints all six command names to stdout`(@TempDir tempDir: Path) {
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/help"), makePipeline(repo))

        cmd.call()
        repo.close()

        val output = out.toString()
        assertThat(output).contains("/help")
        assertThat(output).contains("/clear")
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
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(repo, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/exit"), pipeline)

        val exitCode = cmd.call()
        repo.close()

        assertThat(exitCode).isEqualTo(0)
        assertThat(calls).isEmpty()
    }

    // -----------------------------------------------------------------------
    // Test 13: /status prints chunk count as a number
    // -----------------------------------------------------------------------

    @Test
    fun `slash status prints chunk count to stdout`(@TempDir tempDir: Path) {
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val pipeline = makePipeline(repo)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/status"), pipeline, repository = repo)

        cmd.call()
        repo.close()

        assertThat(out.toString()).matches("(?s).*\\d+.*")
    }

    // -----------------------------------------------------------------------
    // Test 14: /search calls EmbeddingSearchPipeline with correct question and topK
    // -----------------------------------------------------------------------

    @Test
    fun `slash search calls EmbeddingSearchPipeline with correct question and topK and not RagPipeline`(@TempDir tempDir: Path) {
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val ragCalls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(repo, ragCalls)
        val searchCalls = mutableListOf<SearchQuery>()
        val searchPipeline = makeSearchPipeline(repo, searchCalls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/search what is X"), pipeline, searchPipeline)
        cmd.topK = 7

        cmd.call()
        repo.close()

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
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val pipeline = makePipeline(repo)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/verbose", "/verbose"), pipeline)

        cmd.call()
        repo.close()

        val output = out.toString()
        assertThat(output).contains("verbose on")
        assertThat(output).contains("verbose off")
    }

    // -----------------------------------------------------------------------
    // Test 16: /verbose then question → source details on stderr
    // -----------------------------------------------------------------------

    @Test
    fun `slash verbose then question writes source details to stderr`(@TempDir tempDir: Path) {
        createStore(tempDir)
        val luceneRepo = openRepo(tempDir)
        val searchPipelineForVerbose = EmbeddingSearchPipeline(luceneRepo)
        val source = SourceReference("doc.txt", 0, 0.9, "excerpt")
        val pipeline = object : RagPipeline(searchPipelineForVerbose, stubChatModel) {
            override fun query(ragQuery: RagQuery): RagResult = RagResult("Answer", listOf(source))
        }
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/verbose", "what is X"), pipeline)

        cmd.call()
        luceneRepo.close()

        assertThat(err.toString()).contains("doc.txt")
    }

    // -----------------------------------------------------------------------
    // Test 17: /unknown prints error to stderr and loop continues
    // -----------------------------------------------------------------------

    @Test
    fun `slash unknown command writes error to stderr and loop continues without exiting`(@TempDir tempDir: Path) {
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(repo, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/unknown", "real question"), pipeline)

        val exitCode = cmd.call()
        repo.close()

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
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(repo, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/help", "/status", "/verbose"), pipeline)

        cmd.call()
        repo.close()

        assertThat(calls).isEmpty()
    }

    // -----------------------------------------------------------------------
    // Test 19: /search-bm25 uses BM25SearchPipeline and outputs chunk content
    // -----------------------------------------------------------------------

    @Test
    fun `slash search-bm25 calls BM25SearchPipeline and outputs chunk content`(@TempDir tempDir: Path) {
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val bm25Calls = mutableListOf<SearchQuery>()
        val bm25Pipeline = makeBM25SearchPipeline(repo, bm25Calls)
        val ragCalls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(repo, ragCalls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/search-bm25 foxterm", "/exit"), pipeline, bm25SearchPipeline = bm25Pipeline)

        val exitCode = cmd.call()
        repo.close()

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
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val searchCalls = mutableListOf<SearchQuery>()
        val searchPipeline = makeSearchPipeline(repo, searchCalls)
        val ragCalls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(repo, ragCalls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/search-embedding embterm", "/exit"), pipeline, searchPipeline)

        val exitCode = cmd.call()
        repo.close()

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
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/help"), makePipeline(repo))

        cmd.call()
        repo.close()

        val output = out.toString()
        assertThat(output).contains("search-bm25")
        assertThat(output).contains("search-embedding")
    }

    // -----------------------------------------------------------------------
    // Test 22: after one successful turn, second RagQuery has history size 1
    // -----------------------------------------------------------------------

    @Test
    fun `after one successful turn second RagQuery has conversationHistory size 1 with correct question and answer`(@TempDir tempDir: Path) {
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(repo, calls, answer = "Answer one")
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("Question one", "Question two"), pipeline)

        cmd.call()
        repo.close()

        assertThat(calls).hasSize(2)
        assertThat(calls[1].conversationHistory).hasSize(1)
        assertThat(calls[1].conversationHistory[0].userQuestion).isEqualTo("Question one")
        assertThat(calls[1].conversationHistory[0].assistantAnswer).isEqualTo("Answer one")
    }

    // -----------------------------------------------------------------------
    // Test 23: after two successful turns, third RagQuery has history size 2
    // -----------------------------------------------------------------------

    @Test
    fun `after two successful turns third RagQuery has conversationHistory size 2`(@TempDir tempDir: Path) {
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(repo, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("Q1", "Q2", "Q3"), pipeline)

        cmd.call()
        repo.close()

        assertThat(calls).hasSize(3)
        assertThat(calls[2].conversationHistory).hasSize(2)
    }

    // -----------------------------------------------------------------------
    // Test 24: when turn 1 throws, turn 2 has empty conversationHistory
    // -----------------------------------------------------------------------

    @Test
    fun `when turn 1 throws exception turn 2 RagQuery has empty conversationHistory`(@TempDir tempDir: Path) {
        createStore(tempDir)
        val luceneRepo = openRepo(tempDir)
        val searchPipeline0 = EmbeddingSearchPipeline(luceneRepo)
        val calls = mutableListOf<RagQuery>()
        var callIndex = 0
        val pipeline = object : RagPipeline(searchPipeline0, stubChatModel) {
            override fun query(ragQuery: RagQuery): RagResult {
                calls.add(ragQuery)
                val idx = callIndex++
                if (idx == 0) throw RuntimeException("Simulated failure")
                return RagResult("Answer two", emptyList())
            }
        }
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("Q1", "Q2"), pipeline)

        cmd.call()
        luceneRepo.close()

        assertThat(calls).hasSize(2)
        assertThat(calls[1].conversationHistory).isEmpty()
    }

    // -----------------------------------------------------------------------
    // Test 25: slash commands after successful turn do not add to history
    // -----------------------------------------------------------------------

    @Test
    fun `slash command turns do not add entries to conversation history`(@TempDir tempDir: Path) {
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(repo, calls)
        val searchCalls = mutableListOf<SearchQuery>()
        val searchPipeline = makeSearchPipeline(repo, searchCalls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("Real question", "/search something", "Follow up"), pipeline, searchPipeline)

        cmd.call()
        repo.close()

        assertThat(calls).hasSize(2)
        assertThat(calls[1].conversationHistory).hasSize(1)
        assertThat(calls[1].conversationHistory[0].userQuestion).isEqualTo("Real question")
    }

    // -----------------------------------------------------------------------
    // Test 26: /clear causes next RagQuery to have empty conversationHistory
    // -----------------------------------------------------------------------

    @Test
    fun `slash clear after two successful turns causes next RagQuery to have empty conversationHistory`(@TempDir tempDir: Path) {
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val calls = mutableListOf<RagQuery>()
        val pipeline = makePipeline(repo, calls)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("Q1", "Q2", "/clear", "Q3"), pipeline)

        cmd.call()
        repo.close()

        assertThat(calls).hasSize(3)
        assertThat(calls[2].conversationHistory).isEmpty()
    }

    // -----------------------------------------------------------------------
    // Test 27: /clear prints "conversation history cleared"
    // -----------------------------------------------------------------------

    @Test
    fun `slash clear prints conversation history cleared to stdout`(@TempDir tempDir: Path) {
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/clear"), makePipeline(repo))

        cmd.call()
        repo.close()

        assertThat(out.toString()).contains("conversation history cleared")
    }

    // -----------------------------------------------------------------------
    // Test 28: /help output contains /clear
    // -----------------------------------------------------------------------

    @Test
    fun `slash help output contains slash clear`(@TempDir tempDir: Path) {
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createShellCommand(tempDir, out, err, makeInput("/help"), makePipeline(repo))

        cmd.call()
        repo.close()

        assertThat(out.toString()).contains("/clear")
    }

    // -----------------------------------------------------------------------
    // Test 29: per-request lifecycle — write lock released after each query
    // -----------------------------------------------------------------------

    @Test
    fun `write lock is released after each REPL query so a second process can open the store`(@TempDir tempDir: Path) {
        createStore(tempDir)
        val out = StringWriter()
        val err = StringWriter()
        // Provide embeddingModelOverride so ShellCommand uses the per-request openWithRetry path
        // (no pre-built ragPipeline is injected here).
        val stubChatModelLocal: ChatModel = ChatModel { _ ->
            ChatResponse(listOf(Generation(AssistantMessage("PerRequestAnswer"))))
        }
        val cmd = ShellCommand(
            storeDirOverride = tempDir,
            embeddingModelOverride = fakeEmbeddingModel,
            chatModelOverride = stubChatModelLocal,
            outputFormatter = OutputFormatter(),
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
            inputStream = makeInput("What is the answer?"),
        )

        cmd.call()

        // After call() returns, the shell must have released the write lock.
        // Attempt to open a fresh repository on the same directory — must succeed.
        val secondRepo = LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard")
        secondRepo.close()
    }

    // -----------------------------------------------------------------------
    // Picocli-level flag tests: --output-format accepted, --output rejected
    // -----------------------------------------------------------------------

    @Test
    fun `--output-format json is accepted by picocli parser for ShellCommand`(@TempDir tempDir: Path) {
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val pipeline = makePipeline(repo)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = ShellCommand(
            storeDirOverride = tempDir,
            ragPipeline = pipeline,
            outputFormatter = OutputFormatter(),
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
            inputStream = makeInput("exit"),
        )
        val commandLine = CommandLine(cmd)
        val exitCode = commandLine.execute("--output-format", "json")
        repo.close()
        assertThat(exitCode).isNotEqualTo(CommandLine.ExitCode.USAGE)
    }

    @Test
    fun `--output json causes picocli UnmatchedArgumentException for ShellCommand`(@TempDir tempDir: Path) {
        createStore(tempDir)
        val repo = openRepo(tempDir)
        val pipeline = makePipeline(repo)
        val out = StringWriter()
        val err = StringWriter()
        val cmd = ShellCommand(
            storeDirOverride = tempDir,
            ragPipeline = pipeline,
            outputFormatter = OutputFormatter(),
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
            inputStream = makeInput("exit"),
        )
        val commandLine = CommandLine(cmd)
        val exitCode = commandLine.execute("--output", "json")
        repo.close()
        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.USAGE)
    }
}
