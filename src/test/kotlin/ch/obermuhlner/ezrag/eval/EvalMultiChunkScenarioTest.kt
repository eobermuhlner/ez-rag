package ch.obermuhlner.ezrag.eval

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.transformers.TransformersEmbeddingModel
import java.nio.file.Path
import java.nio.file.Paths

@Tag("eval")
class EvalMultiChunkScenarioTest {

    companion object {
        private lateinit var embeddingModel: TransformersEmbeddingModel

        @JvmStatic
        @BeforeAll
        fun setUpModel() {
            val cacheDir = System.getProperty("user.home") + "/.ez-rag/models/"
            embeddingModel = TransformersEmbeddingModel()
            embeddingModel.setResourceCacheDirectory(cacheDir)
            embeddingModel.afterPropertiesSet()
        }
    }

    @Test
    fun `multi-chunk scenario runs without error and reports numeric metrics`(@TempDir tempDir: Path) {
        val scenarioDir = Paths.get(
            javaClass.getResource("/eval/multi-chunk/questions.yaml")!!.toURI()
        ).parent

        val loader = EvalCorpusLoader()
        val scenario = loader.load(scenarioDir)

        assertThat(scenario.questions).isNotEmpty()
        assertThat(scenario.documents).isNotEmpty()

        val engine = EvalEngine()
        val results = engine.evaluate(scenario, tempDir, embeddingModel)

        assertThat(results).hasSize(scenario.questions.size)

        val calculator = EvalMetricsCalculator()
        val metrics = calculator.calculate(results, k = 3)

        // No threshold assertions — just verify numeric values are reported
        assertThat(metrics.recallAtK).isBetween(0.0, 1.0)
        assertThat(metrics.mrr).isBetween(0.0, 1.0)
        assertThat(metrics.hitRateAtK).isBetween(0.0, 1.0)
    }
}
