package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.config.ConfigService
import ch.obermuhlner.ezrag.config.EzRagDirResolver
import ch.obermuhlner.ezrag.ingestion.FileSource
import ch.obermuhlner.ezrag.ingestion.IngestService
import ch.obermuhlner.ezrag.ingestion.IngestSource
import ch.obermuhlner.ezrag.ingestion.JsoupUrlFetcher
import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import ch.obermuhlner.ezrag.ingestion.UrlFetcher
import ch.obermuhlner.ezrag.ingestion.UrlSource
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.transformers.TransformersEmbeddingModel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File
import java.io.PrintWriter
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable

@Command(
    name = "ingest",
    mixinStandardHelpOptions = true,
    description = ["Ingest documents into the vector store."]
)
@Component
class IngestCommand(
    private val embeddingModel: EmbeddingModel? = null,
    private val storeDirOverride: Path? = null,
    private val outputWriter: PrintWriter = PrintWriter(System.out, true),
    private val warningWriter: PrintWriter = PrintWriter(System.err, true),
    private val chunkSize: Int? = null,
    private val chunkOverlap: Int? = null,
    private val verbose: Boolean = false,
    private val quiet: Boolean = false,
    private val modelCachePath: Path = Paths.get(System.getProperty("user.home"), ".ez-rag", "models"),
    private val startDirOverride: Path? = null,
    private val configServiceOverride: ConfigService? = null,
    private val urlFetcher: UrlFetcher = JsoupUrlFetcher(),
) : Callable<Int> {

    @Autowired(required = false)
    private var springEmbeddingModel: EmbeddingModel? = null

    @Autowired(required = false)
    private var springConfigService: ConfigService? = null

    @Parameters(arity = "1..*", description = ["Files, directories, or HTTP/HTTPS URLs to ingest."])
    var paths: List<String> = emptyList()

    @Option(names = ["--store-dir"], description = ["Path to the store directory."])
    var storeDirOption: String? = null

    @Option(names = ["--chunk-size"], description = ["Chunk size in tokens (default: 1000)."])
    var chunkSizeOption: Int? = null

    @Option(names = ["--chunk-overlap"], description = ["Chunk overlap in tokens (default: 200)."])
    var chunkOverlapOption: Int? = null

    @Option(names = ["--quiet", "-q"], description = ["Suppress per-file output; show only the summary line."])
    var quietOption: Boolean = false

    override fun call(): Int {
        val sources = paths.map { path ->
            if (path.startsWith("http://") || path.startsWith("https://")) UrlSource(path)
            else FileSource(File(path))
        }
        return doCall(sources)
    }

    fun call(files: List<File>): Int = doCall(files.map { FileSource(it) })

    private fun doCall(sources: List<IngestSource>): Int {
        val model = embeddingModel ?: springEmbeddingModel ?: return exitWithError("No embedding model configured.")

        // First-run detection: print a one-line message if the ONNX model has not been downloaded yet
        if (model is TransformersEmbeddingModel) {
            val cacheDir = modelCachePath.toFile()
            val cacheEmpty = !cacheDir.exists() || (cacheDir.isDirectory && (cacheDir.list()?.isEmpty() != false))
            if (cacheEmpty) {
                outputWriter.println("Downloading embedding model all-MiniLM-L6-v2 (first run, this may take a moment)…")
            }
        }

        // Pre-flight: verify embedding provider is usable before any file I/O
        try {
            model.embed("test")
        } catch (e: Exception) {
            return exitWithError("Embedding provider is not configured correctly: ${e.message}")
        }

        val resolvedStoreDir = storeDirOverride
            ?: storeDirOption?.let { Paths.get(it) }
            ?: (configServiceOverride ?: springConfigService)?.resolveExplicitStoreDir()?.let { Paths.get(it) }
            ?: EzRagDirResolver().resolve(startDirOverride ?: Paths.get("").toAbsolutePath())
        val resolvedChunkSize = chunkSize ?: chunkSizeOption ?: 1000
        val resolvedChunkOverlap = chunkOverlap ?: chunkOverlapOption ?: 200
        val analyzerName = (configServiceOverride ?: springConfigService)?.resolve()?.analyzer ?: "standard"

        val isQuiet = quiet || quietOption
        val isVerbose = verbose

        LuceneRepository.open(model, resolvedStoreDir, analyzerName).use { repo ->
            val service = IngestService(
                repo, resolvedChunkSize, resolvedChunkOverlap, warningWriter,
                urlFetcher = urlFetcher,
            )

            if (!isQuiet) {
                service.onFileIngesting = { path -> outputWriter.println("Ingesting: $path") }
                service.onFileSkipped = { path, reason -> outputWriter.println("Skipping: $path ($reason)") }
            }

            if (isVerbose) {
                service.onFileLoaded = { _, chunks ->
                    chunks.forEachIndexed { index, chunk ->
                        val tokenCount = chunk.text?.split(Regex("\\s+"))?.size ?: 0
                        val text = chunk.text ?: ""
                        val preview = if (text.length > 60) text.take(60).replace('\n', ' ') + "…"
                                      else text.replace('\n', ' ')
                        outputWriter.println("  Chunk $index [$tokenCount tokens]: \"$preview\"")
                    }
                }
            }

            val result = service.ingest(sources)
            outputWriter.println("${result.filesIngested} files ingested, ${result.chunksCreated} chunks created, ${result.skipped} skipped")
        }
        return 0
    }

    private fun exitWithError(message: String): Int {
        outputWriter.println("Error: $message")
        return 1
    }
}
