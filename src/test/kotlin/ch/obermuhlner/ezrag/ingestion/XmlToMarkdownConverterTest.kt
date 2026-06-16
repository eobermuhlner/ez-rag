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
    fun `two repeated direct children produce one section per element`() {
        val xml = """<project>
            <dependency><groupId>org.spring</groupId></dependency>
            <dependency><groupId>junit</groupId></dependency>
        </project>"""
        val result = XmlToMarkdownConverter().convert(xml)
        val headings = result.lines().filter { it.startsWith("## ") }
        assertThat(headings).hasSize(2)
        assertThat(headings[0]).contains("project > dependency")
        assertThat(headings[1]).contains("project > dependency")
    }

    @Test
    fun `repeated section body lines are relative to the repeated element`() {
        val xml = """<project>
            <dependency><groupId>org.spring</groupId></dependency>
            <dependency><groupId>junit</groupId></dependency>
        </project>"""
        val result = XmlToMarkdownConverter().convert(xml)
        assertThat(result).contains("groupId: org.spring")
        assertThat(result).contains("groupId: junit")
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
        assertThat(result).contains("root > dependency")
        assertThat(result).contains("root > plugin")
        val headings = result.lines().filter { it.startsWith("## ") }
        assertThat(headings).hasSize(4)
    }

    @Test
    fun `attributes of repeated element appear in its heading`() {
        val xml = """<beans>
            <bean id="dataSource" class="DS"/>
            <bean id="userService" class="US"/>
        </beans>"""
        val result = XmlToMarkdownConverter().convert(xml)
        assertThat(result).contains("beans > bean[id=dataSource][class=DS]")
        assertThat(result).contains("beans > bean[id=userService][class=US]")
    }
}
