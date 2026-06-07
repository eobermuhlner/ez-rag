package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.eval.EvalCorpusLoader
import ch.obermuhlner.ezrag.eval.EvalEngine
import ch.obermuhlner.ezrag.eval.EvalMetricsCalculator
import ch.obermuhlner.ezrag.eval.EvalReporter
import ch.obermuhlner.ezrag.eval.EvalScenarioReport
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File
import java.io.PrintWriter
import java.nio.file.Files
import java.util.concurrent.Callable

@Command(
    name = "eval",
    mixinStandardHelpOptions = true,
    description = ["Evaluate retrieval quality against a corpus of scenarios."]
)
@Component
class EvalCommand(
    private val embeddingModel: EmbeddingModel? = null,
    private val outputWriter: PrintWriter = PrintWriter(System.out, true),
    private val errorWriter: PrintWriter = PrintWriter(System.err, true),
) : Callable<Int> {

    @Autowired(required = false)
    private var springEmbeddingModel: EmbeddingModel? = null

    @Parameters(index = "0", description = ["Path to the corpus directory containing scenario subdirectories."])
    var corpusDir: File = File(".")

    @Option(
        names = ["--output-format"],
        description = ["Output format: text (default) or json."],
        defaultValue = "text"
    )
    var outputFormat: String = "text"

    override fun call(): Int {
        val model = embeddingModel ?: springEmbeddingModel
            ?: return exitWithError("No embedding model configured.")

        if (!corpusDir.exists()) {
            errorWriter.println("Error: Corpus directory does not exist: ${corpusDir.absolutePath}")
            return 1
        }

        val corpusPath = corpusDir.toPath()

        // Discover all questions.yaml files under the corpus directory
        val scenarioDirs = Files.walk(corpusPath)
            .filter { path -> path.fileName?.toString() == "questions.yaml" }
            .map { it.parent }
            .sorted()
            .toList()

        if (scenarioDirs.isEmpty()) {
            errorWriter.println("No eval scenarios found in $corpusPath")
            return 1
        }

        val loader = EvalCorpusLoader()
        val engine = EvalEngine()
        val calculator = EvalMetricsCalculator()
        val reporter = EvalReporter()

        val reports = mutableListOf<EvalScenarioReport>()

        for (scenarioDir in scenarioDirs) {
            val scenario = loader.load(scenarioDir)

            val storeDir = Files.createTempDirectory("ez-rag-eval-${scenario.name}-")
            try {
                val results = engine.evaluate(scenario, storeDir, model)
                val hardNegativeFilenames = scenario.documents
                    .filter { it.role == ch.obermuhlner.ezrag.eval.EvalDocumentRole.HARD_NEGATIVE }
                    .map { it.path.fileName.toString() }
                    .toSet()
                val metrics = calculator.calculate(results, k = 3, hardNegativeSourceFilenames = hardNegativeFilenames)

                reports.add(
                    EvalScenarioReport(
                        scenarioName = scenario.name,
                        questionCount = scenario.questions.size,
                        metrics = metrics,
                        thresholds = scenario.thresholds
                    )
                )
            } finally {
                storeDir.toFile().deleteRecursively()
            }
        }

        if (outputFormat == "json") {
            reporter.reportJson(reports, outputWriter)
        } else {
            reporter.reportText(reports, outputWriter)
        }

        return if (reporter.anyThresholdFailing(reports)) 1 else 0
    }

    private fun exitWithError(message: String): Int {
        errorWriter.println("Error: $message")
        return 1
    }
}
