package ch.obermuhlner.ezrag.rag

data class ConversationTurn(
    val userQuestion: String,
    val assistantAnswer: String
)

data class RagQuery(
    val question: String,
    val topK: Int,
    val systemPrompt: String,
    val modelOverride: String?,
    val rerankCandidates: Int? = null,
    val verbose: Boolean = false,
    val conversationHistory: List<ConversationTurn> = emptyList()
)

data class SourceReference(
    val filePath: String,
    val chunkIndex: Int,
    val similarityScore: Double,
    val excerpt: String
)

data class RagResult(
    val answer: String,
    val sources: List<SourceReference>,
    val userMessage: String? = null,
)
