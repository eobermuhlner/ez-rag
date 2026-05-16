package ch.obermuhlner.ezrag.rag

import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt

open class PassthroughChatModel : ChatModel {

    override fun call(prompt: Prompt): ChatResponse {
        throw UnsupportedOperationException(
            "PassthroughChatModel does not support call(). " +
            "RagPipeline short-circuits before invoking ChatModel when passthrough is active."
        )
    }
}
