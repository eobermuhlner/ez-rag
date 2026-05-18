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
import ch.obermuhlner.ezrag.rag.OnnxChatModel
import ch.obermuhlner.ezrag.rag.OnnxCrossEncoderReranker
import ch.obermuhlner.ezrag.rag.PassthroughChatModel
import ch.obermuhlner.ezrag.rag.Reranker
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ProviderConfiguration(
    private val configService: ConfigService,
    private val credentialsService: CredentialsService,
) {

    private fun requireApiKey(key: String?, source: CredentialSource, envVarName: String): String {
        if (source is CredentialSource.Unset) {
            throw IllegalStateException(
                "API key '$envVarName' is not set. " +
                "Set it via the environment variable $envVarName, " +
                "or add it to .ez-rag/credentials.yml (project-local) " +
                "or ~/.ez-rag/credentials.yml (home directory)."
            )
        }
        return key ?: ""
    }

    @Bean
    fun chatModel(): ChatModel {
        val config = configService.resolve()
        val modelName = config.model
        val credentials = credentialsService.resolve()
        return when (config.provider) {
            "openai" -> {
                val apiKey = requireApiKey(credentials.openaiApiKey, credentials.openaiApiKeySource, "OPENAI_API_KEY")
                val openAiApi = OpenAiApi.builder()
                    .apiKey(apiKey)
                    .build()
                OpenAiChatModel.builder()
                    .openAiApi(openAiApi)
                    .defaultOptions(OpenAiChatOptions.builder().model(modelName).build())
                    .build()
            }
            "anthropic" -> {
                val apiKey = requireApiKey(credentials.anthropicApiKey, credentials.anthropicApiKeySource, "ANTHROPIC_API_KEY")
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
            "passthrough" -> PassthroughChatModel()
            "onnx" -> {
                val cacheRoot = java.io.File(System.getProperty("user.home") + "/.ez-rag/models/")
                OnnxChatModel(modelName = modelName, cacheRoot = cacheRoot)
            }
            else -> throw IllegalArgumentException(
                "Unsupported chat provider '${config.provider}'. Valid providers are: openai, anthropic, ollama, onnx, passthrough."
            )
        }
    }

    @Bean
    fun reranker(): Reranker? {
        val config = configService.resolve()
        if (config.rerankModel.isEmpty()) return null
        val cacheDir = System.getProperty("user.home") + "/.ez-rag/models/"
        return OnnxCrossEncoderReranker(config.rerankModel, cacheDir)
    }

    @Bean
    fun embeddingModel(): EmbeddingModel {
        val config = configService.resolve()
        val modelName = config.embeddingModel
        val credentials = credentialsService.resolve()
        return when (config.embeddingProvider) {
            "openai" -> {
                val apiKey = requireApiKey(credentials.openaiApiKey, credentials.openaiApiKeySource, "OPENAI_API_KEY")
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
