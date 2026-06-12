package ch.obermuhlner.ezrag.rag

import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import java.nio.file.Path

class RagPipelineTest {

    private val fakeEmbeddingModel: EmbeddingModel = object : EmbeddingModel {
        override fun call(request: EmbeddingRequest): EmbeddingResponse {
            val embeddings = request.instructions.mapIndexed { idx, _ ->
                Embedding(FloatArray(4) { 0.25f }, idx)
            }
            return EmbeddingResponse(embeddings)
        }

        override fun embed(document: Document): FloatArray = FloatArray(4) { 0.25f }
        override fun embed(text: String): FloatArray = FloatArray(4) { 0.25f }

        override fun embedForResponse(texts: List<String>): EmbeddingResponse {
            val embeddings = texts.mapIndexed { idx, _ ->
                Embedding(FloatArray(4) { 0.25f }, idx)
            }
            return EmbeddingResponse(embeddings)
        }

        override fun dimensions(): Int = 4
    }

    private fun createRepository(tempDir: Path): LuceneRepository {
        return LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard")
    }

    private fun createPipeline(repository: LuceneRepository, chatModel: ChatModel): RagPipeline {
        val searchPipeline = EmbeddingSearchPipeline(repository)
        return RagPipeline(searchPipeline, chatModel)
    }

    @Test
    fun `empty store returns no-documents result without invoking ChatModel`(@TempDir tempDir: Path) {
        val repository = createRepository(tempDir)

        var chatModelInvoked = false
        val stubChatModel: ChatModel = ChatModel { _ ->
            chatModelInvoked = true
            ChatResponse(listOf(Generation(AssistantMessage("should not be called"))))
        }

        val pipeline = createPipeline(repository, stubChatModel)
        val query = RagQuery(question = "What is X?", topK = 5, systemPrompt = "", modelOverride = null)
        val result = pipeline.query(query)

        assertThat(result.answer).isEqualTo("No relevant documents found")
        assertThat(result.sources).isEmpty()
        assertThat(chatModelInvoked).isFalse()
    }

    @Test
    fun `UserMessage sent to ChatModel contains chunk text with Context label and ends with user question`(@TempDir tempDir: Path) {
        val repository = createRepository(tempDir)

        // Add a document to the store
        val doc = Document.builder()
            .text("The capital of France is Paris.")
            .metadata(mapOf("source" to "geography.txt", "chunk_index" to 0))
            .build()
        repository.add(listOf(doc))

        var capturedPrompt: Prompt? = null
        val capturingChatModel: ChatModel = ChatModel { prompt ->
            capturedPrompt = prompt
            ChatResponse(listOf(Generation(AssistantMessage("Paris"))))
        }

        val pipeline = createPipeline(repository, capturingChatModel)
        val query = RagQuery(question = "What is the capital of France?", topK = 5, systemPrompt = "", modelOverride = null)
        pipeline.query(query)

        assertThat(capturedPrompt).isNotNull()
        val userMessage = capturedPrompt!!.instructions
            .filterIsInstance<UserMessage>()
            .firstOrNull()
        assertThat(userMessage).isNotNull()
        val content = userMessage!!.text
        assertThat(content).contains("<document source=")
        assertThat(content).contains("The capital of France is Paris.")
        assertThat(content).endsWith("<question>What is the capital of France?</question>")
    }

    @Test
    fun `UserMessage wraps each document in XML document tags with source attribute`(@TempDir tempDir: Path) {
        val repository = createRepository(tempDir)

        val doc = Document.builder()
            .text("The capital of France is Paris.")
            .metadata(mapOf("source" to "geography.txt", "chunk_index" to 0))
            .build()
        repository.add(listOf(doc))

        var capturedPrompt: Prompt? = null
        val capturingChatModel: ChatModel = ChatModel { prompt ->
            capturedPrompt = prompt
            ChatResponse(listOf(Generation(AssistantMessage("Paris"))))
        }

        val pipeline = createPipeline(repository, capturingChatModel)
        val query = RagQuery(question = "What is the capital of France?", topK = 5, systemPrompt = "", modelOverride = null)
        pipeline.query(query)

        val content = capturedPrompt!!.instructions.filterIsInstance<UserMessage>().first().text
        assertThat(content).contains("<document source=\"geography.txt\">")
        assertThat(content).contains("The capital of France is Paris.")
        assertThat(content).contains("</document>")
        assertThat(content).contains("<question>What is the capital of France?</question>")
    }

    @Test
    fun `topK=1 with two chunks in store produces exactly one source`(@TempDir tempDir: Path) {
        val repository = createRepository(tempDir)

        // Add two documents
        val doc1 = Document.builder()
            .text("Chunk one content.")
            .metadata(mapOf("source" to "file1.txt", "chunk_index" to 0))
            .build()
        val doc2 = Document.builder()
            .text("Chunk two content.")
            .metadata(mapOf("source" to "file2.txt", "chunk_index" to 0))
            .build()
        repository.add(listOf(doc1, doc2))

        val stubChatModel: ChatModel = ChatModel { _ ->
            ChatResponse(listOf(Generation(AssistantMessage("answer"))))
        }

        val pipeline = createPipeline(repository, stubChatModel)
        val query = RagQuery(question = "Tell me something", topK = 1, systemPrompt = "", modelOverride = null)
        val result = pipeline.query(query)

        assertThat(result.sources).hasSize(1)
    }

    @Test
    fun `SourceReference excerpt is truncated to at most 200 characters`(@TempDir tempDir: Path) {
        val repository = createRepository(tempDir)

        val longText = "A".repeat(300)
        val doc = Document.builder()
            .text(longText)
            .metadata(mapOf("source" to "long.txt", "chunk_index" to 0))
            .build()
        repository.add(listOf(doc))

        val stubChatModel: ChatModel = ChatModel { _ ->
            ChatResponse(listOf(Generation(AssistantMessage("answer"))))
        }

        val pipeline = createPipeline(repository, stubChatModel)
        val query = RagQuery(question = "What?", topK = 5, systemPrompt = "", modelOverride = null)
        val result = pipeline.query(query)

        assertThat(result.sources).isNotEmpty()
        result.sources.forEach { source ->
            assertThat(source.text.length).isLessThanOrEqualTo(200)
        }
    }

    @Test
    fun `SourceReference chunkIndex equals the integer stored in chunk_index metadata`(@TempDir tempDir: Path) {
        val repository = createRepository(tempDir)

        val doc = Document.builder()
            .text("Some chunk text.")
            .metadata(mapOf("source" to "test.txt", "chunk_index" to 7))
            .build()
        repository.add(listOf(doc))

        val stubChatModel: ChatModel = ChatModel { _ ->
            ChatResponse(listOf(Generation(AssistantMessage("answer"))))
        }

        val pipeline = createPipeline(repository, stubChatModel)
        val query = RagQuery(question = "What?", topK = 5, systemPrompt = "", modelOverride = null)
        val result = pipeline.query(query)

        assertThat(result.sources).isNotEmpty()
        assertThat(result.sources.first().chunkIndex).isEqualTo(7)
    }

    @Test
    fun `blank systemPrompt uses default RAG system prompt in SystemMessage`(@TempDir tempDir: Path) {
        val repository = createRepository(tempDir)

        val doc = Document.builder()
            .text("Some content.")
            .metadata(mapOf("source" to "test.txt", "chunk_index" to 0))
            .build()
        repository.add(listOf(doc))

        var capturedPrompt: Prompt? = null
        val capturingChatModel: ChatModel = ChatModel { prompt ->
            capturedPrompt = prompt
            ChatResponse(listOf(Generation(AssistantMessage("answer"))))
        }

        val pipeline = createPipeline(repository, capturingChatModel)
        val query = RagQuery(question = "What?", topK = 5, systemPrompt = "", modelOverride = null)
        pipeline.query(query)

        val systemMessage = capturedPrompt!!.instructions
            .filterIsInstance<SystemMessage>()
            .firstOrNull()
        assertThat(systemMessage).isNotNull()
        assertThat(systemMessage!!.text).isEqualTo(RagPipeline.DEFAULT_RAG_SYSTEM_PROMPT)
        assertThat(systemMessage.text).contains("knowledge base")
        assertThat(systemMessage.text).contains("cite the source path")
        assertThat(systemMessage.text).doesNotContain("context documents provided")
    }

    @Test
    fun `query with PassthroughChatModel returns contextText as answer and populates sources`(@TempDir tempDir: Path) {
        val repository = createRepository(tempDir)

        val doc = Document.builder()
            .text("The capital of France is Paris.")
            .metadata(mapOf("source" to "geography.txt", "chunk_index" to 0))
            .build()
        repository.add(listOf(doc))

        val passthroughModel = PassthroughChatModel()
        val pipeline = createPipeline(repository, passthroughModel)
        val query = RagQuery(question = "What is the capital of France?", topK = 5, systemPrompt = "", modelOverride = null)
        val result = pipeline.query(query)

        val expectedContextText = "<document source=\"geography.txt\">\nThe capital of France is Paris.\n</document>"
        assertThat(result.answer).isEqualTo(expectedContextText)
        assertThat(result.sources).hasSize(1)
        assertThat(result.sources.first().path).isEqualTo("geography.txt")
        assertThat(result.sources.first().chunkIndex).isEqualTo(0)
        assertThat(result.sources.first().text).contains("The capital of France is Paris.")
    }

    @Test
    fun `query with PassthroughChatModel never invokes chatModel call`(@TempDir tempDir: Path) {
        val repository = createRepository(tempDir)

        val doc = Document.builder()
            .text("Some content.")
            .metadata(mapOf("source" to "test.txt", "chunk_index" to 0))
            .build()
        repository.add(listOf(doc))

        var chatModelCallInvoked = false
        val trackingPassthrough = object : PassthroughChatModel() {
            override fun call(prompt: Prompt): ChatResponse {
                chatModelCallInvoked = true
                return super.call(prompt)
            }
        }

        val pipeline = createPipeline(repository, trackingPassthrough)
        val query = RagQuery(question = "What?", topK = 5, systemPrompt = "", modelOverride = null)
        pipeline.query(query)

        assertThat(chatModelCallInvoked).isFalse()
    }

    @Test
    fun `non-blank systemPrompt replaces default system prompt in SystemMessage`(@TempDir tempDir: Path) {
        val repository = createRepository(tempDir)

        val doc = Document.builder()
            .text("Some content.")
            .metadata(mapOf("source" to "test.txt", "chunk_index" to 0))
            .build()
        repository.add(listOf(doc))

        var capturedPrompt: Prompt? = null
        val capturingChatModel: ChatModel = ChatModel { prompt ->
            capturedPrompt = prompt
            ChatResponse(listOf(Generation(AssistantMessage("answer"))))
        }

        val pipeline = createPipeline(repository, capturingChatModel)
        val customPrompt = "You are a domain expert. Only use the context."
        val query = RagQuery(question = "What?", topK = 5, systemPrompt = customPrompt, modelOverride = null)
        pipeline.query(query)

        val systemMessage = capturedPrompt!!.instructions
            .filterIsInstance<SystemMessage>()
            .firstOrNull()
        assertThat(systemMessage).isNotNull()
        assertThat(systemMessage!!.text).isEqualTo(customPrompt)
        assertThat(systemMessage.text).doesNotContain(RagPipeline.DEFAULT_RAG_SYSTEM_PROMPT)
    }

    @Test
    fun `RagQuery has rerankCandidates field that defaults to null`() {
        val query = RagQuery(question = "test", topK = 5, systemPrompt = "", modelOverride = null)
        assertThat(query.rerankCandidates).isNull()
    }

    @Test
    fun `empty conversationHistory produces two-message prompt structure`(@TempDir tempDir: Path) {
        val repository = createRepository(tempDir)
        val doc = Document.builder()
            .text("The capital of France is Paris.")
            .metadata(mapOf("source" to "geography.txt", "chunk_index" to 0))
            .build()
        repository.add(listOf(doc))

        var capturedPrompt: Prompt? = null
        val capturingChatModel: ChatModel = ChatModel { prompt ->
            capturedPrompt = prompt
            ChatResponse(listOf(Generation(AssistantMessage("Paris"))))
        }

        val pipeline = createPipeline(repository, capturingChatModel)
        val query = RagQuery(
            question = "What is the capital of France?",
            topK = 5,
            systemPrompt = "",
            modelOverride = null,
            conversationHistory = emptyList()
        )
        pipeline.query(query)

        val messages = capturedPrompt!!.instructions
        assertThat(messages).hasSize(2)
        assertThat(messages[0]).isInstanceOf(SystemMessage::class.java)
        assertThat(messages[1]).isInstanceOf(UserMessage::class.java)
    }

    @Test
    fun `with one history turn prompt has four messages in correct order and content`(@TempDir tempDir: Path) {
        val repository = createRepository(tempDir)
        val doc = Document.builder()
            .text("The capital of France is Paris.")
            .metadata(mapOf("source" to "geography.txt", "chunk_index" to 0))
            .build()
        repository.add(listOf(doc))

        var capturedPrompt: Prompt? = null
        val capturingChatModel: ChatModel = ChatModel { prompt ->
            capturedPrompt = prompt
            ChatResponse(listOf(Generation(AssistantMessage("Paris"))))
        }

        val pipeline = createPipeline(repository, capturingChatModel)
        val history = listOf(ConversationTurn(userQuestion = "What is 1+1?", assistantAnswer = "2"))
        val query = RagQuery(
            question = "What is the capital of France?",
            topK = 5,
            systemPrompt = "",
            modelOverride = null,
            conversationHistory = history
        )
        pipeline.query(query)

        val messages = capturedPrompt!!.instructions
        assertThat(messages).hasSize(4)
        assertThat(messages[0]).isInstanceOf(SystemMessage::class.java)
        assertThat(messages[1]).isInstanceOf(UserMessage::class.java)
        assertThat((messages[1] as UserMessage).text).isEqualTo("What is 1+1?")
        assertThat(messages[2]).isInstanceOf(AssistantMessage::class.java)
        assertThat((messages[2] as AssistantMessage).text).isEqualTo("2")
        assertThat(messages[3]).isInstanceOf(UserMessage::class.java)
        assertThat((messages[3] as UserMessage).text).contains("What is the capital of France?")
        assertThat((messages[3] as UserMessage).text).contains("<document source=")
    }

    @Test
    fun `with two history turns prompt has six messages in correct order`(@TempDir tempDir: Path) {
        val repository = createRepository(tempDir)
        val doc = Document.builder()
            .text("Content.")
            .metadata(mapOf("source" to "test.txt", "chunk_index" to 0))
            .build()
        repository.add(listOf(doc))

        var capturedPrompt: Prompt? = null
        val capturingChatModel: ChatModel = ChatModel { prompt ->
            capturedPrompt = prompt
            ChatResponse(listOf(Generation(AssistantMessage("answer"))))
        }

        val pipeline = createPipeline(repository, capturingChatModel)
        val history = listOf(
            ConversationTurn(userQuestion = "Q1", assistantAnswer = "A1"),
            ConversationTurn(userQuestion = "Q2", assistantAnswer = "A2"),
        )
        val query = RagQuery(
            question = "Q3",
            topK = 5,
            systemPrompt = "",
            modelOverride = null,
            conversationHistory = history
        )
        pipeline.query(query)

        val messages = capturedPrompt!!.instructions
        assertThat(messages).hasSize(6)
        assertThat(messages[0]).isInstanceOf(SystemMessage::class.java)
        assertThat(messages[1]).isInstanceOf(UserMessage::class.java)
        assertThat((messages[1] as UserMessage).text).isEqualTo("Q1")
        assertThat(messages[2]).isInstanceOf(AssistantMessage::class.java)
        assertThat((messages[2] as AssistantMessage).text).isEqualTo("A1")
        assertThat(messages[3]).isInstanceOf(UserMessage::class.java)
        assertThat((messages[3] as UserMessage).text).isEqualTo("Q2")
        assertThat(messages[4]).isInstanceOf(AssistantMessage::class.java)
        assertThat((messages[4] as AssistantMessage).text).isEqualTo("A2")
        assertThat(messages[5]).isInstanceOf(UserMessage::class.java)
    }

    @Test
    fun `history UserMessage contains only prior question text without RAG context documents`(@TempDir tempDir: Path) {
        val repository = createRepository(tempDir)
        val doc = Document.builder()
            .text("The capital of France is Paris.")
            .metadata(mapOf("source" to "geography.txt", "chunk_index" to 0))
            .build()
        repository.add(listOf(doc))

        var capturedPrompt: Prompt? = null
        val capturingChatModel: ChatModel = ChatModel { prompt ->
            capturedPrompt = prompt
            ChatResponse(listOf(Generation(AssistantMessage("answer"))))
        }

        val pipeline = createPipeline(repository, capturingChatModel)
        val history = listOf(ConversationTurn(userQuestion = "What is 1+1?", assistantAnswer = "2"))
        val query = RagQuery(
            question = "Tell me more",
            topK = 5,
            systemPrompt = "",
            modelOverride = null,
            conversationHistory = history
        )
        pipeline.query(query)

        val messages = capturedPrompt!!.instructions
        val historyUserMessage = messages[1] as UserMessage
        assertThat(historyUserMessage.text).isEqualTo("What is 1+1?")
        assertThat(historyUserMessage.text).doesNotContain("<document")
    }

    @Test
    fun `default system prompt contains multi-turn acknowledgement sentence`() {
        assertThat(RagPipeline.DEFAULT_RAG_SYSTEM_PROMPT).contains("conversation history")
    }
}
