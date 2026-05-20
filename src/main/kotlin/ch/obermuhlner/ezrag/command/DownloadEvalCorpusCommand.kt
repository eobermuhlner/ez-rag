package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.beir.BeirCorpusConverter
import ch.obermuhlner.ezrag.beir.BeirCorpusReader
import ch.obermuhlner.ezrag.beir.BeirDatasetRegistry
import ch.obermuhlner.ezrag.beir.BeirDownloader
import ch.obermuhlner.ezrag.beir.ConversionConfig
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File
import java.io.PrintWriter
import java.util.concurrent.Callable

@Command(
    name = "download-eval-corpus",
    mixinStandardHelpOptions = true,
    description = ["Download and convert a BEIR dataset to an ez-rag eval corpus."]
)
@Component
class DownloadEvalCorpusCommand(
    private val registry: BeirDatasetRegistry = BeirDatasetRegistry(),
    private val outputWriter: PrintWriter = PrintWriter(System.out, true),
    private val errorWriter: PrintWriter = PrintWriter(System.err, true),
    private val downloader: BeirDownloader? = null,
) : Callable<Int> {

    @Option(names = ["--list"], description = ["Print all known BEIR datasets and exit."])
    var list: Boolean = false

    @Parameters(index = "0", arity = "0..1", description = ["BEIR dataset name."])
    var dataset: String? = null

    @Option(names = ["--output-dir"], description = ["Output directory. Default: ~/.ez-rag/beir/<dataset>/"])
    var outputDir: File? = null

    @Option(names = ["--split"], description = ["qrels split to use (default: test)."], defaultValue = "test")
    var split: String = "test"

    @Option(names = ["--max-questions"], description = ["Maximum questions to include (default: 50)."], defaultValue = "50")
    var maxQuestions: Int = 50

    @Option(names = ["--max-distractors"], description = ["Maximum distractor documents (default: 20)."], defaultValue = "20")
    var maxDistractors: Int = 20

    @Option(names = ["--recall-threshold"], description = ["Add recall_at_k to thresholds block when given."])
    var recallThreshold: Double? = null

    @Option(names = ["--hit-threshold"], description = ["Add hit_rate_at_k to thresholds block when given."])
    var hitThreshold: Double? = null

    @Option(names = ["--force"], description = ["Re-download and overwrite even when corpus already exists."])
    var force: Boolean = false

    override fun call(): Int {
        if (list) {
            printDatasetTable()
            return 0
        }

        val datasetName = dataset
        if (datasetName == null) {
            errorWriter.println("Error: Missing required argument <dataset>.")
            errorWriter.println("Use --list to see available datasets, or --help for usage.")
            return 1
        }

        if (registry.lookup(datasetName) == null) {
            val known = registry.allDatasets().joinToString(", ") { it.name }
            errorWriter.println("Error: Unknown dataset '$datasetName'.")
            errorWriter.println("Known datasets: $known")
            return 1
        }

        val targetDir = outputDir ?: File(System.getProperty("user.home"), ".ez-rag/beir/$datasetName")

        if (targetDir.resolve("questions.yaml").exists() && !force) {
            outputWriter.println("Skipping: questions.yaml already exists in ${targetDir.absolutePath}")
            return 0
        }

        val effectiveDownloader = downloader ?: BeirDownloader(outputWriter = outputWriter)
        effectiveDownloader.download(datasetName, targetDir, force)

        return runConversion(targetDir)
    }

    private fun runConversion(targetDir: File): Int {
        val reader = BeirCorpusReader()
        val data = reader.readCorpus(targetDir.toPath(), split)
        val config = ConversionConfig(
            maxQuestions = maxQuestions,
            maxDistractors = maxDistractors,
            recallThreshold = recallThreshold,
            hitThreshold = hitThreshold
        )
        outputWriter.println("Writing ${data.documents.size} documents")
        val converter = BeirCorpusConverter()
        converter.convert(data, config, targetDir.toPath())
        outputWriter.println("Writing questions.yaml")
        outputWriter.println("Corpus saved to ${targetDir.absolutePath}")
        return 0
    }

    private fun printDatasetTable() {
        val nameWidth = registry.allDatasets().maxOf { it.name.length }.coerceAtLeast(7)
        val domainWidth = registry.allDatasets().maxOf { it.domain.length }.coerceAtLeast(6)
        val header = "%-${nameWidth}s  %-${domainWidth}s  %8s  %8s".format("Dataset", "Domain", "~Docs", "~Queries")
        val separator = "-".repeat(header.length)
        outputWriter.println(header)
        outputWriter.println(separator)
        for (info in registry.allDatasets()) {
            outputWriter.println(
                "%-${nameWidth}s  %-${domainWidth}s  %8d  %8d".format(
                    info.name, info.domain, info.approxDocCount, info.approxQueryCount
                )
            )
        }
    }
}
