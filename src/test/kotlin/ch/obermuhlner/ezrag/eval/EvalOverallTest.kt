package ch.obermuhlner.ezrag.eval

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.transformers.TransformersEmbeddingModel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Tag("eval")
class EvalOverallTest {

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

        private val scenarioNames = listOf("factual", "multi-chunk", "hard-negatives")
    }

    @Test
    fun `overall Hit Rate@3 across all three scenarios exceeds 0_7`(@TempDir tempDir: Path) {
        val loader = EvalCorpusLoader()
        val engine = EvalEngine()
        val calculator = EvalMetricsCalculator()

        var totalQuestions = 0
        var totalHits = 0.0

        for (scenarioName in scenarioNames) {
            val resource = javaClass.getResource("/eval/$scenarioName/questions.yaml")
                ?: error("Could not find eval corpus for scenario: $scenarioName")
            val scenarioDir = Paths.get(resource.toURI()).parent

            val scenario = loader.load(scenarioDir)
            val storeDir = Files.createTempDirectory(tempDir, "eval-$scenarioName-")

            val results = engine.evaluate(scenario, storeDir, embeddingModel)
            val hardNegativeFilenames = scenario.documents
                .filter { it.role == EvalDocumentRole.HARD_NEGATIVE }
                .map { it.path.fileName.toString() }
                .toSet()
            val metrics = calculator.calculate(results, k = 3, hardNegativeSourceFilenames = hardNegativeFilenames)

            totalQuestions += scenario.questions.size
            totalHits += metrics.hitRateAtK * scenario.questions.size
        }

        assertThat(totalQuestions).isGreaterThan(0)

        val overallHitRate = totalHits / totalQuestions
        assertThat(overallHitRate)
            .describedAs("Overall weighted Hit Rate@3 across all three scenarios should exceed 0.7 (actual: %.2f)".format(overallHitRate))
            .isGreaterThan(0.7)
    }
}
