package ch.obermuhlner.ezrag.command

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
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable

@Command(
    name = "status",
    mixinStandardHelpOptions = true,
    description = ["Show the status of the vector store."]
)
@Component
class StatusCommand(
    private val embeddingModel: EmbeddingModel? = null,
    private val storePathOverride: Path? = null,
    private val outputWriter: PrintWriter = PrintWriter(System.out, true),
    private val errorWriter: PrintWriter = PrintWriter(System.err, true),
) : Callable<Int> {

    @Autowired(required = false)
    private var springEmbeddingModel: EmbeddingModel? = null

    @Option(names = ["--output-format"], description = ["Output format: text, json."])
    var outputFormat: String = "text"

    override fun call(): Int {
        val model = embeddingModel ?: springEmbeddingModel ?: stubEmbeddingModel()
        val storePath = storePathOverride ?: Paths.get(".ez-rag/vector-store.json")

        val repository = VectorStoreRepository(model, storePath)

        if (!repository.storeExists()) {
            outputWriter.println(
                "No vector store found at ${storePath.toAbsolutePath()}. Run 'ez-rag ingest' first."
            )
            return 1
        }

        repository.load()
        val metadata = repository.getMetadata()

        if (outputFormat == "json") {
            val mapper = ObjectMapper()
            val docsArray = metadata.documents.map { doc ->
                mapOf("path" to doc.path, "chunkCount" to doc.chunkCount)
            }
            val result = mapOf(
                "storePath" to metadata.storePath,
                "chunkCount" to metadata.chunkCount,
                "documents" to docsArray
            )
            outputWriter.println(mapper.writeValueAsString(result))
        } else {
            outputWriter.println("Store: ${metadata.storePath}")
            outputWriter.println("Chunks: ${metadata.chunkCount}")
            outputWriter.println()
            for (doc in metadata.documents) {
                outputWriter.println("  ${doc.path}  (${doc.chunkCount} chunks)")
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
