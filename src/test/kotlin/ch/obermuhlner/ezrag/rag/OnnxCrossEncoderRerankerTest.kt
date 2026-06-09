package ch.obermuhlner.ezrag.rag

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

@Tag("integration")
class OnnxCrossEncoderRerankerTest {

    private val modelName = "cross-encoder/ms-marco-MiniLM-L-6-v2"
    private val cacheDir = System.getProperty("user.home") + "/.ez-rag/models/"

    @Test
    fun `relevant chunk scores higher than irrelevant chunk`() {
        val reranker = OnnxCrossEncoderReranker(modelName, cacheDir)

        val query = "What is the capital of France?"
        val relevant = ChunkMatch(path = "a.txt", chunkIndex = 0, score = 0.5, content = "Paris is the capital of France")
        val irrelevant = ChunkMatch(path = "b.txt", chunkIndex = 0, score = 0.9, content = "The weather in London is rainy")

        val reranked = reranker.rerank(query, listOf(relevant, irrelevant))

        assertThat(reranked).hasSize(2)
        assertThat(reranked[0].content).isEqualTo("Paris is the capital of France")
        assertThat(reranked[0].score).isGreaterThan(reranked[1].score)
    }

    @Test
    fun `model files are cached after first use`() {
        val reranker = OnnxCrossEncoderReranker(modelName, cacheDir)

        // Trigger model loading by running a rerank
        val query = "test query"
        val chunk = ChunkMatch(path = "a.txt", chunkIndex = 0, score = 0.5, content = "test content")
        reranker.rerank(query, listOf(chunk))

        val modelDir = File(cacheDir).resolve(modelName)
        assertThat(modelDir).isDirectory()

        val tokenizerFile = modelDir.resolve("tokenizer.json")
        assertThat(tokenizerFile).exists()

        // Check that either onnx/model.onnx or model.onnx exists
        val onnxSubDirModel = modelDir.resolve("onnx/model.onnx")
        val directModel = modelDir.resolve("model.onnx")
        assertThat(onnxSubDirModel.exists() || directModel.exists())
            .withFailMessage("Expected model.onnx to exist at either ${onnxSubDirModel} or ${directModel}")
            .isTrue()
    }
}
