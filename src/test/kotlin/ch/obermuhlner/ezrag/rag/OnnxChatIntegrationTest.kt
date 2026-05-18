package ch.obermuhlner.ezrag.rag

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import java.io.File

@Tag("integration")
class OnnxChatIntegrationTest {

    @Test
    fun `TinyLlama-1 1B-Chat-v1 0 returns non-empty answer for a one-sentence context query`() {
        val cacheRoot = File(System.getProperty("user.home") + "/.ez-rag/models/")
        val model = OnnxChatModel(modelName = "Xenova/TinyLlama-1.1B-Chat-v1.0", cacheRoot = cacheRoot)

        val prompt = Prompt(listOf(
            SystemMessage("You are a helpful assistant. Answer briefly using only the provided context."),
            UserMessage("Context: The Eiffel Tower is located in Paris, France.\n\nQuestion: Where is the Eiffel Tower?")
        ))

        val response = model.call(prompt)

        assertThat(response).isNotNull()
        assertThat(response.result).isNotNull()
        assertThat(response.result.output.text).isNotBlank()
    }
}
