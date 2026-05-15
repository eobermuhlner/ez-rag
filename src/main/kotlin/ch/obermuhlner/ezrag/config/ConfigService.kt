package ch.obermuhlner.ezrag.config

class ConfigService(
    private val configFileSource: () -> EzRagConfig?,
    private val envVars: Map<String, String>
) {

    fun resolve(cliFlags: CliFlags = CliFlags()): EzRagConfig {
        val file = configFileSource() ?: EzRagConfig()

        return EzRagConfig(
            provider = cliFlags.provider ?: envVars["PROVIDER"] ?: file.provider,
            embeddingProvider = cliFlags.embeddingProvider ?: envVars["EMBEDDING_PROVIDER"] ?: file.embeddingProvider,
            model = cliFlags.model ?: envVars["MODEL"] ?: file.model,
            embeddingModel = cliFlags.embeddingModel ?: envVars["EMBEDDING_MODEL"] ?: file.embeddingModel,
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
