package ch.obermuhlner.ezrag

import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationContext
import picocli.CommandLine
import kotlin.system.exitProcess

@SpringBootApplication
class EzRagApplication(
    private val applicationContext: ApplicationContext,
    private val ezRagCommand: EzRagCommand,
) : CommandLineRunner {

    override fun run(vararg args: String) {
        val factory = SpringPicocliFactory(applicationContext)
        val commandLine = CommandLine(ezRagCommand, factory)
        val exitCode = commandLine.execute(*args)
        exitProcess(exitCode)
    }
}

/**
 * Pre-parse provider-related flags from raw args so that ProviderConfiguration
 * can read them via ConfigService at Spring context startup time (before picocli
 * parses the full command line).
 */
fun preParseProviderFlags(args: Array<String>): Map<String, String> {
    val flags = mapOf(
        "--provider" to "ez.rag.provider",
        "--embedding-provider" to "ez.rag.embeddingProvider",
        "--model" to "ez.rag.model",
        "--embedding-model" to "ez.rag.embeddingModel",
        "--ollama-url" to "ez.rag.ollamaUrl"
    )
    val result = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        val eqIdx = arg.indexOf('=')
        if (eqIdx > 0) {
            val name = arg.substring(0, eqIdx)
            val value = arg.substring(eqIdx + 1)
            flags[name]?.let { result[it] = value }
        } else {
            flags[arg]?.let { propKey ->
                if (i + 1 < args.size) {
                    result[propKey] = args[i + 1]
                    i++
                }
            }
        }
        i++
    }
    return result
}

fun main(args: Array<String>) {
    val providerProps = preParseProviderFlags(args)
    SpringApplicationBuilder(EzRagApplication::class.java)
        .properties(providerProps)
        .run(*args)
}
