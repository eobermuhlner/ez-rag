package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class XmlDocumentReaderTest {

    @Test
    fun `simple element with text produces section heading and relative body line`() {
        val xml = "<root><name>myapp</name></root>"
        val docs = XmlDocumentReader(xml).read()
        val allText = docs.joinToString("\n") { it.text ?: "" }
        assertThat(allText).contains("## root")
        assertThat(allText).contains("name: myapp")
    }

    @Test
    fun `nested elements produce relative path body line`() {
        val xml = "<a><b><c>hello</c></b></a>"
        val docs = XmlDocumentReader(xml).read()
        val allText = docs.joinToString("\n") { it.text ?: "" }
        assertThat(allText).contains("b > c: hello")
    }

    @Test
    fun `whitespace-only text node does not produce a chunk line`() {
        val xml = "<root>\n  <name>myapp</name>\n</root>"
        val docs = XmlDocumentReader(xml).read()
        val allText = docs.joinToString("\n") { it.text ?: "" }
        assertThat(allText).contains("name: myapp")
        // Whitespace-only should not produce lines like "root: "
        val lines = allText.lines().filter { it.startsWith("root:") }
        assertThat(lines).isEmpty()
    }

    @Test
    fun `no chunk contains the source metadata key`() {
        val xml = "<root><item>text</item></root>"
        val docs = XmlDocumentReader(xml).read()
        assertThat(docs).isNotEmpty()
        docs.forEach { doc ->
            assertThat(doc.metadata).doesNotContainKey("source")
        }
    }

    @Test
    fun `element with attribute produces bracket attr equals val notation in body line`() {
        val xml = """<root><string name="key">Hello</string></root>"""
        val docs = XmlDocumentReader(xml).read()
        val allText = docs.joinToString("\n") { it.text ?: "" }
        assertThat(allText).contains("string[name=key]: Hello")
    }

    @Test
    fun `element with only attributes and no text produces no colon value suffix`() {
        val xml = """<beans><bean id="ds" class="DS"/></beans>"""
        val docs = XmlDocumentReader(xml).read()
        val allText = docs.joinToString("\n") { it.text ?: "" }
        assertThat(allText).contains("bean[id=ds][class=DS]")
        // Should NOT end with ": " when no text content
        assertThat(allText).doesNotContain("bean[id=ds][class=DS]: ")
    }

    @Test
    fun `xmlns attributes do not appear in any chunk text`() {
        val xml = """<beans xmlns="http://www.springframework.org/schema/beans" xmlns:context="http://www.springframework.org/schema/context"><bean id="x"/></beans>"""
        val docs = XmlDocumentReader(xml).read()
        val allText = docs.joinToString("\n") { it.text ?: "" }
        assertThat(allText).doesNotContain("xmlns=")
        assertThat(allText).doesNotContain("xmlns:context")
        assertThat(allText).doesNotContain("http://www.springframework.org/schema/beans")
    }

    @Test
    fun `namespace-prefixed element appears as local name only in chunk text`() {
        val xml = """<beans xmlns:context="http://www.springframework.org/schema/context"><context:component-scan base-package="com.example"/></beans>"""
        val docs = XmlDocumentReader(xml).read()
        val allText = docs.joinToString("\n") { it.text ?: "" }
        assertThat(allText).contains("component-scan")
        assertThat(allText).doesNotContain("context:component-scan")
    }

    @Test
    fun `XML comment content does not appear in any chunk text`() {
        val xml = "<!-- secret-comment --><root><item>visible</item></root>"
        val docs = XmlDocumentReader(xml).read()
        val allText = docs.joinToString("\n") { it.text ?: "" }
        assertThat(allText).doesNotContain("secret-comment")
        assertThat(allText).contains("visible")
    }

    @Test
    fun `CDATA section content appears in chunk text`() {
        val xml = "<root><data><![CDATA[cdata-content-sentinel]]></data></root>"
        val docs = XmlDocumentReader(xml).read()
        val allText = docs.joinToString("\n") { it.text ?: "" }
        assertThat(allText).contains("cdata-content-sentinel")
    }

    @Test
    fun `every chunk carries heading_title metadata`() {
        val xml = "<project><name>myapp</name></project>"
        val docs = XmlDocumentReader(xml).read()
        assertThat(docs).isNotEmpty()
        docs.forEach { doc ->
            assertThat(doc.metadata).containsKey("heading_title")
        }
    }

    @Test
    fun `every chunk carries heading_path metadata`() {
        val xml = "<project><name>myapp</name></project>"
        val docs = XmlDocumentReader(xml).read()
        assertThat(docs).isNotEmpty()
        docs.forEach { doc ->
            assertThat(doc.metadata).containsKey("heading_path")
        }
    }

    @Test
    fun `xml_root metadata key is absent from all chunks`() {
        val xml = "<project><name>myapp</name></project>"
        val docs = XmlDocumentReader(xml).read()
        assertThat(docs).isNotEmpty()
        docs.forEach { doc ->
            assertThat(doc.metadata).doesNotContainKey("xml_root")
        }
    }

    @Test
    fun `namespace prefix stripped from root element name in heading`() {
        val xml = """<ns:project xmlns:ns="http://example.com"><ns:name>app</ns:name></ns:project>"""
        val docs = XmlDocumentReader(xml).read()
        val allText = docs.joinToString("\n") { it.text ?: "" }
        assertThat(allText).contains("## project")
        assertThat(allText).doesNotContain("## ns:project")
    }

    @Test
    fun `empty root element with no text and no attributes returns empty list`() {
        val xml = "<root/>"
        val docs = XmlDocumentReader(xml).read()
        assertThat(docs).isEmpty()
    }

    @Test
    fun `large XML with many repeated elements produces at least 2 chunks`() {
        val items = (1..200).joinToString("\n") { i -> "<item><id>$i</id><description>This is item number $i with some description text to fill up the chunk</description></item>" }
        val xml = "<catalog>$items</catalog>"
        val docs = XmlDocumentReader(xml, chunkSize = 200, chunkOverlap = 20).read()
        assertThat(docs).hasSizeGreaterThan(1)
    }

    @Test
    fun `xml with only comments and whitespace produces empty list`() {
        val xml = "<!-- only a comment -->"
        val docs = XmlDocumentReader(xml).read()
        assertThat(docs).isNullOrEmpty()
    }

    @Test
    fun `XML with repeated siblings produces chunks with heading_path metadata containing element path`() {
        val xml = """<dependencies>
            <dependency><groupId>org.spring</groupId></dependency>
            <dependency><groupId>junit</groupId></dependency>
        </dependencies>"""
        val docs = XmlDocumentReader(xml).read()
        assertThat(docs).isNotEmpty()
        val allHeadingPaths = docs.flatMap {
            @Suppress("UNCHECKED_CAST")
            (it.metadata["heading_path"] as? List<String>) ?: emptyList()
        }
        assertThat(allHeadingPaths.any { it.contains("dependency") }).isTrue()
    }

    @Test
    fun `XML with no repeated siblings produces chunk containing all leaf content`() {
        val xml = "<config><host>localhost</host><port>5432</port><database>mydb</database></config>"
        val docs = XmlDocumentReader(xml).read()
        assertThat(docs).isNotEmpty()
        val allText = docs.joinToString("\n") { it.text ?: "" }
        assertThat(allText).contains("host: localhost")
        assertThat(allText).contains("port: 5432")
        assertThat(allText).contains("database: mydb")
    }
}
