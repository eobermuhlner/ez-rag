package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class RstDocumentReaderTest {

    // ── String constructor ────────────────────────────────────────────────────

    @Test
    fun `string constructor with a single heading produces one chunk with correct heading_title and heading_level`() {
        val rst = """
            My Section
            ==========

            This is the body of the section.
        """.trimIndent()

        val reader = RstDocumentReader(rst, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        val headingChunks = documents.filter { it.metadata.containsKey("heading_title") }
        assertThat(headingChunks).hasSize(1)
        assertThat(headingChunks[0].metadata["heading_title"]).isEqualTo("My Section")
        assertThat(headingChunks[0].metadata["heading_level"]).isEqualTo(1)
    }

    @Test
    fun `string constructor with two sibling sections produces two chunks with correct heading_title`() {
        val rst = """
            Section One
            ===========

            Content of section one.

            Section Two
            -----------

            Content of section two.
        """.trimIndent()

        val reader = RstDocumentReader(rst, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        val headingChunks = documents.filter { it.metadata.containsKey("heading_title") }
        assertThat(headingChunks).hasSize(2)
        val titles = headingChunks.map { it.metadata["heading_title"] as String }
        assertThat(titles).contains("Section One", "Section Two")
    }

    @Test
    fun `first-seen underline character determines level — dash before equals assigns dash as level 1`() {
        val rst = """
            First Section
            -------------

            Content of first.

            Second Section
            ==============

            Content of second.
        """.trimIndent()

        val reader = RstDocumentReader(rst, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        val first = documents.find { it.metadata["heading_title"] == "First Section" }
        val second = documents.find { it.metadata["heading_title"] == "Second Section" }

        assertThat(first).isNotNull()
        assertThat(second).isNotNull()
        assertThat(first!!.metadata["heading_level"]).isEqualTo(1)
        assertThat(second!!.metadata["heading_level"]).isEqualTo(2)
    }

    @Test
    fun `nested headings produce correct heading_path`() {
        val rst = """
            Parent Section
            ==============

            Parent content.

            Child Section
            -------------

            Child content.
        """.trimIndent()

        val reader = RstDocumentReader(rst, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        val childChunk = documents.find { it.metadata["heading_title"] == "Child Section" }
        assertThat(childChunk).isNotNull()
        @Suppress("UNCHECKED_CAST")
        val path = childChunk!!.metadata["heading_path"] as List<String>
        assertThat(path).containsExactly("Parent Section", "Child Section")
    }

    @Test
    fun `overline plus underline heading is detected as a heading`() {
        val rst = """
            ==================
            Overline Section
            ==================

            Content under overline section.
        """.trimIndent()

        val reader = RstDocumentReader(rst, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        val headingChunks = documents.filter { it.metadata.containsKey("heading_title") }
        assertThat(headingChunks).isNotEmpty()
        assertThat(headingChunks[0].metadata["heading_title"]).isEqualTo("Overline Section")
    }

    @Test
    fun `code-block directive content is contained in a single chunk and not split`() {
        val rst = """
            My Section
            ==========

            Here is a code example:

            .. code-block:: python

                def hello():
                    print("Hello")
                    return True

                class Foo:
                    pass

            Some text after the block.
        """.trimIndent()

        val reader = RstDocumentReader(rst, chunkSize = 1000, chunkOverlap = 200)
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
    fun `double-colon shorthand code block content is contained in a single chunk`() {
        val rst = """
            My Section
            ==========

            Here is an example::

                def hello():
                    print("Hello")
                    return True

                class Foo:
                    pass

            Text after block.
        """.trimIndent()

        val reader = RstDocumentReader(rst, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        val allText = documents.joinToString("\n") { it.text ?: "" }
        assertThat(allText).contains("def hello()")

        val codeChunk = documents.find { (it.text ?: "").contains("def hello()") }
        assertThat(codeChunk).isNotNull()
        assertThat(codeChunk!!.text).contains("class Foo")
    }

    @Test
    fun `string constructor with no headings produces at least one chunk without heading_title`() {
        val rst = "This is plain text with no headings. It has some content that should still be chunked."

        val reader = RstDocumentReader(rst, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        assertThat(documents).isNotEmpty()
        documents.forEach { doc ->
            assertThat(doc.metadata).doesNotContainKey("heading_title")
        }
    }

    @Test
    fun `no chunk produced from an RST string has source in its metadata`() {
        val rst = """
            A Section
            =========

            Some content here.
        """.trimIndent()

        val reader = RstDocumentReader(rst, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        assertThat(documents).isNotEmpty()
        documents.forEach { doc ->
            assertThat(doc.metadata).doesNotContainKey("source")
        }
    }

    @Test
    fun `large section body produces multiple chunks each with correct heading_title and heading_level`() {
        val loremBody = (1..30).joinToString("\n\n") { "Paragraph $it with enough words to consume token budget." }
        val rst = "Big Section\n===========\n\n$loremBody"

        val reader = RstDocumentReader(rst, chunkSize = 30, chunkOverlap = 5)
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
        val rst = """
            My Section
            ==========

            Body content.
        """.trimIndent()

        val reader = RstDocumentReader(rst, chunkSize = 1000, chunkOverlap = 200)
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
        val file = tempDir.resolve("sample.rst").toFile()
        file.writeText("""
            My Document
            ===========

            This is the body of the document.
        """.trimIndent())

        val reader = RstDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        val headingChunks = documents.filter { it.metadata.containsKey("heading_title") }
        assertThat(headingChunks).hasSize(1)
        assertThat(headingChunks[0].metadata["heading_title"]).isEqualTo("My Document")
    }

    @Test
    fun `file constructor with no headings produces at least one chunk`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("plain.rst").toFile()
        file.writeText("This is a plain RST file with no headings. It has some text content.")

        val reader = RstDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `no chunk from a file has source in its metadata`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("nosource.rst").toFile()
        file.writeText("Some RST content without headings.")

        val reader = RstDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        assertThat(documents).isNotEmpty()
        documents.forEach { doc ->
            assertThat(doc.metadata).doesNotContainKey("source")
        }
    }
}
