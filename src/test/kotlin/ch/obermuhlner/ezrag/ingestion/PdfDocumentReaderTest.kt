package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class PdfDocumentReaderTest {

    @Test
    fun `a pdf file produces at least one non-empty chunk`() {
        val path = Paths.get(javaClass.getResource("/documents/sample.pdf")!!.toURI())
        val file = path.toFile()

        val reader = PdfDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        assertThat(documents).isNotEmpty()
        documents.forEach { doc ->
            assertThat(doc.text).isNotBlank()
        }
    }

    @Test
    fun `no chunk has source in its metadata`() {
        val path = Paths.get(javaClass.getResource("/documents/sample.pdf")!!.toURI())
        val file = path.toFile()

        val reader = PdfDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        assertThat(documents).isNotEmpty()
        documents.forEach { doc ->
            assertThat(doc.metadata).doesNotContainKey("source")
        }
    }
}
