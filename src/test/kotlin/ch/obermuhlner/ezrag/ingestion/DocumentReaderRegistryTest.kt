package ch.obermuhlner.ezrag.ingestion

import ch.obermuhlner.ezrag.ingestion.office.PowerPointFixtureGenerator
import ch.obermuhlner.ezrag.ingestion.office.WordToMarkdownConverter
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import java.nio.file.Paths

class DocumentReaderRegistryTest {

    @Test
    fun `supports returns true for txt, pdf, md, docx, doc, pptx, ppt, xlsx, xls, html, htm, and rtf`() {
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
        assertThat(registry.supports("html")).isTrue()
        assertThat(registry.supports("htm")).isTrue()
        assertThat(registry.supports("rtf")).isTrue()
    }

    @Test
    fun `supports returns true for html`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        assertThat(registry.supports("html")).isTrue()
    }

    @Test
    fun `supports returns true for htm`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        assertThat(registry.supports("htm")).isTrue()
    }

    @Test
    fun `read dispatches html file and produces at least one chunk`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("sample.html").toFile()
        file.writeText("<html><head><title>Test</title></head><body><h2>Test Section</h2><p>Content here.</p></body></html>")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `read dispatches htm file and produces at least one chunk`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("sample.htm").toFile()
        file.writeText("<html><head><title>Test</title></head><body><h2>Test Section</h2><p>Content here.</p></body></html>")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `supports returns true for rtf`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        assertThat(registry.supports("rtf")).isTrue()
    }

    @Test
    fun `read dispatches rtf file and produces at least one chunk`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("sample.rtf").toFile()
        file.writeText("{\\rtf1\\ansi Hello world content for registry dispatch test.}")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `supports returns false for unknown extensions`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)

        assertThat(registry.supports("odt")).isFalse()
    }

    @Test
    fun `read falls back to plain text for unknown extension containing plain text`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("config.yaml").toFile()
        file.writeText("key: value\nother: data\n")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `read throws IllegalArgumentException for unknown extension file containing null byte`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("data.xyz").toFile()
        file.writeBytes(byteArrayOf(0x68, 0x00, 0x65, 0x6c, 0x6c, 0x6f))

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            registry.read(file)
        }
    }

    @Test
    fun `read falls back to plain text for file with no extension`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("Makefile").toFile()
        file.writeText("all:\n\techo hello\n")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `read throws IllegalArgumentException for no-extension file containing null byte`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("Dockerfile").toFile()
        file.writeBytes(byteArrayOf(0x46, 0x52, 0x4f, 0x4d, 0x00, 0x62, 0x61, 0x73, 0x65))

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            registry.read(file)
        }
    }

    @Test
    fun `supports returns true for csv`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        assertThat(registry.supports("csv")).isTrue()
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
    fun `read dispatches csv file and produces chunks`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("data.csv").toFile()
        file.writeText("col1,col2\nval1,val2\n")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
        val allText = documents.joinToString(" ") { it.text ?: "" }
        assertThat(allText).contains("col1")
        assertThat(allText).contains("val1")
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
