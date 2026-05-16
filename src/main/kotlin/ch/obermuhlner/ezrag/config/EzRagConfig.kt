package ch.obermuhlner.ezrag.config

data class EzRagConfig(
    val provider: String = "passthrough",
    val embeddingProvider: String = "onnx",
    val model: String = "",
    val embeddingModel: String = "all-MiniLM-L6-v2",
    val ollamaUrl: String = "http://localhost:11434",
    /**
     * Explicitly configured store directory, or null if not configured.
     * When null, commands use [EzRagDirResolver] to find the store by walking parent directories.
     * When non-null (from STORE_DIR env var or config file), the walk is skipped.
     */
    val storeDir: String? = null,
    val chunkSize: Int = 1000,
    val chunkOverlap: Int = 200,
    val topK: Int = 5,
    val systemPrompt: String = "",
    val outputFormat: String = "text",
    val verbose: Boolean = false,
    val rerankModel: String = "cross-encoder/ms-marco-MiniLM-L-6-v2",
    val rerankCandidates: Int? = null
)
