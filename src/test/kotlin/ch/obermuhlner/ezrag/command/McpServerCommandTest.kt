package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.config.EzRagConfiguration
import ch.obermuhlner.ezrag.config.ProviderConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringExtension

/**
 * Verifies that McpServerCommand is present in the Spring application context.
 * Uses a focused Spring context that excludes the MCP server auto-configuration
 * (spring.ai.mcp.server.enabled=false) to avoid starting the stdio transport in tests.
 */
@ExtendWith(SpringExtension::class)
@Import(EzRagConfiguration::class, ProviderConfiguration::class, McpServerCommand::class)
@TestPropertySource(properties = [
    "ez.rag.provider=openai",
    "ez.rag.embeddingProvider=openai",
    "spring.ai.mcp.server.enabled=false"
])
class McpServerCommandTest {

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
    fun `ToolCallbackProvider exposes a tool named status`() {
        val toolCallbacks = mcpToolCallbackProvider.toolCallbacks
        val toolNames = toolCallbacks.map { it.toolDefinition.name() }
        assertThat(toolNames).contains("status")
    }

    @Test
    fun `ToolCallbackProvider exposes a tool named query`() {
        val toolCallbacks = mcpToolCallbackProvider.toolCallbacks
        val toolNames = toolCallbacks.map { it.toolDefinition.name() }
        assertThat(toolNames).contains("query")
    }
}
