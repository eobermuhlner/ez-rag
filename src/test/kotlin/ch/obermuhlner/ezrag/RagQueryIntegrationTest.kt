package ch.obermuhlner.ezrag

import ch.obermuhlner.ezrag.command.IngestCommand
import ch.obermuhlner.ezrag.command.QueryCommand
import ch.obermuhlner.ezrag.ingestion.VectorStoreRepository
import ch.obermuhlner.ezrag.rag.OutputFormatter
import ch.obermuhlner.ezrag.rag.RagPipeline
import com.fasterxml.jackson.databind.ObjectMapper
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
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path

/**
 * End-to-end integration tests for the RAG query pipeline.
 *
 * Uses a fake EmbeddingModel (returns fixed vectors) and a stub ChatModel (returns a fixed answer).
 * Exercises real disk I/O through VectorStoreRepository.
 * No Spring context, no live LLM, no live embedding API.
 */
class RagQueryIntegrationTest {

    // -----------------------------------------------------------------------
    // Shared stubs
    // -----------------------------------------------------------------------

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
        ChatResponse(listOf(Generation(AssistantMessage("The answer is in the document."))))
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Runs IngestCommand against [files] writing to [storePath]. Returns exit code. */
    private fun ingest(files: List<java.io.File>, storePath: Path): Int {
        val cmd = IngestCommand(embeddingModel = fakeEmbeddingModel, storePathOverride = storePath)
        return cmd.call(files)
    }

    /** Builds a QueryCommand wired to real disk and stub pipeline components. */
    private fun buildQueryCommand(
        storePath: Path,
        out: StringWriter,
        err: StringWriter,
        inputStream: ByteArrayInputStream = ByteArrayInputStream(ByteArray(0)),
    ): QueryCommand {
        val repo = VectorStoreRepository(fakeEmbeddingModel, storePath)
        repo.load()
        val pipeline = RagPipeline(repo, stubChatModel)
        return QueryCommand(
            storePathOverride = storePath,
            ragPipeline = pipeline,
            outputFormatter = OutputFormatter(),
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
            inputStream = inputStream,
        )
    }

    // -----------------------------------------------------------------------
    // Test 1: ingest + query exits code 0 and answer is non-empty
    // -----------------------------------------------------------------------

    @Test
    fun `after ingesting a text file querying exits code 0 and answer is non-empty`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("sample.txt").toFile()
        sampleFile.writeText("The project uses a microservices architecture with Docker containers.")

        val storePath = tempDir.resolve("vector-store.json")
        assertThat(ingest(listOf(sampleFile), storePath)).isEqualTo(0)

        val out = StringWriter()
        val err = StringWriter()
        val cmd = buildQueryCommand(storePath, out, err)
        cmd.questionArgs = listOf("What architecture is used?")

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString().trim()).isNotEmpty()
    }

    // -----------------------------------------------------------------------
    // Test 2: sources contain the ingested file's path
    // -----------------------------------------------------------------------

    @Test
    fun `sources in output contain the ingested file path`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("design.txt").toFile()
        sampleFile.writeText("The system relies on event-driven communication between services.")

        val storePath = tempDir.resolve("vector-store.json")
        ingest(listOf(sampleFile), storePath)

        val out = StringWriter()
        val err = StringWriter()
        val cmd = buildQueryCommand(storePath, out, err)
        cmd.questionArgs = listOf("How do services communicate?")

        cmd.call()

        // Text output includes "--- Sources ---" section with the file path
        val output = out.toString()
        assertThat(output).contains(sampleFile.absolutePath)
    }

    // -----------------------------------------------------------------------
    // Test 3: --output json is valid JSON and sources[0].file matches ingested path
    // -----------------------------------------------------------------------

    @Test
    fun `--output json is valid JSON and sources contain ingested file path`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("architecture.txt").toFile()
        sampleFile.writeText("Kubernetes orchestrates all container workloads in the platform.")

        val storePath = tempDir.resolve("vector-store.json")
        ingest(listOf(sampleFile), storePath)

        val out = StringWriter()
        val err = StringWriter()
        val cmd = buildQueryCommand(storePath, out, err)
        cmd.questionArgs = listOf("What orchestrates containers?")
        cmd.outputFormat = "json"

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)

        val jsonText = out.toString().trim()
        val mapper = ObjectMapper()
        val root = mapper.readTree(jsonText)
        assertThat(root.has("answer")).isTrue()
        assertThat(root.has("sources")).isTrue()
        assertThat(root.get("sources").isArray).isTrue()
        assertThat(root.get("sources").size()).isGreaterThanOrEqualTo(1)

        val sourceFiles = (0 until root.get("sources").size()).map { i ->
            root.get("sources").get(i).get("file").asText()
        }
        assertThat(sourceFiles).anyMatch { it.contains("architecture.txt") }
    }

    // -----------------------------------------------------------------------
    // Test 4: querying with no store file exits non-zero with output containing "ingest"
    // -----------------------------------------------------------------------

    @Test
    fun `querying with no store file exits non-zero and output contains ingest`(@TempDir tempDir: Path) {
        val storePath = tempDir.resolve("nonexistent-store.json")

        val out = StringWriter()
        val err = StringWriter()
        val cmd = buildQueryCommand(storePath, out, err)
        cmd.questionArgs = listOf("What is the architecture?")

        val exitCode = cmd.call()

        assertThat(exitCode).isNotEqualTo(0)
        assertThat(out.toString()).containsIgnoringCase("ingest")
    }

    // -----------------------------------------------------------------------
    // Test 5: multi-line stdin question is accepted and exits code 0
    // -----------------------------------------------------------------------

    @Test
    fun `multi-line stdin question is accepted and exits code 0`(@TempDir tempDir: Path) {
        val sampleFile = tempDir.resolve("notes.txt").toFile()
        sampleFile.writeText("The platform supports Java, Kotlin, and Python runtimes.")

        val storePath = tempDir.resolve("vector-store.json")
        ingest(listOf(sampleFile), storePath)

        val multiLineQuestion = """
            What languages are supported?
            Please list each one on its own line.
            Also mention the runtime versions if available.
        """.trimIndent()

        val inputStream = ByteArrayInputStream(multiLineQuestion.toByteArray(Charsets.UTF_8))
        val out = StringWriter()
        val err = StringWriter()
        val cmd = buildQueryCommand(storePath, out, err, inputStream)
        // No --question set; reading from stdin

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString().trim()).isNotEmpty()
    }
}
