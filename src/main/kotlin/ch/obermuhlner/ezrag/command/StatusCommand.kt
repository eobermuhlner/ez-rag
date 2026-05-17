package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.config.ConfigService
import ch.obermuhlner.ezrag.config.CredentialSource
import ch.obermuhlner.ezrag.config.Credentials
import ch.obermuhlner.ezrag.config.CredentialsService
import ch.obermuhlner.ezrag.config.EzRagConfig
import ch.obermuhlner.ezrag.config.EzRagDirResolver
import ch.obermuhlner.ezrag.ingestion.BM25Repository
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
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable

@Command(
    name = "status",
    mixinStandardHelpOptions = true,
    description = ["Show the status of the vector store."]
)
@Component
class StatusCommand(
    private val embeddingModel: EmbeddingModel? = null,
    private val storeDirOverride: Path? = null,
    private val outputWriter: PrintWriter = PrintWriter(System.out, true),
    private val errorWriter: PrintWriter = PrintWriter(System.err, true),
    private val credentials: Credentials = allUnsetCredentials(),
    private val startDirOverride: Path? = null,
    private val config: EzRagConfig? = null,
) : Callable<Int> {

    @Autowired(required = false)
    private var springEmbeddingModel: EmbeddingModel? = null

    @Autowired(required = false)
    private var springConfigService: ConfigService? = null

    @Autowired(required = false)
    private var springCredentialsService: CredentialsService? = null

    @Option(names = ["--store-dir"], description = ["Path to the store directory."])
    var storeDirOption: String? = null

    @Option(names = ["--output-format"], description = ["Output format: text, json."])
    var outputFormat: String = "text"

    override fun call(): Int {
        val model = embeddingModel ?: springEmbeddingModel ?: stubEmbeddingModel()
        val storeDir = storeDirOverride
            ?: storeDirOption?.let { Paths.get(it) }
            ?: springConfigService?.resolveExplicitStoreDir()?.let { Paths.get(it) }
            ?: EzRagDirResolver().resolve(startDirOverride ?: Paths.get("").toAbsolutePath())
        val storeFilePath = storeDir.resolve("vector-store.json")
        // Use injected credentials (for tests), or resolve from Spring service (production), or fall back to all-unset.
        val resolvedCredentials = springCredentialsService?.resolve() ?: credentials
        // Resolve config: constructor param > Spring service > defaults
        val resolvedConfig = config ?: springConfigService?.resolve() ?: EzRagConfig()

        val repository = VectorStoreRepository(model, storeDir)

        if (!repository.storeExists()) {
            outputWriter.println(
                "No vector store found at ${storeFilePath.toAbsolutePath()}. Run 'ez-rag ingest' first."
            )
            return 1
        }

        repository.load()
        val metadata = repository.getMetadata()

        val bm25Metadata = BM25Repository(storeDir, resolvedConfig.analyzer).use { it.getMetadata() }

        if (outputFormat == "json") {
            val mapper = ObjectMapper()

            val credentialsMap = buildCredentialsMap(resolvedConfig, resolvedCredentials)

            val configMap = mapOf(
                "storeDir" to (resolvedConfig.storeDir ?: ""),
                "provider" to resolvedConfig.provider,
                "model" to resolvedConfig.model,
                "embeddingProvider" to resolvedConfig.embeddingProvider,
                "embeddingModel" to resolvedConfig.embeddingModel,
                "rerankModel" to resolvedConfig.rerankModel.ifBlank { "disabled" },
                "chunkSize" to resolvedConfig.chunkSize,
                "chunkOverlap" to resolvedConfig.chunkOverlap,
                "topK" to resolvedConfig.topK
            )

            val lastIngestTimeValue: Any? = if (metadata.lastIngestTime > 0L) metadata.lastIngestTime else null

            val result = mutableMapOf<String, Any?>(
                "storeFilePath" to metadata.storeFilePath,
                "chunkCount" to metadata.chunkCount,
                "documentCount" to metadata.documentCount,
                "storeSizeBytes" to metadata.storeSizeBytes,
                "staleDocumentCount" to metadata.staleDocumentCount,
                "lastIngestTime" to lastIngestTimeValue,
                "bm25" to mapOf(
                    "chunkCount" to bm25Metadata.chunkCount,
                    "indexSizeBytes" to bm25Metadata.indexSizeBytes,
                ),
                "configuration" to configMap,
            )

            if (credentialsMap.isNotEmpty()) {
                result["credentials"] = credentialsMap
            }

            outputWriter.println(mapper.writeValueAsString(result))
        } else {
            outputWriter.println("Store: ${metadata.storeFilePath}")
            outputWriter.println("Chunks: ${metadata.chunkCount}")
            outputWriter.println("Documents: ${metadata.documentCount}")
            outputWriter.println("Size: ${formatBytes(metadata.storeSizeBytes)}")
            outputWriter.println("Stale documents: ${metadata.staleDocumentCount}")
            if (metadata.lastIngestTime > 0L) {
                val iso = Instant.ofEpochMilli(metadata.lastIngestTime)
                    .atOffset(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                outputWriter.println("Last ingest time: $iso")
            } else {
                outputWriter.println("Last ingest time: none")
            }
            outputWriter.println("BM25 chunks: ${bm25Metadata.chunkCount}  index size: ${formatBytes(bm25Metadata.indexSizeBytes)}")
            outputWriter.println()
            outputWriter.println("Configuration:")
            outputWriter.println("  storeDir: ${resolvedConfig.storeDir ?: ""}")
            outputWriter.println("  provider: ${resolvedConfig.provider}")
            outputWriter.println("  model: ${resolvedConfig.model}")
            outputWriter.println("  embeddingProvider: ${resolvedConfig.embeddingProvider}")
            outputWriter.println("  embeddingModel: ${resolvedConfig.embeddingModel}")
            outputWriter.println("  rerankModel: ${resolvedConfig.rerankModel.ifBlank { "disabled" }}")
            outputWriter.println("  chunkSize: ${resolvedConfig.chunkSize}")
            outputWriter.println("  chunkOverlap: ${resolvedConfig.chunkOverlap}")
            outputWriter.println("  topK: ${resolvedConfig.topK}")
            outputWriter.println()

            val needsOpenai = resolvedConfig.provider == "openai" || resolvedConfig.embeddingProvider == "openai"
            val needsAnthropic = resolvedConfig.provider == "anthropic"

            if (needsOpenai || needsAnthropic) {
                outputWriter.println("Credentials:")
                if (needsOpenai) {
                    outputWriter.println("  openai-api-key: ${credentialSourceString(resolvedCredentials.openaiApiKeySource)}")
                }
                if (needsAnthropic) {
                    outputWriter.println("  anthropic-api-key: ${credentialSourceString(resolvedCredentials.anthropicApiKeySource)}")
                }
            }
        }

        return 0
    }

    private fun buildCredentialsMap(resolvedConfig: EzRagConfig, resolvedCredentials: Credentials): Map<String, String> {
        val needsOpenai = resolvedConfig.provider == "openai" || resolvedConfig.embeddingProvider == "openai"
        val needsAnthropic = resolvedConfig.provider == "anthropic"
        val map = mutableMapOf<String, String>()
        if (needsOpenai) {
            map["openaiApiKey"] = credentialSourceString(resolvedCredentials.openaiApiKeySource)
        }
        if (needsAnthropic) {
            map["anthropicApiKey"] = credentialSourceString(resolvedCredentials.anthropicApiKeySource)
        }
        return map
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_048_576L -> "${bytes / 1_048_576L} MB"
        bytes >= 1_024L -> "${bytes / 1_024L} KB"
        else -> "$bytes B"
    }

    private fun credentialSourceString(source: CredentialSource): String = when (source) {
        is CredentialSource.EnvVar -> "set (env var ${source.name})"
        is CredentialSource.File -> "set (${source.path})"
        is CredentialSource.Unset -> "not set"
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

    companion object {
        fun allUnsetCredentials() = Credentials(
            openaiApiKey = null,
            openaiApiKeySource = CredentialSource.Unset,
            anthropicApiKey = null,
            anthropicApiKeySource = CredentialSource.Unset,
        )
    }
}
