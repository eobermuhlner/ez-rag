package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class XmlToMarkdownConverterTest {

    @Test
    fun `flat XML with only unique elements produces heading with root local name`() {
        val xml = "<config><host>localhost</host></config>"
        val result = XmlToMarkdownConverter().convert(xml)
        assertThat(result).contains("## config")
        assertThat(result).contains("host: localhost")
    }

    @Test
    fun `body lines use paths relative to root - direct child has no parent prefix`() {
        val xml = "<config><host>localhost</host></config>"
        val result = XmlToMarkdownConverter().convert(xml)
        // direct child of root: "host: localhost" not "config > host: localhost"
        assertThat(result).contains("host: localhost")
        assertThat(result).doesNotContain("config > host")
    }

    @Test
    fun `body lines use paths relative to root - nested element produces relative path`() {
        val xml = "<config><db><host>localhost</host></db></config>"
        val result = XmlToMarkdownConverter().convert(xml)
        assertThat(result).contains("db > host: localhost")
        assertThat(result).doesNotContain("config > db > host")
    }

    @Test
    fun `attributes formatted as bracket key equals value appended to element name`() {
        val xml = """<root><string name="key">Hello</string></root>"""
        val result = XmlToMarkdownConverter().convert(xml)
        assertThat(result).contains("string[name=key]: Hello")
    }

    @Test
    fun `element with only attributes and no text produces no trailing colon suffix`() {
        val xml = """<beans><bean id="ds" class="DS"/></beans>"""
        val result = XmlToMarkdownConverter().convert(xml)
        assertThat(result).contains("bean[id=ds][class=DS]")
        assertThat(result).doesNotContain("bean[id=ds][class=DS]: ")
    }

    @Test
    fun `xmlns attributes do not appear in output`() {
        val xml = """<beans xmlns="http://www.springframework.org/schema/beans" xmlns:context="http://www.springframework.org/schema/context"><bean id="x"/></beans>"""
        val result = XmlToMarkdownConverter().convert(xml)
        assertThat(result).doesNotContain("xmlns=")
        assertThat(result).doesNotContain("xmlns:context")
        assertThat(result).doesNotContain("http://www.springframework.org/schema/beans")
    }

    @Test
    fun `namespace prefixes stripped from element names`() {
        val xml = """<beans xmlns:context="http://www.springframework.org/schema/context"><context:component-scan base-package="com.example"/></beans>"""
        val result = XmlToMarkdownConverter().convert(xml)
        assertThat(result).contains("component-scan")
        assertThat(result).doesNotContain("context:component-scan")
    }

    @Test
    fun `XML comment content does not appear in output`() {
        val xml = "<!-- secret-comment --><root><item>visible</item></root>"
        val result = XmlToMarkdownConverter().convert(xml)
        assertThat(result).doesNotContain("secret-comment")
        assertThat(result).contains("visible")
    }

    @Test
    fun `CDATA section content appears in output`() {
        val xml = "<root><data><![CDATA[cdata-content-sentinel]]></data></root>"
        val result = XmlToMarkdownConverter().convert(xml)
        assertThat(result).contains("cdata-content-sentinel")
    }

    @Test
    fun `whitespace-only text nodes do not produce body lines`() {
        val xml = "<root>\n  <name>myapp</name>\n</root>"
        val result = XmlToMarkdownConverter().convert(xml)
        assertThat(result).contains("name: myapp")
        // Should not contain lines like "root: " from whitespace-only nodes
        val lines = result.lines().filter { it.startsWith("root:") }
        assertThat(lines).isEmpty()
    }

    @Test
    fun `empty root element with no text no attributes no children returns empty string`() {
        val xml = "<root/>"
        val result = XmlToMarkdownConverter().convert(xml)
        assertThat(result).isBlank()
    }

    @Test
    fun `root element with namespace prefix uses local name as heading`() {
        val xml = """<ns:project xmlns:ns="http://example.com"><ns:name>app</ns:name></ns:project>"""
        val result = XmlToMarkdownConverter().convert(xml)
        assertThat(result).contains("## project")
        assertThat(result).doesNotContain("## ns:project")
    }

    // Task 02: repeated-sibling detection and preamble emission

    @Test
    fun `two repeated direct children produce sections with project-dependency path`() {
        val xml = """<project>
            <dependency><groupId>org.spring</groupId></dependency>
            <dependency><groupId>junit</groupId></dependency>
        </project>"""
        val result = XmlToMarkdownConverter().convert(xml)
        // Small elements may merge into one or more batches; the heading path must still reference the boundary
        val headings = result.lines().filter { it.startsWith("## ") }
        assertThat(headings).isNotEmpty()
        assertThat(headings.all { it.contains("project > dependency") }).isTrue()
    }

    @Test
    fun `two large repeated direct children produce one section per element`() {
        // Elements whose body exceeds chunkSize*3 are never merged
        val largeContent = "x".repeat(250) // 250 chars > chunkSize(50)*3=150
        val xml = """<project>
            <dependency>$largeContent</dependency>
            <dependency>$largeContent</dependency>
        </project>"""
        val result = XmlToMarkdownConverter(chunkSize = 50).convert(xml)
        val headings = result.lines().filter { it.startsWith("## ") }
        assertThat(headings).hasSize(2)
        assertThat(headings[0]).contains("project > dependency")
        assertThat(headings[1]).contains("project > dependency")
    }

    @Test
    fun `repeated section body lines contain content of each element`() {
        val xml = """<project>
            <dependency><groupId>org.spring</groupId></dependency>
            <dependency><groupId>junit</groupId></dependency>
        </project>"""
        val result = XmlToMarkdownConverter().convert(xml)
        // Both groups' content must appear in the output (merged or separate)
        assertThat(result).contains("org.spring")
        assertThat(result).contains("junit")
        assertThat(result).doesNotContain("dependency > groupId")
    }

    @Test
    fun `unique siblings appear in preamble section before repeated sections`() {
        val xml = """<project>
            <groupId>com.example</groupId>
            <artifactId>myapp</artifactId>
            <dependency><groupId>junit</groupId></dependency>
            <dependency><groupId>spring</groupId></dependency>
        </project>"""
        val result = XmlToMarkdownConverter().convert(xml)
        // Preamble heading
        assertThat(result).contains("## project")
        assertThat(result).contains("groupId: com.example")
        assertThat(result).contains("artifactId: myapp")
        // Repeated sections also present
        val headings = result.lines().filter { it.startsWith("## ") }
        assertThat(headings.any { it.contains("project > dependency") }).isTrue()
    }

    @Test
    fun `preamble is omitted when all root children belong to repeated groups`() {
        val xml = """<items>
            <item>first</item>
            <item>second</item>
        </items>"""
        val result = XmlToMarkdownConverter().convert(xml)
        // Only repeated sections, no preamble heading "## items" without items > item
        val headings = result.lines().filter { it.startsWith("## ") }
        // All headings should be for the repeated element, not the preamble
        // (small elements may be merged into one batch heading)
        assertThat(headings).isNotEmpty()
        assertThat(headings.all { it.contains("items > item") }).isTrue()
    }

    @Test
    fun `two distinct repeated groups at same level both produce sections`() {
        val xml = """<root>
            <dependency><id>a</id></dependency>
            <dependency><id>b</id></dependency>
            <plugin><id>x</id></plugin>
            <plugin><id>y</id></plugin>
        </root>"""
        val result = XmlToMarkdownConverter().convert(xml)
        // Both groups must appear in headings (small elements may be merged within each group)
        assertThat(result).contains("root > dependency")
        assertThat(result).contains("root > plugin")
        // Content must still be accessible
        assertThat(result).contains("a")
        assertThat(result).contains("x")
    }

    @Test
    fun `attributes of repeated large element appear in its heading`() {
        // Large elements (body > chunkSize*3) never merge; their attributes appear in the heading
        val largeContent = "x".repeat(250)
        val xml = """<beans>
            <bean id="dataSource" class="DS">$largeContent</bean>
            <bean id="userService" class="US">$largeContent</bean>
        </beans>"""
        val result = XmlToMarkdownConverter(chunkSize = 50).convert(xml)
        assertThat(result).contains("beans > bean[id=dataSource][class=DS]")
        assertThat(result).contains("beans > bean[id=userService][class=US]")
    }

    @Test
    fun `attributes of small repeated elements appear in body prefix lines`() {
        // Small elements are merged; attributes appear as localName[attrs]: prefix lines in the body
        val xml = """<beans>
            <bean id="dataSource" class="DS"/>
            <bean id="userService" class="US"/>
        </beans>"""
        val result = XmlToMarkdownConverter().convert(xml)
        // Content should still be accessible, attributes in body prefix
        assertThat(result).contains("bean[id=dataSource][class=DS]")
        assertThat(result).contains("bean[id=userService][class=US]")
    }

    // Task 02: multi-level boundary detection

    @Test
    fun `single-wrapper XML produces headings each containing full ancestor path`() {
        // Products with substantial content to avoid merging at default chunkSize
        val largeContent = "x".repeat(250)
        val xml = """<catalog><products><product><name>Widget A $largeContent</name></product><product><name>Widget B $largeContent</name></product></products></catalog>"""
        val result = XmlToMarkdownConverter(chunkSize = 50).convert(xml)
        val headings = result.lines().filter { it.startsWith("## ") }
        assertThat(headings).hasSize(2)
        assertThat(headings[0]).contains("catalog > products > product")
        assertThat(headings[1]).contains("catalog > products > product")
    }

    @Test
    fun `single-wrapper XML small products are merged into one batch heading with correct path`() {
        val xml = """<catalog><products><product><name>Widget A</name></product><product><name>Widget B</name></product></products></catalog>"""
        val result = XmlToMarkdownConverter().convert(xml)
        val headings = result.lines().filter { it.startsWith("## ") }
        // Small products may merge into one batch; heading must still reference the ancestor path
        assertThat(headings).isNotEmpty()
        assertThat(headings.all { it.contains("catalog > products > product") }).isTrue()
        // Both product names must appear in the output
        assertThat(result).contains("Widget A")
        assertThat(result).contains("Widget B")
    }

    @Test
    fun `two-level nesting places boundary at product not category`() {
        // Large products so they don't merge
        val largeContent = "x".repeat(250)
        val xml = """<catalog><category><product>A $largeContent</product><product>B $largeContent</product></category></catalog>"""
        val result = XmlToMarkdownConverter(chunkSize = 50).convert(xml)
        val headings = result.lines().filter { it.startsWith("## ") }
        assertThat(headings).hasSize(2)
        assertThat(headings[0]).contains("catalog > category > product")
        assertThat(headings[1]).contains("catalog > category > product")
    }

    @Test
    fun `two-level nesting small products boundary is at product level`() {
        val xml = """<catalog><category><product>A</product><product>B</product></category></catalog>"""
        val result = XmlToMarkdownConverter().convert(xml)
        val headings = result.lines().filter { it.startsWith("## ") }
        // Boundary is at product level (not category), even if products are merged
        assertThat(headings.all { it.contains("catalog > category > product") }).isTrue()
        assertThat(headings).isNotEmpty()
    }

    @Test
    fun `preamble meta sibling plus repeated products at root level`() {
        val xml = """<catalog><meta>doc title</meta><product>A</product><product>B</product></catalog>"""
        val result = XmlToMarkdownConverter().convert(xml)
        // One preamble section with catalog heading containing meta content
        assertThat(result).contains("## catalog")
        assertThat(result).contains("doc title")
        // At least one product section heading (small products may be merged into one batch)
        val productHeadings = result.lines().filter { it.startsWith("## ") && it.contains("catalog > product") }
        assertThat(productHeadings).isNotEmpty()
        // meta text should NOT appear in the product sections
        val productSections = result.substringAfter("## catalog > product")
        assertThat(productSections).doesNotContain("doc title")
    }

    // Task 04: boundary tag override

    @Test
    fun `boundary tag override forces chunking at specified element`() {
        // Auto-detection would pick product (deepest repeated level); override forces category
        val xml = """<catalog><category><product>A</product><product>B</product></category></catalog>"""
        val result = XmlToMarkdownConverter().convert(xml, boundaryTags = listOf("category"))
        val headings = result.lines().filter { it.startsWith("## ") }
        // Only category elements should produce headings, not product
        assertThat(headings).isNotEmpty()
        assertThat(headings.all { it.contains("category") }).isTrue()
        assertThat(headings.none { it.contains("product") }).isTrue()
    }

    @Test
    fun `boundary tag override with multiple tags produces sections for each matching element`() {
        val xml = """<catalog>
            <category name="A"><product>P1</product></category>
            <category name="B"><product>P2</product></category>
        </catalog>"""
        val result = XmlToMarkdownConverter().convert(xml, boundaryTags = listOf("category"))
        val headings = result.lines().filter { it.startsWith("## ") }
        assertThat(headings).isNotEmpty()
        assertThat(headings.all { it.contains("category") }).isTrue()
        assertThat(result).contains("P1")
        assertThat(result).contains("P2")
    }

    // Task 03: small sibling merging

    @Test
    fun `ten small items with chunkSize 50 produce exactly one heading`() {
        // chunkSize=50, flush threshold = 50*12 = 600 chars
        // each item body is "one word" = 8 chars, 10 items = 80 chars < 600 → single batch
        val items = (1..10).joinToString("") { "<item>one word</item>" }
        val xml = "<root>$items</root>"
        val result = XmlToMarkdownConverter(chunkSize = 50).convert(xml)
        val headings = result.lines().filter { it.startsWith("## ") }
        assertThat(headings).hasSize(1)
    }

    @Test
    fun `100 small items with chunkSize 50 produce exactly two headings`() {
        // chunkSize=50, flush threshold = 50*12 = 600 chars
        // each item body is "one word" = 8 chars, 100 items = 800 chars > 600 → two batches
        val items = (1..100).joinToString("") { "<item>one word</item>" }
        val xml = "<root>$items</root>"
        val result = XmlToMarkdownConverter(chunkSize = 50).convert(xml)
        val headings = result.lines().filter { it.startsWith("## ") }
        assertThat(headings).hasSize(2)
    }
}
