package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.EzRagCommand
import ch.obermuhlner.ezrag.config.ConfigService
import ch.obermuhlner.ezrag.config.EzRagDirResolver
import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.tool.StaticToolCallbackProvider
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.io.PrintWriter
import java.nio.file.Paths
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch

enum class Transport { stdio, http }

@Command(
    name = "mcp-server",
    mixinStandardHelpOptions = true,
    description = ["Start the MCP server (stdio or HTTP transport)."]
)
@Component
class McpServerCommand(
    private val outputWriter: PrintWriter = PrintWriter(System.out, true),
    private val lockTimeoutOverride: Int? = null,
) : Callable<Int> {

    @ParentCommand
    private var parent: EzRagCommand? = null

    @Autowired(required = false)
    private var springEmbeddingModel: EmbeddingModel? = null

    @Autowired(required = false)
    private var springConfigService: ConfigService? = null

    @Option(names = ["--store-dir"], description = ["Path to the store directory."])
    var storeDirOption: String? = null

    @Option(names = ["--transport"], description = ["Transport mode: stdio (default) or http."], defaultValue = "stdio")
    var transport: Transport = Transport.stdio

    @Option(names = ["--port"], description = ["HTTP port (used with --transport http, default: 8080)."], defaultValue = "8080")
    var port: Int = 8080

    @Option(names = ["--url-freshness-hours"], description = ["Freshness window in hours for URL sources (default: 24)."])
    var urlFreshnessHours: Int = 24

    private fun resolveStoreDir() = storeDirOption?.let { Paths.get(it) }
        ?: springConfigService?.resolveExplicitStoreDir()?.let { Paths.get(it) }
        ?: EzRagDirResolver().resolve(Paths.get("").toAbsolutePath())

    private fun resolveLockTimeout() = lockTimeoutOverride ?: parent?.lockTimeout ?: 30

    /**
     * Provides the MCP tool callbacks. Builds a [StoreConfig] and passes it to each tool so
     * tools open their own repository per-request via [LuceneRepository.openWithRetry].
     * No shared [LuceneRepository] is opened here — no write lock is held between tool calls.
     */
    @Bean
    fun mcpToolCallbackProvider(): ToolCallbackProvider {
        val embeddingModel = springEmbeddingModel
            ?: return ToolCallbackProvider { emptyArray<ToolCallback>() }

        val storeDir = resolveStoreDir()
        val analyzer = springConfigService?.resolve()?.analyzer ?: "standard"
        val lockTimeout = resolveLockTimeout()

        val storeConfig = StoreConfig(
            embeddingModel = embeddingModel,
            storeDir = storeDir,
            analyzerName = analyzer,
            lockTimeoutSeconds = lockTimeout,
        )

        val listTool = McpListTool(storeConfig, urlFreshnessThresholdMs = urlFreshnessHours * 3_600_000L)
        val searchTool = McpSearchTool(storeConfig)
        val ingestTool = McpIngestTool(storeConfig)
        val reIngestTool = McpReIngestTool(storeConfig, urlFreshnessThresholdMs = urlFreshnessHours * 3_600_000L)
        val chunkTool = McpChunkTool(storeConfig)

        val tools = buildList {
            add(listTool)
            add(searchTool)
            add(ingestTool)
            add(reIngestTool)
            add(chunkTool)
        }.toTypedArray()

        return StaticToolCallbackProvider(*ToolCallbacks.from(*tools))
    }

    override fun call(): Int {
        val storeDir = resolveStoreDir()

        if (!LuceneRepository.storeExists(storeDir)) {
            outputWriter.println(
                "Error: store directory does not exist: ${storeDir.toAbsolutePath()} — run 'ez-rag ingest' first."
            )
            return 1
        }

        // The MCP server transport is started automatically by Spring auto-configuration.
        // Block this thread until the process receives a termination signal (SIGTERM)
        // so that exitProcess() is not called prematurely.
        if (transport == Transport.http) {
            println("MCP server listening on http://localhost:$port/mcp")
        }
        val done = CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(Thread { done.countDown() })
        try {
            done.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return 0
    }
}
