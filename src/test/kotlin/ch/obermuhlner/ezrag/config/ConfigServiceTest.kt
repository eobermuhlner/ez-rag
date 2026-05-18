package ch.obermuhlner.ezrag.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ConfigServiceTest {

    @Test
    fun `CLI flag beats config file value`() {
        val service = ConfigService(
            configFileSource = { EzRagConfig(provider = "anthropic") },
            envVars = emptyMap()
        )
        assertThat(service.resolve(CliFlags(provider = "openai")).provider).isEqualTo("openai")
    }

    @Test
    fun `env var beats config file value`() {
        val service = ConfigService(
            configFileSource = { EzRagConfig(provider = "anthropic") },
            envVars = mapOf("PROVIDER" to "openai")
        )
        assertThat(service.resolve().provider).isEqualTo("openai")
    }

    @Test
    fun `file value used when no override`() {
        val service = ConfigService(
            configFileSource = { EzRagConfig(provider = "anthropic") },
            envVars = emptyMap()
        )
        assertThat(service.resolve().provider).isEqualTo("anthropic")
    }

    @Test
    fun `missing config file yields all defaults`() {
        val service = ConfigService(
            configFileSource = { null },
            envVars = emptyMap()
        )
        val resolved = service.resolve()
        assertThat(resolved.provider).isEqualTo("passthrough")
        assertThat(resolved.embeddingProvider).isEqualTo("onnx")
        assertThat(resolved.model).isEqualTo("Xenova/TinyLlama-1.1B-Chat-v1.0")
        assertThat(resolved.embeddingModel).isEqualTo("all-MiniLM-L6-v2")
        assertThat(resolved.ollamaUrl).isEqualTo("http://localhost:11434")
        assertThat(resolved.storeDir).isEqualTo(".ez-rag")
        assertThat(resolved.chunkSize).isEqualTo(1000)
        assertThat(resolved.chunkOverlap).isEqualTo(200)
        assertThat(resolved.topK).isEqualTo(5)
        assertThat(resolved.systemPrompt).isEqualTo("")
        assertThat(resolved.outputFormat).isEqualTo("text")
        assertThat(resolved.verbose).isEqualTo(false)
    }

    @Test
    fun `EzRagConfig has all twelve fields with correct defaults`() {
        val config = EzRagConfig()
        assertThat(config.provider).isEqualTo("passthrough")
        assertThat(config.embeddingProvider).isEqualTo("onnx")
        assertThat(config.model).isEqualTo("Xenova/TinyLlama-1.1B-Chat-v1.0")
        assertThat(config.embeddingModel).isEqualTo("all-MiniLM-L6-v2")
        assertThat(config.ollamaUrl).isEqualTo("http://localhost:11434")
        // storeDir is null by default in EzRagConfig; resolved storeDir defaults to ".ez-rag" in ConfigService
        assertThat(config.storeDir).isNull()
        assertThat(config.chunkSize).isEqualTo(1000)
        assertThat(config.chunkOverlap).isEqualTo(200)
        assertThat(config.topK).isEqualTo(5)
        assertThat(config.systemPrompt).isEqualTo("")
        assertThat(config.outputFormat).isEqualTo("text")
        assertThat(config.verbose).isEqualTo(false)
    }

    @Test
    fun `ConfigService resolves storeDir correctly from YAML key storeDir`() {
        val service = ConfigService(
            configFileSource = { EzRagConfig(storeDir = "/custom/store") },
            envVars = emptyMap()
        )
        assertThat(service.resolve().storeDir).isEqualTo("/custom/store")
    }

    @Test
    fun `ConfigService resolves storeDir correctly from YAML key store-dir`() {
        // ConfigFileReader maps store-dir to storeDir; we simulate the result here
        val service = ConfigService(
            configFileSource = { EzRagConfig(storeDir = "/via/store-dir") },
            envVars = emptyMap()
        )
        assertThat(service.resolve().storeDir).isEqualTo("/via/store-dir")
    }

    @Test
    fun `ConfigService resolves storeDir correctly from env var STORE_DIR`() {
        val service = ConfigService(
            configFileSource = { null },
            envVars = mapOf("STORE_DIR" to "/env/store")
        )
        assertThat(service.resolve().storeDir).isEqualTo("/env/store")
    }

    @Test
    fun `storeDir defaults to dot-ez-rag when nothing is configured`() {
        val service = ConfigService(
            configFileSource = { null },
            envVars = emptyMap()
        )
        assertThat(service.resolve().storeDir).isEqualTo(".ez-rag")
    }

    // ---- resolveExplicitStoreDir tests ----

    @Test
    fun `resolveExplicitStoreDir returns null when nothing is configured`() {
        val service = ConfigService(
            configFileSource = { null },
            envVars = emptyMap()
        )
        assertThat(service.resolveExplicitStoreDir()).isNull()
    }

    @Test
    fun `resolveExplicitStoreDir returns env var value when STORE_DIR is set`() {
        val service = ConfigService(
            configFileSource = { null },
            envVars = mapOf("STORE_DIR" to "/from/env")
        )
        assertThat(service.resolveExplicitStoreDir()).isEqualTo("/from/env")
    }

    @Test
    fun `resolveExplicitStoreDir returns config file value when storeDir is set in file`() {
        val service = ConfigService(
            configFileSource = { EzRagConfig(storeDir = "/from/config") },
            envVars = emptyMap()
        )
        assertThat(service.resolveExplicitStoreDir()).isEqualTo("/from/config")
    }

    @Test
    fun `resolveExplicitStoreDir returns null when config file exists but storeDir is not set`() {
        // Config file exists but storeDir is null (not set in the file)
        val service = ConfigService(
            configFileSource = { EzRagConfig(storeDir = null) },
            envVars = emptyMap()
        )
        assertThat(service.resolveExplicitStoreDir()).isNull()
    }

    @Test
    fun `resolveExplicitStoreDir CLI flag beats env var`() {
        val service = ConfigService(
            configFileSource = { null },
            envVars = mapOf("STORE_DIR" to "/from/env")
        )
        assertThat(service.resolveExplicitStoreDir(CliFlags(storeDir = "/from/cli"))).isEqualTo("/from/cli")
    }

    @Test
    fun `resolveExplicitStoreDir env var beats config file`() {
        val service = ConfigService(
            configFileSource = { EzRagConfig(storeDir = "/from/config") },
            envVars = mapOf("STORE_DIR" to "/from/env")
        )
        assertThat(service.resolveExplicitStoreDir()).isEqualTo("/from/env")
    }

    // ---- rerankModel resolution tests ----

    @Test
    fun `rerankModel resolves to CLI flag when flag is set`() {
        val service = ConfigService(
            configFileSource = { EzRagConfig(rerankModel = "file-model") },
            envVars = mapOf("RERANK_MODEL" to "env-model")
        )
        assertThat(service.resolve(CliFlags(rerankModel = "cli-model")).rerankModel).isEqualTo("cli-model")
    }

    @Test
    fun `rerankModel resolves to env var when only env var is set`() {
        val service = ConfigService(
            configFileSource = { null },
            envVars = mapOf("RERANK_MODEL" to "env-model")
        )
        assertThat(service.resolve().rerankModel).isEqualTo("env-model")
    }

    @Test
    fun `rerankModel resolves to config file value when only file sets it`() {
        val service = ConfigService(
            configFileSource = { EzRagConfig(rerankModel = "file-model") },
            envVars = emptyMap()
        )
        assertThat(service.resolve().rerankModel).isEqualTo("file-model")
    }

    @Test
    fun `EzRagConfig rerankModel defaults to cross-encoder ms-marco-MiniLM-L-6-v2`() {
        assertThat(EzRagConfig().rerankModel).isEqualTo("cross-encoder/ms-marco-MiniLM-L-6-v2")
    }

    @Test
    fun `rerankModel resolves to cross-encoder model when no source specifies it`() {
        val service = ConfigService(
            configFileSource = { null },
            envVars = emptyMap()
        )
        assertThat(service.resolve().rerankModel).isEqualTo("cross-encoder/ms-marco-MiniLM-L-6-v2")
    }

    // ---- rerankCandidates resolution tests ----

    @Test
    fun `rerankCandidates defaults to topK times 3 when rerankModel is set but rerankCandidates is not`() {
        val service = ConfigService(
            configFileSource = { null },
            envVars = mapOf("RERANK_MODEL" to "some-model")
        )
        val config = service.resolve(CliFlags(topK = 4))
        assertThat(config.rerankCandidates).isEqualTo(12) // 4 * 3
    }

    @Test
    fun `rerankCandidates defaults to topK times 3 using default topK=5 when rerankModel is set`() {
        val service = ConfigService(
            configFileSource = { null },
            envVars = mapOf("RERANK_MODEL" to "some-model")
        )
        assertThat(service.resolve().rerankCandidates).isEqualTo(15) // 5 * 3
    }

    @Test
    fun `rerankCandidates defaults to topK times 3 when no source specifies rerankModel`() {
        val service = ConfigService(
            configFileSource = { null },
            envVars = emptyMap()
        )
        assertThat(service.resolve().rerankCandidates).isEqualTo(15) // default topK=5, 5*3=15
    }

    @Test
    fun `rerankCandidates is null when rerankModel is explicitly disabled via env var`() {
        val service = ConfigService(
            configFileSource = { null },
            envVars = mapOf("RERANK_MODEL" to "")
        )
        assertThat(service.resolve().rerankCandidates).isNull()
    }

    @Test
    fun `rerankCandidates explicit CLI value overrides topK times 3 default`() {
        val service = ConfigService(
            configFileSource = { null },
            envVars = mapOf("RERANK_MODEL" to "some-model")
        )
        val config = service.resolve(CliFlags(rerankCandidates = 20))
        assertThat(config.rerankCandidates).isEqualTo(20)
    }

    // ---- searchMode resolution tests ----

    @Test
    fun `searchMode resolves to CLI flag value when flag is set`() {
        val service = ConfigService(
            configFileSource = { EzRagConfig(searchMode = "bm25") },
            envVars = mapOf("SEARCH_MODE" to "embedding")
        )
        assertThat(service.resolve(CliFlags(searchMode = "bm25")).searchMode).isEqualTo("bm25")
    }

    @Test
    fun `searchMode resolves to SEARCH_MODE env var when only env var is set`() {
        val service = ConfigService(
            configFileSource = { null },
            envVars = mapOf("SEARCH_MODE" to "embedding")
        )
        assertThat(service.resolve().searchMode).isEqualTo("embedding")
    }

    @Test
    fun `searchMode resolves to config file value when only file sets it`() {
        val service = ConfigService(
            configFileSource = { EzRagConfig(searchMode = "bm25") },
            envVars = emptyMap()
        )
        assertThat(service.resolve().searchMode).isEqualTo("bm25")
    }

    @Test
    fun `searchMode defaults to hybrid when no source specifies it`() {
        val service = ConfigService(
            configFileSource = { null },
            envVars = emptyMap()
        )
        assertThat(service.resolve().searchMode).isEqualTo("hybrid")
    }

    // ---- analyzer resolution tests ----

    @Test
    fun `analyzer resolves to CLI flag value when flag is set`() {
        val service = ConfigService(
            configFileSource = { EzRagConfig(analyzer = "english") },
            envVars = mapOf("ANALYZER" to "english")
        )
        assertThat(service.resolve(CliFlags(analyzer = "english")).analyzer).isEqualTo("english")
    }

    @Test
    fun `analyzer resolves to ANALYZER env var when only env var is set`() {
        val service = ConfigService(
            configFileSource = { null },
            envVars = mapOf("ANALYZER" to "english")
        )
        assertThat(service.resolve().analyzer).isEqualTo("english")
    }

    @Test
    fun `analyzer resolves to config file value when only file sets it`() {
        val service = ConfigService(
            configFileSource = { EzRagConfig(analyzer = "english") },
            envVars = emptyMap()
        )
        assertThat(service.resolve().analyzer).isEqualTo("english")
    }

    @Test
    fun `analyzer defaults to standard when no source specifies it`() {
        val service = ConfigService(
            configFileSource = { null },
            envVars = emptyMap()
        )
        assertThat(service.resolve().analyzer).isEqualTo("standard")
    }
}
