package ch.obermuhlner.ezrag.config

import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.anthropic.api.AnthropicApi
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.ai.ollama.OllamaEmbeddingModel
import org.springframework.ai.ollama.api.OllamaApi
import org.springframework.ai.ollama.api.OllamaOptions
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.OpenAiEmbeddingModel
import org.springframework.ai.openai.OpenAiEmbeddingOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.ai.transformers.TransformersEmbeddingModel
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ProviderConfiguration(private val configService: ConfigService) {

    @Bean
    fun chatModel(): ChatModel {
        val config = configService.resolve()
        val modelName = config.model
        return when (config.provider) {
            "openai" -> {
                val apiKey = System.getenv("OPENAI_API_KEY") ?: ""
                val openAiApi = OpenAiApi.builder()
                    .apiKey(apiKey)
                    .build()
                OpenAiChatModel.builder()
                    .openAiApi(openAiApi)
                    .defaultOptions(OpenAiChatOptions.builder().model(modelName).build())
                    .build()
            }
            "anthropic" -> {
                val apiKey = System.getenv("ANTHROPIC_API_KEY") ?: ""
                val anthropicApi = AnthropicApi.builder()
                    .apiKey(apiKey)
                    .build()
                AnthropicChatModel.builder()
                    .anthropicApi(anthropicApi)
                    .defaultOptions(AnthropicChatOptions.builder().model(modelName).build())
                    .build()
            }
            "ollama" -> {
                val ollamaApi = OllamaApi.builder()
                    .baseUrl(config.ollamaUrl)
                    .build()
                OllamaChatModel.builder()
                    .ollamaApi(ollamaApi)
                    .defaultOptions(OllamaOptions.builder().model(modelName).build())
                    .build()
            }
            else -> throw IllegalArgumentException(
                "Unsupported chat provider '${config.provider}'. Valid providers are: openai, anthropic, ollama."
            )
        }
    }

    @Bean
    fun embeddingModel(): EmbeddingModel {
        val config = configService.resolve()
        val modelName = config.embeddingModel
        return when (config.embeddingProvider) {
            "openai" -> {
                val apiKey = System.getenv("OPENAI_API_KEY") ?: ""
                val openAiApi = OpenAiApi.builder()
                    .apiKey(apiKey)
                    .build()
                OpenAiEmbeddingModel(openAiApi, org.springframework.ai.document.MetadataMode.EMBED,
                    OpenAiEmbeddingOptions.builder().model(modelName).build())
            }
            "ollama" -> {
                val ollamaApi = OllamaApi.builder()
                    .baseUrl(config.ollamaUrl)
                    .build()
                OllamaEmbeddingModel.builder()
                    .ollamaApi(ollamaApi)
                    .defaultOptions(OllamaOptions.builder().model(modelName).build())
                    .build()
            }
            "onnx" -> {
                val cacheDir = System.getProperty("user.home") + "/.ez-rag/models/"
                TransformersEmbeddingModel().also { model ->
                    model.setResourceCacheDirectory(cacheDir)
                }
            }
            "anthropic" -> throw IllegalArgumentException(
                "Provider 'anthropic' does not provide an embedding API. " +
                "Please use a different embedding provider. Valid embedding providers are: openai, ollama, onnx."
            )
            else -> throw IllegalArgumentException(
                "Unsupported embedding provider '${config.embeddingProvider}'. " +
                "Valid embedding providers are: openai, ollama, onnx."
            )
        }
    }
}
