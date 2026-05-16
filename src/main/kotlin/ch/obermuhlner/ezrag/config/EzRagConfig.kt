package ch.obermuhlner.ezrag.config

data class EzRagConfig(
    val provider: String = "passthrough",
    val embeddingProvider: String = "onnx",
    val model: String = "",
    val embeddingModel: String = "all-MiniLM-L6-v2",
    val ollamaUrl: String = "http://localhost:11434",
    val storePath: String = ".ez-rag/vector-store.json",
    val chunkSize: Int = 1000,
    val chunkOverlap: Int = 200,
    val topK: Int = 5,
    val systemPrompt: String = "",
    val outputFormat: String = "text",
    val verbose: Boolean = false
)
