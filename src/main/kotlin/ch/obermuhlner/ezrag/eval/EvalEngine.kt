package ch.obermuhlner.ezrag.eval

import ch.obermuhlner.ezrag.command.IngestCommand
import ch.obermuhlner.ezrag.command.SearchCommand
import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import ch.obermuhlner.ezrag.rag.EmbeddingSearchPipeline
import ch.obermuhlner.ezrag.rag.OutputFormatter
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.ai.embedding.EmbeddingModel
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path

/**
 * Orchestrates evaluation of a single [EvalScenario] against a given store directory.
 *
 * @param searchProvider Optional override for the search step. Receives (question, scenario,
 *   storeDir, embeddingModel) and returns a (exitCode, jsonOutput) pair. Used in tests to
 *   simulate SearchCommand failures without running the full CLI pipeline.
 */
class EvalEngine(
    private val searchProvider: ((EvalQuestion, EvalScenario, Path, EmbeddingModel) -> Pair<Int, String>)? = null
) {

    private val objectMapper = ObjectMapper()

    /**
     * Evaluates a scenario against a given store directory.
     *
     * Steps:
     * 1. Ingest all scenario documents using IngestCommand.call()
     * 2. For each question, call SearchCommand.call() with --output json
     * 3. Parse results into EvalQuestionResult list
     */
    fun evaluate(
        scenario: EvalScenario,
        storeDir: Path,
        embeddingModel: EmbeddingModel
    ): List<EvalQuestionResult> {
        // Step 1: ingest all scenario documents
        val ingestOut = StringWriter()
        val ingestErr = StringWriter()
        val ingestCommand = IngestCommand(
            embeddingModel = embeddingModel,
            storeDirOverride = storeDir,
            outputWriter = PrintWriter(ingestOut, true),
            warningWriter = PrintWriter(ingestErr, true),
            quiet = true
        )
        val files = scenario.documents.map { it.path.toFile() }
        ingestCommand.call(files)

        // Step 2: open the unified Lucene index for reuse across questions
        return LuceneRepository.open(embeddingModel, storeDir, "standard").use { repository ->
            val pipeline = EmbeddingSearchPipeline(repository)

            // Step 3: for each question, call SearchCommand.call() with JSON output
            scenario.questions.map { question ->
                val (exitCode, jsonOutput) = if (searchProvider != null) {
                    searchProvider.invoke(question, scenario, storeDir, embeddingModel)
                } else {
                    defaultSearch(question, storeDir, pipeline, repository)
                }

                if (exitCode != 0) {
                    throw RuntimeException(
                        "Search failed (exit code $exitCode) for scenario '${scenario.name}', question '${question.id}'"
                    )
                }

                val retrievedChunks = if (jsonOutput.isNotEmpty()) {
                    parseRetrievedChunks(jsonOutput)
                } else {
                    emptyList()
                }

                EvalQuestionResult(
                    questionId = question.id,
                    expectedSources = question.expectedSources,
                    expectedChunkContains = question.expectedChunkContains,
                    retrievedChunks = retrievedChunks
                )
            }
        }
    }

    private fun defaultSearch(
        question: EvalQuestion,
        storeDir: Path,
        pipeline: EmbeddingSearchPipeline,
        repository: LuceneRepository
    ): Pair<Int, String> {
        val searchOut = StringWriter()
        val searchErr = StringWriter()
        val searchCommand = SearchCommand(
            storeDirOverride = storeDir,
            searchPipeline = pipeline,
            outputFormatter = OutputFormatter(),
            outputWriter = PrintWriter(searchOut, true),
            errorWriter = PrintWriter(searchErr, true),
        )
        searchCommand.questionArgs = listOf(question.question)
        searchCommand.outputFormat = "json"
        searchCommand.topK = 3

        val exitCode = searchCommand.call()
        return Pair(exitCode, searchOut.toString().trim())
    }

    /**
     * Parses retrieved chunks from the SearchCommand JSON output.
     * JSON format: {"chunks": [{"path": "<path>", "content": "<text>", ...}, ...]}
     */
    private fun parseRetrievedChunks(json: String): List<EvalRetrievedChunk> {
        return try {
            val root = objectMapper.readTree(json)
            val chunks = root.get("chunks") ?: return emptyList()
            chunks.mapNotNull { chunk ->
                val file = chunk.get("path")?.asText() ?: return@mapNotNull null
                val source = java.nio.file.Paths.get(file).fileName?.toString() ?: file
                val content = chunk.get("content")?.asText() ?: ""
                EvalRetrievedChunk(source = source, content = content)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
