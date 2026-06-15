package ch.obermuhlner.ezrag.ingestion

import ch.obermuhlner.ezrag.ingestion.office.PowerPointFixtureGenerator
import ch.obermuhlner.ezrag.ingestion.office.WordToMarkdownConverter
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import java.nio.file.Paths

class DocumentReaderRegistryTest {

    @Test
    fun `supports returns true for txt, pdf, md, docx, doc, pptx, ppt, xlsx, and xls`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)

        assertThat(registry.supports("txt")).isTrue()
        assertThat(registry.supports("pdf")).isTrue()
        assertThat(registry.supports("md")).isTrue()
        assertThat(registry.supports("docx")).isTrue()
        assertThat(registry.supports("doc")).isTrue()
        assertThat(registry.supports("pptx")).isTrue()
        assertThat(registry.supports("ppt")).isTrue()
        assertThat(registry.supports("xlsx")).isTrue()
        assertThat(registry.supports("xls")).isTrue()
    }

    @Test
    fun `supports returns false for unknown extensions`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)

        assertThat(registry.supports("csv")).isFalse()
        assertThat(registry.supports("odt")).isFalse()
    }

    @Test
    fun `read dispatches txt file and produces at least one chunk`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("sample.txt").toFile()
        file.writeText("Hello plain text content for registry dispatch test.")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `read dispatches md file and produces at least one chunk`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("sample.md").toFile()
        file.writeText("Hello markdown content for registry dispatch test.")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `read dispatches pdf file and produces at least one chunk`() {
        val path = Paths.get(javaClass.getResource("/documents/sample.pdf")!!.toURI())
        val file = path.toFile()

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `supports returns true for docx`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        assertThat(registry.supports("docx")).isTrue()
    }

    @Test
    fun `read dispatches docx file and produces at least one chunk`(@TempDir tempDir: Path) {
        // Create a minimal DOCX fixture inline for this test
        val docxFile = tempDir.resolve("test.docx").toFile()
        XWPFDocument().use { doc ->
            val h1 = doc.createParagraph()
            h1.style = "Heading1"
            h1.createRun().setText("Test Heading")
            val para = doc.createParagraph()
            para.createRun().setText("Test paragraph content for registry dispatch test.")
            FileOutputStream(docxFile).use { fos -> doc.write(fos) }
        }

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(docxFile)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `supports returns true for xlsx`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        assertThat(registry.supports("xlsx")).isTrue()
    }

    @Test
    fun `read dispatches xlsx file and produces at least one chunk`() {
        ch.obermuhlner.ezrag.ingestion.office.ExcelFixtureGenerator.createXlsxFixture(
            ch.obermuhlner.ezrag.ingestion.office.ExcelFixtureGenerator.xlsxFile
        )
        val xlsxFile = ch.obermuhlner.ezrag.ingestion.office.ExcelFixtureGenerator.xlsxFile

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(xlsxFile)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `read throws IllegalArgumentException for unsupported extension`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("data.csv").toFile()
        file.writeText("col1,col2\nval1,val2")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)

        assertThatThrownBy { registry.read(file) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("csv")
    }

    @Test
    fun `supports returns true for pptx`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        assertThat(registry.supports("pptx")).isTrue()
    }

    @Test
    fun `read dispatches pptx file and produces at least one chunk`() {
        PowerPointFixtureGenerator.createPptxFixture(PowerPointFixtureGenerator.pptxFile)
        val pptxFile = PowerPointFixtureGenerator.pptxFile

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(pptxFile)

        assertThat(documents).isNotEmpty()
    }
}
