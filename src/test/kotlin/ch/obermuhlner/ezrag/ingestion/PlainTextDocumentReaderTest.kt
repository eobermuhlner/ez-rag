package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PlainTextDocumentReaderTest {

    @Test
    fun `a txt file with known content produces at least one chunk whose text contains that content`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("sample.txt").toFile()
        file.writeText("Hello from plain text reader test.")

        val reader = PlainTextDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        assertThat(documents).isNotEmpty()
        val allText = documents.joinToString(" ") { it.text ?: "" }
        assertThat(allText).contains("Hello from plain text reader test.")
    }

    @Test
    fun `no chunk has source in its metadata`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("sample.txt").toFile()
        file.writeText("Some content for the reader.")

        val reader = PlainTextDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        assertThat(documents).isNotEmpty()
        documents.forEach { doc ->
            assertThat(doc.metadata).doesNotContainKey("source")
        }
    }
}
