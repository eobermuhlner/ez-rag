package ch.obermuhlner.ezrag.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringExtension

@ExtendWith(SpringExtension::class)
@Import(McpHttpTransportConfiguration::class, McpHttpTransportConfigurationTest.ObjectMapperConfig::class)
@TestPropertySource(properties = ["spring.ai.mcp.server.stdio=false"])
class McpHttpTransportConfigurationTest {

    @TestConfiguration
    class ObjectMapperConfig {
        @Bean
        fun objectMapper(): ObjectMapper = ObjectMapper()
    }

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun `HttpServletStreamableServerTransportProvider bean is present when stdio is false`() {
        val bean = applicationContext.getBean(HttpServletStreamableServerTransportProvider::class.java)
        assertThat(bean).isNotNull()
    }
}
