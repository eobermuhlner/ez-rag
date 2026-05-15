package ch.obermuhlner.ezrag.config

data class CliFlags(
    val provider: String? = null,
    val embeddingProvider: String? = null,
    val model: String? = null,
    val embeddingModel: String? = null,
    val ollamaUrl: String? = null,
    val storePath: String? = null,
    val chunkSize: Int? = null,
    val chunkOverlap: Int? = null,
    val topK: Int? = null,
    val systemPrompt: String? = null,
    val outputFormat: String? = null,
    val verbose: Boolean? = null
)
