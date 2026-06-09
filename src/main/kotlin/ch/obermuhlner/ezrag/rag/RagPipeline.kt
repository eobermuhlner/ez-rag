package ch.obermuhlner.ezrag.rag

import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt

open class RagPipeline(
    private val searchPipeline: EmbeddingSearchPipeline,
    private val chatModel: ChatModel
) {

    companion object {
        const val DEFAULT_RAG_SYSTEM_PROMPT = "You are a helpful assistant. Answer the user's question using ONLY content from the knowledge base provided below. For each claim, cite the source path. If the answer is not in the knowledge base, say so. The conversation history shows earlier exchanges; you may refer to them when answering follow-up questions."
        private const val EXCERPT_MAX_LENGTH = 200
    }

    open fun query(ragQuery: RagQuery): RagResult {
        val searchQuery = SearchQuery(
            question = ragQuery.question,
            topK = ragQuery.topK,
            minScore = 0.0,
            rerankCandidates = ragQuery.rerankCandidates,
            verbose = ragQuery.verbose
        )
        val searchResult = searchPipeline.search(searchQuery)

        if (searchResult.chunks.isEmpty()) {
            return RagResult(answer = "No relevant documents found", sources = emptyList())
        }

        val sources = searchResult.chunks.map { chunk ->
            val excerpt = if (chunk.content.length > EXCERPT_MAX_LENGTH) {
                chunk.content.substring(0, EXCERPT_MAX_LENGTH)
            } else {
                chunk.content
            }
            SourceReference(
                path = chunk.path,
                chunkIndex = chunk.chunkIndex,
                score = chunk.score,
                excerpt = excerpt
            )
        }

        val effectiveSystemPrompt = if (ragQuery.systemPrompt.isBlank()) {
            DEFAULT_RAG_SYSTEM_PROMPT
        } else {
            ragQuery.systemPrompt
        }

        val contextText = searchResult.chunks.joinToString("\n\n") { chunk ->
            "<document source=\"${chunk.path}\">\n${chunk.content}\n</document>"
        }

        if (chatModel is PassthroughChatModel) {
            return RagResult(answer = contextText, sources = sources)
        }

        val userContent = "$contextText\n\n<question>${ragQuery.question}</question>"

        val messages = mutableListOf<Message>()
        messages.add(SystemMessage(effectiveSystemPrompt))
        ragQuery.conversationHistory.forEach { turn ->
            messages.add(UserMessage(turn.userQuestion))
            messages.add(AssistantMessage(turn.assistantAnswer))
        }
        messages.add(UserMessage(userContent))
        val prompt = Prompt(messages)
        val response = chatModel.call(prompt)
        val answer = response.result?.output?.text ?: ""

        return RagResult(answer = answer, sources = sources, userMessage = userContent)
    }
}
