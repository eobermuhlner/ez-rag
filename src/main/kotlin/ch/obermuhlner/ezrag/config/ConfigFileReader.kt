package ch.obermuhlner.ezrag.config

import org.yaml.snakeyaml.Yaml
import java.io.File

fun readConfigFile(path: String = System.getProperty("user.home") + "/.ez-rag/config.yml"): EzRagConfig? {
    val file = File(path)
    if (!file.exists()) return null

    val data: Map<String, Any> = file.inputStream().use { Yaml().load(it) } ?: return null

    return EzRagConfig(
        provider = data.string("provider") ?: "openai",
        embeddingProvider = data.string("embeddingProvider") ?: data.string("embedding-provider") ?: "openai",
        model = data.string("model") ?: "gpt-4o-mini",
        embeddingModel = data.string("embeddingModel") ?: data.string("embedding-model") ?: "text-embedding-3-small",
        storePath = data.string("storePath") ?: data.string("store-path") ?: ".ez-rag/vector-store.json",
        chunkSize = data.int("chunkSize") ?: data.int("chunk-size") ?: 1000,
        chunkOverlap = data.int("chunkOverlap") ?: data.int("chunk-overlap") ?: 200,
        topK = data.int("topK") ?: data.int("top-k") ?: 5,
        systemPrompt = data.string("systemPrompt") ?: data.string("system-prompt") ?: "",
        outputFormat = data.string("outputFormat") ?: data.string("output-format") ?: "text",
        verbose = data["verbose"] as? Boolean ?: false
    )
}

private fun Map<String, Any>.string(key: String): String? = this[key] as? String
private fun Map<String, Any>.int(key: String): Int? = this[key] as? Int
