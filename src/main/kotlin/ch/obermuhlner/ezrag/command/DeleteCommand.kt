package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.config.ConfigService
import ch.obermuhlner.ezrag.config.EzRagDirResolver
import ch.obermuhlner.ezrag.ingestion.LuceneRepository
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
    name = "delete",
    mixinStandardHelpOptions = true,
    description = ["Delete one or more ingested documents from the vector store."]
)
@Component
class DeleteCommand(
    private val embeddingModel: EmbeddingModel? = null,
    private val storeDirOverride: Path? = null,
    private val outputWriter: PrintWriter = PrintWriter(System.out, true),
    private val quiet: Boolean = false,
    private val startDirOverride: Path? = null,
    private val configServiceOverride: ConfigService? = null,
) : Callable<Int> {

    @Autowired(required = false)
    private var springEmbeddingModel: EmbeddingModel? = null

    @Autowired(required = false)
    private var springConfigService: ConfigService? = null

    @Parameters(arity = "1..*", description = ["Files to delete from the vector store."])
    var filePaths: List<String> = emptyList()

    @Option(names = ["--store-dir"], description = ["Path to the store directory."])
    var storeDirOption: String? = null

    @Option(names = ["--quiet", "-q"], description = ["Suppress output on success."])
    var quietOption: Boolean = false

    override fun call(): Int {
        val model = embeddingModel ?: springEmbeddingModel
            ?: return exitWithError("No embedding model configured.")

        val resolvedStoreDir = storeDirOverride
            ?: storeDirOption?.let { Paths.get(it) }
            ?: (configServiceOverride ?: springConfigService)?.resolveExplicitStoreDir()?.let { Paths.get(it) }
            ?: EzRagDirResolver().resolve(startDirOverride ?: Paths.get("").toAbsolutePath())

        val isQuiet = quiet || quietOption

        LuceneRepository.open(model, resolvedStoreDir, "standard").use { repository ->
            for (rawPath in filePaths) {
                val absolutePath = if (rawPath.startsWith("http://") || rawPath.startsWith("https://")) {
                    rawPath
                } else {
                    Paths.get(rawPath).toAbsolutePath().normalize().toString()
                }
                val removed = repository.delete(absolutePath)
                if (removed == 0) {
                    outputWriter.println("Warning: not found in store: $absolutePath")
                } else if (!isQuiet) {
                    outputWriter.println("Deleted: $absolutePath ($removed chunks)")
                }
            }
        }

        return 0
    }

    private fun exitWithError(message: String): Int {
        outputWriter.println("Error: $message")
        return 1
    }
}
