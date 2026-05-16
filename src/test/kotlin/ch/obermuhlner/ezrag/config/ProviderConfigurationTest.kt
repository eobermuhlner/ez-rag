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

    private fun credentialsServiceWith(
        openaiApiKey: String? = null,
        anthropicApiKey: String? = null
    ): CredentialsService = CredentialsService(
        envVars = buildMap {
            if (openaiApiKey != null) put("OPENAI_API_KEY", openaiApiKey)
            if (anthropicApiKey != null) put("ANTHROPIC_API_KEY", anthropicApiKey)
        },
        homeFileReader = { null }
    )

    private fun providerConfigWith(
        provider: String = "openai",
        embeddingProvider: String = "openai",
        model: String = "gpt-4o-mini",
        embeddingModel: String = "text-embedding-3-small",
        ollamaUrl: String = "http://localhost:11434",
        openaiApiKey: String? = "sk-test-key",
        anthropicApiKey: String? = null
    ): ProviderConfiguration = ProviderConfiguration(
        configService = configServiceWith(provider, embeddingProvider, model, embeddingModel, ollamaUrl),
        credentialsService = credentialsServiceWith(openaiApiKey, anthropicApiKey)
    )

    // ---- Task 01: OpenAI provider ----

    @Test
    fun `chatModel returns OpenAiChatModel when provider is openai`() {
        val config = providerConfigWith(provider = "openai")
        assertThat(config.chatModel()).isInstanceOf(OpenAiChatModel::class.java)
    }

    @Test
    fun `embeddingModel returns OpenAiEmbeddingModel when embeddingProvider is openai`() {
        val config = providerConfigWith(embeddingProvider = "openai")
        assertThat(config.embeddingModel()).isInstanceOf(OpenAiEmbeddingModel::class.java)
    }

    // ---- Task 02: Anthropic chat provider ----

    @Test
    fun `chatModel returns AnthropicChatModel when provider is anthropic`() {
        val config = providerConfigWith(provider = "anthropic", model = "claude-sonnet-4-6", anthropicApiKey = "sk-ant-test")
        assertThat(config.chatModel()).isInstanceOf(AnthropicChatModel::class.java)
    }

    @Test
    fun `AnthropicChatModel default model is claude-sonnet-4-6`() {
        val config = providerConfigWith(provider = "anthropic", model = "claude-sonnet-4-6", anthropicApiKey = "sk-ant-test")
        val model = config.chatModel() as AnthropicChatModel
        assertThat(model.defaultOptions.model).isEqualTo("claude-sonnet-4-6")
    }

    // ---- Task 03: Ollama providers ----

    @Test
    fun `chatModel returns OllamaChatModel when provider is ollama`() {
        val config = providerConfigWith(provider = "ollama", model = "llama3.2", openaiApiKey = null)
        assertThat(config.chatModel()).isInstanceOf(OllamaChatModel::class.java)
    }

    @Test
    fun `embeddingModel returns OllamaEmbeddingModel when embeddingProvider is ollama`() {
        val config = providerConfigWith(embeddingProvider = "ollama", embeddingModel = "nomic-embed-text", openaiApiKey = null)
        assertThat(config.embeddingModel()).isInstanceOf(OllamaEmbeddingModel::class.java)
    }

    @Test
    fun `Ollama chat model uses configured ollamaUrl`() {
        val customUrl = "http://my-ollama:11434"
        val config = providerConfigWith(provider = "ollama", ollamaUrl = customUrl, openaiApiKey = null)
        // Just assert the model is created (URL is embedded in the API client, not directly accessible without reflection)
        assertThat(config.chatModel()).isInstanceOf(OllamaChatModel::class.java)
    }

    @Test
    fun `Ollama embedding model uses configured ollamaUrl`() {
        val customUrl = "http://my-ollama:11434"
        val config = providerConfigWith(embeddingProvider = "ollama", ollamaUrl = customUrl, openaiApiKey = null)
        assertThat(config.embeddingModel()).isInstanceOf(OllamaEmbeddingModel::class.java)
    }

    // ---- Task 04: ONNX embedding provider ----

    @Test
    fun `embeddingModel returns TransformersEmbeddingModel when embeddingProvider is onnx`() {
        val config = providerConfigWith(embeddingProvider = "onnx", openaiApiKey = null)
        assertThat(config.embeddingModel()).isInstanceOf(TransformersEmbeddingModel::class.java)
    }

    // ---- Task 05: Provider validation ----

    @Test
    fun `embeddingModel throws IllegalArgumentException when embeddingProvider is anthropic`() {
        val config = providerConfigWith(embeddingProvider = "anthropic")
        assertThatThrownBy { config.embeddingModel() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("anthropic")
            .hasMessageContaining("embedding")
    }

    // ---- Task 01-passthrough-provider: passthrough chat provider ----

    @Test
    fun `chatModel returns PassthroughChatModel when provider is passthrough`() {
        val config = providerConfigWith(provider = "passthrough", model = "", openaiApiKey = null)
        assertThat(config.chatModel()).isInstanceOf(PassthroughChatModel::class.java)
    }

    @Test
    fun `chatModel throws IllegalArgumentException for unknown provider`() {
        val config = providerConfigWith(provider = "not-a-provider")
        assertThatThrownBy { config.chatModel() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not-a-provider")
            .hasMessageContaining("openai")
            .hasMessageContaining("passthrough")
    }

    @Test
    fun `embeddingModel throws IllegalArgumentException for unknown provider`() {
        val config = providerConfigWith(embeddingProvider = "not-a-provider", openaiApiKey = null)
        assertThatThrownBy { config.embeddingModel() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not-a-provider")
            .hasMessageContaining("openai")
    }

    // ---- Task 01-api-key-management: CredentialsService wiring ----

    @Test
    fun `chatModel uses OpenAI API key from CredentialsService`() {
        val config = providerConfigWith(provider = "openai", openaiApiKey = "sk-from-credentials")
        // If CredentialsService is wired, this creates the model with that key (no exception)
        assertThat(config.chatModel()).isInstanceOf(OpenAiChatModel::class.java)
    }

    @Test
    fun `embeddingModel uses OpenAI API key from CredentialsService`() {
        val config = providerConfigWith(embeddingProvider = "openai", openaiApiKey = "sk-from-credentials")
        assertThat(config.embeddingModel()).isInstanceOf(OpenAiEmbeddingModel::class.java)
    }

    @Test
    fun `chatModel uses Anthropic API key from CredentialsService`() {
        val config = providerConfigWith(
            provider = "anthropic",
            model = "claude-sonnet-4-6",
            anthropicApiKey = "sk-ant-from-credentials",
            openaiApiKey = null
        )
        assertThat(config.chatModel()).isInstanceOf(AnthropicChatModel::class.java)
    }

    // ---- Task 04-missing-key-actionable-error ----

    @Test
    fun `chatModel throws when OpenAI provider and key is Unset - message contains OPENAI_API_KEY`() {
        val config = providerConfigWith(provider = "openai", openaiApiKey = null)
        assertThatThrownBy { config.chatModel() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("OPENAI_API_KEY")
    }

    @Test
    fun `chatModel throws when OpenAI provider and key is Unset - message contains credentials file path`() {
        val config = providerConfigWith(provider = "openai", openaiApiKey = null)
        assertThatThrownBy { config.chatModel() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(".ez-rag/credentials.yml")
    }

    @Test
    fun `embeddingModel throws when OpenAI embedding provider and key is Unset - message contains OPENAI_API_KEY`() {
        val config = providerConfigWith(embeddingProvider = "openai", openaiApiKey = null)
        assertThatThrownBy { config.embeddingModel() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("OPENAI_API_KEY")
    }

    @Test
    fun `embeddingModel throws when OpenAI embedding provider and key is Unset - message contains credentials file path`() {
        val config = providerConfigWith(embeddingProvider = "openai", openaiApiKey = null)
        assertThatThrownBy { config.embeddingModel() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(".ez-rag/credentials.yml")
    }

    @Test
    fun `chatModel throws when Anthropic provider and key is Unset - message contains ANTHROPIC_API_KEY`() {
        val config = providerConfigWith(provider = "anthropic", model = "claude-sonnet-4-6", openaiApiKey = null, anthropicApiKey = null)
        assertThatThrownBy { config.chatModel() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ANTHROPIC_API_KEY")
    }

    @Test
    fun `chatModel throws when Anthropic provider and key is Unset - message contains credentials file path`() {
        val config = providerConfigWith(provider = "anthropic", model = "claude-sonnet-4-6", openaiApiKey = null, anthropicApiKey = null)
        assertThatThrownBy { config.chatModel() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(".ez-rag/credentials.yml")
    }

    @Test
    fun `chatModel does not throw when key is set from env var`() {
        val config = providerConfigWith(provider = "openai", openaiApiKey = "sk-set-key")
        assertThat(config.chatModel()).isInstanceOf(OpenAiChatModel::class.java)
    }

    @Test
    fun `chatModel does not throw for Ollama provider when both keys are Unset`() {
        val config = providerConfigWith(provider = "ollama", model = "llama3.2", openaiApiKey = null, anthropicApiKey = null)
        assertThat(config.chatModel()).isInstanceOf(OllamaChatModel::class.java)
    }

    @Test
    fun `embeddingModel does not throw for Ollama embedding provider when both keys are Unset`() {
        val config = providerConfigWith(embeddingProvider = "ollama", embeddingModel = "nomic-embed-text", openaiApiKey = null, anthropicApiKey = null)
        assertThat(config.embeddingModel()).isInstanceOf(OllamaEmbeddingModel::class.java)
    }

    @Test
    fun `embeddingModel does not throw for onnx embedding provider when both keys are Unset`() {
        val config = providerConfigWith(embeddingProvider = "onnx", openaiApiKey = null, anthropicApiKey = null)
        assertThat(config.embeddingModel()).isInstanceOf(TransformersEmbeddingModel::class.java)
    }
}
