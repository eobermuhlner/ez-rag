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
        assertThat(resolved.model).isEqualTo("")
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
        assertThat(config.model).isEqualTo("")
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
}
