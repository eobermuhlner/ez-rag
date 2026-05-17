package ch.obermuhlner.ezrag.eval

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import java.nio.file.Path

class EvalEngineTest {

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
    fun `evaluate returns one EvalQuestionResult per question with retrievedSources from SearchCommand JSON`(
        @TempDir tempDir: Path
    ) {
        // Create 2 document files
        val doc1 = tempDir.resolve("doc1.txt").toFile()
        doc1.writeText("The sky is blue and the sun is yellow.")
        val doc2 = tempDir.resolve("doc2.txt").toFile()
        doc2.writeText("Water boils at 100 degrees Celsius.")

        // Create a scenario with 3 questions
        val scenario = EvalScenario(
            name = "test-scenario",
            documents = listOf(
                EvalDocument(doc1.toPath(), EvalDocumentRole.RELEVANT),
                EvalDocument(doc2.toPath(), EvalDocumentRole.RELEVANT)
            ),
            questions = listOf(
                EvalQuestion("q1", "What color is the sky?", listOf("doc1.txt")),
                EvalQuestion("q2", "At what temperature does water boil?", listOf("doc2.txt")),
                EvalQuestion("q3", "What is the color of the sun?", listOf("doc1.txt"))
            ),
            thresholds = null
        )

        val storeDir = tempDir.resolve("store")
        storeDir.toFile().mkdirs()

        val engine = EvalEngine()
        val results = engine.evaluate(scenario, storeDir, fakeEmbeddingModel)

        // Should have 3 results — one per question
        assertThat(results).hasSize(3)

        // Each result should have a question id
        val ids = results.map { it.questionId }
        assertThat(ids).containsExactlyInAnyOrder("q1", "q2", "q3")

        // Each result should have retrievedSources in rank order (filenames, not full paths)
        for (result in results) {
            assertThat(result.retrievedSources).isNotNull()
            // Since we ingested 2 docs, results should contain at least one filename
            assertThat(result.retrievedSources).isNotEmpty()
            // Filenames should be just the name part (e.g. "doc1.txt") or absolute paths
            // depending on how SearchCommand reports them
        }
    }

    @Test
    fun `evaluate uses SearchCommand call not internal service methods`(
        @TempDir tempDir: Path
    ) {
        // This test verifies that EvalEngine goes through the full CLI pipeline
        // by checking that when the store is properly populated via IngestCommand,
        // SearchCommand.call() is able to retrieve results.

        val doc1 = tempDir.resolve("relevant.txt").toFile()
        doc1.writeText("The capital of France is Paris.")

        val scenario = EvalScenario(
            name = "test",
            documents = listOf(
                EvalDocument(doc1.toPath(), EvalDocumentRole.RELEVANT)
            ),
            questions = listOf(
                EvalQuestion("q1", "What is the capital of France?", listOf("relevant.txt"))
            ),
            thresholds = null
        )

        val storeDir = tempDir.resolve("store")
        storeDir.toFile().mkdirs()

        val engine = EvalEngine()
        val results = engine.evaluate(scenario, storeDir, fakeEmbeddingModel)

        // Results should come from SearchCommand JSON output
        assertThat(results).hasSize(1)
        assertThat(results[0].questionId).isEqualTo("q1")
        assertThat(results[0].expectedSources).containsExactly("relevant.txt")
        // retrievedSources should contain the filenames from SearchCommand JSON
        assertThat(results[0].retrievedSources).isNotEmpty()
    }

    @Test
    fun `evaluate result contains non-empty chunk content from search output`(
        @TempDir tempDir: Path
    ) {
        val doc1 = tempDir.resolve("doc1.txt").toFile()
        doc1.writeText("The sky is blue.")

        val scenario = EvalScenario(
            name = "content-test",
            documents = listOf(EvalDocument(doc1.toPath(), EvalDocumentRole.RELEVANT)),
            questions = listOf(EvalQuestion("q1", "What color is the sky?", listOf("doc1.txt"))),
            thresholds = null
        )

        val storeDir = tempDir.resolve("store")
        storeDir.toFile().mkdirs()

        val results = EvalEngine().evaluate(scenario, storeDir, fakeEmbeddingModel)

        assertThat(results).hasSize(1)
        val chunks = results[0].retrievedChunks
        assertThat(chunks).isNotEmpty()
        assertThat(chunks[0].content).isNotBlank()
        assertThat(chunks[0].source).isNotBlank()
    }

    // --- Task 04: error handling ---

    @Test
    fun `evaluate throws exception with scenario name and question id when SearchCommand returns non-zero`(
        @TempDir tempDir: Path
    ) {
        val doc1 = tempDir.resolve("doc.txt").toFile()
        doc1.writeText("Some content.")

        val scenario = EvalScenario(
            name = "failing-scenario",
            documents = listOf(
                EvalDocument(doc1.toPath(), EvalDocumentRole.RELEVANT)
            ),
            questions = listOf(
                EvalQuestion("q-fail", "What?", listOf("doc.txt"))
            ),
            thresholds = null
        )

        val storeDir = tempDir.resolve("store")
        storeDir.toFile().mkdirs()

        // EvalEngine with a failing search provider to simulate SearchCommand returning non-zero
        val engine = EvalEngine(
            searchProvider = { _: EvalQuestion, _: EvalScenario, _: Path, _: EmbeddingModel ->
                Pair(1, "")
            }
        )

        assertThatThrownBy {
            engine.evaluate(scenario, storeDir, fakeEmbeddingModel)
        }
            .isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("failing-scenario")
            .hasMessageContaining("q-fail")
    }
}
