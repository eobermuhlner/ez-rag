package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import ch.obermuhlner.ezrag.rag.BM25SearchPipeline
import ch.obermuhlner.ezrag.rag.EmbeddingSearchPipeline
import ch.obermuhlner.ezrag.rag.HybridSearchPipeline
import ch.obermuhlner.ezrag.rag.OutputFormatter
import ch.obermuhlner.ezrag.rag.RagPipeline
import ch.obermuhlner.ezrag.rag.RagQuery
import ch.obermuhlner.ezrag.rag.SearchQuery
import ch.obermuhlner.ezrag.rag.SearchResult
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable

@Command(
    name = "shell",
    mixinStandardHelpOptions = true,
    description = ["Start an interactive REPL for querying the vector store."]
)
@Component
class ShellCommand(
    private val storeDirOverride: Path? = null,
    private val ragPipeline: RagPipeline? = null,
    private val embeddingSearchPipeline: EmbeddingSearchPipeline? = null,
    private val hybridSearchPipeline: HybridSearchPipeline? = null,
    private val bm25SearchPipeline: BM25SearchPipeline? = null,
    private val luceneRepository: LuceneRepository? = null,
    private val outputFormatter: OutputFormatter = OutputFormatter(),
    private val outputWriter: PrintWriter = PrintWriter(System.out, true),
    private val errorWriter: PrintWriter = PrintWriter(System.err, true),
    private val inputStream: InputStream = System.`in`,
) : Callable<Int> {

    @Autowired(required = false)
    private var springEmbeddingModel: EmbeddingModel? = null

    @Autowired(required = false)
    private var springChatModel: ChatModel? = null

    @Option(names = ["--store-dir"], description = ["Path to the store directory."])
    var storeDirOption: String? = null

    @Option(names = ["--top-k"], description = ["Number of chunks to retrieve (default: 5)."])
    var topK: Int = 5

    @Option(names = ["--output"], description = ["Output format: text (default) or json."])
    var outputFormat: String = "text"

    var modelOverride: String? = null
    var verbose: Boolean = false

    override fun call(): Int {
        val storeDir = storeDirOverride
            ?: storeDirOption?.let { Paths.get(it) }
            ?: Paths.get(".ez-rag")

        if (!LuceneRepository.storeExists(storeDir)) {
            outputWriter.println(
                "Store not found at ${storeDir.toAbsolutePath()}. Run 'ez-rag ingest' first."
            )
            return 1
        }

        data class ResolvedPipelines(
            val ragPipeline: RagPipeline,
            val embeddingSearchPipeline: EmbeddingSearchPipeline?,
            val hybridSearchPipeline: HybridSearchPipeline?,
            val bm25SearchPipeline: BM25SearchPipeline?,
            val repository: LuceneRepository?
        )

        val resolved = if (ragPipeline != null) {
            ResolvedPipelines(ragPipeline, embeddingSearchPipeline, hybridSearchPipeline, bm25SearchPipeline, luceneRepository)
        } else {
            val embeddingModel = springEmbeddingModel
                ?: return exitWithError("No embedding model configured.")
            val chatModel = springChatModel
                ?: return exitWithError("No chat model configured.")
            val repository = LuceneRepository.open(embeddingModel, storeDir, "standard")
            val esp = EmbeddingSearchPipeline(repository)
            ResolvedPipelines(
                RagPipeline(esp, chatModel),
                esp,
                null,
                null,
                repository
            )
        }

        val reader = BufferedReader(InputStreamReader(inputStream))
        while (true) {
            errorWriter.print("> ")
            errorWriter.flush()
            val line = reader.readLine() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed == "exit" || trimmed == "quit") break
            if (trimmed.startsWith("/")) {
                if (handleSlashCommand(trimmed, resolved.hybridSearchPipeline, resolved.embeddingSearchPipeline, resolved.bm25SearchPipeline, resolved.repository)) break
                continue
            }

            try {
                val ragQuery = RagQuery(
                    question = trimmed,
                    topK = topK,
                    systemPrompt = "",
                    modelOverride = modelOverride,
                )
                val result = resolved.ragPipeline.query(ragQuery)

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
            } catch (e: Exception) {
                errorWriter.println("Error: ${e.message}")
            }
        }
        return 0
    }

    private fun handleSlashCommand(
        line: String,
        hybridPipeline: HybridSearchPipeline?,
        embeddingPipeline: EmbeddingSearchPipeline?,
        bm25Pipeline: BM25SearchPipeline?,
        repository: LuceneRepository?,
    ): Boolean {
        val parts = line.split(" ", limit = 2)
        val command = parts[0]
        val args = if (parts.size > 1) parts[1].trim() else ""

        return when (command) {
            "/exit" -> true

            "/help" -> {
                outputWriter.println("/help                        - Show this help")
                outputWriter.println("/status                      - Show vector store metadata")
                outputWriter.println("/search <question>           - Run hybrid search without LLM")
                outputWriter.println("/search-bm25 <question>      - Run BM25 (keyword) search without LLM")
                outputWriter.println("/search-embedding <question> - Run embedding search without LLM")
                outputWriter.println("/verbose                     - Toggle verbose mode")
                outputWriter.println("/exit                        - Exit the REPL")
                false
            }

            "/status" -> {
                if (repository != null) {
                    val metadata = repository.getMetadata()
                    outputWriter.println("Chunks: ${metadata.chunkCount}")
                    metadata.documents.forEach { doc ->
                        outputWriter.println("  ${doc.path}: ${doc.chunkCount} chunk(s)")
                    }
                } else {
                    errorWriter.println("Status not available: no repository configured.")
                }
                false
            }

            "/search" -> {
                if (args.isBlank()) {
                    errorWriter.println("Usage: /search <question>")
                } else {
                    val searchQuery = SearchQuery(question = args, topK = topK, minScore = 0.0, mode = "hybrid")
                    val activePipeline: Any? = hybridPipeline ?: embeddingPipeline
                    if (activePipeline != null) {
                        try {
                            val result: SearchResult = when (activePipeline) {
                                is HybridSearchPipeline -> activePipeline.search(searchQuery)
                                is EmbeddingSearchPipeline -> activePipeline.search(searchQuery)
                                else -> throw IllegalStateException("Unknown pipeline type")
                            }
                            val formatted = if (outputFormat == "json") {
                                outputFormatter.formatJson(result)
                            } else {
                                outputFormatter.formatText(result)
                            }
                            outputWriter.println(formatted)
                        } catch (e: Exception) {
                            errorWriter.println("Search error: ${e.message}")
                        }
                    } else {
                        errorWriter.println("Search not available: no search pipeline configured.")
                    }
                }
                false
            }

            "/search-bm25" -> {
                if (args.isBlank()) {
                    errorWriter.println("Usage: /search-bm25 <question>")
                } else if (bm25Pipeline != null) {
                    try {
                        val searchQuery = SearchQuery(question = args, topK = topK, minScore = 0.0, mode = "bm25")
                        val result = bm25Pipeline.search(searchQuery)
                        val formatted = if (outputFormat == "json") {
                            outputFormatter.formatJson(result)
                        } else {
                            outputFormatter.formatText(result)
                        }
                        outputWriter.println(formatted)
                    } catch (e: Exception) {
                        errorWriter.println("Search error: ${e.message}")
                    }
                } else {
                    errorWriter.println("BM25 search not available: no BM25 pipeline configured.")
                }
                false
            }

            "/search-embedding" -> {
                if (args.isBlank()) {
                    errorWriter.println("Usage: /search-embedding <question>")
                } else if (embeddingPipeline != null) {
                    try {
                        val searchQuery = SearchQuery(question = args, topK = topK, minScore = 0.0, mode = "embedding")
                        val result = embeddingPipeline.search(searchQuery)
                        val formatted = if (outputFormat == "json") {
                            outputFormatter.formatJson(result)
                        } else {
                            outputFormatter.formatText(result)
                        }
                        outputWriter.println(formatted)
                    } catch (e: Exception) {
                        errorWriter.println("Search error: ${e.message}")
                    }
                } else {
                    errorWriter.println("Embedding search not available: no embedding pipeline configured.")
                }
                false
            }

            "/verbose" -> {
                verbose = !verbose
                outputWriter.println(if (verbose) "verbose on" else "verbose off")
                false
            }

            else -> {
                errorWriter.println("Unknown command: $command. Type /help for available commands.")
                false
            }
        }
    }

    private fun exitWithError(message: String): Int {
        outputWriter.println("Error: $message")
        return 1
    }
}
