package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.EzRagCommand
import ch.obermuhlner.ezrag.config.ConfigService
import ch.obermuhlner.ezrag.config.EzRagDirResolver
import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import ch.obermuhlner.ezrag.rag.BM25SearchPipeline
import ch.obermuhlner.ezrag.rag.EmbeddingSearchPipeline
import ch.obermuhlner.ezrag.rag.HybridSearchPipeline
import ch.obermuhlner.ezrag.rag.OutputFormatter
import ch.obermuhlner.ezrag.rag.Reranker
import ch.obermuhlner.ezrag.rag.SearchQuery
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
    name = "search",
    mixinStandardHelpOptions = true,
    description = ["Search the vector store for similar documents."]
)
@Component
class SearchCommand(
    private val storeDirOverride: Path? = null,
    private val searchPipeline: EmbeddingSearchPipeline? = null,
    private val bm25Pipeline: BM25SearchPipeline? = null,
    private val hybridPipeline: HybridSearchPipeline? = null,
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
    private var springConfigService: ConfigService? = null

    @Autowired(required = false)
    private var springReranker: Reranker? = null

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

    @Option(names = ["--mode"], description = ["Search mode: embedding, bm25, or hybrid (default from config)."])
    var modeOption: String? = null

    // Fallback fields for unit tests (where @ParentCommand is not wired by picocli).
    // In production, parent?.x takes precedence.
    var verbose: Boolean = false
    var rerankModel: String? = null
    var rerankCandidates: Int? = null

    override fun call(): Int {
        val verbose = parent?.verbose ?: this.verbose
        val rerankModel = parent?.rerankModel ?: this.rerankModel
        val rerankCandidates = parent?.rerankCandidates ?: this.rerankCandidates

        val storeDir = storeDirOverride
            ?: storeDirOption?.let { Paths.get(it) }
            ?: springConfigService?.resolveExplicitStoreDir()?.let { Paths.get(it) }
            ?: EzRagDirResolver().resolve(startDirOverride ?: Paths.get("").toAbsolutePath())

        val resolvedMode = modeOption
            ?: springConfigService?.resolve()?.searchMode
            ?: "hybrid"

        // Check store existence for non-BM25 modes (when no pre-wired pipeline)
        if (resolvedMode != "bm25" && searchPipeline == null && hybridPipeline == null) {
            if (!LuceneRepository.storeExists(storeDir)) {
                outputWriter.println(
                    "Store not found at ${storeDir.toAbsolutePath()}. Run 'ez-rag ingest' first."
                )
                return 1
            }
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

        val effectiveMinScore = minScore

        val searchQuery = SearchQuery(
            question = resolvedQuestion,
            topK = topK,
            minScore = effectiveMinScore,
            rerankCandidates = null,
            verbose = verbose,
            mode = resolvedMode,
        )

        val result = when {
            resolvedMode == "bm25" && bm25Pipeline != null -> {
                bm25Pipeline.search(searchQuery)
            }
            resolvedMode == "bm25" -> {
                val analyzer = springConfigService?.resolve()?.analyzer ?: "standard"
                val embeddingModel = springEmbeddingModel
                    ?: return exitWithError("No embedding model configured.")
                LuceneRepository.open(embeddingModel, storeDir, analyzer).use { repo ->
                    BM25SearchPipeline(repo).search(searchQuery)
                }
            }
            resolvedMode == "hybrid" && hybridPipeline != null -> {
                hybridPipeline.search(searchQuery)
            }
            resolvedMode == "hybrid" && springEmbeddingModel != null -> {
                val embeddingModel = springEmbeddingModel!!
                val analyzer = springConfigService?.resolve()?.analyzer ?: "standard"
                LuceneRepository.open(embeddingModel, storeDir, analyzer).use { repo ->
                    HybridSearchPipeline(repo).search(searchQuery)
                }
            }
            searchPipeline != null -> {
                val effectiveRerankCandidates = if (springReranker != null || rerankModel?.isNotEmpty() == true) {
                    rerankCandidates ?: (topK * 3)
                } else {
                    null
                }
                searchPipeline.search(searchQuery.copy(rerankCandidates = effectiveRerankCandidates, minScore = minScore))
            }
            else -> {
                val embeddingModel = springEmbeddingModel
                    ?: return exitWithError("No embedding model configured.")
                val analyzer = springConfigService?.resolve()?.analyzer ?: "standard"
                val effectiveRerankCandidates = if (springReranker != null || rerankModel?.isNotEmpty() == true) {
                    rerankCandidates ?: (topK * 3)
                } else {
                    null
                }
                LuceneRepository.open(embeddingModel, storeDir, analyzer).use { repo ->
                    if (verbose) {
                        errorWriter.println("Embedding dimension: ${embeddingModel.dimensions()}")
                        errorWriter.println("Total chunks in store: ${repo.getMetadata().chunkCount}")
                    }
                    EmbeddingSearchPipeline(repo, springReranker)
                        .search(searchQuery.copy(rerankCandidates = effectiveRerankCandidates, minScore = minScore))
                }
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
