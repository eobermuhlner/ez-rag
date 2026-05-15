package ch.obermuhlner.ezrag

import ch.obermuhlner.ezrag.command.IngestCommand
import ch.obermuhlner.ezrag.command.StatusCommand
import ch.obermuhlner.ezrag.config.EzRagConfiguration
import ch.obermuhlner.ezrag.config.ProviderConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.junit.jupiter.api.extension.ExtendWith

// Uses a focused Spring context (no CommandLineRunner, no exitProcess) to verify
// that Spring field-injects EmbeddingModel into the command beans at startup.
@ExtendWith(SpringExtension::class)
@Import(EzRagConfiguration::class, ProviderConfiguration::class, IngestCommand::class, StatusCommand::class)
@TestPropertySource(properties = [
    "ez.rag.provider=openai",
    "ez.rag.embeddingProvider=openai"
])
class SpringWiringTest {

    @Autowired
    private lateinit var ingestCommand: IngestCommand

    @Autowired
    private lateinit var statusCommand: StatusCommand

    @Test
    fun `IngestCommand receives EmbeddingModel via Spring field injection`() {
        val field = IngestCommand::class.java.getDeclaredField("springEmbeddingModel")
        field.isAccessible = true
        assertThat(field.get(ingestCommand))
            .describedAs("springEmbeddingModel must not be null — Spring failed to wire EmbeddingModel into IngestCommand")
            .isNotNull()
    }

    @Test
    fun `StatusCommand receives EmbeddingModel via Spring field injection`() {
        val field = StatusCommand::class.java.getDeclaredField("springEmbeddingModel")
        field.isAccessible = true
        assertThat(field.get(statusCommand))
            .describedAs("springEmbeddingModel must not be null — Spring failed to wire EmbeddingModel into StatusCommand")
            .isNotNull()
    }
}
