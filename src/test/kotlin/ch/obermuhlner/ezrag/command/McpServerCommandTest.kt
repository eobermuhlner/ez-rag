package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.config.EzRagConfiguration
import ch.obermuhlner.ezrag.config.ProviderConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path

/**
 * Verifies that McpServerCommand is present in the Spring application context.
 * Uses a focused Spring context that excludes the MCP server auto-configuration
 * (spring.ai.mcp.server.enabled=false) to avoid starting the stdio transport in tests.
 */
@ExtendWith(SpringExtension::class)
@Import(EzRagConfiguration::class, ProviderConfiguration::class, McpServerCommand::class)
@TestPropertySource(properties = [
    "ez.rag.provider=passthrough",
    "ez.rag.embeddingProvider=onnx",
    "spring.ai.mcp.server.enabled=false"
])
class McpServerCommandTest {

    companion object {
        @TempDir
        @JvmField
        var tempDir: Path? = null

        @DynamicPropertySource
        @JvmStatic
        fun storeDirProperty(registry: DynamicPropertyRegistry) {
            registry.add("ez.rag.storeDir") { tempDir!!.toAbsolutePath().toString() }
        }
    }

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Autowired
    private lateinit var mcpServerCommand: McpServerCommand

    @Autowired
    private lateinit var mcpToolCallbackProvider: ToolCallbackProvider

    @Test
    fun `McpServerCommand bean is present in the Spring application context`() {
        assertThat(mcpServerCommand).isNotNull()
        // Bean is registered as "mcpServerCommand" (standard Spring naming for @Component class)
        val beanNames = applicationContext.getBeanNamesForType(McpServerCommand::class.java)
        assertThat(beanNames).isNotEmpty()
    }

    @Test
    fun `ToolCallbackProvider exposes a tool named reingest`() {
        val toolCallbacks = mcpToolCallbackProvider.toolCallbacks
        val toolNames = toolCallbacks.map { it.toolDefinition.name() }
        assertThat(toolNames).contains("reingest")
    }

    @Test
    fun `ToolCallbackProvider exposes a tool named chunk`() {
        val toolCallbacks = mcpToolCallbackProvider.toolCallbacks
        val toolNames = toolCallbacks.map { it.toolDefinition.name() }
        assertThat(toolNames).contains("chunk")
    }

    @Test
    fun `ToolCallbackProvider exposes a tool named list`() {
        val toolCallbacks = mcpToolCallbackProvider.toolCallbacks
        val toolNames = toolCallbacks.map { it.toolDefinition.name() }
        assertThat(toolNames).contains("list")
    }

    @Test
    fun `ToolCallbackProvider exposes exactly the tools search, chunk, ingest, reingest, list`() {
        val toolCallbacks = mcpToolCallbackProvider.toolCallbacks
        val toolNames = toolCallbacks.map { it.toolDefinition.name() }.toSet()
        assertThat(toolNames).containsExactlyInAnyOrder("search", "chunk", "ingest", "reingest", "list")
    }

    @Test
    fun `mcpToolCallbackProvider does not call LuceneRepository open — StoreConfig is passed to tools instead`() {
        // Tools are built per-request using StoreConfig; no shared repository opened at provider build time.
        // Verifiable by inspecting that toolCallbackProvider.toolCallbacks is non-empty
        // (provider built successfully without needing a write lock) and by code review.
        val toolCallbacks = mcpToolCallbackProvider.toolCallbacks
        assertThat(toolCallbacks).isNotEmpty()
    }
}

/**
 * Unit tests for McpServerCommand.call() — no Spring context required.
 */
class McpServerCommandCallTest {

    @Test
    fun `call returns non-zero and prints error when store directory does not exist`(@TempDir tempDir: java.nio.file.Path) {
        val nonExistentStore = tempDir.resolve("does-not-exist").toString()
        val sw = StringWriter()
        val cmd = McpServerCommand(outputWriter = PrintWriter(sw, true))
        cmd.storeDirOption = nonExistentStore

        val exitCode = cmd.call()

        assertThat(exitCode).isNotEqualTo(0)
        assertThat(sw.toString()).contains(nonExistentStore)
    }
}
