package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.config.ConfigService
import ch.obermuhlner.ezrag.config.EzRagDirResolver
import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import ch.obermuhlner.ezrag.ingestion.ReIngestService
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.PrintWriter
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable

@Command(
    name = "reingest",
    mixinStandardHelpOptions = true,
    description = ["Re-ingest stale documents (or all documents with --all) into the vector store."]
)
@Component
class ReIngestCommand(
    private val embeddingModel: EmbeddingModel? = null,
    private val storeDirOverride: Path? = null,
    private val outputWriter: PrintWriter = PrintWriter(System.out, true),
    private val warningWriter: PrintWriter = PrintWriter(System.err, true),
    private val chunkSize: Int? = null,
    private val chunkOverlap: Int? = null,
    private val quiet: Boolean = false,
    private val startDirOverride: Path? = null,
    private val configServiceOverride: ConfigService? = null,
) : Callable<Int> {

    @Autowired(required = false)
    private var springEmbeddingModel: EmbeddingModel? = null

    @Autowired(required = false)
    private var springConfigService: ConfigService? = null

    @Option(names = ["--store-dir"], description = ["Path to the store directory."])
    var storeDirOption: String? = null

    @Option(names = ["--chunk-size"], description = ["Chunk size in tokens (default: 1000)."])
    var chunkSizeOption: Int? = null

    @Option(names = ["--chunk-overlap"], description = ["Chunk overlap in tokens (default: 200)."])
    var chunkOverlapOption: Int? = null

    @Option(names = ["--quiet", "-q"], description = ["Suppress per-file output; show only the summary line."])
    var quietOption: Boolean = false

    @Option(names = ["--all"], description = ["Re-ingest all documents regardless of staleness."])
    var forceAllOption: Boolean = false

    @Option(names = ["--url-freshness-hours"], description = ["Freshness window in hours for URL sources (default: 24)."])
    var urlFreshnessHours: Int = 24

    override fun call(): Int {
        val model = embeddingModel ?: springEmbeddingModel ?: return exitWithError("No embedding model configured.")

        val resolvedStoreDir = storeDirOverride
            ?: storeDirOption?.let { Paths.get(it) }
            ?: (configServiceOverride ?: springConfigService)?.resolveExplicitStoreDir()?.let { Paths.get(it) }
            ?: EzRagDirResolver().resolve(startDirOverride ?: Paths.get("").toAbsolutePath())

        val resolvedChunkSize = chunkSize ?: chunkSizeOption ?: 1000
        val resolvedChunkOverlap = chunkOverlap ?: chunkOverlapOption ?: 200
        val isQuiet = quiet || quietOption
        val analyzerName = (configServiceOverride ?: springConfigService)?.resolve()?.analyzer ?: "standard"

        if (forceAllOption) {
            LuceneRepository.resetStoredDimension(resolvedStoreDir)
        }

        LuceneRepository.open(model, resolvedStoreDir, analyzerName).use { repo ->
            val service = ReIngestService(repo, resolvedChunkSize, resolvedChunkOverlap, warningWriter)

            if (!isQuiet) {
                service.onFileReIngesting = { path -> outputWriter.println("Re-ingesting: $path") }
            }

            val result = service.reIngest(forceAll = forceAllOption, urlFreshnessThresholdMs = urlFreshnessHours * 3_600_000L)

            if (!forceAllOption) {
                outputWriter.println("Stale documents: ${result.staleFound}")
            }
            outputWriter.println("${result.filesReIngested} files re-ingested, ${result.chunksCreated} chunks created, ${result.filesSkipped} skipped")
        }
        return 0
    }

    private fun exitWithError(message: String): Int {
        outputWriter.println("Error: $message")
        return 1
    }
}
