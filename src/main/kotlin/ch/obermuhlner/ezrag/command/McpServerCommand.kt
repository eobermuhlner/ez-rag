package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.config.ConfigService
import ch.obermuhlner.ezrag.config.EzRagDirResolver
import ch.obermuhlner.ezrag.ingestion.IngestService
import ch.obermuhlner.ezrag.ingestion.VectorStoreRepository
import ch.obermuhlner.ezrag.rag.EmbeddingSearchPipeline
import ch.obermuhlner.ezrag.rag.RagPipeline
import org.springframework.ai.chat.model.ChatModel
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

@Command(
    name = "mcp-server",
    mixinStandardHelpOptions = true,
    description = ["Start the MCP server over stdio."]
)
@Component
class McpServerCommand : Callable<Int> {

    @Autowired(required = false)
    private var springEmbeddingModel: EmbeddingModel? = null

    @Autowired(required = false)
    private var springChatModel: ChatModel? = null

    @Autowired(required = false)
    private var springConfigService: ConfigService? = null

    @Option(names = ["--store-dir"], description = ["Path to the store directory."])
    var storeDirOption: String? = null

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
        val storeFilePath = storeDir.resolve("vector-store.json")

        val repository = VectorStoreRepository(embeddingModel, storeFilePath)
        repository.load()

        val chatModel = springChatModel
        val statusTool = McpStatusTool(repository)
        val searchTool = McpSearchTool(EmbeddingSearchPipeline(repository, embeddingModel))
        val queryTool = chatModel?.let { McpQueryTool(RagPipeline(repository, it)) }

        val ingestTool = McpIngestTool(embeddingModel, storeFilePath)

        val tools = buildList {
            add(statusTool)
            add(searchTool)
            if (queryTool != null) add(queryTool)
            add(ingestTool)
        }.toTypedArray()

        return StaticToolCallbackProvider(*ToolCallbacks.from(*tools))
    }

    override fun call(): Int {
        // The MCP server (with stdio transport) is started automatically by Spring
        // auto-configuration when spring.ai.mcp.server.stdio=true is set.
        // Block this thread until the process receives a termination signal (SIGTERM)
        // so that exitProcess() is not called prematurely.
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
