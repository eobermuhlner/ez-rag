package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.config.ConfigService
import ch.obermuhlner.ezrag.config.EzRagDirResolver
import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.PrintWriter
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable

@Command(
    name = "show",
    mixinStandardHelpOptions = true,
    description = ["Show per-chunk metadata (and optionally raw text) for an ingested file."]
)
@Component
class ShowCommand(
    private val embeddingModel: EmbeddingModel? = null,
    private val storeDirOverride: Path? = null,
    private val outputWriter: PrintWriter = PrintWriter(System.out, true),
    private val errorWriter: PrintWriter = PrintWriter(System.err, true),
    private val includeChunks: Boolean = false,
    private val startDirOverride: Path? = null,
    private val configServiceOverride: ConfigService? = null,
) : Callable<Int> {

    @Autowired(required = false)
    private var springEmbeddingModel: EmbeddingModel? = null

    @Autowired(required = false)
    private var springConfigService: ConfigService? = null

    @Parameters(arity = "1", description = ["File path to show chunks for."])
    var filePath: String = ""

    @Option(names = ["--store-dir"], description = ["Path to the store directory."])
    var storeDirOption: String? = null

    @Option(names = ["--chunks"], description = ["Include raw chunk text in output."])
    var chunksOption: Boolean = false

    @Option(names = ["--output"], description = ["Output format: text (default) or json."])
    var outputFormat: String = "text"

    override fun call(): Int {
        val model = embeddingModel ?: springEmbeddingModel
            ?: return exitWithError("No embedding model configured.")

        val resolvedStoreDir = storeDirOverride
            ?: storeDirOption?.let { Paths.get(it) }
            ?: (configServiceOverride ?: springConfigService)?.resolveExplicitStoreDir()?.let { Paths.get(it) }
            ?: EzRagDirResolver().resolve(startDirOverride ?: Paths.get("").toAbsolutePath())

        val absolutePath = Paths.get(filePath).toAbsolutePath().normalize().toString()

        LuceneRepository.open(model, resolvedStoreDir, "standard").use { repository ->
            val chunks = repository.getChunksForFile(absolutePath)

            if (chunks.isEmpty()) {
                errorWriter.println("Error: file not found in store: $absolutePath")
                return 1
            }

            val showText = includeChunks || chunksOption

            if (outputFormat == "json") {
                val mapper = ObjectMapper()
                val chunksArray = chunks.map { chunk ->
                    buildMap {
                        put("chunkIndex", chunk.chunkIndex)
                        put("charCount", chunk.charCount)
                        put("mtime", chunk.mtime)
                        if (showText) put("text", chunk.text)
                        if (chunk.headingTitle != null) {
                            put("headingTitle", chunk.headingTitle)
                            put("headingLevel", chunk.headingLevel)
                            put("headingPath", chunk.headingPath)
                        }
                    }
                }
                val result = mapOf(
                    "file" to absolutePath,
                    "chunks" to chunksArray
                )
                outputWriter.println(mapper.writeValueAsString(result))
            } else {
                outputWriter.println("File: $absolutePath")
                outputWriter.println("Chunks: ${chunks.size}")
                outputWriter.println()
                for ((idx, chunk) in chunks.withIndex()) {
                    val headingSuffix = if (chunk.headingTitle != null) {
                        ", heading: ${"#".repeat(chunk.headingLevel ?: 1)} ${chunk.headingTitle}"
                    } else {
                        ""
                    }
                    outputWriter.println("Chunk ${idx + 1} — ${chunk.charCount} chars, mtime: ${chunk.mtime}$headingSuffix")
                    if (showText) {
                        for (line in chunk.text.lines()) {
                            outputWriter.println("  $line")
                        }
                        outputWriter.println()
                    }
                }
            }
        }

        return 0
    }

    private fun exitWithError(message: String): Int {
        errorWriter.println("Error: $message")
        return 1
    }
}
