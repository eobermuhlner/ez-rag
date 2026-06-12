package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.config.ConfigService
import ch.obermuhlner.ezrag.config.EzRagDirResolver
import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import ch.obermuhlner.ezrag.rag.HybridSearchPipeline
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
class McpServerCommand : Callable<Int> {

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

    /**
     * Provides the MCP tool callbacks. Registers the status tool and additional tools
     * added in subsequent tasks. The auto-configuration injects all ToolCallbackProvider
     * beans and registers their tools with McpSyncServer.
     */
    @Bean
    fun mcpToolCallbackProvider(): ToolCallbackProvider {
        val embeddingModel = springEmbeddingModel
            ?: return ToolCallbackProvider { emptyArray<ToolCallback>() }

        val storeDir = storeDirOption?.let { Paths.get(it) }
            ?: springConfigService?.resolveExplicitStoreDir()?.let { Paths.get(it) }
            ?: EzRagDirResolver().resolve(Paths.get("").toAbsolutePath())

        val analyzer = springConfigService?.resolve()?.analyzer ?: "standard"
        val luceneRepository = LuceneRepository.open(embeddingModel, storeDir, analyzer)

        val listTool = McpListTool(luceneRepository)
        val hybridSearchPipeline = HybridSearchPipeline(luceneRepository)
        val searchTool = McpSearchTool(hybridSearchPipeline)

        val ingestTool = McpIngestTool(embeddingModel, storeDir)
        val reIngestTool = McpReIngestTool(embeddingModel, storeDir)
        val chunkTool = McpChunkTool(luceneRepository)

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
