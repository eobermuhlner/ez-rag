package ch.obermuhlner.ezrag.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

@Configuration
class EzRagConfiguration(private val environment: Environment) {

    @Bean
    fun configService(): ConfigService {
        // Collect pre-parsed CLI provider flags that were injected into the Spring
        // environment before context startup (via SpringApplicationBuilder.properties).
        val startupFlags = CliFlags(
            provider = environment.getProperty("ez.rag.provider"),
            embeddingProvider = environment.getProperty("ez.rag.embeddingProvider"),
            model = environment.getProperty("ez.rag.model"),
            embeddingModel = environment.getProperty("ez.rag.embeddingModel"),
            ollamaUrl = environment.getProperty("ez.rag.ollamaUrl")
        )
        return ConfigService(
            configFileSource = { readConfigFile() },
            envVars = System.getenv(),
            startupFlags = startupFlags
        )
    }
}
