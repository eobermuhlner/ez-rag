package ch.obermuhlner.ezrag

import ch.obermuhlner.ezrag.config.EzRagDirResolver
import ch.obermuhlner.ezrag.config.readConfigRaw
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ApplicationContext
import picocli.CommandLine
import java.nio.file.Paths
import kotlin.system.exitProcess

@SpringBootApplication
class EzRagApplication(
    private val applicationContext: ApplicationContext,
    private val ezRagCommand: EzRagCommand,
) : CommandLineRunner {

    override fun run(vararg args: String) {
        val factory = SpringPicocliFactory(applicationContext)
        val commandLine = CommandLine(ezRagCommand, factory)
        commandLine.setExecutionExceptionHandler(EzRagExecutionExceptionHandler(ezRagCommand))
        val exitCode = commandLine.execute(*args)
        exitProcess(exitCode)
    }
}

/**
 * Pre-parse provider-related flags from raw args so that ProviderConfiguration
 * can read them via ConfigService at Spring context startup time (before picocli
 * parses the full command line).
 *
 * Also detects the `mcp-server` subcommand so that MCP stdio transport and
 * logging can be configured before the Spring context starts.
 */
fun preParseProviderFlags(args: Array<String>, localEzRagDir: java.nio.file.Path? = null): Map<String, String> {
    val flags = mapOf(
        "--provider" to "ez.rag.provider",
        "--model" to "ez.rag.model",
        "--ollama-url" to "ez.rag.ollamaUrl",
        "--rerank-model" to "ez.rag.rerankModel",
        "--rerank-candidates" to "ez.rag.rerankCandidates",
        "--store-dir" to "ez.rag.storeDir"
    )
    val result = mutableMapOf<String, String>()
    var isMcpServer = false
    var transport = "stdio"
    var port = "8080"
    var verbose = false
    var storeDirArg: String? = null
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        if (arg == "mcp-server") {
            isMcpServer = true
        }
        if (arg == "--verbose" || arg == "-v") {
            verbose = true
        }
        val eqIdx = arg.indexOf('=')
        if (eqIdx > 0) {
            val name = arg.substring(0, eqIdx)
            val value = arg.substring(eqIdx + 1)
            flags[name]?.let { result[it] = value }
            if (name == "--transport") transport = value
            if (name == "--port") port = value
            if (name == "--store-dir") storeDirArg = value
        } else {
            flags[arg]?.let { propKey ->
                if (i + 1 < args.size) {
                    result[propKey] = args[i + 1]
                    i++
                }
            }
            if (arg == "--transport" && i + 1 < args.size) {
                transport = args[i + 1]
                i++
            }
            if (arg == "--port" && i + 1 < args.size) {
                port = args[i + 1]
                i++
            }
            if (arg == "--store-dir" && i + 1 < args.size) {
                storeDirArg = args[i + 1]
            }
        }
        i++
    }

    // Resolve local config: use provided localEzRagDir (for tests), or derive from CLI/cwd
    val ezRagDir = localEzRagDir ?: run {
        if (storeDirArg != null) {
            Paths.get(storeDirArg).resolve(".ez-rag")
        } else {
            EzRagDirResolver().resolve(Paths.get("").toAbsolutePath())
        }
    }
    val localConfigPath = ezRagDir.resolve("config.yml")
    val localConfig = readConfigRaw(localConfigPath.toString())
    if (localConfig != null) {
        (localConfig["embeddingProvider"] as? String)?.let { result["ez.rag.embeddingProvider"] = it }
        (localConfig["embeddingModel"] as? String)?.let { result["ez.rag.embeddingModel"] = it }
    }

    if (isMcpServer) {
        result["spring.ai.mcp.server.name"] = "ez-rag"
        result["spring.ai.mcp.server.version"] = "1.0.0"
        if (transport == "http") {
            // HTTP transport: start embedded servlet container.
            // Disable lazy init so McpSyncServer is created at startup and calls
            // setSessionFactory() on the transport provider before the first request.
            result["spring.ai.mcp.server.stdio"] = "false"
            result["spring.main.web-application-type"] = "servlet"
            result["server.port"] = port
            result["spring.main.lazy-initialization"] = "false"
            if (!verbose) {
                result["logging.level.root"] = "off"
            }
        } else {
            // stdio transport: suppress console logging so stdout is reserved for MCP protocol messages
            result["spring.ai.mcp.server.stdio"] = "true"
            result["spring.main.web-application-type"] = "none"
            result["spring.main.lazy-initialization"] = "true"
            result["logging.pattern.console"] = ""
            result["logging.level.root"] = "off"
        }
    } else {
        // Disable the MCP server entirely when not running as mcp-server to avoid
        // the auto-configuration overhead and unintended side effects.
        result["spring.ai.mcp.server.enabled"] = "false"
        result["spring.main.web-application-type"] = "none"
        result["spring.main.lazy-initialization"] = "true"
        result["logging.level.root"] = "off"
    }

    return result
}

fun main(args: Array<String>) {
    val providerProps = preParseProviderFlags(args)
    SpringApplicationBuilder(EzRagApplication::class.java)
        .properties(providerProps)
        .run(*args)
}
