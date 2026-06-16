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
            ExcelFixtureGenerator.createXlsxManyRowsFixture(ExcelFixtureGenerator.xlsxManyRowsFile)
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

    // ---- New row-chunking tests using ExcelDocumentReader ----

    @Test
    fun `many-row sheet produces more than one chunk with small chunkSize`() {
        // chunkSize=100 is small enough to force multiple chunks for 50 rows
        val reader = ExcelDocumentReader(ExcelFixtureGenerator.xlsxManyRowsFile, chunkSize = 100)
        val docs = reader.read()

        assertThat(docs.size).isGreaterThan(1)
    }

    @Test
    fun `every chunk contains the sheet header column names`() {
        val reader = ExcelDocumentReader(ExcelFixtureGenerator.xlsxManyRowsFile, chunkSize = 100)
        val docs = reader.read()

        docs.forEach { doc ->
            val text = doc.text ?: ""
            assertThat(text).contains("ID")
            assertThat(text).contains("Name")
            assertThat(text).contains("Description")
        }
    }

    @Test
    fun `no data row is split across two chunks`() {
        val reader = ExcelDocumentReader(ExcelFixtureGenerator.xlsxManyRowsFile, chunkSize = 100)
        val docs = reader.read()

        // Each row has a unique "Description for item number <N> in the large fixture" phrase;
        // verify each appears in exactly one chunk
        for (i in 1..50) {
            val label = "Description for item number $i in the large fixture"
            val chunksContaining = docs.count { (it.text ?: "").contains(label) }
            assertThat(chunksContaining)
                .withFailMessage("Row description for item $i appeared in $chunksContaining chunks (expected 1)")
                .isEqualTo(1)
        }
    }

    @Test
    fun `no row appears in more than one chunk (non-overlapping)`() {
        val reader = ExcelDocumentReader(ExcelFixtureGenerator.xlsxManyRowsFile, chunkSize = 100)
        val docs = reader.read()

        // Use the unique description phrase to identify each row unambiguously
        for (i in 1..50) {
            val uniquePhrase = "Description for item number $i in the large fixture"
            val chunksContaining = docs.count { (it.text ?: "").contains(uniquePhrase) }
            assertThat(chunksContaining)
                .withFailMessage("Row for item $i appeared in $chunksContaining chunks (expected 1)")
                .isEqualTo(1)
        }
    }

    @Test
    fun `every chunk text begins with sheet name heading`() {
        val reader = ExcelDocumentReader(ExcelFixtureGenerator.xlsxManyRowsFile, chunkSize = 100)
        val docs = reader.read()

        docs.forEach { doc ->
            assertThat(doc.text ?: "").startsWith("## Data")
        }
    }
}
