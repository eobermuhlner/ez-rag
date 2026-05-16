package ch.obermuhlner.ezrag.config

import org.yaml.snakeyaml.Yaml
import java.io.File

fun readConfigFile(path: String = System.getProperty("user.home") + "/.ez-rag/config.yml"): EzRagConfig? {
    val file = File(path)
    if (!file.exists()) return null

    val data: Map<String, Any> = file.inputStream().use { Yaml().load(it) } ?: return null

    // Use null for unset optional fields so ConfigService can distinguish "not configured"
    // from "configured to the default value". ConfigService applies its own defaults.
    return EzRagConfig(
        provider = data.string("provider") ?: "openai",
        embeddingProvider = data.string("embeddingProvider") ?: data.string("embedding-provider") ?: "openai",
        model = data.string("model") ?: "gpt-4o-mini",
        embeddingModel = data.string("embeddingModel") ?: data.string("embedding-model") ?: "text-embedding-3-small",
        ollamaUrl = data.string("ollamaUrl") ?: data.string("ollama-url") ?: "http://localhost:11434",
        storeDir = data.string("storeDir") ?: data.string("store-dir"),
        chunkSize = data.int("chunkSize") ?: data.int("chunk-size") ?: 1000,
        chunkOverlap = data.int("chunkOverlap") ?: data.int("chunk-overlap") ?: 200,
        topK = data.int("topK") ?: data.int("top-k") ?: 5,
        systemPrompt = data.string("systemPrompt") ?: data.string("system-prompt") ?: "",
        outputFormat = data.string("outputFormat") ?: data.string("output-format") ?: "text",
        verbose = data["verbose"] as? Boolean ?: false,
        rerankModel = data.string("rerankModel") ?: data.string("rerank-model") ?: EzRagConfig().rerankModel,
        rerankCandidates = data.int("rerankCandidates") ?: data.int("rerank-candidates")
    )
}

private fun Map<String, Any>.string(key: String): String? = this[key] as? String
private fun Map<String, Any>.int(key: String): Int? = this[key] as? Int
