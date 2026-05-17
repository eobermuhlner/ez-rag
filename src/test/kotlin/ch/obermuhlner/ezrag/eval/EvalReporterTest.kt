package ch.obermuhlner.ezrag.eval

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.io.StringWriter

class EvalReporterTest {

    private val reporter = EvalReporter()

    // (a) text output contains a header row with columns Scenario, Questions, Recall@3, MRR, Hit@3, Status
    @Test
    fun `text table contains header row with required columns`() {
        val out = StringWriter()
        val reports = listOf(
            EvalScenarioReport(
                scenarioName = "factual",
                questionCount = 5,
                metrics = EvalMetrics(recallAtK = 1.0, mrr = 0.9, hitRateAtK = 1.0),
                thresholds = null
            )
        )

        reporter.reportText(reports, PrintWriter(out, true))

        val output = out.toString()
        assertThat(output).contains("Scenario")
        assertThat(output).contains("Questions")
        assertThat(output).containsAnyOf("Recall@3", "Recall")
        assertThat(output).contains("MRR")
        assertThat(output).containsAnyOf("Hit@3", "Hit Rate")
        assertThat(output).contains("Status")
    }

    // (b) a scenario meeting all thresholds shows PASS
    @Test
    fun `scenario meeting all thresholds shows PASS`() {
        val out = StringWriter()
        val reports = listOf(
            EvalScenarioReport(
                scenarioName = "factual",
                questionCount = 8,
                metrics = EvalMetrics(recallAtK = 1.0, mrr = 0.94, hitRateAtK = 1.0),
                thresholds = EvalThresholds(recallAtK = 0.8, mrr = 0.6, hitRateAtK = 0.9)
            )
        )

        reporter.reportText(reports, PrintWriter(out, true))

        assertThat(out.toString()).contains("PASS")
    }

    // (c) a scenario failing one threshold shows FAIL and names the failing metric
    @Test
    fun `scenario failing recall threshold shows FAIL with metric name`() {
        val out = StringWriter()
        val reports = listOf(
            EvalScenarioReport(
                scenarioName = "hard-negatives",
                questionCount = 5,
                metrics = EvalMetrics(recallAtK = 0.6, mrr = 0.65, hitRateAtK = 0.6),
                thresholds = EvalThresholds(recallAtK = 0.85, mrr = 0.6, hitRateAtK = 0.85)
            )
        )

        reporter.reportText(reports, PrintWriter(out, true))

        val output = out.toString()
        assertThat(output).contains("FAIL")
        assertThat(output).contains("recall_at_k")
    }

    // (d) a scenario with null thresholds has an empty Status cell
    @Test
    fun `scenario with null thresholds has empty status cell`() {
        val out = StringWriter()
        val reports = listOf(
            EvalScenarioReport(
                scenarioName = "multi-chunk",
                questionCount = 6,
                metrics = EvalMetrics(recallAtK = 0.83, mrr = 0.71, hitRateAtK = 0.83),
                thresholds = null
            )
        )

        reporter.reportText(reports, PrintWriter(out, true))

        val output = out.toString()
        assertThat(output).doesNotContain("PASS")
        assertThat(output).doesNotContain("FAIL")
    }

    // (e) the overall row aggregates question counts and averages metrics
    @Test
    fun `overall row sums question counts and averages metrics`() {
        val out = StringWriter()
        val reports = listOf(
            EvalScenarioReport(
                scenarioName = "factual",
                questionCount = 8,
                metrics = EvalMetrics(recallAtK = 1.0, mrr = 0.9, hitRateAtK = 1.0),
                thresholds = null
            ),
            EvalScenarioReport(
                scenarioName = "multi-chunk",
                questionCount = 6,
                metrics = EvalMetrics(recallAtK = 0.5, mrr = 0.5, hitRateAtK = 0.5),
                thresholds = null
            )
        )

        reporter.reportText(reports, PrintWriter(out, true))

        val output = out.toString()
        // Overall row should appear
        assertThat(output).containsAnyOf("Overall", "overall")
        // Total question count: 8 + 6 = 14
        assertThat(output).contains("14")
    }

    // --- Task 03: JSON output ---

    // reportJson produces valid JSON with required fields
    @Test
    fun `reportJson produces valid JSON array with required fields`() {
        val out = StringWriter()
        val reports = listOf(
            EvalScenarioReport(
                scenarioName = "factual",
                questionCount = 8,
                metrics = EvalMetrics(recallAtK = 1.0, mrr = 0.94, hitRateAtK = 1.0),
                thresholds = EvalThresholds(recallAtK = 0.8, mrr = 0.6, hitRateAtK = 0.9)
            )
        )

        reporter.reportJson(reports, PrintWriter(out, true))

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

    // reportJson includes status field for scenarios with thresholds
    @Test
    fun `reportJson includes status field for threshold-bearing scenarios`() {
        val out = StringWriter()
        val reports = listOf(
            EvalScenarioReport(
                scenarioName = "factual",
                questionCount = 8,
                metrics = EvalMetrics(recallAtK = 1.0, mrr = 0.94, hitRateAtK = 1.0),
                thresholds = EvalThresholds(recallAtK = 0.8, mrr = 0.6, hitRateAtK = 0.9)
            )
        )

        reporter.reportJson(reports, PrintWriter(out, true))

        val mapper = ObjectMapper()
        val element = mapper.readTree(out.toString())[0]
        assertThat(element.has("status")).isTrue()
        assertThat(element.get("status").asText()).isEqualTo("PASS")
    }

    // reportJson omits status field (or null) for scenarios without thresholds
    @Test
    fun `reportJson omits status field for scenarios without thresholds`() {
        val out = StringWriter()
        val reports = listOf(
            EvalScenarioReport(
                scenarioName = "multi-chunk",
                questionCount = 6,
                metrics = EvalMetrics(recallAtK = 0.83, mrr = 0.71, hitRateAtK = 0.83),
                thresholds = null
            )
        )

        reporter.reportJson(reports, PrintWriter(out, true))

        val mapper = ObjectMapper()
        val element = mapper.readTree(out.toString())[0]
        // Status should be absent or null when no thresholds
        val statusNode = element.get("status")
        assertThat(statusNode == null || statusNode.isNull).isTrue()
    }

    // --- Task 03: hard-negative sub-row in text table ---

    @Test
    fun `text table shows indented hard-negative sub-row when hardNegativeMetrics is non-null`() {
        val out = StringWriter()
        val reports = listOf(
            EvalScenarioReport(
                scenarioName = "hard-negatives",
                questionCount = 5,
                metrics = EvalMetrics(
                    recallAtK = 0.8, mrr = 0.65, hitRateAtK = 0.8,
                    hardNegativeMetrics = EvalMetrics(recallAtK = 0.5, mrr = 0.4, hitRateAtK = 0.5)
                ),
                thresholds = null
            )
        )

        reporter.reportText(reports, PrintWriter(out, true))

        val output = out.toString()
        assertThat(output).containsAnyOf("hard-negative", "hard_negative", "hard negatives")
    }

    @Test
    fun `hard-negative sub-row MRR value aligns with the MRR header column`() {
        val out = StringWriter()
        val reports = listOf(
            EvalScenarioReport(
                scenarioName = "hard-negatives",
                questionCount = 5,
                metrics = EvalMetrics(
                    recallAtK = 0.8, mrr = 0.65, hitRateAtK = 0.8,
                    hardNegativeMetrics = EvalMetrics(recallAtK = 0.50, mrr = 0.40, hitRateAtK = 0.50)
                ),
                thresholds = null
            )
        )

        reporter.reportText(reports, PrintWriter(out, true))

        val lines = out.toString().lines().filter { it.isNotBlank() }
        val headerLine = lines.first()
        val subRowLine = lines.first { it.trimStart().startsWith("hard-negative") }

        val mrrHeaderCol = headerLine.indexOf("MRR")
        // Check that a digit appears at the MRR column position in the sub-row (locale-agnostic)
        assertThat(subRowLine.getOrNull(mrrHeaderCol)?.isDigit())
            .describedAs("Sub-row should have a digit at the MRR column position ($mrrHeaderCol), but got: '${subRowLine.getOrNull(mrrHeaderCol)}'")
            .isTrue()
    }

    @Test
    fun `text table does not show hard-negative sub-row when hardNegativeMetrics is null`() {
        val out = StringWriter()
        val reports = listOf(
            EvalScenarioReport(
                scenarioName = "factual",
                questionCount = 5,
                metrics = EvalMetrics(recallAtK = 1.0, mrr = 1.0, hitRateAtK = 1.0, hardNegativeMetrics = null),
                thresholds = null
            )
        )

        reporter.reportText(reports, PrintWriter(out, true))

        val output = out.toString()
        assertThat(output).doesNotContain("hard-negative")
        assertThat(output).doesNotContain("hard_negative")
    }

    // --- Task 03: hardNegatives in JSON ---

    @Test
    fun `reportJson includes hardNegatives nested object when hardNegativeMetrics is non-null`() {
        val out = StringWriter()
        val reports = listOf(
            EvalScenarioReport(
                scenarioName = "hard-negatives",
                questionCount = 5,
                metrics = EvalMetrics(
                    recallAtK = 0.8, mrr = 0.65, hitRateAtK = 0.8,
                    hardNegativeMetrics = EvalMetrics(recallAtK = 0.5, mrr = 0.4, hitRateAtK = 0.5)
                ),
                thresholds = null
            )
        )

        reporter.reportJson(reports, PrintWriter(out, true))

        val mapper = ObjectMapper()
        val element = mapper.readTree(out.toString())[0]
        assertThat(element.has("hardNegatives")).isTrue()
        assertThat(element.get("hardNegatives").isNull).isFalse()
    }

    @Test
    fun `reportJson omits hardNegatives when hardNegativeMetrics is null`() {
        val out = StringWriter()
        val reports = listOf(
            EvalScenarioReport(
                scenarioName = "factual",
                questionCount = 5,
                metrics = EvalMetrics(recallAtK = 1.0, mrr = 1.0, hitRateAtK = 1.0, hardNegativeMetrics = null),
                thresholds = null
            )
        )

        reporter.reportJson(reports, PrintWriter(out, true))

        val mapper = ObjectMapper()
        val element = mapper.readTree(out.toString())[0]
        val hardNeg = element.get("hardNegatives")
        assertThat(hardNeg == null || hardNeg.isNull).isTrue()
    }
}
