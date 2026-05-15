package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class DocumentLoaderTest {

    private val loader = DocumentLoader()

    @Test
    fun `loading a txt file returns at least one Document`() {
        val path = Paths.get(javaClass.getResource("/documents/sample.txt")!!.toURI())

        val documents = loader.load(path)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `loading a txt file sets source metadata to the file path`() {
        val path = Paths.get(javaClass.getResource("/documents/sample.txt")!!.toURI())

        val documents = loader.load(path)

        assertThat(documents).isNotEmpty()
        documents.forEach { doc ->
            assertThat(doc.metadata["source"] as String).isEqualTo(path.toString())
        }
    }

    @Test
    fun `loading a pdf file returns at least one Document`() {
        val path = Paths.get(javaClass.getResource("/documents/sample.pdf")!!.toURI())

        val documents = loader.load(path)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `loading a pdf file sets source metadata to the file path`() {
        val path = Paths.get(javaClass.getResource("/documents/sample.pdf")!!.toURI())

        val documents = loader.load(path)

        assertThat(documents).isNotEmpty()
        documents.forEach { doc ->
            assertThat(doc.metadata["source"] as String).isEqualTo(path.toString())
        }
    }

    @Test
    fun `loading a md file with yaml front-matter returns content without front-matter delimiters`() {
        val path = Paths.get(javaClass.getResource("/documents/sample.md")!!.toURI())

        val documents = loader.load(path)

        assertThat(documents).isNotEmpty()
        documents.forEach { doc ->
            assertThat(doc.text).doesNotContain("---")
            assertThat(doc.text).contains("Sample Markdown Document")
        }
    }

    @Test
    fun `loading a md file sets source metadata to the file path`() {
        val path = Paths.get(javaClass.getResource("/documents/sample.md")!!.toURI())

        val documents = loader.load(path)

        assertThat(documents).isNotEmpty()
        documents.forEach { doc ->
            assertThat(doc.metadata["source"] as String).isEqualTo(path.toString())
        }
    }
}
