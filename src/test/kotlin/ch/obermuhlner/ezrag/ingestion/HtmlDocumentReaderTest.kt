package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HtmlDocumentReaderTest {

    @Test
    fun `h2 element produces chunk with heading_title equal to heading text`() {
        val html = """
            <html><head><title>My Page</title></head>
            <body>
              <h2>Installation</h2>
              <p>Follow these steps to install the package.</p>
            </body></html>
        """.trimIndent()

        val reader = HtmlDocumentReader(html)
        val docs = reader.read()

        val headingChunks = docs.filter { it.metadata["heading_title"] == "Installation" }
        assertThat(headingChunks).isNotEmpty()
    }

    @Test
    fun `all produced chunks carry page_title metadata matching HTML title element`() {
        val html = """
            <html><head><title>My Docs Page</title></head>
            <body>
              <h1>Overview</h1>
              <p>Some overview text.</p>
              <h2>Details</h2>
              <p>More detail text.</p>
            </body></html>
        """.trimIndent()

        val reader = HtmlDocumentReader(html)
        val docs = reader.read()

        assertThat(docs).isNotEmpty()
        docs.forEach { doc ->
            assertThat(doc.metadata["page_title"]).isEqualTo("My Docs Page")
        }
    }

    @Test
    fun `HTML with no headings falls back to at least one chunk`() {
        val html = """
            <html><head><title>No Headings</title></head>
            <body>
              <p>Just a plain paragraph without any headings.</p>
              <p>Another paragraph of text content here.</p>
            </body></html>
        """.trimIndent()

        val reader = HtmlDocumentReader(html)
        val docs = reader.read()

        assertThat(docs).isNotEmpty()
    }

    @Test
    fun `head content (title, meta, script, style) does not appear in chunk text`() {
        val html = """
            <html>
            <head>
              <title>Test Page</title>
              <meta name="description" content="secret-meta-content"/>
              <script>var secret = "script-content";</script>
              <style>body { color: hidden-style; }</style>
            </head>
            <body>
              <h1>Visible Content</h1>
              <p>This paragraph should appear in the chunks.</p>
            </body></html>
        """.trimIndent()

        val reader = HtmlDocumentReader(html)
        val docs = reader.read()

        assertThat(docs).isNotEmpty()
        docs.forEach { doc ->
            assertThat(doc.text).doesNotContain("secret-meta-content")
            assertThat(doc.text).doesNotContain("script-content")
            assertThat(doc.text).doesNotContain("hidden-style")
        }
    }

    @Test
    fun `pre code block content is preserved in chunks`() {
        val html = """
            <html><head><title>Code Page</title></head>
            <body>
              <h2>Usage</h2>
              <pre><code>npm install my-package</code></pre>
            </body></html>
        """.trimIndent()

        val reader = HtmlDocumentReader(html)
        val docs = reader.read()

        val allText = docs.joinToString(" ") { it.text ?: "" }
        assertThat(allText).contains("npm install my-package")
    }

    @Test
    fun `heading hierarchy produces correct heading_level and heading_path`() {
        val html = """
            <html><head><title>Hierarchy</title></head>
            <body>
              <h1>Chapter One</h1>
              <p>Chapter intro.</p>
              <h2>Section A</h2>
              <p>Section content here.</p>
            </body></html>
        """.trimIndent()

        val reader = HtmlDocumentReader(html)
        val docs = reader.read()

        val sectionChunk = docs.find { it.metadata["heading_title"] == "Section A" }
        assertThat(sectionChunk).isNotNull()
        assertThat(sectionChunk!!.metadata["heading_level"]).isEqualTo(2)
        @Suppress("UNCHECKED_CAST")
        val headingPath = sectionChunk.metadata["heading_path"] as List<String>
        assertThat(headingPath).containsExactly("Chapter One", "Section A")
    }

    @Test
    fun `list items appear in chunk text`() {
        val html = """
            <html><head><title>List Page</title></head>
            <body>
              <h2>Requirements</h2>
              <ul>
                <li>Java 11 or later</li>
                <li>Maven 3.6+</li>
              </ul>
            </body></html>
        """.trimIndent()

        val reader = HtmlDocumentReader(html)
        val docs = reader.read()

        val allText = docs.joinToString(" ") { it.text ?: "" }
        assertThat(allText).contains("Java 11 or later")
        assertThat(allText).contains("Maven 3.6+")
    }

    @Test
    fun `no chunk has source key in metadata`() {
        val html = "<html><head><title>T</title></head><body><p>Some text content here.</p></body></html>"

        val reader = HtmlDocumentReader(html)
        val docs = reader.read()

        assertThat(docs).isNotEmpty()
        docs.forEach { doc ->
            assertThat(doc.metadata).doesNotContainKey("source")
        }
    }
}
