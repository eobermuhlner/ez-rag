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
class EvalFactualScenarioTest {

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
    fun `factual scenario achieves Recall@3 above threshold`(@TempDir tempDir: Path) {
        val scenarioDir = Paths.get(
            javaClass.getResource("/eval/factual/questions.yaml")!!.toURI()
        ).parent

        val loader = EvalCorpusLoader()
        val scenario = loader.load(scenarioDir)

        assertThat(scenario.questions).isNotEmpty()
        assertThat(scenario.documents).isNotEmpty()

        val engine = EvalEngine()
        val results = engine.evaluate(scenario, tempDir, embeddingModel)

        val hardNegativeFilenames = scenario.documents
            .filter { it.role == EvalDocumentRole.HARD_NEGATIVE }
            .map { it.path.fileName.toString() }
            .toSet()

        val calculator = EvalMetricsCalculator()
        val metrics = calculator.calculate(results, k = 3, hardNegativeSourceFilenames = hardNegativeFilenames)

        assertThat(metrics.recallAtK)
            .describedAs("factual scenario Recall@3 should be >= 0.85")
            .isGreaterThanOrEqualTo(0.85)

        assertThat(metrics.hitRateAtK)
            .describedAs("factual scenario Hit Rate@3 should be >= 0.85")
            .isGreaterThanOrEqualTo(0.85)
    }
}
