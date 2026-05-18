package ch.obermuhlner.ezrag.rag

import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt

open class RagPipeline(
    private val searchPipeline: EmbeddingSearchPipeline,
    private val chatModel: ChatModel
) {

    companion object {
        const val DEFAULT_RAG_SYSTEM_PROMPT = """You are a helpful assistant. Answer the user's question using ONLY the context documents provided below.
If the answer is not found in the context, say "I don't know based on the provided documents."
Always cite which document(s) your answer is based on."""
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
                filePath = chunk.filePath,
                chunkIndex = chunk.chunkIndex,
                similarityScore = chunk.score,
                excerpt = excerpt
            )
        }

        val effectiveSystemPrompt = if (ragQuery.systemPrompt.isBlank()) {
            DEFAULT_RAG_SYSTEM_PROMPT
        } else {
            ragQuery.systemPrompt
        }

        val contextText = searchResult.chunks.joinToString("\n\n") { chunk ->
            "<document source=\"${chunk.filePath}\">\n${chunk.content}\n</document>"
        }

        if (chatModel is PassthroughChatModel) {
            return RagResult(answer = contextText, sources = sources)
        }

        val userContent = "$contextText\n\n<question>${ragQuery.question}</question>"

        val prompt = Prompt(listOf(SystemMessage(effectiveSystemPrompt), UserMessage(userContent)))
        val response = chatModel.call(prompt)
        val answer = response.result?.output?.text ?: ""

        return RagResult(answer = answer, sources = sources, userMessage = userContent)
    }
}
