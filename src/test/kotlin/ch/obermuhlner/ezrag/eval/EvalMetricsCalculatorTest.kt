package ch.obermuhlner.ezrag.eval

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EvalMetricsCalculatorTest {

    private val calculator = EvalMetricsCalculator()

    private fun chunks(vararg sources: String): List<EvalRetrievedChunk> =
        sources.map { EvalRetrievedChunk(it, "") }

    private fun result(
        id: String,
        expectedSources: List<String>,
        retrievedSources: List<String>
    ) = EvalQuestionResult(id, expectedSources, emptyList(), chunks(*retrievedSources.toTypedArray()))

    // (a) all questions hit at rank 1 → recall=1.0, mrr=1.0
    @Test
    fun `all questions hit at rank 1 gives recall 1 and mrr 1`() {
        val results = listOf(
            result("q1", listOf("doc1.txt"), listOf("doc1.txt", "doc2.txt", "doc3.txt")),
            result("q2", listOf("doc2.txt"), listOf("doc2.txt", "doc1.txt")),
            result("q3", listOf("doc3.txt"), listOf("doc3.txt"))
        )

        val metrics = calculator.calculate(results, k = 5)

        assertThat(metrics.recallAtK).isEqualTo(1.0)
        assertThat(metrics.mrr).isEqualTo(1.0)
    }

    // (b) no questions hit → recall=0.0, mrr=0.0
    @Test
    fun `no questions hit gives recall 0 and mrr 0`() {
        val results = listOf(
            result("q1", listOf("doc1.txt"), listOf("doc2.txt", "doc3.txt")),
            result("q2", listOf("doc2.txt"), listOf("doc1.txt", "doc3.txt"))
        )

        val metrics = calculator.calculate(results, k = 5)

        assertThat(metrics.recallAtK).isEqualTo(0.0)
        assertThat(metrics.mrr).isEqualTo(0.0)
    }

    // (c) expected source at rank 2 for every question → mrr=0.5
    @Test
    fun `expected source at rank 2 for every question gives mrr 0_5`() {
        val results = listOf(
            result("q1", listOf("doc1.txt"), listOf("other.txt", "doc1.txt", "doc3.txt")),
            result("q2", listOf("doc2.txt"), listOf("other.txt", "doc2.txt", "doc3.txt")),
            result("q3", listOf("doc3.txt"), listOf("other.txt", "doc3.txt"))
        )

        val metrics = calculator.calculate(results, k = 5)

        assertThat(metrics.mrr).isEqualTo(0.5)
    }

    // (d) hitRateAtK equals recallAtK for the same input
    @Test
    fun `hitRateAtK equals recallAtK for the same input`() {
        val results = listOf(
            result("q1", listOf("doc1.txt"), listOf("doc1.txt", "doc2.txt")),
            result("q2", listOf("doc2.txt"), listOf("doc3.txt", "doc4.txt")) // miss
        )

        val metrics = calculator.calculate(results, k = 5)

        assertThat(metrics.hitRateAtK).isEqualTo(metrics.recallAtK)
        assertThat(metrics.hitRateAtK).isEqualTo(0.5)
    }

    // k-cutoff: expected source beyond rank k is not counted
    @Test
    fun `expected source beyond rank k is not counted`() {
        val results = listOf(
            result("q1", listOf("doc1.txt"), listOf("a.txt", "b.txt", "c.txt", "doc1.txt")), // rank 4, beyond k=3
        )

        val metrics = calculator.calculate(results, k = 3)

        assertThat(metrics.recallAtK).isEqualTo(0.0)
        assertThat(metrics.mrr).isEqualTo(0.0)
    }

    // empty results list gives 0.0 metrics
    @Test
    fun `empty results list gives zero metrics`() {
        val metrics = calculator.calculate(emptyList(), k = 5)

        assertThat(metrics.recallAtK).isEqualTo(0.0)
        assertThat(metrics.mrr).isEqualTo(0.0)
        assertThat(metrics.hitRateAtK).isEqualTo(0.0)
    }

    // --- Task 03: hard-negative subset metrics ---

    // hardNegativeMetrics is computed over only those questions targeting a hard-negative document
    @Test
    fun `hardNegativeMetrics is computed for questions targeting hard-negative documents`() {
        // 5 questions: 2 target hard-negative doc, 3 target regular docs
        val results = listOf(
            result("q1", listOf("relevant1.txt"), listOf("relevant1.txt")),
            result("q2", listOf("relevant1.txt"), listOf("relevant1.txt")),
            result("q3", listOf("relevant1.txt"), listOf("other.txt")),
            result("q4", listOf("hard-neg.txt"), listOf("hard-neg.txt")), // hard-negative hit
            result("q5", listOf("hard-neg.txt"), listOf("other.txt"))     // hard-negative miss
        )

        val hardNegFilenames = setOf("hard-neg.txt")
        val metrics = calculator.calculate(results, k = 5, hardNegativeSourceFilenames = hardNegFilenames)

        // Main metrics cover all 5 questions
        assertThat(metrics.hardNegativeMetrics).isNotNull()
        // hardNegativeMetrics covers only q4 and q5: 1 hit out of 2
        assertThat(metrics.hardNegativeMetrics!!.recallAtK).isEqualTo(0.5)
        assertThat(metrics.hardNegativeMetrics.mrr).isEqualTo(0.5)
    }

    // hardNegativeMetrics is null when no question targets a hard-negative document
    @Test
    fun `hardNegativeMetrics is null when no question targets a hard-negative document`() {
        val results = listOf(
            result("q1", listOf("doc1.txt"), listOf("doc1.txt")),
            result("q2", listOf("doc2.txt"), listOf("doc2.txt"))
        )

        // hard-negative set that does not overlap with any expected source
        val hardNegFilenames = setOf("hard-neg.txt")
        val metrics = calculator.calculate(results, k = 5, hardNegativeSourceFilenames = hardNegFilenames)

        assertThat(metrics.hardNegativeMetrics).isNull()
    }

    // --- chunk content matching ---

    @Test
    fun `chunk content substring match counts as hit when expectedChunkContains is set`() {
        val results = listOf(
            EvalQuestionResult(
                "q1",
                listOf("doc1.txt"),
                listOf("the answer phrase"),
                listOf(
                    EvalRetrievedChunk("doc1.txt", "This chunk contains the answer phrase right here."),
                    EvalRetrievedChunk("doc2.txt", "Unrelated content about something else.")
                )
            )
        )

        val metrics = calculator.calculate(results, k = 5)

        assertThat(metrics.recallAtK).isEqualTo(1.0)
        assertThat(metrics.mrr).isEqualTo(1.0)
    }

    @Test
    fun `chunk content miss when expected substring not found in any retrieved chunk`() {
        val results = listOf(
            EvalQuestionResult(
                "q1",
                listOf("doc1.txt"),
                listOf("missing phrase"),
                listOf(
                    EvalRetrievedChunk("doc1.txt", "This chunk does not have what you need."),
                    EvalRetrievedChunk("doc2.txt", "Also unrelated.")
                )
            )
        )

        val metrics = calculator.calculate(results, k = 5)

        assertThat(metrics.recallAtK).isEqualTo(0.0)
        assertThat(metrics.mrr).isEqualTo(0.0)
    }

    @Test
    fun `chunk content match is case-insensitive`() {
        val results = listOf(
            EvalQuestionResult(
                "q1",
                listOf("doc1.txt"),
                listOf("CAPITAL LETTERS"),
                listOf(
                    EvalRetrievedChunk("doc1.txt", "This chunk has capital letters in it.")
                )
            )
        )

        val metrics = calculator.calculate(results, k = 5)

        assertThat(metrics.recallAtK).isEqualTo(1.0)
    }

    @Test
    fun `chunk content match respects k cutoff`() {
        val results = listOf(
            EvalQuestionResult(
                "q1",
                listOf("doc1.txt"),
                listOf("the answer"),
                listOf(
                    EvalRetrievedChunk("other.txt", "no match"),
                    EvalRetrievedChunk("other.txt", "no match"),
                    EvalRetrievedChunk("other.txt", "no match"),
                    EvalRetrievedChunk("doc1.txt", "the answer is here")  // rank 4, beyond k=3
                )
            )
        )

        val metrics = calculator.calculate(results, k = 3)

        assertThat(metrics.recallAtK).isEqualTo(0.0)
    }

    @Test
    fun `falls back to filename matching when expectedChunkContains is empty`() {
        val results = listOf(
            EvalQuestionResult(
                "q1",
                listOf("doc1.txt"),
                emptyList(),
                listOf(
                    EvalRetrievedChunk("doc1.txt", "Any content"),
                    EvalRetrievedChunk("doc2.txt", "Other content")
                )
            )
        )

        val metrics = calculator.calculate(results, k = 5)

        assertThat(metrics.recallAtK).isEqualTo(1.0)
    }
}
