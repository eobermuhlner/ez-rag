package ch.obermuhlner.ezrag.eval

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class EvalCorpusLoaderTest {

    @Test
    fun `loads scenario with 3 documents and 5 questions from questions yaml`(@TempDir tempDir: Path) {
        // Create document files
        tempDir.resolve("doc1.txt").toFile().writeText("Document 1 content")
        tempDir.resolve("doc2.txt").toFile().writeText("Document 2 content")
        tempDir.resolve("doc3.txt").toFile().writeText("Document 3 content")

        val yaml = """
            documents:
              - file: doc1.txt
                role: relevant
              - file: doc2.txt
                role: distractor
              - file: doc3.txt
                role: hard-negative

            questions:
              - id: q1
                question: "What is the content of doc1?"
                expected_sources: ["doc1.txt"]
              - id: q2
                question: "What is in doc2?"
                expected_sources: ["doc2.txt"]
              - id: q3
                question: "Third question?"
                expected_sources: ["doc1.txt"]
              - id: q4
                question: "Fourth question?"
                expected_sources: ["doc1.txt"]
              - id: q5
                question: "Fifth question?"
                expected_sources: ["doc1.txt"]
        """.trimIndent()

        tempDir.resolve("questions.yaml").toFile().writeText(yaml)

        val loader = EvalCorpusLoader()
        val scenario = loader.load(tempDir)

        assertThat(scenario.documents).hasSize(3)
        assertThat(scenario.questions).hasSize(5)
    }

    @Test
    fun `document roles are loaded correctly`(@TempDir tempDir: Path) {
        tempDir.resolve("relevant.txt").toFile().writeText("Relevant content")
        tempDir.resolve("distractor.txt").toFile().writeText("Distractor content")
        tempDir.resolve("hard.txt").toFile().writeText("Hard negative content")

        val yaml = """
            documents:
              - file: relevant.txt
                role: relevant
              - file: distractor.txt
                role: distractor
              - file: hard.txt
                role: hard-negative

            questions:
              - id: q1
                question: "What?"
                expected_sources: ["relevant.txt"]
        """.trimIndent()

        tempDir.resolve("questions.yaml").toFile().writeText(yaml)

        val loader = EvalCorpusLoader()
        val scenario = loader.load(tempDir)

        val docs = scenario.documents
        assertThat(docs[0].role).isEqualTo(EvalDocumentRole.RELEVANT)
        assertThat(docs[1].role).isEqualTo(EvalDocumentRole.DISTRACTOR)
        assertThat(docs[2].role).isEqualTo(EvalDocumentRole.HARD_NEGATIVE)
    }

    @Test
    fun `document paths are resolved relative to the scenario directory`(@TempDir tempDir: Path) {
        tempDir.resolve("mydoc.txt").toFile().writeText("Content")

        val yaml = """
            documents:
              - file: mydoc.txt
                role: relevant

            questions:
              - id: q1
                question: "What?"
                expected_sources: ["mydoc.txt"]
        """.trimIndent()

        tempDir.resolve("questions.yaml").toFile().writeText(yaml)

        val loader = EvalCorpusLoader()
        val scenario = loader.load(tempDir)

        val docPath = scenario.documents[0].path
        assertThat(docPath).isEqualTo(tempDir.resolve("mydoc.txt"))
        // The path should be absolute (resolved)
        assertThat(docPath.isAbsolute).isTrue()
    }

    @Test
    fun `question ids and expected sources are loaded correctly`(@TempDir tempDir: Path) {
        tempDir.resolve("doc.txt").toFile().writeText("Content")

        val yaml = """
            documents:
              - file: doc.txt
                role: relevant

            questions:
              - id: q1
                question: "What is this?"
                expected_sources: ["doc.txt"]
              - id: q2
                question: "Another question?"
                expected_sources: ["doc.txt"]
        """.trimIndent()

        tempDir.resolve("questions.yaml").toFile().writeText(yaml)

        val loader = EvalCorpusLoader()
        val scenario = loader.load(tempDir)

        assertThat(scenario.questions[0].id).isEqualTo("q1")
        assertThat(scenario.questions[0].question).isEqualTo("What is this?")
        assertThat(scenario.questions[0].expectedSources).containsExactly("doc.txt")
        assertThat(scenario.questions[1].id).isEqualTo("q2")
    }

    @Test
    fun `scenario name is derived from the directory name`(@TempDir tempDir: Path) {
        tempDir.resolve("doc.txt").toFile().writeText("Content")
        val scenarioDir = tempDir.resolve("my-scenario")
        scenarioDir.toFile().mkdirs()
        scenarioDir.resolve("doc.txt").toFile().writeText("Content")

        val yaml = """
            documents:
              - file: doc.txt
                role: relevant

            questions:
              - id: q1
                question: "What?"
                expected_sources: ["doc.txt"]
        """.trimIndent()

        scenarioDir.resolve("questions.yaml").toFile().writeText(yaml)

        val loader = EvalCorpusLoader()
        val scenario = loader.load(scenarioDir)

        assertThat(scenario.name).isEqualTo("my-scenario")
    }

    @Test
    fun `thresholds are null when not present in yaml`(@TempDir tempDir: Path) {
        tempDir.resolve("doc.txt").toFile().writeText("Content")

        val yaml = """
            documents:
              - file: doc.txt
                role: relevant

            questions:
              - id: q1
                question: "What?"
                expected_sources: ["doc.txt"]
        """.trimIndent()

        tempDir.resolve("questions.yaml").toFile().writeText(yaml)

        val loader = EvalCorpusLoader()
        val scenario = loader.load(tempDir)

        assertThat(scenario.thresholds).isNull()
    }

    @Test
    fun `thresholds block is parsed into EvalThresholds with correct values`(@TempDir tempDir: Path) {
        tempDir.resolve("doc.txt").toFile().writeText("Content")

        val yaml = """
            documents:
              - file: doc.txt
                role: relevant

            questions:
              - id: q1
                question: "What?"
                expected_sources: ["doc.txt"]

            thresholds:
              recall_at_k: 0.8
              mrr: 0.6
              hit_rate_at_k: 0.9
        """.trimIndent()

        tempDir.resolve("questions.yaml").toFile().writeText(yaml)

        val loader = EvalCorpusLoader()
        val scenario = loader.load(tempDir)

        assertThat(scenario.thresholds).isNotNull()
        assertThat(scenario.thresholds!!.recallAtK).isEqualTo(0.8)
        assertThat(scenario.thresholds!!.mrr).isEqualTo(0.6)
        assertThat(scenario.thresholds!!.hitRateAtK).isEqualTo(0.9)
    }

    // --- Task 04: error handling ---

    @Test
    fun `expected_chunk_contains is parsed from yaml when present`(@TempDir tempDir: Path) {
        tempDir.resolve("doc.txt").toFile().writeText("Content")

        val yaml = """
            documents:
              - file: doc.txt
                role: relevant

            questions:
              - id: q1
                question: "What?"
                expected_sources: ["doc.txt"]
                expected_chunk_contains: ["specific phrase in the chunk"]
        """.trimIndent()

        tempDir.resolve("questions.yaml").toFile().writeText(yaml)

        val scenario = EvalCorpusLoader().load(tempDir)

        assertThat(scenario.questions[0].expectedChunkContains)
            .containsExactly("specific phrase in the chunk")
    }

    @Test
    fun `expected_chunk_contains defaults to empty list when absent from yaml`(@TempDir tempDir: Path) {
        tempDir.resolve("doc.txt").toFile().writeText("Content")

        val yaml = """
            documents:
              - file: doc.txt
                role: relevant

            questions:
              - id: q1
                question: "What?"
                expected_sources: ["doc.txt"]
        """.trimIndent()

        tempDir.resolve("questions.yaml").toFile().writeText(yaml)

        val scenario = EvalCorpusLoader().load(tempDir)

        assertThat(scenario.questions[0].expectedChunkContains).isEmpty()
    }

    @Test
    fun `EvalCorpusLoader throws descriptive exception when document file does not exist`(@TempDir tempDir: Path) {
        val yaml = """
            documents:
              - file: missing-file.txt
                role: relevant

            questions:
              - id: q1
                question: "What?"
                expected_sources: ["missing-file.txt"]
        """.trimIndent()

        tempDir.resolve("questions.yaml").toFile().writeText(yaml)

        val loader = EvalCorpusLoader()

        assertThatThrownBy { loader.load(tempDir) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("missing-file.txt")
    }
}
