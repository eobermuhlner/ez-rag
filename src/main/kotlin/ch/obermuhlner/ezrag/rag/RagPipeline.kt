package ch.obermuhlner.ezrag.rag

import ch.obermuhlner.ezrag.ingestion.VectorStoreRepository
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
open class RagPipeline(
    private val repository: VectorStoreRepository,
    private val chatModel: ChatModel
) {

    companion object {
        const val DEFAULT_RAG_SYSTEM_PROMPT = """You are a helpful assistant. Answer the user's question using ONLY the context documents provided below.
If the answer is not found in the context, say "I don't know based on the provided documents."
Always cite which document(s) your answer is based on."""
        private const val EXCERPT_MAX_LENGTH = 200
    }

    open fun query(ragQuery: RagQuery): RagResult {
        val similarDocs = repository.getStore().similaritySearch(
            org.springframework.ai.vectorstore.SearchRequest.builder()
                .query(ragQuery.question)
                .topK(ragQuery.topK)
                .build()
        )

        if (similarDocs.isNullOrEmpty()) {
            return RagResult(answer = "No relevant documents found", sources = emptyList())
        }

        val sources = similarDocs.map { doc ->
            val filePath = doc.metadata["source"] as? String ?: ""
            val chunkIndex = doc.metadata["chunk_index"]?.let { toInt(it) } ?: 0
            val score = doc.score ?: 0.0
            val text = doc.text ?: ""
            val excerpt = if (text.length > EXCERPT_MAX_LENGTH) text.substring(0, EXCERPT_MAX_LENGTH) else text
            SourceReference(
                filePath = filePath,
                chunkIndex = chunkIndex,
                similarityScore = score,
                excerpt = excerpt
            )
        }

        val effectiveSystemPrompt = if (ragQuery.systemPrompt.isBlank()) {
            DEFAULT_RAG_SYSTEM_PROMPT
        } else {
            ragQuery.systemPrompt
        }

        val contextText = similarDocs.mapIndexed { idx, doc ->
            "--- Context: ${doc.metadata["source"] ?: "unknown"} ---\n${doc.text}"
        }.joinToString("\n\n")

        if (chatModel is PassthroughChatModel) {
            return RagResult(answer = contextText, sources = sources)
        }

        val userContent = "$contextText\n\n${ragQuery.question}"

        val prompt = Prompt(listOf(SystemMessage(effectiveSystemPrompt), UserMessage(userContent)))
        val response = chatModel.call(prompt)
        val answer = response.result?.output?.text ?: ""

        return RagResult(answer = answer, sources = sources)
    }

    private fun toInt(value: Any): Int? = when (value) {
        is Int -> value
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
}
