package ch.obermuhlner.ezrag.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.ai.transformers.TransformersEmbeddingModel

@Tag("integration")
class OnnxEmbeddingIntegrationTest {

    @Test
    fun `ONNX all-MiniLM-L6-v2 embed returns 384-dimensional non-zero vector`() {
        val cacheDir = System.getProperty("user.home") + "/.ez-rag/models/"
        val model = TransformersEmbeddingModel()
        model.setResourceCacheDirectory(cacheDir)
        model.afterPropertiesSet()

        val embedding = model.embed("hello")

        assertThat(embedding).hasSize(384)
        assertThat(embedding.any { it != 0f }).isTrue()
    }
}
