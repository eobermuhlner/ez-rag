package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class RtfDocumentReaderTest {

    @Test
    fun `minimal RTF file produces at least one chunk containing the plain text words`(@TempDir tempDir: Path) {
        val rtfFile = tempDir.resolve("sample.rtf").toFile()
        rtfFile.writeText("{\\rtf1\\ansi Hello world. Second sentence here.}")

        val reader = RtfDocumentReader(rtfFile, chunkSize = 1000, chunkOverlap = 200)
        val docs = reader.read()

        assertThat(docs).isNotEmpty()
        val allText = docs.joinToString(" ") { it.text ?: "" }
        assertThat(allText).contains("Hello")
        assertThat(allText).contains("world")
        assertThat(allText).contains("Second")
        assertThat(allText).contains("sentence")
    }

    @Test
    fun `RTF chunk text does not contain RTF control codes`(@TempDir tempDir: Path) {
        val rtfFile = tempDir.resolve("sample.rtf").toFile()
        rtfFile.writeText("{\\rtf1\\ansi\\deff0 {\\fonttbl{\\f0\\froman Serif;}} \\f0 Hello world content here.}")

        val reader = RtfDocumentReader(rtfFile, chunkSize = 1000, chunkOverlap = 200)
        val docs = reader.read()

        assertThat(docs).isNotEmpty()
        val allText = docs.joinToString(" ") { it.text ?: "" }
        // Should not contain RTF control codes (backslash sequences)
        assertThat(allText).doesNotContain("\\rtf1")
        assertThat(allText).doesNotContain("\\ansi")
        assertThat(allText).doesNotContain("\\fonttbl")
        assertThat(allText).doesNotContain("\\f0")
    }

    @Test
    fun `RTF file with multiple paragraphs produces chunks with the text content`(@TempDir tempDir: Path) {
        val rtfFile = tempDir.resolve("paragraphs.rtf").toFile()
        rtfFile.writeText(
            "{\\rtf1\\ansi " +
            "First paragraph content here.\\par " +
            "Second paragraph follows.\\par " +
            "Third paragraph concludes.}"
        )

        val reader = RtfDocumentReader(rtfFile, chunkSize = 1000, chunkOverlap = 200)
        val docs = reader.read()

        assertThat(docs).isNotEmpty()
        val allText = docs.joinToString(" ") { it.text ?: "" }
        assertThat(allText).contains("First")
        assertThat(allText).contains("Second")
        assertThat(allText).contains("Third")
    }

    @Test
    fun `no chunk has source key in metadata`(@TempDir tempDir: Path) {
        val rtfFile = tempDir.resolve("sample.rtf").toFile()
        rtfFile.writeText("{\\rtf1\\ansi Some text content for metadata test.}")

        val reader = RtfDocumentReader(rtfFile, chunkSize = 1000, chunkOverlap = 200)
        val docs = reader.read()

        assertThat(docs).isNotEmpty()
        docs.forEach { doc ->
            assertThat(doc.metadata).doesNotContainKey("source")
        }
    }
}
