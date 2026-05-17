package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Paths

class DocumentReaderRegistryTest {

    @Test
    fun `supports returns true for txt, pdf, and md`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)

        assertThat(registry.supports("txt")).isTrue()
        assertThat(registry.supports("pdf")).isTrue()
        assertThat(registry.supports("md")).isTrue()
    }

    @Test
    fun `supports returns false for unknown extensions`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)

        assertThat(registry.supports("docx")).isFalse()
        assertThat(registry.supports("csv")).isFalse()
        assertThat(registry.supports("xlsx")).isFalse()
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
    fun `read throws IllegalArgumentException for unsupported extension`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("data.docx").toFile()
        file.writeText("Some content")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)

        assertThatThrownBy { registry.read(file) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("docx")
    }
}
