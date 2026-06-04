package ch.obermuhlner.ezrag.config

import org.yaml.snakeyaml.Yaml
import java.io.File

fun readConfigFile(path: String = System.getProperty("user.home") + "/.ez-rag/config.yml"): EzRagConfig? {
    val data = readConfigRaw(path) ?: return null
    return configFromRaw(data)
}

fun readConfigRaw(path: String): Map<String, Any>? {
    val file = File(path)
    if (!file.exists()) return null
    return file.inputStream().use { Yaml().load<Map<String, Any>>(it) }
}

fun mergeConfigRaw(home: Map<String, Any>?, local: Map<String, Any>?): EzRagConfig? {
    if (home == null && local == null) return null
    val localWithoutStoreDir = local?.filterKeys { it != "storeDir" && it != "store-dir" } ?: emptyMap()
    val merged = (home ?: emptyMap()) + localWithoutStoreDir
    return configFromRaw(merged)
}

private fun defaultEmbeddingModelFor(embeddingProvider: String): String = when (embeddingProvider) {
    "openai" -> "text-embedding-3-small"
    "ollama" -> "nomic-embed-text"
    else     -> EzRagConfig().embeddingModel
}

private fun configFromRaw(data: Map<String, Any>): EzRagConfig {
    val d = EzRagConfig()
    val embeddingProvider = data.string("embeddingProvider") ?: data.string("embedding-provider")
        ?: data.string("provider") ?: d.embeddingProvider
    return EzRagConfig(
        provider = data.string("provider") ?: d.provider,
        embeddingProvider = embeddingProvider,
        model = data.string("model") ?: d.model,
        embeddingModel = data.string("embeddingModel") ?: data.string("embedding-model") ?: defaultEmbeddingModelFor(embeddingProvider),
        ollamaUrl = data.string("ollamaUrl") ?: data.string("ollama-url") ?: d.ollamaUrl,
        storeDir = data.string("storeDir") ?: data.string("store-dir"),
        chunkSize = data.int("chunkSize") ?: data.int("chunk-size") ?: d.chunkSize,
        chunkOverlap = data.int("chunkOverlap") ?: data.int("chunk-overlap") ?: d.chunkOverlap,
        topK = data.int("topK") ?: data.int("top-k") ?: d.topK,
        systemPrompt = data.string("systemPrompt") ?: data.string("system-prompt") ?: d.systemPrompt,
        outputFormat = data.string("outputFormat") ?: data.string("output-format") ?: d.outputFormat,
        verbose = data["verbose"] as? Boolean ?: d.verbose,
        rerankModel = data.string("rerankModel") ?: data.string("rerank-model") ?: d.rerankModel,
        rerankCandidates = data.int("rerankCandidates") ?: data.int("rerank-candidates"),
        searchMode = data.string("searchMode") ?: data.string("search-mode") ?: d.searchMode,
        analyzer = data.string("analyzer") ?: d.analyzer
    )
}

private fun Map<String, Any>.string(key: String): String? = this[key] as? String
private fun Map<String, Any>.int(key: String): Int? = this[key] as? Int
