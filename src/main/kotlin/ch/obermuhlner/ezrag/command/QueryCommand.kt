package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.EzRagCommand
import ch.obermuhlner.ezrag.config.ConfigService
import ch.obermuhlner.ezrag.config.EzRagDirResolver
import ch.obermuhlner.ezrag.ingestion.VectorStoreRepository
import ch.obermuhlner.ezrag.rag.EmbeddingSearchPipeline
import ch.obermuhlner.ezrag.rag.OutputFormatter
import ch.obermuhlner.ezrag.rag.RagPipeline
import ch.obermuhlner.ezrag.rag.RagQuery
import ch.obermuhlner.ezrag.rag.Reranker
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.io.InputStream
import java.io.PrintWriter
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable

@Command(
    name = "query",
    mixinStandardHelpOptions = true,
    description = ["Query the vector store using RAG."]
)
@Component
class QueryCommand(
    private val storeDirOverride: Path? = null,
    private val ragPipeline: RagPipeline? = null,
    private val outputFormatter: OutputFormatter = OutputFormatter(),
    private val outputWriter: PrintWriter = PrintWriter(System.out, true),
    private val errorWriter: PrintWriter = PrintWriter(System.err, true),
    private val inputStream: InputStream = System.`in`,
    private val startDirOverride: Path? = null,
) : Callable<Int> {

    @ParentCommand
    private var parent: EzRagCommand? = null

    @Autowired(required = false)
    private var springEmbeddingModel: EmbeddingModel? = null

    @Autowired(required = false)
    private var springChatModel: ChatModel? = null

    @Autowired(required = false)
    private var springConfigService: ConfigService? = null

    @Autowired(required = false)
    private var springReranker: Reranker? = null

    @Parameters(index = "0..*", description = ["Question to ask. Multiple tokens are joined with spaces. Reads from stdin if omitted."])
    var questionArgs: List<String> = emptyList()

    @Option(names = ["--top-k"], description = ["Number of chunks to retrieve (default: 5)."])
    var topK: Int = 5

    @Option(names = ["--system-prompt"], description = ["Override the system prompt for this query."])
    var systemPromptOverride: String = ""

    @Option(names = ["--output"], description = ["Output format: text (default) or json."])
    var outputFormat: String = "text"

    @Option(names = ["--store-dir"], description = ["Path to the store directory."])
    var storeDirOption: String? = null

    // Fallback fields for unit tests (where @ParentCommand is not wired by picocli).
    // In production, parent?.x takes precedence.
    var modelOverride: String? = null
    var verbose: Boolean = false
    var rerankModel: String? = null
    var rerankCandidates: Int? = null

    override fun call(): Int {
        val verbose = parent?.verbose ?: this.verbose
        val rerankModel = parent?.rerankModel ?: this.rerankModel
        val rerankCandidates = parent?.rerankCandidates ?: this.rerankCandidates
        val modelOverride = parent?.model ?: this.modelOverride

        val storeDir = storeDirOverride
            ?: storeDirOption?.let { Paths.get(it) }
            ?: springConfigService?.resolveExplicitStoreDir()?.let { Paths.get(it) }
            ?: EzRagDirResolver().resolve(startDirOverride ?: Paths.get("").toAbsolutePath())
        val storeFilePath = storeDir.resolve("vector-store.json")

        val storeFile = storeFilePath.toFile()
        if (!storeFile.exists()) {
            outputWriter.println(
                "Vector store not found at ${storeFilePath.toAbsolutePath()}. Run 'ez-rag ingest' first."
            )
            return 1
        }

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

        val pipeline = ragPipeline
            ?: run {
                val embeddingModel = springEmbeddingModel
                    ?: return exitWithError("No embedding model configured.")
                val chatModel = springChatModel
                    ?: return exitWithError("No chat model configured.")
                val repository = VectorStoreRepository(embeddingModel, storeFilePath)
                repository.load()
                val embeddingSearchPipeline = EmbeddingSearchPipeline(repository, embeddingModel, springReranker)
                RagPipeline(embeddingSearchPipeline, chatModel)
            }

        val effectiveRerankCandidates = if (springReranker != null || rerankModel?.isNotEmpty() == true) {
            rerankCandidates ?: (topK * 3)
        } else {
            null
        }

        val ragQuery = RagQuery(
            question = resolvedQuestion,
            topK = topK,
            systemPrompt = systemPromptOverride,
            modelOverride = modelOverride,
            rerankCandidates = effectiveRerankCandidates,
            verbose = verbose,
        )

        val result = pipeline.query(ragQuery)

        if (verbose) {
            result.sources.forEach { source ->
                errorWriter.println("  Source: ${source.filePath}  score=${source.similarityScore}  chunk=${source.chunkIndex}")
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
