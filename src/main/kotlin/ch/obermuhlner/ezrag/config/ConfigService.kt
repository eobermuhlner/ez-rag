package ch.obermuhlner.ezrag.config

class ConfigService(
    private val configFileSource: () -> EzRagConfig?,
    private val envVars: Map<String, String>,
    private val startupFlags: CliFlags = CliFlags()
) {

    fun resolve(cliFlags: CliFlags = CliFlags()): EzRagConfig {
        val file = configFileSource() ?: EzRagConfig()

        return EzRagConfig(
            provider = cliFlags.provider ?: startupFlags.provider ?: envVars["PROVIDER"] ?: file.provider,
            embeddingProvider = cliFlags.embeddingProvider ?: startupFlags.embeddingProvider ?: envVars["EMBEDDING_PROVIDER"] ?: file.embeddingProvider,
            model = cliFlags.model ?: startupFlags.model ?: envVars["MODEL"] ?: file.model,
            embeddingModel = cliFlags.embeddingModel ?: startupFlags.embeddingModel ?: envVars["EMBEDDING_MODEL"] ?: file.embeddingModel,
            ollamaUrl = cliFlags.ollamaUrl ?: startupFlags.ollamaUrl ?: envVars["OLLAMA_BASE_URL"] ?: file.ollamaUrl,
            storePath = cliFlags.storePath ?: envVars["STORE_PATH"] ?: file.storePath,
            chunkSize = cliFlags.chunkSize ?: envVars["CHUNK_SIZE"]?.toIntOrNull() ?: file.chunkSize,
            chunkOverlap = cliFlags.chunkOverlap ?: envVars["CHUNK_OVERLAP"]?.toIntOrNull() ?: file.chunkOverlap,
            topK = cliFlags.topK ?: envVars["TOP_K"]?.toIntOrNull() ?: file.topK,
            systemPrompt = cliFlags.systemPrompt ?: envVars["SYSTEM_PROMPT"] ?: file.systemPrompt,
            outputFormat = cliFlags.outputFormat ?: envVars["OUTPUT_FORMAT"] ?: file.outputFormat,
            verbose = cliFlags.verbose ?: envVars["VERBOSE"]?.toBooleanStrictOrNull() ?: file.verbose
        )
    }
}
