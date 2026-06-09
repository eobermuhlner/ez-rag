package ch.obermuhlner.ezrag.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider
import io.modelcontextprotocol.spec.McpServerTransportProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the HTTP/SSE MCP server transport when `spring.ai.mcp.server.stdio=false`.
 * The guard `@ConditionalOnMissingBean(McpServerTransportProvider::class)` prevents
 * double-registration in case a future SDK version provides this bean via its own
 * auto-configuration.
 */
@Configuration
@ConditionalOnProperty(name = ["spring.ai.mcp.server.stdio"], havingValue = "false")
@ConditionalOnMissingBean(McpServerTransportProvider::class)
class McpHttpTransportConfiguration {

    @Bean
    fun httpServletSseServerTransportProvider(objectMapper: ObjectMapper): HttpServletSseServerTransportProvider =
        HttpServletSseServerTransportProvider(objectMapper, "/mcp/message", "/sse")

    @Bean
    fun mcpServletRegistration(
        provider: HttpServletSseServerTransportProvider
    ): ServletRegistrationBean<HttpServletSseServerTransportProvider> =
        ServletRegistrationBean(provider, "/sse", "/mcp/message")
}
