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
    name = "chunk",
    mixinStandardHelpOptions = true,
    description = ["Retrieve one or more chunks by file path and chunk index."]
)
@Component
class ChunkCommand(
    private val embeddingModel: EmbeddingModel? = null,
    private val storeDirOverride: Path? = null,
    private val outputWriter: PrintWriter = PrintWriter(System.out, true),
    private val errorWriter: PrintWriter = PrintWriter(System.err, true),
    private val startDirOverride: Path? = null,
    private val configServiceOverride: ConfigService? = null,
) : Callable<Int> {

    @Autowired(required = false)
    private var springEmbeddingModel: EmbeddingModel? = null

    @Autowired(required = false)
    private var springConfigService: ConfigService? = null

    @Parameters(index = "0", description = ["File path to retrieve chunks for."])
    var filePath: String = ""

    @Parameters(index = "1", description = ["Chunk index to retrieve."])
    var chunkIndex: Int = 0

    @Option(names = ["--window"], description = ["Number of chunks before and after the target to include."])
    var window: Int = 0

    @Option(names = ["--output-format"], description = ["Output format: text (default) or json."])
    var outputFormat: String = "text"

    @Option(names = ["--store-dir"], description = ["Path to the store directory."])
    var storeDirOption: String? = null

    override fun call(): Int {
        val model = embeddingModel ?: springEmbeddingModel
            ?: return exitWithError("No embedding model configured.")

        val resolvedStoreDir = storeDirOverride
            ?: storeDirOption?.let { Paths.get(it) }
            ?: (configServiceOverride ?: springConfigService)?.resolveExplicitStoreDir()?.let { Paths.get(it) }
            ?: EzRagDirResolver().resolve(startDirOverride ?: Paths.get("").toAbsolutePath())

        val absolutePath = Paths.get(filePath).toAbsolutePath().normalize().toString()
        val fromIndex = maxOf(0, chunkIndex - window)
        val toIndex = chunkIndex + window

        LuceneRepository.open(model, resolvedStoreDir, "standard").use { repository ->
            val allChunks = repository.getChunksForFile(absolutePath)
            if (allChunks.isEmpty()) {
                errorWriter.println("Error: file not found in store: $absolutePath")
                return 1
            }

            val chunks = allChunks.filter { it.chunkIndex in fromIndex..toIndex }

            if (chunks.none { it.chunkIndex == chunkIndex }) {
                errorWriter.println("Error: chunk index $chunkIndex not found in file: $absolutePath")
                return 1
            }

            if (outputFormat == "json") {
                val mapper = ObjectMapper()
                val chunksArray = chunks.map { chunk ->
                    buildMap {
                        put("chunkIndex", chunk.chunkIndex)
                        put("text", chunk.text)
                        if (chunk.headingTitle != null) {
                            put("headingTitle", chunk.headingTitle)
                            put("headingLevel", chunk.headingLevel)
                            put("headingPath", chunk.headingPath)
                        }
                    }
                }
                val result = mapOf("file" to absolutePath, "chunks" to chunksArray)
                outputWriter.println(mapper.writeValueAsString(result))
            } else {
                for (chunk in chunks) {
                    outputWriter.println("Chunk ${chunk.chunkIndex}")
                    if (chunk.headingTitle != null) {
                        val pathStr = chunk.headingPath?.joinToString(" > ") ?: chunk.headingTitle
                        outputWriter.println("  ${"#".repeat(chunk.headingLevel ?: 1)} $pathStr")
                    }
                    for (line in chunk.text.lines()) {
                        outputWriter.println("  $line")
                    }
                    outputWriter.println()
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
