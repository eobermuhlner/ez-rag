package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.config.ConfigService
import ch.obermuhlner.ezrag.config.EzRagDirResolver
import ch.obermuhlner.ezrag.ingestion.VectorStoreRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable

@Command(
    name = "list",
    mixinStandardHelpOptions = true,
    description = ["List all ingested documents in the vector store."]
)
@Component
class ListCommand(
    private val embeddingModel: EmbeddingModel? = null,
    private val storeDirOverride: Path? = null,
    private val outputWriter: PrintWriter = PrintWriter(System.out, true),
    private val errorWriter: PrintWriter = PrintWriter(System.err, true),
    private val startDirOverride: Path? = null,
) : Callable<Int> {

    @Autowired(required = false)
    private var springEmbeddingModel: EmbeddingModel? = null

    @Autowired(required = false)
    private var springConfigService: ConfigService? = null

    @Option(names = ["--store-dir"], description = ["Path to the store directory."])
    var storeDirOption: String? = null

    @Option(names = ["--output-format"], description = ["Output format: text, json."])
    var outputFormat: String = "text"

    /**
     * Optional override for the filesystem probe used in staleness detection.
     * When non-null, takes precedence over the real filesystem probe.
     * The lambda receives the stored mtime and returns the current mtime (or null if missing).
     * This is used in tests to avoid real filesystem interaction.
     */
    var filesystemProbeOverride: ((String) -> Long?)? = null

    override fun call(): Int {
        val model = embeddingModel ?: springEmbeddingModel ?: stubEmbeddingModel()
        val storeDir = storeDirOverride
            ?: storeDirOption?.let { Paths.get(it) }
            ?: springConfigService?.resolveExplicitStoreDir()?.let { Paths.get(it) }
            ?: EzRagDirResolver().resolve(startDirOverride ?: Paths.get("").toAbsolutePath())
        val storeFilePath = storeDir.resolve("vector-store.json")

        val repository = VectorStoreRepository(model, storeDir)

        if (!repository.storeExists()) {
            errorWriter.println(
                "No vector store found at ${storeFilePath.toAbsolutePath()}. Run 'ez-rag ingest' first."
            )
            return 1
        }

        repository.load()

        val probe: (String) -> Long? = filesystemProbeOverride ?: { path ->
            try {
                Files.getLastModifiedTime(Paths.get(path)).toMillis()
            } catch (_: Exception) {
                null
            }
        }
        val metadata = repository.getMetadata(probe)
        val documents = metadata.documents // already sorted alphabetically by VectorStoreRepository

        if (outputFormat == "json") {
            val mapper = ObjectMapper()
            val result = documents.map { doc ->
                mapOf(
                    "path" to doc.path,
                    "chunks" to doc.chunkCount,
                    "stale" to doc.stale
                )
            }
            outputWriter.println(mapper.writeValueAsString(result))
        } else {
            val cwd = Paths.get("").toAbsolutePath()
            for (doc in documents) {
                val displayPath = try {
                    val rel = cwd.relativize(Paths.get(doc.path))
                    if (rel.toString().startsWith("..")) doc.path else rel.toString()
                } catch (_: Exception) {
                    doc.path
                }
                val staleMarker = if (doc.stale) "  [STALE]" else ""
                outputWriter.println("$displayPath  (${doc.chunkCount} chunks)$staleMarker")
            }
        }

        return 0
    }

    private fun stubEmbeddingModel(): EmbeddingModel = object : EmbeddingModel {
        override fun call(request: EmbeddingRequest): EmbeddingResponse =
            EmbeddingResponse(request.instructions.mapIndexed { i, _ -> Embedding(FloatArray(0), i) })
        override fun embed(document: Document): FloatArray = FloatArray(0)
        override fun embed(text: String): FloatArray = FloatArray(0)
        override fun embedForResponse(texts: List<String>): EmbeddingResponse =
            EmbeddingResponse(texts.mapIndexed { i, _ -> Embedding(FloatArray(0), i) })
        override fun dimensions(): Int = 0
    }
}
