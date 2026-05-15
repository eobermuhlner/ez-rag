package ch.obermuhlner.ezrag.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class EzRagConfiguration {

    @Bean
    fun configService(): ConfigService = ConfigService(
        configFileSource = { readConfigFile() },
        envVars = System.getenv()
    )
}
