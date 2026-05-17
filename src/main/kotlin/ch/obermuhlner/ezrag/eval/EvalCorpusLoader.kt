package ch.obermuhlner.ezrag.eval

import org.yaml.snakeyaml.Yaml
import java.nio.file.Path

class EvalCorpusLoader {

    fun load(scenarioDir: Path): EvalScenario {
        val yamlFile = scenarioDir.resolve("questions.yaml").toFile()
        require(yamlFile.exists()) { "No questions.yaml found in $scenarioDir" }

        val data: Map<String, Any> = yamlFile.inputStream().use { Yaml().load(it) }

        val name = scenarioDir.fileName?.toString() ?: scenarioDir.toString()

        val documents = parseDocuments(data, scenarioDir)
        val questions = parseQuestions(data)
        val thresholds = parseThresholds(data)

        return EvalScenario(
            name = name,
            documents = documents,
            questions = questions,
            thresholds = thresholds
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseDocuments(data: Map<String, Any>, scenarioDir: Path): List<EvalDocument> {
        val docList = data["documents"] as? List<Map<String, Any>> ?: emptyList()
        return docList.map { docMap ->
            val file = docMap["file"] as String
            val roleStr = (docMap["role"] as? String) ?: "relevant"
            val role = when (roleStr) {
                "relevant" -> EvalDocumentRole.RELEVANT
                "distractor" -> EvalDocumentRole.DISTRACTOR
                "hard-negative" -> EvalDocumentRole.HARD_NEGATIVE
                else -> throw IllegalArgumentException("Unknown role: $roleStr")
            }
            val resolvedPath = scenarioDir.resolve(file).toAbsolutePath()
            require(resolvedPath.toFile().exists()) {
                "Document file does not exist: $file (resolved to $resolvedPath)"
            }
            EvalDocument(
                path = resolvedPath,
                role = role
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseQuestions(data: Map<String, Any>): List<EvalQuestion> {
        val questionList = data["questions"] as? List<Map<String, Any>> ?: emptyList()
        return questionList.map { qMap ->
            val id = qMap["id"] as String
            val question = qMap["question"] as String
            val expectedSources = (qMap["expected_sources"] as? List<String>) ?: emptyList()
            val expectedChunkContains = (qMap["expected_chunk_contains"] as? List<String>) ?: emptyList()
            val expectedAnswerContains = (qMap["expected_answer_contains"] as? List<String>) ?: emptyList()
            EvalQuestion(
                id = id,
                question = question,
                expectedSources = expectedSources,
                expectedChunkContains = expectedChunkContains,
                expectedAnswerContains = expectedAnswerContains
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseThresholds(data: Map<String, Any>): EvalThresholds? {
        val threshMap = data["thresholds"] as? Map<String, Any> ?: return null
        return EvalThresholds(
            recallAtK = threshMap.double("recall_at_k"),
            mrr = threshMap.double("mrr"),
            hitRateAtK = threshMap.double("hit_rate_at_k")
        )
    }

    private fun Map<String, Any>.double(key: String): Double? = when (val v = this[key]) {
        is Double -> v
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }
}
