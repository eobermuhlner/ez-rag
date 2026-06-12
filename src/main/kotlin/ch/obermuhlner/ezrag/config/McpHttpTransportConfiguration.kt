package ch.obermuhlner.ezrag.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider
import io.modelcontextprotocol.spec.McpServerTransportProviderBase
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the Streamable HTTP MCP transport (protocol 2025-11-25) when
 * `spring.ai.mcp.server.stdio=false`. The guard prevents double-registration in
 * case a future SDK version provides this bean via its own auto-configuration.
 */
@Configuration
@ConditionalOnProperty(name = ["spring.ai.mcp.server.stdio"], havingValue = "false")
@ConditionalOnMissingBean(McpServerTransportProviderBase::class)
class McpHttpTransportConfiguration {

    @Bean
    fun httpServletStreamableServerTransportProvider(objectMapper: ObjectMapper): HttpServletStreamableServerTransportProvider =
        HttpServletStreamableServerTransportProvider.builder()
            .jsonMapper(JacksonMcpJsonMapper(objectMapper))
            .mcpEndpoint("/mcp")
            .build()

    @Bean
    fun mcpServletRegistration(
        provider: HttpServletStreamableServerTransportProvider
    ): ServletRegistrationBean<HttpServletStreamableServerTransportProvider> =
        ServletRegistrationBean(provider, "/mcp")
}
