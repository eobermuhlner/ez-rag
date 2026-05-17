package ch.obermuhlner.ezrag.eval

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.PrintWriter

/**
 * Formats eval scenario reports as a plain-text table or JSON.
 */
class EvalReporter {

    private val objectMapper = ObjectMapper()

    /**
     * Writes a plain-text table of scenario reports to the given writer.
     *
     * Format:
     * ```
     * Scenario          Questions  Recall@3  MRR    Hit@3   Status
     * ──────────────────────────────────────────────────────────────
     * factual           8          1.00      0.94   1.00    PASS
     * ...
     * ──────────────────────────────────────────────────────────────
     * Overall           19         0.89      0.77   0.89
     * ```
     */
    fun reportText(reports: List<EvalScenarioReport>, writer: PrintWriter) {
        val hardNegativeSubRowLabel = "  hard-negative"
        val col1Width = maxOf(
            "Scenario".length,
            reports.maxOfOrNull { it.scenarioName.length } ?: 0,
            "Overall".length,
            if (reports.any { it.metrics.hardNegativeMetrics != null }) hardNegativeSubRowLabel.length else 0
        )
        val col2Width = maxOf("Questions".length, 9)
        val col3Width = maxOf("Recall@3".length, 8)
        val col4Width = maxOf("MRR".length, 6)
        val col5Width = maxOf("Hit@3".length, 6)
        val col6Width = maxOf("Status".length, 30) // allow room for FAIL messages

        val header = buildRow(
            "Scenario", col1Width,
            "Questions", col2Width,
            "Recall@3", col3Width,
            "MRR", col4Width,
            "Hit@3", col5Width,
            "Status", col6Width
        )
        val separator = "─".repeat(header.length)

        writer.println(header)
        writer.println(separator)

        for (report in reports) {
            val status = computeStatus(report)
            val row = buildRow(
                report.scenarioName, col1Width,
                report.questionCount.toString(), col2Width,
                "%.2f".format(report.metrics.recallAtK), col3Width,
                "%.2f".format(report.metrics.mrr), col4Width,
                "%.2f".format(report.metrics.hitRateAtK), col5Width,
                status, col6Width
            )
            writer.println(row)

            // If hard-negative subset metrics are present, show an indented sub-row
            val hn = report.metrics.hardNegativeMetrics
            if (hn != null) {
                val hnRow = buildRow(
                    hardNegativeSubRowLabel, col1Width,
                    "", col2Width,
                    "%.2f".format(hn.recallAtK), col3Width,
                    "%.2f".format(hn.mrr), col4Width,
                    "%.2f".format(hn.hitRateAtK), col5Width,
                    "", col6Width
                )
                writer.println(hnRow)
            }
        }

        writer.println(separator)

        // Overall row
        val totalQuestions = reports.sumOf { it.questionCount }
        val avgRecall = if (reports.isEmpty()) 0.0 else reports.map { it.metrics.recallAtK }.average()
        val avgMrr = if (reports.isEmpty()) 0.0 else reports.map { it.metrics.mrr }.average()
        val avgHit = if (reports.isEmpty()) 0.0 else reports.map { it.metrics.hitRateAtK }.average()

        val overallRow = buildRow(
            "Overall", col1Width,
            totalQuestions.toString(), col2Width,
            "%.2f".format(avgRecall), col3Width,
            "%.2f".format(avgMrr), col4Width,
            "%.2f".format(avgHit), col5Width,
            "", col6Width
        )
        writer.println(overallRow)
    }

    /**
     * Computes the status string for a scenario report.
     * Returns empty string if no thresholds are set.
     * Returns "PASS" if all thresholds are met.
     * Returns "FAIL  (<metric> < <threshold>)" for the first failing metric.
     */
    fun computeStatus(report: EvalScenarioReport): String {
        val thresholds = report.thresholds ?: return ""
        val metrics = report.metrics

        if (thresholds.recallAtK != null && metrics.recallAtK < thresholds.recallAtK) {
            return "FAIL  (recall_at_k < ${thresholds.recallAtK})"
        }
        if (thresholds.mrr != null && metrics.mrr < thresholds.mrr) {
            return "FAIL  (mrr < ${thresholds.mrr})"
        }
        if (thresholds.hitRateAtK != null && metrics.hitRateAtK < thresholds.hitRateAtK) {
            return "FAIL  (hit_rate_at_k < ${thresholds.hitRateAtK})"
        }
        return "PASS"
    }

    /**
     * Returns true if any threshold-bearing scenario fails its thresholds.
     */
    fun anyThresholdFailing(reports: List<EvalScenarioReport>): Boolean {
        return reports.any { report ->
            val status = computeStatus(report)
            status.startsWith("FAIL")
        }
    }

    /**
     * Writes a JSON array of scenario report objects to the given writer.
     *
     * Each element has: scenario, questions, recallAtK, mrr, hitRateAtK,
     * optional status (only when thresholds are defined),
     * optional hardNegatives nested object (only when hardNegativeMetrics is non-null).
     */
    fun reportJson(reports: List<EvalScenarioReport>, writer: PrintWriter) {
        val array: ArrayNode = objectMapper.createArrayNode()

        for (report in reports) {
            val obj: ObjectNode = objectMapper.createObjectNode()
            obj.put("scenario", report.scenarioName)
            obj.put("questions", report.questionCount)
            obj.put("recallAtK", report.metrics.recallAtK)
            obj.put("mrr", report.metrics.mrr)
            obj.put("hitRateAtK", report.metrics.hitRateAtK)

            val status = computeStatus(report)
            if (report.thresholds != null) {
                obj.put("status", status)
            }

            val hn = report.metrics.hardNegativeMetrics
            if (hn != null) {
                val hnObj: ObjectNode = objectMapper.createObjectNode()
                hnObj.put("recallAtK", hn.recallAtK)
                hnObj.put("mrr", hn.mrr)
                hnObj.put("hitRateAtK", hn.hitRateAtK)
                obj.set<ObjectNode>("hardNegatives", hnObj)
            }

            array.add(obj)
        }

        writer.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(array))
    }

    private fun buildRow(
        v1: String, w1: Int,
        v2: String, w2: Int,
        v3: String, w3: Int,
        v4: String, w4: Int,
        v5: String, w5: Int,
        v6: String, @Suppress("UNUSED_PARAMETER") w6: Int
    ): String {
        return "%-${w1}s  %-${w2}s  %-${w3}s  %-${w4}s  %-${w5}s  %s".format(v1, v2, v3, v4, v5, v6).trimEnd()
    }
}
