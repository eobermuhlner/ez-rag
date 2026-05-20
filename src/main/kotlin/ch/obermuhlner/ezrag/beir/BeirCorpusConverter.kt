package ch.obermuhlner.ezrag.beir

import java.nio.file.Path
import kotlin.random.Random

class BeirCorpusConverter {

    fun convert(data: BeirCorpusData, config: ConversionConfig, outputDir: Path) {
        outputDir.toFile().mkdirs()

        val rng = Random(config.randomSeed)

        val queriesWithRelevant = data.qrels.entries
            .filter { (_, docs) -> docs.isNotEmpty() }
            .map { it.key }
            .filter { queryId -> data.queries.containsKey(queryId) }
            .shuffled(rng)
            .take(config.maxQuestions)

        val relevantDocIds = queriesWithRelevant.flatMap { queryId ->
            data.qrels[queryId]?.keys ?: emptySet()
        }.toSet()

        val docById = data.documents.associateBy { it.id }

        val distractorCandidates = data.documents
            .filter { it.id !in relevantDocIds }
            .shuffled(rng)
        val distractorCount = minOf(config.maxDistractors, distractorCandidates.size)
        val distractorDocIds = distractorCandidates.take(distractorCount).map { it.id }.toSet()

        val allDocIds = relevantDocIds + distractorDocIds

        for (docId in allDocIds) {
            val doc = docById[docId] ?: continue
            outputDir.resolve(docFilename(docId)).toFile().writeText("${doc.title}\n\n${doc.text}")
        }

        writeQuestionsYaml(outputDir, queriesWithRelevant, data, relevantDocIds, distractorDocIds, config)
    }

    private fun writeQuestionsYaml(
        outputDir: Path,
        selectedQueryIds: List<String>,
        data: BeirCorpusData,
        relevantDocIds: Set<String>,
        distractorDocIds: Set<String>,
        config: ConversionConfig
    ) {
        val sb = StringBuilder()
        sb.appendLine("documents:")
        for (docId in relevantDocIds.sorted()) {
            sb.appendLine("  - file: ${docFilename(docId)}")
            sb.appendLine("    role: relevant")
        }
        for (docId in distractorDocIds.sorted()) {
            sb.appendLine("  - file: ${docFilename(docId)}")
            sb.appendLine("    role: distractor")
        }
        sb.appendLine()
        sb.appendLine("questions:")
        for (queryId in selectedQueryIds) {
            val questionText = data.queries[queryId] ?: continue
            val relevantForQuery = data.qrels[queryId]?.keys?.sorted() ?: emptyList()
            val escapedQuestion = questionText.replace("\"", "\\\"")
            sb.appendLine("  - id: \"${sanitize(queryId)}\"")
            sb.appendLine("    question: \"$escapedQuestion\"")
            sb.append("    expected_sources: [")
            sb.append(relevantForQuery.joinToString(", ") { "\"${docFilename(it)}\"" })
            sb.appendLine("]")
        }
        if (config.recallThreshold != null || config.hitThreshold != null) {
            sb.appendLine()
            sb.appendLine("thresholds:")
            if (config.recallThreshold != null) {
                sb.appendLine("  recall_at_k: ${config.recallThreshold}")
            }
            if (config.hitThreshold != null) {
                sb.appendLine("  hit_rate_at_k: ${config.hitThreshold}")
            }
        }
        outputDir.resolve("questions.yaml").toFile().writeText(sb.toString())
    }

    internal fun docFilename(id: String): String = sanitize(id) + ".txt"

    private fun sanitize(id: String): String =
        id.replace(Regex("[^a-zA-Z0-9]"), "_").take(120)
}
