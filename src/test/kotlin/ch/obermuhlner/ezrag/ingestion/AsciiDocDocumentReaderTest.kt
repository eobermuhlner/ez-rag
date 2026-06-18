package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AsciiDocDocumentReaderTest {

    // ── String constructor ────────────────────────────────────────────────────

    @Test
    fun `string constructor with a single heading produces one chunk with correct heading_title and heading_level`() {
        val adoc = """
            = My Section

            This is the body of the section.
        """.trimIndent()

        val reader = AsciiDocDocumentReader(adoc, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        val headingChunks = documents.filter { it.metadata.containsKey("heading_title") }
        assertThat(headingChunks).hasSize(1)
        assertThat(headingChunks[0].metadata["heading_title"]).isEqualTo("My Section")
        assertThat(headingChunks[0].metadata["heading_level"]).isEqualTo(1)
    }

    @Test
    fun `heading level equals count of equals signs`() {
        val adoc = """
            == Level Two Section

            Content here.
        """.trimIndent()

        val reader = AsciiDocDocumentReader(adoc, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        val headingChunks = documents.filter { it.metadata.containsKey("heading_title") }
        assertThat(headingChunks).hasSize(1)
        assertThat(headingChunks[0].metadata["heading_title"]).isEqualTo("Level Two Section")
        assertThat(headingChunks[0].metadata["heading_level"]).isEqualTo(2)
    }

    @Test
    fun `two sibling level-1 headings produce two chunks with correct heading_title`() {
        val adoc = """
            = Heading One

            Content of heading one.

            = Heading Two

            Content of heading two.
        """.trimIndent()

        val reader = AsciiDocDocumentReader(adoc, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        val headingChunks = documents.filter { it.metadata.containsKey("heading_title") }
        assertThat(headingChunks).hasSize(2)
        val titles = headingChunks.map { it.metadata["heading_title"] as String }
        assertThat(titles).contains("Heading One", "Heading Two")
    }

    @Test
    fun `nested headings produce correct heading_path`() {
        val adoc = """
            = Level One

            Content of level one.

            == Level Two

            Content of level two.
        """.trimIndent()

        val reader = AsciiDocDocumentReader(adoc, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        val level2Chunk = documents.find { it.metadata["heading_title"] == "Level Two" }
        assertThat(level2Chunk).isNotNull()
        @Suppress("UNCHECKED_CAST")
        val path = level2Chunk!!.metadata["heading_path"] as List<String>
        assertThat(path).containsExactly("Level One", "Level Two")
    }

    @Test
    fun `code block delimited by four dashes is contained in a single chunk`() {
        val adoc = """
            = My Section

            Here is a code example:

            ----
            def hello():
                print("Hello")
                return True

            class Foo:
                pass
            ----

            Some text after the block.
        """.trimIndent()

        val reader = AsciiDocDocumentReader(adoc, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        val allText = documents.joinToString("\n") { it.text ?: "" }
        assertThat(allText).contains("def hello()")
        assertThat(allText).contains("class Foo")

        // The code block content should appear in a single chunk
        val codeChunk = documents.find { (it.text ?: "").contains("def hello()") }
        assertThat(codeChunk).isNotNull()
        assertThat(codeChunk!!.text).contains("class Foo")
    }

    @Test
    fun `string constructor with no headings produces at least one chunk without heading_title`() {
        val adoc = "This is plain text with no headings. It has some content that should still be chunked."

        val reader = AsciiDocDocumentReader(adoc, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        assertThat(documents).isNotEmpty()
        documents.forEach { doc ->
            assertThat(doc.metadata).doesNotContainKey("heading_title")
        }
    }

    @Test
    fun `no chunk produced from an AsciiDoc string has source in its metadata`() {
        val adoc = """
            = A Section

            Some content here.
        """.trimIndent()

        val reader = AsciiDocDocumentReader(adoc, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        assertThat(documents).isNotEmpty()
        documents.forEach { doc ->
            assertThat(doc.metadata).doesNotContainKey("source")
        }
    }

    @Test
    fun `large section body produces multiple chunks each with correct heading_title and heading_level`() {
        val loremBody = (1..30).joinToString("\n\n") { "Paragraph $it with enough words to consume token budget." }
        val adoc = "= Big Section\n\n$loremBody"

        val reader = AsciiDocDocumentReader(adoc, chunkSize = 30, chunkOverlap = 5)
        val documents = reader.read()

        val sectionDocs = documents.filter { it.metadata["heading_title"] == "Big Section" }
        assertThat(sectionDocs.size).isGreaterThan(1)
        sectionDocs.forEach { doc ->
            assertThat(doc.metadata["heading_title"]).isEqualTo("Big Section")
            assertThat(doc.metadata["heading_level"]).isEqualTo(1)
            @Suppress("UNCHECKED_CAST")
            val path = doc.metadata["heading_path"] as List<String>
            assertThat(path).containsExactly("Big Section")
        }
    }

    @Test
    fun `heading_path for a single-level heading contains only that heading title`() {
        val adoc = """
            = My Section

            Body content.
        """.trimIndent()

        val reader = AsciiDocDocumentReader(adoc, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        val headingChunks = documents.filter { it.metadata.containsKey("heading_title") }
        assertThat(headingChunks).isNotEmpty()
        @Suppress("UNCHECKED_CAST")
        val path = headingChunks[0].metadata["heading_path"] as List<String>
        assertThat(path).containsExactly("My Section")
    }

    // ── File constructor ──────────────────────────────────────────────────────

    @Test
    fun `file constructor with a heading produces a chunk with correct heading metadata`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("sample.adoc").toFile()
        file.writeText("""
            = My Document

            This is the body of the document.
        """.trimIndent())

        val reader = AsciiDocDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        val headingChunks = documents.filter { it.metadata.containsKey("heading_title") }
        assertThat(headingChunks).hasSize(1)
        assertThat(headingChunks[0].metadata["heading_title"]).isEqualTo("My Document")
    }

    @Test
    fun `file constructor with no headings produces at least one chunk`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("plain.adoc").toFile()
        file.writeText("This is a plain AsciiDoc file with no headings. It has some text content.")

        val reader = AsciiDocDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `no chunk from a file has source in its metadata`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("nosource.adoc").toFile()
        file.writeText("Some AsciiDoc content without headings.")

        val reader = AsciiDocDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        assertThat(documents).isNotEmpty()
        documents.forEach { doc ->
            assertThat(doc.metadata).doesNotContainKey("source")
        }
    }
}
