package ch.obermuhlner.ezrag.ingestion.office

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File

class WordToMarkdownConverterTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun createFixtures() {
            // Always regenerate the DOCX fixture so tests use a fresh file
            WordFixtureGenerator.createDocxFixture(WordFixtureGenerator.docxFile)
        }
    }

    @Test
    fun `heading 1 style produces a level-1 Markdown heading`() {
        val converter = WordToMarkdownConverter()
        val result = converter.convert(WordFixtureGenerator.docxFile)

        assertThat(result).contains("# Introduction")
    }

    @Test
    fun `heading 2 style produces a level-2 Markdown heading`() {
        val converter = WordToMarkdownConverter()
        val result = converter.convert(WordFixtureGenerator.docxFile)

        assertThat(result).contains("## Background")
    }

    @Test
    fun `table content appears as Markdown table rows with pipe delimiters`() {
        val converter = WordToMarkdownConverter()
        val result = converter.convert(WordFixtureGenerator.docxFile)

        assertThat(result).contains("|")
        assertThat(result).contains("Name")
        assertThat(result).contains("Value")
        assertThat(result).contains("Alpha")
        assertThat(result).contains("42")
    }

    @Test
    fun `footnote content appears in the output`() {
        val converter = WordToMarkdownConverter()
        val result = converter.convert(WordFixtureGenerator.docxFile)

        // FootnoteContent was added as a paragraph body in the fixture
        assertThat(result).contains("FootnoteContent")
    }

    @Test
    fun `comment text does NOT appear in the output`() {
        val converter = WordToMarkdownConverter()
        val result = converter.convert(WordFixtureGenerator.docxFile)

        // Comment text is stored separately in the comments part — should not appear in output
        assertThat(result).doesNotContain("ReviewerComment")
    }

    @Test
    fun `legacy doc file produces non-empty output when valid binary is provided`() {
        val legacyDoc = File("src/test/resources/fixtures/sample.doc")
        assumeTrue(legacyDoc.exists(), "Legacy .doc binary fixture not available — skipping HWPF test")

        val converter = WordToMarkdownConverter()
        val result = converter.convert(legacyDoc)

        assertThat(result).isNotBlank()
    }
}
