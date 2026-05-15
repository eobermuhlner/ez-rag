package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.DirectoryWalker
import ch.obermuhlner.ezrag.ingestion.DocumentChunker
import ch.obermuhlner.ezrag.ingestion.DocumentLoader
import ch.obermuhlner.ezrag.ingestion.VectorStoreRepository
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
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
) : Callable<Int> {

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
        val model = embeddingModel ?: return exitWithError("No embedding model configured.")

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

        val loader = DocumentLoader()
        val chunker = DocumentChunker(resolvedChunkSize, resolvedChunkOverlap)
        val repository = VectorStoreRepository(model, resolvedStorePath)
        val directoryWalker = DirectoryWalker(warningWriter)
        repository.load()

        var filesIngested = 0
        var chunksCreated = 0
        var skipped = 0

        val resolvedPaths = files.flatMap { file ->
            if (file.isDirectory) {
                directoryWalker.walk(file.toPath())
            } else {
                listOf(file.toPath())
            }
        }

        for (path in resolvedPaths) {
            val mtime = path.toFile().lastModified()
            val sourceKey = path.toString()
            if (repository.isAlreadyIngested(sourceKey, mtime)) {
                skipped++
                continue
            }
            if (verbose) {
                outputWriter.println("Loading: $path")
            }
            val documents = loader.load(path)
            val chunks = withMtime(chunker.split(documents), mtime)
            if (verbose) {
                chunks.forEachIndexed { index, chunk ->
                    val tokenCount = chunk.text?.split(Regex("\\s+"))?.size ?: 0
                    outputWriter.println("Chunk $index: $tokenCount tokens")
                }
            }
            repository.add(chunks)
            filesIngested++
            chunksCreated += chunks.size
        }

        repository.save()

        outputWriter.println("$filesIngested files ingested, $chunksCreated chunks created, $skipped skipped")
        return 0
    }

    private fun withMtime(documents: List<Document>, mtime: Long): List<Document> {
        return documents.map { doc ->
            Document.builder()
                .id(doc.id)
                .text(doc.text)
                .metadata(doc.metadata + mapOf("mtime" to mtime))
                .build()
        }
    }

    private fun exitWithError(message: String): Int {
        outputWriter.println("Error: $message")
        return 1
    }
}
