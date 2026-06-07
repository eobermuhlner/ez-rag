package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HtmlToMarkdownConverterTest {

    private val converter = HtmlToMarkdownConverter()

    // ── Headings ──────────────────────────────────────────────────────────────

    @Test
    fun `h1 produces # prefixed heading line`() {
        val result = converter.convert("<h1>Introduction</h1>")
        assertThat(result.trim()).startsWith("# Introduction")
    }

    @Test
    fun `h2 produces ## prefixed heading line`() {
        val result = converter.convert("<h2>Section</h2>")
        assertThat(result.trim()).startsWith("## Section")
    }

    @Test
    fun `h3 produces ### prefixed heading line`() {
        val result = converter.convert("<h3>Sub-section</h3>")
        assertThat(result.trim()).startsWith("### Sub-section")
    }

    @Test
    fun `h4 produces #### prefixed heading line`() {
        val result = converter.convert("<h4>Level 4</h4>")
        assertThat(result.trim()).startsWith("#### Level 4")
    }

    @Test
    fun `h5 produces ##### prefixed heading line`() {
        val result = converter.convert("<h5>Level 5</h5>")
        assertThat(result.trim()).startsWith("##### Level 5")
    }

    @Test
    fun `h6 produces ###### prefixed heading line`() {
        val result = converter.convert("<h6>Level 6</h6>")
        assertThat(result.trim()).startsWith("###### Level 6")
    }

    // ── Paragraph ─────────────────────────────────────────────────────────────

    @Test
    fun `p element produces paragraph text followed by blank line`() {
        val result = converter.convert("<p>Hello</p>")
        assertThat(result).contains("Hello")
        // Blank line = two consecutive newline characters after the text
        assertThat(result).contains("Hello\n\n")
    }

    // ── Line break ────────────────────────────────────────────────────────────

    @Test
    fun `br inside p produces a newline between the two text parts`() {
        val result = converter.convert("<p>Line one<br>Line two</p>")
        assertThat(result).contains("Line one")
        assertThat(result).contains("Line two")
        val lineOne = result.indexOf("Line one")
        val lineTwo = result.indexOf("Line two")
        // There must be a newline character between them
        assertThat(result.substring(lineOne, lineTwo)).contains("\n")
    }

    // ── Pre / code ────────────────────────────────────────────────────────────

    @Test
    fun `pre element produces fenced code block with triple backticks`() {
        val result = converter.convert("<pre>code here</pre>")
        assertThat(result).contains("```")
        val backtickIdx = result.indexOf("```")
        val closingIdx = result.indexOf("```", backtickIdx + 3)
        assertThat(closingIdx).isGreaterThan(backtickIdx)
        assertThat(result).contains("code here")
    }

    // ── Image ─────────────────────────────────────────────────────────────────

    @Test
    fun `img with non-blank alt produces the alt text`() {
        val result = converter.convert("<img alt=\"A diagram\">")
        assertThat(result).contains("A diagram")
    }

    @Test
    fun `img with blank alt produces no output`() {
        val result = converter.convert("<img alt=\"\">")
        assertThat(result.trim()).isEmpty()
    }

    @Test
    fun `img without alt attribute produces no output`() {
        val result = converter.convert("<img src=\"image.png\">")
        assertThat(result.trim()).isEmpty()
    }

    // ── Unordered list ────────────────────────────────────────────────────────

    @Test
    fun `ul with two li elements produces dash-prefixed bullet lines`() {
        val result = converter.convert("<ul><li>Apple</li><li>Banana</li></ul>")
        assertThat(result).contains("- Apple")
        assertThat(result).contains("- Banana")
    }

    // ── Script, style, noscript ───────────────────────────────────────────────

    @Test
    fun `script element produces no output`() {
        val result = converter.convert("<script>var x = 1;</script>")
        assertThat(result.trim()).isEmpty()
    }

    @Test
    fun `style element produces no output`() {
        val result = converter.convert("<style>body { color: red; }</style>")
        assertThat(result.trim()).isEmpty()
    }

    @Test
    fun `noscript element produces no output`() {
        val result = converter.convert("<noscript>Enable JavaScript</noscript>")
        assertThat(result.trim()).isEmpty()
    }

    // ── Container transparency ────────────────────────────────────────────────

    @Test
    fun `div wrapping p produces same output as p alone`() {
        val withDiv = converter.convert("<div><p>text</p></div>")
        val withoutDiv = converter.convert("<p>text</p>")
        assertThat(withDiv.trim()).isEqualTo(withoutDiv.trim())
    }

    @Test
    fun `section wrapping p recurses into children transparently`() {
        val result = converter.convert("<section><p>Section content</p></section>")
        assertThat(result).contains("Section content")
    }

    // ── Navigation chrome filtering ───────────────────────────────────────────

    @Test
    fun `nav element containing unique link text produces no output`() {
        val result = converter.convert("<nav><a href='/home'>unique-nav-sentinel-xyzzy</a></nav>")
        assertThat(result).doesNotContain("unique-nav-sentinel-xyzzy")
    }

    @Test
    fun `header element containing unique text produces no output`() {
        val result = converter.convert("<header><p>unique-header-sentinel-xyzzy</p></header>")
        assertThat(result).doesNotContain("unique-header-sentinel-xyzzy")
    }

    @Test
    fun `footer element containing unique text produces no output`() {
        val result = converter.convert("<footer><p>unique-footer-sentinel-xyzzy</p></footer>")
        assertThat(result).doesNotContain("unique-footer-sentinel-xyzzy")
    }

    @Test
    fun `aside element containing unique text produces no output`() {
        val result = converter.convert("<aside><p>unique-aside-sentinel-xyzzy</p></aside>")
        assertThat(result).doesNotContain("unique-aside-sentinel-xyzzy")
    }

    // ── Ordered list ──────────────────────────────────────────────────────────

    @Test
    fun `ol with three li elements produces sequentially numbered items`() {
        val result = converter.convert("<ol><li>First</li><li>Second</li><li>Third</li></ol>")
        assertThat(result).contains("1. First")
        assertThat(result).contains("2. Second")
        assertThat(result).contains("3. Third")
    }

    @Test
    fun `two adjacent ol lists each start numbering from 1`() {
        val result = converter.convert("<ol><li>A</li><li>B</li></ol><ol><li>X</li><li>Y</li></ol>")
        // First list
        assertThat(result).contains("1. A")
        assertThat(result).contains("2. B")
        // Second list must also start at 1
        assertThat(result).contains("1. X")
        assertThat(result).contains("2. Y")
    }

    // ── Blockquote ────────────────────────────────────────────────────────────

    @Test
    fun `blockquote with p child produces lines prefixed with greater-than`() {
        val result = converter.convert("<blockquote><p>A quote</p></blockquote>")
        assertThat(result).contains("> A quote")
    }

    // ── Horizontal rule ───────────────────────────────────────────────────────

    @Test
    fun `hr produces triple-dash line`() {
        val result = converter.convert("<hr>")
        assertThat(result.trim()).isEqualTo("---")
    }

    // ── Table conversion ──────────────────────────────────────────────────────

    @Test
    fun `simple table with th headers and td data row produces pipe table with separator row`() {
        val html = """
            <table>
              <tr><th>Name</th><th>Age</th></tr>
              <tr><td>Alice</td><td>30</td></tr>
            </table>
        """.trimIndent()
        val result = converter.convert(html)
        // Header row
        assertThat(result).contains("| Name | Age |")
        // Separator row with two columns
        assertThat(result).contains("| --- | --- |")
        // Data row
        assertThat(result).contains("| Alice | 30 |")
    }

    @Test
    fun `table cell with colspan attribute - cell text appears in output`() {
        val html = """
            <table>
              <tr><th>Name</th><th>Details</th></tr>
              <tr><td colspan="2">Alice spanning cell</td></tr>
            </table>
        """.trimIndent()
        val result = converter.convert(html)
        assertThat(result).contains("Alice spanning cell")
    }

    @Test
    fun `table cell with rowspan attribute - cell text appears in output`() {
        val html = """
            <table>
              <tr><th>Name</th><th>Category</th></tr>
              <tr><td rowspan="2">Bob rowspan cell</td><td>A</td></tr>
              <tr><td>B</td></tr>
            </table>
        """.trimIndent()
        val result = converter.convert(html)
        assertThat(result).contains("Bob rowspan cell")
    }

    // ── CSS class heading detection ───────────────────────────────────────────

    @Test
    fun `div with class h2 produces ## heading`() {
        val result = converter.convert("<div class=\"h2\">Title</div>")
        assertThat(result.trim()).startsWith("## Title")
    }

    @Test
    fun `span with class heading-3 produces ### heading`() {
        val result = converter.convert("<span class=\"heading-3\">Sub</span>")
        assertThat(result.trim()).startsWith("### Sub")
    }

    @Test
    fun `element with heading class alongside other classes produces heading`() {
        val result = converter.convert("<div class=\"h2 extra-class\">Title</div>")
        assertThat(result.trim()).startsWith("## Title")
    }

    @Test
    fun `element with unrelated class does not produce a heading line`() {
        val result = converter.convert("<div class=\"some-unrelated-class\">Just text</div>")
        assertThat(result).doesNotContain("#")
        assertThat(result).contains("Just text")
    }
}
