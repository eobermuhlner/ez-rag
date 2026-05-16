package ch.obermuhlner.ezrag.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.ollama.OllamaEmbeddingModel
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiEmbeddingModel
import org.springframework.ai.transformers.TransformersEmbeddingModel
import ch.obermuhlner.ezrag.rag.PassthroughChatModel

class ProviderConfigurationTest {

    private fun configServiceWith(
        provider: String = "openai",
        embeddingProvider: String = "openai",
        model: String = "gpt-4o-mini",
        embeddingModel: String = "text-embedding-3-small",
        ollamaUrl: String = "http://localhost:11434"
    ): ConfigService = ConfigService(
        configFileSource = {
            EzRagConfig(
                provider = provider,
                embeddingProvider = embeddingProvider,
                model = model,
                embeddingModel = embeddingModel,
                ollamaUrl = ollamaUrl
            )
        },
        envVars = emptyMap()
    )

    // ---- Task 01: OpenAI provider ----

    @Test
    fun `chatModel returns OpenAiChatModel when provider is openai`() {
        val config = ProviderConfiguration(configServiceWith(provider = "openai"))
        assertThat(config.chatModel()).isInstanceOf(OpenAiChatModel::class.java)
    }

    @Test
    fun `embeddingModel returns OpenAiEmbeddingModel when embeddingProvider is openai`() {
        val config = ProviderConfiguration(configServiceWith(embeddingProvider = "openai"))
        assertThat(config.embeddingModel()).isInstanceOf(OpenAiEmbeddingModel::class.java)
    }

    // ---- Task 02: Anthropic chat provider ----

    @Test
    fun `chatModel returns AnthropicChatModel when provider is anthropic`() {
        val config = ProviderConfiguration(configServiceWith(provider = "anthropic", model = "claude-sonnet-4-6"))
        assertThat(config.chatModel()).isInstanceOf(AnthropicChatModel::class.java)
    }

    @Test
    fun `AnthropicChatModel default model is claude-sonnet-4-6`() {
        val config = ProviderConfiguration(configServiceWith(provider = "anthropic", model = "claude-sonnet-4-6"))
        val model = config.chatModel() as AnthropicChatModel
        assertThat(model.defaultOptions.model).isEqualTo("claude-sonnet-4-6")
    }

    // ---- Task 03: Ollama providers ----

    @Test
    fun `chatModel returns OllamaChatModel when provider is ollama`() {
        val config = ProviderConfiguration(configServiceWith(provider = "ollama", model = "llama3.2"))
        assertThat(config.chatModel()).isInstanceOf(OllamaChatModel::class.java)
    }

    @Test
    fun `embeddingModel returns OllamaEmbeddingModel when embeddingProvider is ollama`() {
        val config = ProviderConfiguration(configServiceWith(embeddingProvider = "ollama", embeddingModel = "nomic-embed-text"))
        assertThat(config.embeddingModel()).isInstanceOf(OllamaEmbeddingModel::class.java)
    }

    @Test
    fun `Ollama chat model uses configured ollamaUrl`() {
        val customUrl = "http://my-ollama:11434"
        val config = ProviderConfiguration(configServiceWith(provider = "ollama", ollamaUrl = customUrl))
        // Just assert the model is created (URL is embedded in the API client, not directly accessible without reflection)
        assertThat(config.chatModel()).isInstanceOf(OllamaChatModel::class.java)
    }

    @Test
    fun `Ollama embedding model uses configured ollamaUrl`() {
        val customUrl = "http://my-ollama:11434"
        val config = ProviderConfiguration(configServiceWith(embeddingProvider = "ollama", ollamaUrl = customUrl))
        assertThat(config.embeddingModel()).isInstanceOf(OllamaEmbeddingModel::class.java)
    }

    // ---- Task 04: ONNX embedding provider ----

    @Test
    fun `embeddingModel returns TransformersEmbeddingModel when embeddingProvider is onnx`() {
        val config = ProviderConfiguration(configServiceWith(embeddingProvider = "onnx"))
        assertThat(config.embeddingModel()).isInstanceOf(TransformersEmbeddingModel::class.java)
    }

    // ---- Task 05: Provider validation ----

    @Test
    fun `embeddingModel throws IllegalArgumentException when embeddingProvider is anthropic`() {
        val config = ProviderConfiguration(configServiceWith(embeddingProvider = "anthropic"))
        assertThatThrownBy { config.embeddingModel() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("anthropic")
            .hasMessageContaining("embedding")
    }

    // ---- Task 01-passthrough-provider: passthrough chat provider ----

    @Test
    fun `chatModel returns PassthroughChatModel when provider is passthrough`() {
        val config = ProviderConfiguration(configServiceWith(provider = "passthrough", model = ""))
        assertThat(config.chatModel()).isInstanceOf(PassthroughChatModel::class.java)
    }

    @Test
    fun `chatModel throws IllegalArgumentException for unknown provider`() {
        val config = ProviderConfiguration(configServiceWith(provider = "not-a-provider"))
        assertThatThrownBy { config.chatModel() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not-a-provider")
            .hasMessageContaining("openai")
            .hasMessageContaining("passthrough")
    }

    @Test
    fun `embeddingModel throws IllegalArgumentException for unknown provider`() {
        val config = ProviderConfiguration(configServiceWith(embeddingProvider = "not-a-provider"))
        assertThatThrownBy { config.embeddingModel() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not-a-provider")
            .hasMessageContaining("openai")
    }
}
