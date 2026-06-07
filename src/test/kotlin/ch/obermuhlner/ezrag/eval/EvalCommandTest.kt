package ch.obermuhlner.ezrag.eval

import ch.obermuhlner.ezrag.command.EvalCommand
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
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path

class EvalCommandTest {

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

    @Test
    fun `eval command with one scenario prints scenario name and metric values and exits 0`(
        @TempDir tempDir: Path
    ) {
        // Set up a corpus dir with one scenario
        val scenarioDir = tempDir.resolve("my-scenario")
        scenarioDir.toFile().mkdirs()

        val doc1 = scenarioDir.resolve("doc1.txt").toFile()
        doc1.writeText("The capital of France is Paris.")

        val yaml = """
            documents:
              - file: doc1.txt
                role: relevant

            questions:
              - id: q1
                question: "What is the capital of France?"
                expected_sources: ["doc1.txt"]
        """.trimIndent()

        scenarioDir.resolve("questions.yaml").toFile().writeText(yaml)

        val out = StringWriter()
        val err = StringWriter()
        val cmd = EvalCommand(
            embeddingModel = fakeEmbeddingModel,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true)
        )
        cmd.corpusDir = tempDir.toFile()

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        val output = out.toString()
        // Should contain scenario name
        assertThat(output).contains("my-scenario")
        // Should contain metric column headers
        assertThat(output).containsAnyOf("Recall@5", "Recall")
        assertThat(output).contains("MRR")
        assertThat(output).containsAnyOf("Hit@5", "Hit")
        // Should contain numeric values
        assertThat(output).containsPattern(Regex("\\d+\\.\\d+").toPattern())
    }

    @Test
    fun `eval command with two scenarios prints one line per scenario`(
        @TempDir tempDir: Path
    ) {
        // Create two scenarios
        for (scenarioName in listOf("scenario-a", "scenario-b")) {
            val scenarioDir = tempDir.resolve(scenarioName)
            scenarioDir.toFile().mkdirs()
            scenarioDir.resolve("doc.txt").toFile().writeText("Content about $scenarioName.")
            val yaml = """
                documents:
                  - file: doc.txt
                    role: relevant

                questions:
                  - id: q1
                    question: "What is $scenarioName?"
                    expected_sources: ["doc.txt"]
            """.trimIndent()
            scenarioDir.resolve("questions.yaml").toFile().writeText(yaml)
        }

        val out = StringWriter()
        val err = StringWriter()
        val cmd = EvalCommand(
            embeddingModel = fakeEmbeddingModel,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true)
        )
        cmd.corpusDir = tempDir.toFile()

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        val output = out.toString()
        assertThat(output).contains("scenario-a")
        assertThat(output).contains("scenario-b")
    }

    @Test
    fun `eval command exits 0 when no scenario defines thresholds`(
        @TempDir tempDir: Path
    ) {
        val scenarioDir = tempDir.resolve("no-thresholds")
        scenarioDir.toFile().mkdirs()
        scenarioDir.resolve("doc.txt").toFile().writeText("Some content.")
        val yaml = """
            documents:
              - file: doc.txt
                role: relevant

            questions:
              - id: q1
                question: "What is this?"
                expected_sources: ["doc.txt"]
        """.trimIndent()
        scenarioDir.resolve("questions.yaml").toFile().writeText(yaml)

        val out = StringWriter()
        val err = StringWriter()
        val cmd = EvalCommand(
            embeddingModel = fakeEmbeddingModel,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true)
        )
        cmd.corpusDir = tempDir.toFile()

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    fun `eval command exits non-zero when a threshold-bearing scenario fails`(
        @TempDir tempDir: Path
    ) {
        val scenarioDir = tempDir.resolve("failing-scenario")
        scenarioDir.toFile().mkdirs()
        scenarioDir.resolve("doc.txt").toFile().writeText("Some content.")
        // Use an impossible threshold (recall must be >= 1.0 but with fake model it may be 0 or 1)
        // We use an expected_source that will never match to guarantee recall = 0
        val yaml = """
            documents:
              - file: doc.txt
                role: relevant

            questions:
              - id: q1
                question: "What is this?"
                expected_sources: ["nonexistent-file.txt"]

            thresholds:
              recall_at_k: 0.99
              mrr: 0.99
              hit_rate_at_k: 0.99
        """.trimIndent()
        scenarioDir.resolve("questions.yaml").toFile().writeText(yaml)

        val out = StringWriter()
        val err = StringWriter()
        val cmd = EvalCommand(
            embeddingModel = fakeEmbeddingModel,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true)
        )
        cmd.corpusDir = tempDir.toFile()

        val exitCode = cmd.call()

        assertThat(exitCode).isNotEqualTo(0)
        assertThat(out.toString()).contains("FAIL")
    }

    @Test
    fun `eval command exits 0 when all thresholds pass`(
        @TempDir tempDir: Path
    ) {
        val scenarioDir = tempDir.resolve("passing-scenario")
        scenarioDir.toFile().mkdirs()
        scenarioDir.resolve("doc.txt").toFile().writeText("Some content.")
        // threshold of 0.0 always passes
        val yaml = """
            documents:
              - file: doc.txt
                role: relevant

            questions:
              - id: q1
                question: "What is this?"
                expected_sources: ["doc.txt"]

            thresholds:
              recall_at_k: 0.0
              mrr: 0.0
              hit_rate_at_k: 0.0
        """.trimIndent()
        scenarioDir.resolve("questions.yaml").toFile().writeText(yaml)

        val out = StringWriter()
        val err = StringWriter()
        val cmd = EvalCommand(
            embeddingModel = fakeEmbeddingModel,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true)
        )
        cmd.corpusDir = tempDir.toFile()

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString()).contains("PASS")
    }

    // --- Task 04: error handling ---

    @Test
    fun `eval command with non-existent corpus dir exits non-zero and prints error to stderr`(
        @TempDir tempDir: Path
    ) {
        val nonExistent = tempDir.resolve("does-not-exist").toFile()

        val out = StringWriter()
        val err = StringWriter()
        val cmd = EvalCommand(
            embeddingModel = fakeEmbeddingModel,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true)
        )
        cmd.corpusDir = nonExistent

        val exitCode = cmd.call()

        assertThat(exitCode).isNotEqualTo(0)
        assertThat(err.toString()).containsIgnoringCase("does not exist")
    }

    @Test
    fun `eval command with corpus dir containing no questions yaml exits non-zero and prints no scenarios found`(
        @TempDir tempDir: Path
    ) {
        // tempDir exists but has no questions.yaml inside
        val out = StringWriter()
        val err = StringWriter()
        val cmd = EvalCommand(
            embeddingModel = fakeEmbeddingModel,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true)
        )
        cmd.corpusDir = tempDir.toFile()

        val exitCode = cmd.call()

        assertThat(exitCode).isNotEqualTo(0)
        assertThat(err.toString()).containsIgnoringCase("No eval scenarios found")
    }

    // -----------------------------------------------------------------------
    // Picocli-level flag tests: --output-format accepted, --format rejected
    // -----------------------------------------------------------------------

    @Test
    fun `--output-format json is accepted by picocli parser for EvalCommand`(@TempDir tempDir: Path) {
        // Create a minimal valid corpus so the command can parse and run
        val scenarioDir = tempDir.resolve("picocli-scenario")
        scenarioDir.toFile().mkdirs()
        scenarioDir.resolve("doc.txt").toFile().writeText("Some content.")
        scenarioDir.resolve("questions.yaml").toFile().writeText("""
            documents:
              - file: doc.txt
                role: relevant
            questions:
              - id: q1
                question: "What is this?"
                expected_sources: ["doc.txt"]
        """.trimIndent())

        val out = StringWriter()
        val err = StringWriter()
        val cmd = EvalCommand(
            embeddingModel = fakeEmbeddingModel,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true)
        )
        val commandLine = CommandLine(cmd)
        val exitCode = commandLine.execute("--output-format", "json", tempDir.toString())
        assertThat(exitCode).isNotEqualTo(CommandLine.ExitCode.USAGE)
    }

    @Test
    fun `--format json is rejected by picocli parser for EvalCommand`(@TempDir tempDir: Path) {
        val out = StringWriter()
        val err = StringWriter()
        val cmd = EvalCommand(
            embeddingModel = fakeEmbeddingModel,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true)
        )
        val commandLine = CommandLine(cmd)
        val exitCode = commandLine.execute("--format", "json", tempDir.toString())
        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.USAGE)
    }

    // --- Task 03: --format json ---

    @Test
    fun `eval command with --format json produces valid JSON array`(
        @TempDir tempDir: Path
    ) {
        val scenarioDir = tempDir.resolve("json-scenario")
        scenarioDir.toFile().mkdirs()
        scenarioDir.resolve("doc.txt").toFile().writeText("The capital of France is Paris.")
        val yaml = """
            documents:
              - file: doc.txt
                role: relevant

            questions:
              - id: q1
                question: "What is the capital of France?"
                expected_sources: ["doc.txt"]
        """.trimIndent()
        scenarioDir.resolve("questions.yaml").toFile().writeText(yaml)

        val out = StringWriter()
        val err = StringWriter()
        val cmd = EvalCommand(
            embeddingModel = fakeEmbeddingModel,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true)
        )
        cmd.corpusDir = tempDir.toFile()
        cmd.outputFormat = "json"

        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        val json = out.toString()
        val mapper = ObjectMapper()
        val array = mapper.readTree(json)
        assertThat(array.isArray).isTrue()
        assertThat(array.size()).isEqualTo(1)
        val element = array[0]
        assertThat(element.has("scenario")).isTrue()
        assertThat(element.has("questions")).isTrue()
        assertThat(element.has("recallAtK")).isTrue()
        assertThat(element.has("mrr")).isTrue()
        assertThat(element.has("hitRateAtK")).isTrue()
    }
}
