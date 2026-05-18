package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import ch.obermuhlner.ezrag.rag.EmbeddingSearchPipeline
import ch.obermuhlner.ezrag.rag.OutputFormatter
import ch.obermuhlner.ezrag.rag.RagPipeline
import ch.obermuhlner.ezrag.rag.RagQuery
import ch.obermuhlner.ezrag.rag.RagResult
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
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path

class QueryCommandTest {

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

    private fun populateRepository(storeDir: Path) {
        val repo = LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard")
        val doc = Document.builder()
            .text("Test content for querying.")
            .metadata(mapOf("source" to "test.txt", "chunk_index" to 0, "mtime" to 1000L))
            .build()
        repo.add(listOf(doc))
        repo.close()
    }

    private fun createQueryCommand(
        storeDir: Path,
        out: StringWriter,
        err: StringWriter,
        inputStream: ByteArrayInputStream = ByteArrayInputStream(ByteArray(0)),
        pipeline: RagPipeline? = null,
        formatter: OutputFormatter = OutputFormatter(),
    ): QueryCommand {
        // Only create a repo/pipeline if the store already exists, so that
        // tests for non-existent stores can observe the command's own existence check.
        val ragPipeline = pipeline ?: run {
            if (LuceneRepository.storeExists(storeDir)) {
                val repo = LuceneRepository.open(fakeEmbeddingModel, storeDir, "standard")
                RagPipeline(EmbeddingSearchPipeline(repo), stubChatModel)
            } else {
                null
            }
        }
        return QueryCommand(
            storeDirOverride = storeDir,
            ragPipeline = ragPipeline,
            outputFormatter = formatter,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
            inputStream = inputStream,
        )
    }

    // -----------------------------------------------------------------------
    // Test 0a: positional words joined into single question string
    // -----------------------------------------------------------------------

    @Test
    fun `positional words are joined into single question string`(@TempDir tempDir: Path) {
        populateRepository(tempDir)

        val capturedQueries = mutableListOf<RagQuery>()
        val capturingPipeline = object : RagPipeline(
            EmbeddingSearchPipeline(LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard")),
            stubChatModel
        ) {
            override fun query(ragQuery: RagQuery): RagResult {
                capturedQueries.add(ragQuery)
                return RagResult("Answer", emptyList())
            }
        }

        val out = StringWriter()
        val err = StringWriter()
        val cmd = QueryCommand(
            storeDirOverride = tempDir,
            ragPipeline = capturingPipeline,
            outputFormatter = OutputFormatter(),
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
            inputStream = ByteArrayInputStream(ByteArray(0)),
        )
        cmd.questionArgs = listOf("Summarize", "the", "architecture")

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(capturedQueries).hasSize(1)
        assertThat(capturedQueries[0].question).isEqualTo("Summarize the architecture")
    }

    // -----------------------------------------------------------------------
    // Test 0b: single quoted token passed via questionArgs
    // -----------------------------------------------------------------------

    @Test
    fun `single quoted token in questionArgs is used as question`(@TempDir tempDir: Path) {
        populateRepository(tempDir)

        val capturedQueries = mutableListOf<RagQuery>()
        val capturingPipeline = object : RagPipeline(
            EmbeddingSearchPipeline(LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard")),
            stubChatModel
        ) {
            override fun query(ragQuery: RagQuery): RagResult {
                capturedQueries.add(ragQuery)
                return RagResult("Answer", emptyList())
            }
        }

        val out = StringWriter()
        val err = StringWriter()
        val cmd = QueryCommand(
            storeDirOverride = tempDir,
            ragPipeline = capturingPipeline,
            outputFormatter = OutputFormatter(),
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
            inputStream = ByteArrayInputStream(ByteArray(0)),
        )
        cmd.questionArgs = listOf("Summarize the architecture")

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(capturedQueries).hasSize(1)
        assertThat(capturedQueries[0].question).isEqualTo("Summarize the architecture")
    }

    // -----------------------------------------------------------------------
    // Test 0c: picocli-level test — multi-word positional args without USAGE error
    // -----------------------------------------------------------------------

    @Test
    fun `commandLine execute query with multi-word positional args exits without USAGE error`() {
        val commandLine = CommandLine(ch.obermuhlner.ezrag.EzRagCommand())
        val exitCode = commandLine.execute("query", "who", "are", "you?")
        assertThat(exitCode).isNotEqualTo(CommandLine.ExitCode.USAGE)
    }

    // -----------------------------------------------------------------------
    // Test 1: non-existent store exits 1 and stdout contains "ingest"
    // -----------------------------------------------------------------------

    @Test
    fun `non-existent store exits code 1 and output contains word ingest`(@TempDir tempDir: Path) {
        val nonExistentDir = tempDir.resolve("nonexistent-dir")
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createQueryCommand(nonExistentDir, out, err)
        cmd.questionArgs = listOf("hello")

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(1)
        assertThat(out.toString()).containsIgnoringCase("ingest")
    }

    // -----------------------------------------------------------------------
    // Test 2: positional question with populated store exits 0 and answer on outputWriter
    // -----------------------------------------------------------------------

    @Test
    fun `positional question with populated store exits code 0 and answer on outputWriter`(@TempDir tempDir: Path) {
        populateRepository(tempDir)

        val out = StringWriter()
        val err = StringWriter()
        val cmd = createQueryCommand(tempDir, out, err)
        cmd.questionArgs = listOf("hello")

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString()).contains("Stub answer")
    }

    // -----------------------------------------------------------------------
    // Test 3: absence of positional args reads stdin until EOF
    // -----------------------------------------------------------------------

    @Test
    fun `absence of positional args reads stdin until EOF and uses as question`(@TempDir tempDir: Path) {
        populateRepository(tempDir)

        val stdinContent = "Question from stdin"
        val inputStream = ByteArrayInputStream(stdinContent.toByteArray())
        val capturedQueries = mutableListOf<RagQuery>()
        val capturingPipeline = object : RagPipeline(
            EmbeddingSearchPipeline(LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard")),
            stubChatModel
        ) {
            override fun query(ragQuery: RagQuery): RagResult {
                capturedQueries.add(ragQuery)
                return RagResult("Answer", emptyList())
            }
        }

        val out = StringWriter()
        val err = StringWriter()
        val cmd = QueryCommand(
            storeDirOverride = tempDir,
            ragPipeline = capturingPipeline,
            outputFormatter = OutputFormatter(),
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
            inputStream = inputStream,
        )
        // no questionArgs set

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(capturedQueries).hasSize(1)
        assertThat(capturedQueries[0].question).isEqualTo(stdinContent)
    }

    // -----------------------------------------------------------------------
    // Test 4: empty stdin exits code 1 with message "No question provided"
    // -----------------------------------------------------------------------

    @Test
    fun `empty stdin exits code 1 with message No question provided`(@TempDir tempDir: Path) {
        populateRepository(tempDir)

        val inputStream = ByteArrayInputStream(ByteArray(0))
        val out = StringWriter()
        val err = StringWriter()
        val cmd = createQueryCommand(tempDir, out, err, inputStream)
        // no questionArgs set

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(1)
        assertThat(out.toString()).contains("No question provided")
    }

    // -----------------------------------------------------------------------
    // Test 5: --output json produces JSON-formatted output
    // -----------------------------------------------------------------------

    @Test
    fun `--output json produces JSON-formatted output`(@TempDir tempDir: Path) {
        populateRepository(tempDir)

        val out = StringWriter()
        val err = StringWriter()
        val cmd = createQueryCommand(tempDir, out, err)
        cmd.questionArgs = listOf("hello")
        cmd.outputFormat = "json"

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        val output = out.toString().trim()
        assertThat(output).startsWith("{")
        assertThat(output).contains("\"answer\"")
        assertThat(output).contains("\"sources\"")
    }

    // -----------------------------------------------------------------------
    // Test 6: --top-k N passes topK=N to RagPipeline via RagQuery
    // -----------------------------------------------------------------------

    @Test
    fun `--top-k 2 passes topK=2 to RagPipeline via RagQuery`(@TempDir tempDir: Path) {
        populateRepository(tempDir)

        val capturedQueries = mutableListOf<RagQuery>()
        val capturingPipeline = object : RagPipeline(
            EmbeddingSearchPipeline(LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard")),
            stubChatModel
        ) {
            override fun query(ragQuery: RagQuery): RagResult {
                capturedQueries.add(ragQuery)
                return RagResult("Answer", emptyList())
            }
        }

        val out = StringWriter()
        val err = StringWriter()
        val cmd = QueryCommand(
            storeDirOverride = tempDir,
            ragPipeline = capturingPipeline,
            outputFormatter = OutputFormatter(),
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
            inputStream = ByteArrayInputStream(ByteArray(0)),
        )
        cmd.questionArgs = listOf("hello")
        cmd.topK = 2

        cmd.call()

        assertThat(capturedQueries).hasSize(1)
        assertThat(capturedQueries[0].topK).isEqualTo(2)
    }

    // -----------------------------------------------------------------------
    // Test 7: --model "claude-3-5-sonnet" passes modelOverride to RagQuery
    // -----------------------------------------------------------------------

    @Test
    fun `--model claude-3-5-sonnet passes modelOverride to RagQuery`(@TempDir tempDir: Path) {
        populateRepository(tempDir)

        val capturedQueries = mutableListOf<RagQuery>()
        val capturingPipeline = object : RagPipeline(
            EmbeddingSearchPipeline(LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard")),
            stubChatModel
        ) {
            override fun query(ragQuery: RagQuery): RagResult {
                capturedQueries.add(ragQuery)
                return RagResult("Answer", emptyList())
            }
        }

        val out = StringWriter()
        val err = StringWriter()
        val cmd = QueryCommand(
            storeDirOverride = tempDir,
            ragPipeline = capturingPipeline,
            outputFormatter = OutputFormatter(),
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
            inputStream = ByteArrayInputStream(ByteArray(0)),
        )
        cmd.questionArgs = listOf("hello")
        cmd.modelOverride = "claude-3-5-sonnet"

        cmd.call()

        assertThat(capturedQueries).hasSize(1)
        assertThat(capturedQueries[0].modelOverride).isEqualTo("claude-3-5-sonnet")
    }

    // -----------------------------------------------------------------------
    // Test 8: --system-prompt "Custom" passes systemPrompt to RagQuery
    // -----------------------------------------------------------------------

    @Test
    fun `--system-prompt Custom passes systemPrompt to RagQuery`(@TempDir tempDir: Path) {
        populateRepository(tempDir)

        val capturedQueries = mutableListOf<RagQuery>()
        val capturingPipeline = object : RagPipeline(
            EmbeddingSearchPipeline(LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard")),
            stubChatModel
        ) {
            override fun query(ragQuery: RagQuery): RagResult {
                capturedQueries.add(ragQuery)
                return RagResult("Answer", emptyList())
            }
        }

        val out = StringWriter()
        val err = StringWriter()
        val cmd = QueryCommand(
            storeDirOverride = tempDir,
            ragPipeline = capturingPipeline,
            outputFormatter = OutputFormatter(),
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
            inputStream = ByteArrayInputStream(ByteArray(0)),
        )
        cmd.questionArgs = listOf("hello")
        cmd.systemPromptOverride = "Custom prompt"

        cmd.call()

        assertThat(capturedQueries).hasSize(1)
        assertThat(capturedQueries[0].systemPrompt).isEqualTo("Custom prompt")
    }

    // -----------------------------------------------------------------------
    // Test 9: --verbose writes source details to errorWriter
    // -----------------------------------------------------------------------

    @Test
    fun `--verbose writes source info to errorWriter`(@TempDir tempDir: Path) {
        populateRepository(tempDir)

        val fixedResult = RagResult(
            answer = "Answer",
            sources = listOf(
                SourceReference(
                    filePath = "doc/source.txt",
                    chunkIndex = 0,
                    similarityScore = 0.87,
                    excerpt = "Some excerpt"
                )
            )
        )
        val stubPipeline = object : RagPipeline(
            EmbeddingSearchPipeline(LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard")),
            stubChatModel
        ) {
            override fun query(ragQuery: RagQuery): RagResult = fixedResult
        }

        val out = StringWriter()
        val err = StringWriter()
        val cmd = QueryCommand(
            storeDirOverride = tempDir,
            ragPipeline = stubPipeline,
            outputFormatter = OutputFormatter(),
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
            inputStream = ByteArrayInputStream(ByteArray(0)),
        )
        cmd.questionArgs = listOf("hello")
        cmd.verbose = true

        cmd.call()

        val errOutput = err.toString()
        assertThat(errOutput).contains("doc/source.txt")
        assertThat(errOutput).contains("0.87")
    }
}
