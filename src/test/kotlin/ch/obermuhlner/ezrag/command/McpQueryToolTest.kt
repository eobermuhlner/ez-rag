package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.VectorStoreRepository
import ch.obermuhlner.ezrag.rag.EmbeddingSearchPipeline
import ch.obermuhlner.ezrag.rag.RagPipeline
import ch.obermuhlner.ezrag.rag.RagQuery
import ch.obermuhlner.ezrag.rag.RagResult
import ch.obermuhlner.ezrag.rag.SourceReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import java.nio.file.Path

class McpQueryToolTest {

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

    private val stubChatModel: ChatModel = ChatModel { _ ->
        ChatResponse(listOf(Generation(AssistantMessage("Stub answer"))))
    }

    private fun stubPipeline(
        tempDir: Path,
        capturedQueries: MutableList<RagQuery> = mutableListOf(),
        resultToReturn: RagResult = RagResult(answer = "answer", sources = emptyList()),
        throwException: Exception? = null
    ): RagPipeline {
        val repo = VectorStoreRepository(fakeEmbeddingModel, tempDir.resolve("store.json"))
        repo.load()
        val searchPipeline = EmbeddingSearchPipeline(repo, fakeEmbeddingModel)
        return object : RagPipeline(searchPipeline, stubChatModel) {
            override fun query(ragQuery: RagQuery): RagResult {
                capturedQueries.add(ragQuery)
                if (throwException != null) throw throwException
                return resultToReturn
            }
        }
    }

    @Test
    fun `query with only question uses default topK and no model override`(@TempDir tempDir: Path) {
        val capturedQueries = mutableListOf<RagQuery>()
        val pipeline = stubPipeline(tempDir, capturedQueries)

        val tool = McpQueryTool(pipeline)
        val result = tool.query("What is RAG?", null, null, null)

        assertThat(capturedQueries).hasSize(1)
        assertThat(capturedQueries[0].question).isEqualTo("What is RAG?")
        assertThat(capturedQueries[0].topK).isEqualTo(5)
        assertThat(capturedQueries[0].modelOverride).isNull()
        assertThat(result.error).isNull()
    }

    @Test
    fun `query with explicit topK and model forwards those values to RagQuery`(@TempDir tempDir: Path) {
        val capturedQueries = mutableListOf<RagQuery>()
        val pipeline = stubPipeline(tempDir, capturedQueries)

        val tool = McpQueryTool(pipeline)
        tool.query("What is RAG?", 10, null, "claude-3-5-sonnet")

        assertThat(capturedQueries).hasSize(1)
        assertThat(capturedQueries[0].topK).isEqualTo(10)
        assertThat(capturedQueries[0].modelOverride).isEqualTo("claude-3-5-sonnet")
    }

    @Test
    fun `query returns answer and sources from RagResult`(@TempDir tempDir: Path) {
        val sources = listOf(
            SourceReference(filePath = "doc.txt", chunkIndex = 0, similarityScore = 0.9, excerpt = "Some excerpt")
        )
        val pipeline = stubPipeline(tempDir, resultToReturn = RagResult("The answer", sources))

        val tool = McpQueryTool(pipeline)
        val result = tool.query("question", null, null, null)

        assertThat(result.answer).isEqualTo("The answer")
        assertThat(result.sources).hasSize(1)
        assertThat(result.sources[0].filePath).isEqualTo("doc.txt")
        assertThat(result.sources[0].chunkIndex).isEqualTo(0)
        assertThat(result.sources[0].similarityScore).isEqualTo(0.9)
        assertThat(result.sources[0].excerpt).isEqualTo("Some excerpt")
        assertThat(result.error).isNull()
    }

    @Test
    fun `query returns structured error response when pipeline throws exception`(@TempDir tempDir: Path) {
        val pipeline = stubPipeline(tempDir, throwException = RuntimeException("Query failed catastrophically"))

        val tool = McpQueryTool(pipeline)
        val result = tool.query("question", null, null, null)

        assertThat(result.error).isNotNull()
        assertThat(result.error).contains("Query failed catastrophically")
        assertThat(result.answer).isEmpty()
        assertThat(result.sources).isEmpty()
    }
}
