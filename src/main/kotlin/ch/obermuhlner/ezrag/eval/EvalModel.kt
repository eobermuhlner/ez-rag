package ch.obermuhlner.ezrag.eval

import java.nio.file.Path

enum class EvalDocumentRole {
    RELEVANT,
    DISTRACTOR,
    HARD_NEGATIVE
}

data class EvalDocument(
    val path: Path,
    val role: EvalDocumentRole
)

data class EvalQuestion(
    val id: String,
    val question: String,
    val expectedSources: List<String> = emptyList(),
    val expectedChunkContains: List<String> = emptyList(),
    val expectedAnswerContains: List<String> = emptyList()
)

data class EvalThresholds(
    val recallAtK: Double? = null,
    val mrr: Double? = null,
    val hitRateAtK: Double? = null
)

data class EvalScenario(
    val name: String,
    val documents: List<EvalDocument>,
    val questions: List<EvalQuestion>,
    val thresholds: EvalThresholds? = null
)

data class EvalRetrievedChunk(val source: String, val content: String)

data class EvalQuestionResult(
    val questionId: String,
    val expectedSources: List<String>,
    val expectedChunkContains: List<String>,
    val retrievedChunks: List<EvalRetrievedChunk>
) {
    val retrievedSources: List<String> get() = retrievedChunks.map { it.source }
}

data class EvalMetrics(
    val recallAtK: Double,
    val mrr: Double,
    val hitRateAtK: Double,
    val hardNegativeMetrics: EvalMetrics? = null
)

data class EvalScenarioReport(
    val scenarioName: String,
    val questionCount: Int,
    val metrics: EvalMetrics,
    val thresholds: EvalThresholds?
)
