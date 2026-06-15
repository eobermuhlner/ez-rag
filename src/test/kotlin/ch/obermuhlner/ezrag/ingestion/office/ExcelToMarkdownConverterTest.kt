package ch.obermuhlner.ezrag.ingestion.office

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File

class ExcelToMarkdownConverterTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun createFixtures() {
            ExcelFixtureGenerator.createXlsxFixture(ExcelFixtureGenerator.xlsxFile)
        }
    }

    @Test
    fun `sheet Summary heading appears in converter output`() {
        val converter = ExcelToMarkdownConverter()
        val result = converter.convert(ExcelFixtureGenerator.xlsxFile)

        assertThat(result).contains("## Summary")
    }

    @Test
    fun `sheet Details heading appears in converter output`() {
        val converter = ExcelToMarkdownConverter()
        val result = converter.convert(ExcelFixtureGenerator.xlsxFile)

        assertThat(result).contains("## Details")
    }

    @Test
    fun `Markdown header separator row appears in converter output`() {
        val converter = ExcelToMarkdownConverter()
        val result = converter.convert(ExcelFixtureGenerator.xlsxFile)

        assertThat(result).contains("| --- |")
    }

    @Test
    fun `Summary sheet header columns appear as Markdown table header`() {
        val converter = ExcelToMarkdownConverter()
        val result = converter.convert(ExcelFixtureGenerator.xlsxFile)

        assertThat(result).contains("Name")
        assertThat(result).contains("Value")
    }

    @Test
    fun `Details sheet header columns appear as Markdown table header`() {
        val converter = ExcelToMarkdownConverter()
        val result = converter.convert(ExcelFixtureGenerator.xlsxFile)

        assertThat(result).contains("Item")
        assertThat(result).contains("Count")
        assertThat(result).contains("Notes")
    }

    @Test
    fun `cell values from Summary data rows appear in converter output`() {
        val converter = ExcelToMarkdownConverter()
        val result = converter.convert(ExcelFixtureGenerator.xlsxFile)

        assertThat(result).contains("Alpha")
        assertThat(result).contains("100")
        assertThat(result).contains("Beta")
        assertThat(result).contains("200")
    }

    @Test
    fun `cell values from Details data rows appear in converter output`() {
        val converter = ExcelToMarkdownConverter()
        val result = converter.convert(ExcelFixtureGenerator.xlsxFile)

        assertThat(result).contains("Widget")
        assertThat(result).contains("42")
        assertThat(result).contains("Gadget")
        assertThat(result).contains("First item")
        assertThat(result).contains("Second item")
    }

    @Test
    fun `legacy xls file produces non-empty output when valid binary is provided`() {
        val legacyXls = File("src/test/resources/fixtures/sample.xls")
        assumeTrue(legacyXls.exists(), "Legacy .xls binary fixture not available — skipping HSSF test")

        val converter = ExcelToMarkdownConverter()
        val result = converter.convert(legacyXls)

        assertThat(result).isNotBlank()
    }
}
