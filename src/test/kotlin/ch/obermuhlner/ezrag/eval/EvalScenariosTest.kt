package ch.obermuhlner.ezrag.eval

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.ai.transformers.TransformersEmbeddingModel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Stream

@Tag("eval")
class EvalScenariosTest {

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

        @JvmStatic
        fun discoverScenarios(): Stream<org.junit.jupiter.params.provider.Arguments> {
            val corpusRoot = EvalScenariosTest::class.java.getResource("/eval/")
                ?: error("Could not find eval/ classpath resource root. Check that test resources are on the classpath.")
            val corpusPath = Paths.get(corpusRoot.toURI())

            val scenarios = Files.list(corpusPath)
                .filter { Files.isDirectory(it) && Files.exists(it.resolve("questions.yaml")) }
                .sorted()
                .toList()

            check(scenarios.isNotEmpty()) {
                "No eval scenario directories found under $corpusPath. Check that the corpus is on the test classpath."
            }

            return scenarios.map { org.junit.jupiter.params.provider.Arguments.of(it.fileName.toString(), it) }
                .stream()
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("discoverScenarios")
    fun `scenario meets thresholds`(scenarioName: String, scenarioDir: Path, @TempDir tempDir: Path) {
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

        // Assert hard-negative metrics are present when there are hard-negative documents
        val hasHardNegatives = scenario.documents.any { it.role == EvalDocumentRole.HARD_NEGATIVE }
        if (hasHardNegatives) {
            assertThat(metrics.hardNegativeMetrics)
                .describedAs("hardNegativeMetrics should be non-null for scenario '$scenarioName' which has HARD_NEGATIVE documents")
                .isNotNull()
        }

        // Assert thresholds if present in the scenario YAML
        scenario.thresholds?.recallAtK?.let { threshold ->
            assertThat(metrics.recallAtK)
                .describedAs("$scenarioName Recall@3 should be >= $threshold")
                .isGreaterThanOrEqualTo(threshold)
        }
        scenario.thresholds?.hitRateAtK?.let { threshold ->
            assertThat(metrics.hitRateAtK)
                .describedAs("$scenarioName Hit Rate@3 should be >= $threshold")
                .isGreaterThanOrEqualTo(threshold)
        }
        scenario.thresholds?.mrr?.let { threshold ->
            assertThat(metrics.mrr)
                .describedAs("$scenarioName MRR should be >= $threshold")
                .isGreaterThanOrEqualTo(threshold)
        }
    }

    @Test
    fun `aggregate weighted Hit Rate@3 across all scenarios exceeds 0_7`() {
        val loader = EvalCorpusLoader()
        val engine = EvalEngine()
        val calculator = EvalMetricsCalculator()

        val corpusRoot = javaClass.getResource("/eval/")
            ?: error("Could not find eval/ classpath resource root")
        val corpusPath = Paths.get(corpusRoot.toURI())

        val scenarioDirs = Files.list(corpusPath)
            .filter { Files.isDirectory(it) && Files.exists(it.resolve("questions.yaml")) }
            .sorted()
            .toList()

        check(scenarioDirs.isNotEmpty()) {
            "No eval scenario directories found under $corpusPath. Check that the corpus is on the test classpath."
        }

        var totalQuestions = 0
        var totalHits = 0.0

        for (scenarioDir in scenarioDirs) {
            val scenario = loader.load(scenarioDir)
            val storeDir = Files.createTempDirectory("eval-${scenarioDir.fileName}-")

            try {
                val results = engine.evaluate(scenario, storeDir, embeddingModel)
                val hardNegativeFilenames = scenario.documents
                    .filter { it.role == EvalDocumentRole.HARD_NEGATIVE }
                    .map { it.path.fileName.toString() }
                    .toSet()
                val metrics = calculator.calculate(results, k = 3, hardNegativeSourceFilenames = hardNegativeFilenames)

                totalQuestions += scenario.questions.size
                totalHits += metrics.hitRateAtK * scenario.questions.size
            } finally {
                storeDir.toFile().deleteRecursively()
            }
        }

        assertThat(totalQuestions).isGreaterThan(0)

        val overallHitRate = totalHits / totalQuestions
        assertThat(overallHitRate)
            .describedAs("Overall weighted Hit Rate@3 across all scenarios should exceed 0.7 (actual: %.2f)".format(overallHitRate))
            .isGreaterThan(0.7)
    }
}
