package ch.obermuhlner.ezrag.beir

import ch.obermuhlner.ezrag.eval.EvalCorpusLoader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class BeirCorpusConverterTest {

    private fun sampleData(): BeirCorpusData {
        val documents = listOf(
            BeirDocument("d1", "Title 1", "Body 1"),
            BeirDocument("d2", "Title 2", "Body 2"),
            BeirDocument("d3", "Title 3", "Body 3"),
            BeirDocument("d4", "Title 4", "Body 4"),
            BeirDocument("d5", "Title 5", "Body 5"),
        )
        val queries = mapOf(
            "q1" to "Query 1",
            "q2" to "Query 2",
        )
        val qrels = mapOf(
            "q1" to mapOf("d1" to 1, "d2" to 1),
            "q2" to mapOf("d3" to 1),
        )
        return BeirCorpusData(documents, queries, qrels)
    }

    @Test
    fun `converter writes txt files for relevant and distractor documents`(@TempDir dir: Path) {
        val data = sampleData()
        val config = ConversionConfig(maxQuestions = 10, maxDistractors = 2)
        BeirCorpusConverter().convert(data, config, dir)

        val txtFiles = dir.toFile().listFiles { f -> f.extension == "txt" }!!
        // 3 relevant docs (d1, d2, d3) + up to 2 distractors from d4, d5
        assertThat(txtFiles.size).isEqualTo(5)
    }

    @Test
    fun `converter writes correct number of files with bounded distractors`(@TempDir dir: Path) {
        val data = sampleData()
        val config = ConversionConfig(maxQuestions = 10, maxDistractors = 1)
        BeirCorpusConverter().convert(data, config, dir)

        val txtFiles = dir.toFile().listFiles { f -> f.extension == "txt" }!!
        // 3 relevant + min(1, 5-3) = 1 distractor
        assertThat(txtFiles.size).isEqualTo(4)
    }

    @Test
    fun `generated questions yaml is parseable by EvalCorpusLoader`(@TempDir dir: Path) {
        val data = sampleData()
        val config = ConversionConfig(maxQuestions = 10, maxDistractors = 2)
        BeirCorpusConverter().convert(data, config, dir)

        val scenario = EvalCorpusLoader().load(dir)
        assertThat(scenario.documents).isNotEmpty
        assertThat(scenario.questions).isNotEmpty
    }

    @Test
    fun `all filenames in documents block physically exist in output directory`(@TempDir dir: Path) {
        val data = sampleData()
        val config = ConversionConfig(maxQuestions = 10, maxDistractors = 2)
        BeirCorpusConverter().convert(data, config, dir)

        val scenario = EvalCorpusLoader().load(dir)
        for (doc in scenario.documents) {
            assertThat(doc.path.toFile()).exists()
        }
    }

    @Test
    fun `expected_sources contains only filenames without path separators`(@TempDir dir: Path) {
        val data = sampleData()
        val config = ConversionConfig(maxQuestions = 10, maxDistractors = 2)
        BeirCorpusConverter().convert(data, config, dir)

        val scenario = EvalCorpusLoader().load(dir)
        for (question in scenario.questions) {
            for (source in question.expectedSources) {
                assertThat(source).doesNotContain("/")
                assertThat(source).doesNotContain("\\")
            }
        }
    }

    @Test
    fun `thresholds block is absent when no threshold config given`(@TempDir dir: Path) {
        val data = sampleData()
        val config = ConversionConfig(maxQuestions = 10, maxDistractors = 2)
        BeirCorpusConverter().convert(data, config, dir)

        val scenario = EvalCorpusLoader().load(dir)
        assertThat(scenario.thresholds).isNull()
    }

    @Test
    fun `thresholds block contains recall_at_k and hit_rate_at_k when both configured`(@TempDir dir: Path) {
        val data = sampleData()
        val config = ConversionConfig(maxQuestions = 10, maxDistractors = 2, recallThreshold = 0.8, hitThreshold = 0.9)
        BeirCorpusConverter().convert(data, config, dir)

        val scenario = EvalCorpusLoader().load(dir)
        assertThat(scenario.thresholds).isNotNull
        assertThat(scenario.thresholds!!.recallAtK).isEqualTo(0.8)
        assertThat(scenario.thresholds!!.hitRateAtK).isEqualTo(0.9)
    }

    @Test
    fun `thresholds block has no mrr key when only recall and hit thresholds configured`(@TempDir dir: Path) {
        val data = sampleData()
        val config = ConversionConfig(maxQuestions = 10, maxDistractors = 2, recallThreshold = 0.8, hitThreshold = 0.9)
        BeirCorpusConverter().convert(data, config, dir)

        val scenario = EvalCorpusLoader().load(dir)
        assertThat(scenario.thresholds!!.mrr).isNull()
    }

    @Test
    fun `two runs with same seed produce identical questions yaml`(@TempDir dir1: Path, @TempDir dir2: Path) {
        val data = sampleData()
        val config = ConversionConfig(maxQuestions = 10, maxDistractors = 2, randomSeed = 42L)
        BeirCorpusConverter().convert(data, config, dir1)
        BeirCorpusConverter().convert(data, config, dir2)

        val yaml1 = dir1.resolve("questions.yaml").toFile().readText()
        val yaml2 = dir2.resolve("questions.yaml").toFile().readText()
        assertThat(yaml1).isEqualTo(yaml2)
    }

    @Test
    fun `documents block lists all written files with correct roles`(@TempDir dir: Path) {
        val data = sampleData()
        val config = ConversionConfig(maxQuestions = 10, maxDistractors = 2)
        BeirCorpusConverter().convert(data, config, dir)

        val scenario = EvalCorpusLoader().load(dir)
        val relevantDocs = scenario.documents.filter { it.role.name == "RELEVANT" }
        val distractorDocs = scenario.documents.filter { it.role.name == "DISTRACTOR" }
        assertThat(relevantDocs).hasSize(3)
        assertThat(distractorDocs).hasSize(2)
    }

    @Test
    fun `maxQuestions limits number of questions in output`(@TempDir dir: Path) {
        val data = sampleData()
        val config = ConversionConfig(maxQuestions = 1, maxDistractors = 2)
        BeirCorpusConverter().convert(data, config, dir)

        val scenario = EvalCorpusLoader().load(dir)
        assertThat(scenario.questions).hasSize(1)
    }

    @Test
    fun `document filename is derived from BEIR id with non-alphanumeric replaced by underscore`(@TempDir dir: Path) {
        val data = BeirCorpusData(
            documents = listOf(BeirDocument("doc-with-dashes/and.dots", "Title", "Body")),
            queries = mapOf("q1" to "Query"),
            qrels = mapOf("q1" to mapOf("doc-with-dashes/and.dots" to 1))
        )
        val config = ConversionConfig(maxQuestions = 10, maxDistractors = 0)
        BeirCorpusConverter().convert(data, config, dir)

        val txtFiles = dir.toFile().listFiles { f -> f.extension == "txt" }!!
        assertThat(txtFiles).hasSize(1)
        assertThat(txtFiles[0].name).matches("[a-zA-Z0-9_]+\\.txt")
        assertThat(txtFiles[0].name).doesNotContain("-").doesNotContain("/")
    }

    @Test
    fun `numeric query ids are written as strings and parseable by EvalCorpusLoader`(@TempDir dir: Path) {
        val data = BeirCorpusData(
            documents = listOf(BeirDocument("102904", "Title", "Body")),
            queries = mapOf("0" to "What is a good investment?"),
            qrels = mapOf("0" to mapOf("102904" to 1))
        )
        val config = ConversionConfig(maxQuestions = 10, maxDistractors = 0)
        BeirCorpusConverter().convert(data, config, dir)

        val scenario = EvalCorpusLoader().load(dir)
        assertThat(scenario.questions).hasSize(1)
        assertThat(scenario.questions[0].id).isEqualTo("0")
    }

    @Test
    fun `document txt file contains title and body`(@TempDir dir: Path) {
        val data = BeirCorpusData(
            documents = listOf(BeirDocument("d1", "My Title", "My Body Text")),
            queries = mapOf("q1" to "Query"),
            qrels = mapOf("q1" to mapOf("d1" to 1))
        )
        val config = ConversionConfig(maxQuestions = 10, maxDistractors = 0)
        BeirCorpusConverter().convert(data, config, dir)

        val txtFile = dir.toFile().listFiles { f -> f.extension == "txt" }!![0]
        val content = txtFile.readText()
        assertThat(content).contains("My Title")
        assertThat(content).contains("My Body Text")
    }
}
