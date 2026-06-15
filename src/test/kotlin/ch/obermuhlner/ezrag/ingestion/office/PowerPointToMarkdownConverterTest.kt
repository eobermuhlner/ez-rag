package ch.obermuhlner.ezrag.ingestion.office

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File

class PowerPointToMarkdownConverterTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun createFixtures() {
            PowerPointFixtureGenerator.createPptxFixture(PowerPointFixtureGenerator.pptxFile)
        }
    }

    @Test
    fun `slide 1 title produces a level-2 Markdown heading with slide number`() {
        val converter = PowerPointToMarkdownConverter()
        val result = converter.convert(PowerPointFixtureGenerator.pptxFile)

        assertThat(result).contains("## Slide 1: Introduction")
    }

    @Test
    fun `slide 2 title produces a level-2 Markdown heading with slide number`() {
        val converter = PowerPointToMarkdownConverter()
        val result = converter.convert(PowerPointFixtureGenerator.pptxFile)

        assertThat(result).contains("## Slide 2: Summary")
    }

    @Test
    fun `body text from slides appears in converter output`() {
        val converter = PowerPointToMarkdownConverter()
        val result = converter.convert(PowerPointFixtureGenerator.pptxFile)

        assertThat(result).contains("introduction slide body text")
        assertThat(result).contains("summary slide body text")
    }

    @Test
    fun `speaker notes text appears in converter output`() {
        val converter = PowerPointToMarkdownConverter()
        val result = converter.convert(PowerPointFixtureGenerator.pptxFile)

        assertThat(result).contains("SpeakerNotes")
    }

    @Test
    fun `legacy ppt file produces non-empty output when valid binary is provided`() {
        val legacyPpt = File("src/test/resources/fixtures/sample.ppt")
        assumeTrue(legacyPpt.exists(), "Legacy .ppt binary fixture not available — skipping HSLF test")

        val converter = PowerPointToMarkdownConverter()
        val result = converter.convert(legacyPpt)

        assertThat(result).isNotBlank()
    }
}
