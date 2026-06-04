package ch.obermuhlner.ezrag.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.io.File
import java.io.PrintWriter
import java.nio.file.Paths

fun resolveConfigSources(homeConfigPath: String, localConfigPath: String): ConfigSources {
    val homeExists = File(homeConfigPath).exists()
    val localExists = File(localConfigPath).exists()
    val sameFile = homeExists && localExists &&
        File(homeConfigPath).canonicalPath == File(localConfigPath).canonicalPath
    return ConfigSources(
        homeConfigPath = if (homeExists) homeConfigPath else null,
        localConfigPath = if (localExists && !sameFile) localConfigPath else null
    )
}

@Configuration
class EzRagConfiguration(private val environment: Environment) {

    @Bean
    fun configSources(): ConfigSources {
        val homeConfigPath = System.getProperty("user.home") + "/.ez-rag/config.yml"
        val ezRagDir = EzRagDirResolver().resolve(Paths.get("").toAbsolutePath())
        val localConfigPath = ezRagDir.resolve("config.yml").toString()
        return resolveConfigSources(homeConfigPath, localConfigPath)
    }

    @Bean
    fun configService(configSources: ConfigSources): ConfigService {
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
            configFileSource = {
                val homeRaw = configSources.homeConfigPath?.let { readConfigRaw(it) }
                val localRaw = configSources.localConfigPath?.let { readConfigRaw(it) }
                mergeConfigRaw(homeRaw, localRaw)
            },
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
