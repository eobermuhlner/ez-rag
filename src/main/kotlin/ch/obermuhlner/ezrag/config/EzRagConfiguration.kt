package ch.obermuhlner.ezrag.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.io.PrintWriter

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
            ollamaUrl = environment.getProperty("ez.rag.ollamaUrl"),
            rerankModel = environment.getProperty("ez.rag.rerankModel"),
            rerankCandidates = environment.getProperty("ez.rag.rerankCandidates")?.toIntOrNull(),
            storeDir = environment.getProperty("ez.rag.storeDir"),
        )
        return ConfigService(
            configFileSource = { readConfigFile() },
            envVars = System.getenv(),
            startupFlags = startupFlags
        )
    }

    @Bean
    fun credentialsService(): CredentialsService {
        val warningWriter = PrintWriter(System.err, true)
        val noticeWriter = PrintWriter(System.out, true)
        val homeCredentialsPath = System.getProperty("user.home") + "/.ez-rag/credentials.yml"
        val projectLocalCredentialsPath = ".ez-rag/credentials.yml"
        val reader = CredentialsFileReader(warningWriter)
        val gitIgnoreUpdater = GitIgnoreUpdater(noticeWriter)
        return CredentialsService(
            envVars = System.getenv(),
            projectLocalFileReader = {
                val raw = reader.read(projectLocalCredentialsPath)
                if (raw != null) {
                    gitIgnoreUpdater.update(java.io.File("."))
                    raw to java.io.File(projectLocalCredentialsPath).absolutePath
                } else {
                    null
                }
            },
            homeFileReader = {
                reader.read(homeCredentialsPath)?.let { it to homeCredentialsPath }
            }
        )
    }
}
