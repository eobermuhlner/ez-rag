package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.IngestService
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
    private val storePathOverride: Path? = null,
    private val outputWriter: PrintWriter = PrintWriter(System.out, true),
    private val warningWriter: PrintWriter = PrintWriter(System.err, true),
    private val chunkSize: Int? = null,
    private val chunkOverlap: Int? = null,
    private val verbose: Boolean = false,
    private val modelCachePath: Path = Paths.get(System.getProperty("user.home"), ".ez-rag", "models"),
) : Callable<Int> {

    @Autowired(required = false)
    private var springEmbeddingModel: EmbeddingModel? = null

    @Parameters(arity = "1..*", description = ["Files or directories to ingest."])
    var paths: List<File> = emptyList()

    @Option(names = ["--store"], description = ["Path to the vector store JSON file."])
    var storePathOption: String? = null

    @Option(names = ["--chunk-size"], description = ["Chunk size in tokens (default: 1000)."])
    var chunkSizeOption: Int? = null

    @Option(names = ["--chunk-overlap"], description = ["Chunk overlap in tokens (default: 200)."])
    var chunkOverlapOption: Int? = null

    override fun call(): Int = call(paths)

    fun call(files: List<File>): Int {
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

        val resolvedStorePath = storePathOverride
            ?: storePathOption?.let { Paths.get(it) }
            ?: Paths.get(".ez-rag/vector-store.json")
        val resolvedChunkSize = chunkSize ?: chunkSizeOption ?: 1000
        val resolvedChunkOverlap = chunkOverlap ?: chunkOverlapOption ?: 200

        val service = IngestService(model, resolvedStorePath, resolvedChunkSize, resolvedChunkOverlap, warningWriter)

        if (verbose) {
            service.onFileLoaded = { path, chunks ->
                outputWriter.println("Loading: $path")
                chunks.forEachIndexed { index, chunk ->
                    val tokenCount = chunk.text?.split(Regex("\\s+"))?.size ?: 0
                    outputWriter.println("Chunk $index: $tokenCount tokens")
                }
            }
        }

        val result = service.ingest(files)
        outputWriter.println("${result.filesIngested} files ingested, ${result.chunksCreated} chunks created, ${result.skipped} skipped")
        return 0
    }

    private fun exitWithError(message: String): Int {
        outputWriter.println("Error: $message")
        return 1
    }
}
