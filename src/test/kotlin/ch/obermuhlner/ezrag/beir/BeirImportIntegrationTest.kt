package ch.obermuhlner.ezrag.beir

import ch.obermuhlner.ezrag.eval.EvalCorpusLoader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path

@Tag("integration")
class BeirImportIntegrationTest {

    @Test
    fun `download nfcorpus from CDN and verify questions yaml is parseable`(@TempDir tempDir: Path) {
        val targetDir = tempDir.resolve("nfcorpus").toFile()
        val downloader = BeirDownloader(outputWriter = PrintWriter(StringWriter()))
        downloader.download("nfcorpus", targetDir, force = false)

        assertThat(targetDir.resolve("corpus.jsonl")).exists()

        val reader = BeirCorpusReader()
        val data = reader.readCorpus(targetDir.toPath())
        val config = ConversionConfig(maxQuestions = 10, maxDistractors = 5)
        val converter = BeirCorpusConverter()
        converter.convert(data, config, targetDir.toPath())

        assertThat(targetDir.resolve("questions.yaml")).exists()
        val scenario = EvalCorpusLoader().load(targetDir.toPath())
        assertThat(scenario.questions).isNotEmpty
    }
}
