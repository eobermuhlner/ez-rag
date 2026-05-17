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
class EvalHardNegativesScenarioTest {

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
    fun `hard-negatives scenario reports non-null hardNegativeMetrics`(@TempDir tempDir: Path) {
        val scenarioDir = Paths.get(
            javaClass.getResource("/eval/hard-negatives/questions.yaml")!!.toURI()
        ).parent

        val loader = EvalCorpusLoader()
        val scenario = loader.load(scenarioDir)

        assertThat(scenario.questions).isNotEmpty()
        val hardNegativeDocs = scenario.documents.filter { it.role == EvalDocumentRole.HARD_NEGATIVE }
        assertThat(hardNegativeDocs).isNotEmpty()

        val engine = EvalEngine()
        val results = engine.evaluate(scenario, tempDir, embeddingModel)

        assertThat(results).hasSize(scenario.questions.size)

        val hardNegativeFilenames = hardNegativeDocs
            .map { it.path.fileName.toString() }
            .toSet()

        val calculator = EvalMetricsCalculator()
        val metrics = calculator.calculate(results, k = 3, hardNegativeSourceFilenames = hardNegativeFilenames)

        // The scenario has a question targeting a hard-negative document,
        // so hardNegativeMetrics must be non-null
        assertThat(metrics.hardNegativeMetrics)
            .describedAs("hardNegativeMetrics should be non-null when questions target hard-negative docs")
            .isNotNull()

        // All metrics should be valid doubles in [0, 1]
        assertThat(metrics.recallAtK).isBetween(0.0, 1.0)
        assertThat(metrics.mrr).isBetween(0.0, 1.0)
        assertThat(metrics.hitRateAtK).isBetween(0.0, 1.0)
    }
}
