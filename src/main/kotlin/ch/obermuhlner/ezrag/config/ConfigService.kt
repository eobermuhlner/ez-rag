package ch.obermuhlner.ezrag.config

class ConfigService(
    private val configFileSource: () -> EzRagConfig?,
    private val envVars: Map<String, String>,
    private val startupFlags: CliFlags = CliFlags()
) {

    fun resolve(cliFlags: CliFlags = CliFlags()): EzRagConfig {
        val file = configFileSource() ?: EzRagConfig()

        val topK = cliFlags.topK ?: envVars["TOP_K"]?.toIntOrNull() ?: file.topK
        val rerankModel = cliFlags.rerankModel ?: envVars["RERANK_MODEL"] ?: file.rerankModel
        val explicitRerankCandidates = cliFlags.rerankCandidates ?: envVars["RERANK_CANDIDATES"]?.toIntOrNull() ?: file.rerankCandidates
        val rerankCandidates = if (rerankModel.isNotEmpty()) {
            explicitRerankCandidates ?: (topK * 3)
        } else {
            null
        }

        return EzRagConfig(
            provider = cliFlags.provider ?: startupFlags.provider ?: envVars["PROVIDER"] ?: file.provider,
            embeddingProvider = cliFlags.embeddingProvider ?: startupFlags.embeddingProvider ?: envVars["EMBEDDING_PROVIDER"] ?: file.embeddingProvider,
            model = cliFlags.model ?: startupFlags.model ?: envVars["MODEL"] ?: file.model,
            embeddingModel = cliFlags.embeddingModel ?: startupFlags.embeddingModel ?: envVars["EMBEDDING_MODEL"] ?: file.embeddingModel,
            ollamaUrl = cliFlags.ollamaUrl ?: startupFlags.ollamaUrl ?: envVars["OLLAMA_BASE_URL"] ?: file.ollamaUrl,
            storeDir = cliFlags.storeDir ?: envVars["STORE_DIR"] ?: file.storeDir ?: ".ez-rag",
            chunkSize = cliFlags.chunkSize ?: envVars["CHUNK_SIZE"]?.toIntOrNull() ?: file.chunkSize,
            chunkOverlap = cliFlags.chunkOverlap ?: envVars["CHUNK_OVERLAP"]?.toIntOrNull() ?: file.chunkOverlap,
            topK = topK,
            systemPrompt = cliFlags.systemPrompt ?: envVars["SYSTEM_PROMPT"] ?: file.systemPrompt,
            outputFormat = cliFlags.outputFormat ?: envVars["OUTPUT_FORMAT"] ?: file.outputFormat,
            verbose = cliFlags.verbose ?: envVars["VERBOSE"]?.toBooleanStrictOrNull() ?: file.verbose,
            rerankModel = rerankModel,
            rerankCandidates = rerankCandidates
        )
    }

    /**
     * Returns the explicitly configured store directory, or null if no explicit configuration
     * was provided (meaning the parent directory walk should be used).
     *
     * Priority: CLI cliFlags.storeDir > STORE_DIR env var > config file storeDir (only when
     * the config file exists and sets the key — uses raw file value to avoid default confusion).
     */
    fun resolveExplicitStoreDir(cliFlags: CliFlags = CliFlags()): String? {
        val rawFileStoreDir = configFileSource()?.storeDir
        return cliFlags.storeDir ?: envVars["STORE_DIR"] ?: rawFileStoreDir
    }
}
