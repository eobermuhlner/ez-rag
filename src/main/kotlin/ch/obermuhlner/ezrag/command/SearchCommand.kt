package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.config.ConfigService
import ch.obermuhlner.ezrag.config.EzRagDirResolver
import ch.obermuhlner.ezrag.ingestion.VectorStoreRepository
import ch.obermuhlner.ezrag.rag.EmbeddingSearchPipeline
import ch.obermuhlner.ezrag.rag.OutputFormatter
import ch.obermuhlner.ezrag.rag.SearchQuery
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.InputStream
import java.io.PrintWriter
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable

@Command(
    name = "search",
    mixinStandardHelpOptions = true,
    description = ["Search the vector store for similar documents."]
)
@Component
class SearchCommand(
    private val storeDirOverride: Path? = null,
    private val searchPipeline: EmbeddingSearchPipeline? = null,
    private val repositoryForVerbose: VectorStoreRepository? = null,
    private val outputFormatter: OutputFormatter = OutputFormatter(),
    private val outputWriter: PrintWriter = PrintWriter(System.out, true),
    private val errorWriter: PrintWriter = PrintWriter(System.err, true),
    private val inputStream: InputStream = System.`in`,
    private val startDirOverride: Path? = null,
) : Callable<Int> {

    @Autowired(required = false)
    private var springEmbeddingModel: EmbeddingModel? = null

    @Autowired(required = false)
    private var springConfigService: ConfigService? = null

    @Parameters(index = "0..*", description = ["Question to search for. Multiple tokens are joined with spaces. Reads from stdin if omitted."])
    var questionArgs: List<String> = emptyList()

    @Option(names = ["--top-k"], description = ["Number of chunks to retrieve (default: 5)."])
    var topK: Int = 5

    @Option(names = ["--min-score"], description = ["Minimum similarity score threshold (default: 0.0)."])
    var minScore: Double = 0.0

    @Option(names = ["--store-dir"], description = ["Path to the store directory."])
    var storeDirOption: String? = null

    @Option(names = ["--output"], description = ["Output format: text (default) or json."])
    var outputFormat: String = "text"

    // --verbose is inherited from the parent EzRagCommand (ScopeType.INHERIT)
    // and not re-declared here to avoid DuplicateOptionAnnotationsException.
    var verbose: Boolean = false

    override fun call(): Int {
        val storeDir = storeDirOverride
            ?: storeDirOption?.let { Paths.get(it) }
            ?: springConfigService?.resolveExplicitStoreDir()?.let { Paths.get(it) }
            ?: EzRagDirResolver().resolve(startDirOverride ?: Paths.get("").toAbsolutePath())
        val storeFilePath = storeDir.resolve("vector-store.json")

        // Check store existence first
        val storeFile = storeFilePath.toFile()
        if (!storeFile.exists()) {
            outputWriter.println(
                "Vector store not found at ${storeFilePath.toAbsolutePath()}. Run 'ez-rag ingest' first."
            )
            return 1
        }

        // Resolve the question
        val resolvedQuestion = if (questionArgs.isNotEmpty()) {
            questionArgs.joinToString(" ")
        } else {
            val stdin = inputStream.readBytes().toString(Charsets.UTF_8)
            if (stdin.isEmpty()) {
                outputWriter.println("No question provided")
                return 1
            }
            stdin
        }

        // Resolve the pipeline and repository (injected or built from Spring beans)
        val (pipeline, repository) = if (searchPipeline != null) {
            Pair(searchPipeline, repositoryForVerbose)
        } else {
            val embeddingModel = springEmbeddingModel
                ?: return exitWithError("No embedding model configured.")
            val repo = VectorStoreRepository(embeddingModel, storeFilePath)
            repo.load()
            Pair(EmbeddingSearchPipeline(repo, embeddingModel), repo)
        }

        val searchQuery = SearchQuery(
            question = resolvedQuestion,
            topK = topK,
            minScore = minScore,
        )

        val result = pipeline.search(searchQuery)

        if (verbose) {
            val embeddingModel = springEmbeddingModel
            if (embeddingModel != null) {
                errorWriter.println("Embedding dimension: ${embeddingModel.dimensions()}")
            }
            if (repository != null) {
                errorWriter.println("Total chunks in store: ${repository.getMetadata().chunkCount}")
            }
        }

        val formatted = if (outputFormat == "json") {
            outputFormatter.formatJson(result)
        } else {
            outputFormatter.formatText(result)
        }

        outputWriter.println(formatted)
        return 0
    }

    private fun exitWithError(message: String): Int {
        outputWriter.println("Error: $message")
        return 1
    }
}
