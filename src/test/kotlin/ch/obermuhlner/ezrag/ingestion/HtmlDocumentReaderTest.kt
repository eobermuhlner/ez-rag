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

    @Test
    fun `nav element text does not appear in any chunk`() {
        val html = """
            <html><head><title>Nav Test</title></head>
            <body>
              <nav>
                <a href="/home">unique-nav-link-sentinel-xyzzy</a>
                <a href="/about">unique-nav-about-sentinel-xyzzy</a>
              </nav>
              <h1>Main Content</h1>
              <p>This is the actual page content.</p>
            </body></html>
        """.trimIndent()

        val reader = HtmlDocumentReader(html)
        val docs = reader.read()

        assertThat(docs).isNotEmpty()
        docs.forEach { doc ->
            assertThat(doc.text).doesNotContain("unique-nav-link-sentinel-xyzzy")
            assertThat(doc.text).doesNotContain("unique-nav-about-sentinel-xyzzy")
        }
    }

    @Test
    fun `footer element text does not appear in any chunk`() {
        val html = """
            <html><head><title>Footer Test</title></head>
            <body>
              <h1>Main Content</h1>
              <p>This is the actual page content.</p>
              <footer>
                <p>unique-footer-copyright-sentinel-xyzzy</p>
                <p>unique-footer-links-sentinel-xyzzy</p>
              </footer>
            </body></html>
        """.trimIndent()

        val reader = HtmlDocumentReader(html)
        val docs = reader.read()

        assertThat(docs).isNotEmpty()
        docs.forEach { doc ->
            assertThat(doc.text).doesNotContain("unique-footer-copyright-sentinel-xyzzy")
            assertThat(doc.text).doesNotContain("unique-footer-links-sentinel-xyzzy")
        }
    }

    @Test
    fun `ol items appear numbered in chunk text`() {
        val html = """
            <html><head><title>Ordered List</title></head>
            <body>
              <h2>Steps</h2>
              <ol>
                <li>Download the package</li>
                <li>Run the installer</li>
                <li>Verify the installation</li>
              </ol>
            </body></html>
        """.trimIndent()

        val reader = HtmlDocumentReader(html)
        val docs = reader.read()

        val allText = docs.joinToString("\n") { it.text ?: "" }
        assertThat(allText).contains("1. Download the package")
        assertThat(allText).contains("2. Run the installer")
        assertThat(allText).contains("3. Verify the installation")
    }

    @Test
    fun `blockquote content appears with greater-than prefix in chunk text`() {
        val html = """
            <html><head><title>Blockquote Page</title></head>
            <body>
              <h2>Notable Quote</h2>
              <blockquote><p>To be or not to be, that is the question.</p></blockquote>
            </body></html>
        """.trimIndent()

        val reader = HtmlDocumentReader(html)
        val docs = reader.read()

        val allText = docs.joinToString("\n") { it.text ?: "" }
        assertThat(allText).contains("> To be or not to be, that is the question.")
    }

    @Test
    fun `table in HTML body produces pipe-table syntax in chunk text`() {
        val html = """
            <html><head><title>Table Page</title></head>
            <body>
              <h2>Data</h2>
              <table>
                <tr><th>Name</th><th>Value</th></tr>
                <tr><td>Alpha</td><td>1</td></tr>
                <tr><td>Beta</td><td>2</td></tr>
              </table>
            </body></html>
        """.trimIndent()

        val reader = HtmlDocumentReader(html)
        val docs = reader.read()

        val allText = docs.joinToString("\n") { it.text ?: "" }
        assertThat(allText).contains("| --- |")
    }

    @Test
    fun `table with 5 rows and 3 columns is not split across chunks when chunkSize is 2000`() {
        val html = """
            <html><head><title>Big Table</title></head>
            <body>
              <table>
                <tr><th>Col1</th><th>Col2</th><th>Col3</th></tr>
                <tr><td>R1C1-unique-sentinel</td><td>R1C2</td><td>R1C3</td></tr>
                <tr><td>R2C1</td><td>R2C2</td><td>R2C3</td></tr>
                <tr><td>R3C1</td><td>R3C2</td><td>R3C3</td></tr>
                <tr><td>R4C1</td><td>R4C2</td><td>R4C3-unique-sentinel</td></tr>
              </table>
            </body></html>
        """.trimIndent()

        val reader = HtmlDocumentReader(html, chunkSize = 2000)
        val docs = reader.read()

        // All unique sentinel values must appear in the same chunk
        val chunkWithFirstSentinel = docs.find { it.text?.contains("R1C1-unique-sentinel") == true }
        assertThat(chunkWithFirstSentinel).isNotNull()
        assertThat(chunkWithFirstSentinel!!.text).contains("R4C3-unique-sentinel")
    }

    @Test
    fun `div with class h2 produces chunk with heading_level 2 and heading_title`() {
        val html = """
            <html><head><title>CSS Class Heading</title></head>
            <body>
              <div class="h2">Section Title</div>
              <p>Content under the section.</p>
            </body></html>
        """.trimIndent()

        val reader = HtmlDocumentReader(html)
        val docs = reader.read()

        val headingChunk = docs.find { it.metadata["heading_title"] == "Section Title" }
        assertThat(headingChunk).isNotNull()
        assertThat(headingChunk!!.metadata["heading_level"]).isEqualTo(2)
    }

    @Test
    fun `content before and after hr appears in separate chunks`() {
        val html = """
            <html><head><title>HR Test</title></head>
            <body>
              <h2>Before Section</h2>
              <p>Content before the rule - unique-before-sentinel-xyzzy</p>
              <hr>
              <h2>After Section</h2>
              <p>Content after the rule - unique-after-sentinel-xyzzy</p>
            </body></html>
        """.trimIndent()

        val reader = HtmlDocumentReader(html)
        val docs = reader.read()

        assertThat(docs.size).isGreaterThanOrEqualTo(2)
        val beforeChunk = docs.find { it.text?.contains("unique-before-sentinel-xyzzy") == true }
        val afterChunk = docs.find { it.text?.contains("unique-after-sentinel-xyzzy") == true }
        assertThat(beforeChunk).isNotNull()
        assertThat(afterChunk).isNotNull()
        assertThat(beforeChunk).isNotSameAs(afterChunk)
    }
}
