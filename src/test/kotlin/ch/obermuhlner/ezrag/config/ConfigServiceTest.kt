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
        assertThat(service.resolve()).isEqualTo(EzRagConfig())
    }

    @Test
    fun `EzRagConfig has all eleven fields with correct defaults`() {
        val config = EzRagConfig()
        assertThat(config.provider).isEqualTo("openai")
        assertThat(config.embeddingProvider).isEqualTo("openai")
        assertThat(config.model).isEqualTo("gpt-4o-mini")
        assertThat(config.embeddingModel).isEqualTo("text-embedding-3-small")
        assertThat(config.storePath).isEqualTo(".ez-rag/vector-store.json")
        assertThat(config.chunkSize).isEqualTo(1000)
        assertThat(config.chunkOverlap).isEqualTo(200)
        assertThat(config.topK).isEqualTo(5)
        assertThat(config.systemPrompt).isEqualTo("")
        assertThat(config.outputFormat).isEqualTo("text")
        assertThat(config.verbose).isEqualTo(false)
    }
}
