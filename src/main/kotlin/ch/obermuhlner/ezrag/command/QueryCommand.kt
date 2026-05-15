package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.VectorStoreRepository
import ch.obermuhlner.ezrag.rag.OutputFormatter
import ch.obermuhlner.ezrag.rag.RagPipeline
import ch.obermuhlner.ezrag.rag.RagQuery
import org.springframework.ai.chat.model.ChatModel
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
    name = "query",
    mixinStandardHelpOptions = true,
    description = ["Query the vector store using RAG."]
)
@Component
class QueryCommand(
    private val storePathOverride: Path? = null,
    private val ragPipeline: RagPipeline? = null,
    private val outputFormatter: OutputFormatter = OutputFormatter(),
    private val outputWriter: PrintWriter = PrintWriter(System.out, true),
    private val errorWriter: PrintWriter = PrintWriter(System.err, true),
    private val inputStream: InputStream = System.`in`,
) : Callable<Int> {

    @Autowired(required = false)
    private var springEmbeddingModel: EmbeddingModel? = null

    @Autowired(required = false)
    private var springChatModel: ChatModel? = null

    @Parameters(index = "0..*", description = ["Question to ask. Multiple tokens are joined with spaces. Reads from stdin if omitted."])
    var questionArgs: List<String> = emptyList()

    @Option(names = ["--top-k"], description = ["Number of chunks to retrieve (default: 5)."])
    var topK: Int = 5

    @Option(names = ["--system-prompt"], description = ["Override the system prompt for this query."])
    var systemPromptOverride: String = ""

    @Option(names = ["--output"], description = ["Output format: text (default) or json."])
    var outputFormat: String = "text"

    @Option(names = ["--store"], description = ["Path to the vector store JSON file."])
    var storePathOption: String? = null

    // These fields are settable for tests and also used when a caller sets them directly.
    // --model and --verbose are inherited from the parent EzRagCommand (ScopeType.INHERIT)
    // and not re-declared here to avoid DuplicateOptionAnnotationsException.
    var modelOverride: String? = null
    var verbose: Boolean = false

    override fun call(): Int {
        val storePath = storePathOverride
            ?: storePathOption?.let { Paths.get(it) }
            ?: Paths.get(".ez-rag/vector-store.json")

        // Check store existence first
        val storeFile = storePath.toFile()
        if (!storeFile.exists()) {
            outputWriter.println(
                "Vector store not found at ${storePath.toAbsolutePath()}. Run 'ez-rag ingest' first."
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

        // Resolve the pipeline (injected or built from Spring beans)
        val pipeline = ragPipeline
            ?: run {
                val embeddingModel = springEmbeddingModel
                    ?: return exitWithError("No embedding model configured.")
                val chatModel = springChatModel
                    ?: return exitWithError("No chat model configured.")
                val repository = VectorStoreRepository(embeddingModel, storePath)
                repository.load()
                RagPipeline(repository, chatModel)
            }

        val ragQuery = RagQuery(
            question = resolvedQuestion,
            topK = topK,
            systemPrompt = systemPromptOverride,
            modelOverride = modelOverride,
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
