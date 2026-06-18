package ch.obermuhlner.ezrag.ingestion

import ch.obermuhlner.ezrag.ingestion.office.PowerPointFixtureGenerator
import ch.obermuhlner.ezrag.ingestion.office.WordToMarkdownConverter
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Path
import java.nio.file.Paths

class DocumentReaderRegistryTest {

    @Test
    fun `supports returns true for txt, pdf, md, docx, doc, pptx, ppt, xlsx, xls, html, htm, and rtf`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)

        assertThat(registry.supports("txt")).isTrue()
        assertThat(registry.supports("pdf")).isTrue()
        assertThat(registry.supports("md")).isTrue()
        assertThat(registry.supports("docx")).isTrue()
        assertThat(registry.supports("doc")).isTrue()
        assertThat(registry.supports("pptx")).isTrue()
        assertThat(registry.supports("ppt")).isTrue()
        assertThat(registry.supports("xlsx")).isTrue()
        assertThat(registry.supports("xls")).isTrue()
        assertThat(registry.supports("html")).isTrue()
        assertThat(registry.supports("htm")).isTrue()
        assertThat(registry.supports("rtf")).isTrue()
    }

    @Test
    fun `supports returns true for html`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        assertThat(registry.supports("html")).isTrue()
    }

    @Test
    fun `supports returns true for htm`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        assertThat(registry.supports("htm")).isTrue()
    }

    @Test
    fun `read dispatches html file and produces at least one chunk`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("sample.html").toFile()
        file.writeText("<html><head><title>Test</title></head><body><h2>Test Section</h2><p>Content here.</p></body></html>")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `read dispatches htm file and produces at least one chunk`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("sample.htm").toFile()
        file.writeText("<html><head><title>Test</title></head><body><h2>Test Section</h2><p>Content here.</p></body></html>")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `supports returns true for rtf`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        assertThat(registry.supports("rtf")).isTrue()
    }

    @Test
    fun `read dispatches rtf file and produces at least one chunk`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("sample.rtf").toFile()
        file.writeText("{\\rtf1\\ansi Hello world content for registry dispatch test.}")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `supports returns false for unknown extensions`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)

        assertThat(registry.supports("odt")).isFalse()
    }

    @Test
    fun `read falls back to plain text for unknown extension containing plain text`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("config.yaml").toFile()
        file.writeText("key: value\nother: data\n")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `read throws IllegalArgumentException for unknown extension file containing null byte`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("data.xyz").toFile()
        file.writeBytes(byteArrayOf(0x68, 0x00, 0x65, 0x6c, 0x6c, 0x6f))

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            registry.read(file)
        }
    }

    @Test
    fun `read falls back to plain text for file with no extension`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("Makefile").toFile()
        file.writeText("all:\n\techo hello\n")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `read throws IllegalArgumentException for no-extension file containing null byte`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("Dockerfile").toFile()
        file.writeBytes(byteArrayOf(0x46, 0x52, 0x4f, 0x4d, 0x00, 0x62, 0x61, 0x73, 0x65))

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            registry.read(file)
        }
    }

    @Test
    fun `supports returns true for csv`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        assertThat(registry.supports("csv")).isTrue()
    }

    @Test
    fun `read dispatches txt file and produces at least one chunk`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("sample.txt").toFile()
        file.writeText("Hello plain text content for registry dispatch test.")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `read dispatches md file and produces at least one chunk`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("sample.md").toFile()
        file.writeText("Hello markdown content for registry dispatch test.")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `read dispatches pdf file and produces at least one chunk`() {
        val path = Paths.get(javaClass.getResource("/documents/sample.pdf")!!.toURI())
        val file = path.toFile()

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `supports returns true for docx`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        assertThat(registry.supports("docx")).isTrue()
    }

    @Test
    fun `read dispatches docx file and produces at least one chunk`(@TempDir tempDir: Path) {
        // Create a minimal DOCX fixture inline for this test
        val docxFile = tempDir.resolve("test.docx").toFile()
        XWPFDocument().use { doc ->
            val h1 = doc.createParagraph()
            h1.style = "Heading1"
            h1.createRun().setText("Test Heading")
            val para = doc.createParagraph()
            para.createRun().setText("Test paragraph content for registry dispatch test.")
            FileOutputStream(docxFile).use { fos -> doc.write(fos) }
        }

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(docxFile)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `supports returns true for xlsx`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        assertThat(registry.supports("xlsx")).isTrue()
    }

    @Test
    fun `read dispatches xlsx file and produces at least one chunk`() {
        ch.obermuhlner.ezrag.ingestion.office.ExcelFixtureGenerator.createXlsxFixture(
            ch.obermuhlner.ezrag.ingestion.office.ExcelFixtureGenerator.xlsxFile
        )
        val xlsxFile = ch.obermuhlner.ezrag.ingestion.office.ExcelFixtureGenerator.xlsxFile

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(xlsxFile)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `read dispatches csv file and produces chunks`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("data.csv").toFile()
        file.writeText("col1,col2\nval1,val2\n")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
        val allText = documents.joinToString(" ") { it.text ?: "" }
        assertThat(allText).contains("col1")
        assertThat(allText).contains("val1")
    }

    @Test
    fun `supports returns true for pptx`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        assertThat(registry.supports("pptx")).isTrue()
    }

    @Test
    fun `read dispatches pptx file and produces at least one chunk`() {
        PowerPointFixtureGenerator.createPptxFixture(PowerPointFixtureGenerator.pptxFile)
        val pptxFile = PowerPointFixtureGenerator.pptxFile

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(pptxFile)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `supports returns true for xml, svg, rss, atom, xhtml`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        assertThat(registry.supports("xml")).isTrue()
        assertThat(registry.supports("svg")).isTrue()
        assertThat(registry.supports("rss")).isTrue()
        assertThat(registry.supports("atom")).isTrue()
        assertThat(registry.supports("xhtml")).isTrue()
    }

    @Test
    fun `read dispatches xml file and produces at least one chunk`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("sample.xml").toFile()
        file.writeText("<root><item>content</item></root>")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `read dispatches svg file and produces at least one chunk`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("sample.svg").toFile()
        file.writeText("<svg><title>Test SVG</title></svg>")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `read dispatches rss file and produces at least one chunk`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("sample.rss").toFile()
        file.writeText("<rss><channel><item><title>Latest Release</title></item></channel></rss>")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `read dispatches atom file and produces at least one chunk`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("sample.atom").toFile()
        file.writeText("<feed><entry><title>My Entry</title></entry></feed>")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `read dispatches xhtml file with h1 heading and chunk carries heading_title metadata`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("sample.xhtml").toFile()
        file.writeText("""<!DOCTYPE html><html><body><h1>Installation Guide</h1><p>Follow these steps to install.</p></body></html>""")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
        assertThat(documents.any { it.metadata.containsKey("heading_title") }).isTrue()
        assertThat(documents.any { (it.metadata["heading_title"] as? String)?.contains("Installation Guide") == true }).isTrue()
    }

    @Test
    fun `supports returns true for kt and kts`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        assertThat(registry.supports("kt")).isTrue()
        assertThat(registry.supports("kts")).isTrue()
    }

    @Test
    fun `read dispatches kt file using source-code-aware reader and produces chunks with language kotlin`() {
        val file = Paths.get(javaClass.getResource("/fixtures/sample.kt")!!.toURI()).toFile()

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
        assertThat(documents.any { it.metadata["language"] == "kotlin" }).isTrue()
        assertThat(documents.any { it.metadata["declaration_type"] == "class" }).isTrue()
    }

    @Test
    fun `read dispatches kts file using source-code-aware reader and produces chunks with language kotlin`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("build.kts").toFile()
        file.writeText("fun greet(name: String) {\n    println(\"Hello, \${name}!\")\n}")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
        assertThat(documents.any { it.metadata["language"] == "kotlin" }).isTrue()
    }

    @Test
    fun `supports returns true for java`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        assertThat(registry.supports("java")).isTrue()
    }

    @Test
    fun `read dispatches java file using source-code-aware reader and produces chunks with language java`() {
        val file = Paths.get(javaClass.getResource("/fixtures/sample.java")!!.toURI()).toFile()

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
        assertThat(documents.any { it.metadata["language"] == "java" }).isTrue()
        assertThat(documents.any { it.metadata["declaration_type"] == "class" }).isTrue()
    }

    @Test
    fun `supports returns true for ts, tsx, js, jsx`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        assertThat(registry.supports("ts")).isTrue()
        assertThat(registry.supports("tsx")).isTrue()
        assertThat(registry.supports("js")).isTrue()
        assertThat(registry.supports("jsx")).isTrue()
    }

    @Test
    fun `read dispatches ts file using source-code-aware reader and produces chunks with language typescript`() {
        val file = Paths.get(javaClass.getResource("/fixtures/sample.ts")!!.toURI()).toFile()

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
        assertThat(documents.any { it.metadata["language"] == "typescript" }).isTrue()
        assertThat(documents.any { it.metadata["declaration_type"] == "class" }).isTrue()
    }

    @Test
    fun `read dispatches tsx file using source-code-aware reader and produces chunks with language typescript`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("Sample.tsx").toFile()
        file.writeText("""
            class MyComponent {
                render(): void {
                    console.log("rendering");
                }
            }
        """.trimIndent())

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
        assertThat(documents.any { it.metadata["language"] == "typescript" }).isTrue()
    }

    @Test
    fun `read dispatches js file using source-code-aware reader and produces chunks with language javascript`() {
        val file = Paths.get(javaClass.getResource("/fixtures/sample.js")!!.toURI()).toFile()

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
        assertThat(documents.any { it.metadata["language"] == "javascript" }).isTrue()
    }

    @Test
    fun `read dispatches jsx file using source-code-aware reader and produces chunks with language javascript`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("sample.jsx").toFile()
        file.writeText("""
            class Sample {
                greet() {
                    console.log("hello");
                }
            }
        """.trimIndent())

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
        assertThat(documents.any { it.metadata["language"] == "javascript" }).isTrue()
    }

    @Test
    fun `read falls through to plain text for go file without error`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("main.go").toFile()
        file.writeText("""
            package main

            import "fmt"

            func main() {
                fmt.Println("Hello, World!")
            }
        """.trimIndent())

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        // Go falls through to plain-text — should produce at least one chunk, no language metadata
        assertThat(documents).isNotEmpty()
        assertThat(documents.none { it.metadata.containsKey("language") }).isTrue()
    }

    @Test
    fun `supports returns true for json`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        assertThat(registry.supports("json")).isTrue()
    }

    @Test
    fun `read dispatches json file using fixture and produces chunks containing all records`() {
        val file = Paths.get(javaClass.getResource("/fixtures/sample.json")!!.toURI()).toFile()

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
        val allText = documents.joinToString("\n") { it.text ?: "" }
        assertThat(allText).contains("Alice Johnson")
        assertThat(allText).contains("BM25")
    }

    @Test
    fun `supports returns true for jsonc`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        assertThat(registry.supports("jsonc")).isTrue()
    }

    @Test
    fun `read dispatches jsonc file using fixture and produces chunks without comment text`() {
        val file = Paths.get(javaClass.getResource("/fixtures/sample.jsonc")!!.toURI()).toFile()

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
        val allText = documents.joinToString("\n") { it.text ?: "" }
        assertThat(allText).contains("hybrid")
        assertThat(allText).doesNotContain("// ez-rag application configuration")
    }

    @Test
    fun `supports returns true for jsonl`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        assertThat(registry.supports("jsonl")).isTrue()
    }

    @Test
    fun `read dispatches jsonl file using fixture and produces chunks containing all records`() {
        val file = Paths.get(javaClass.getResource("/fixtures/sample.jsonl")!!.toURI()).toFile()

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
        val allText = documents.joinToString("\n") { it.text ?: "" }
        assertThat(allText).contains("Ingestion job started")
        assertThat(allText).contains("Ingestion job completed")
    }

    @Test
    fun `supports returns true for adoc and asciidoc`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        assertThat(registry.supports("adoc")).isTrue()
        assertThat(registry.supports("asciidoc")).isTrue()
    }

    @Test
    fun `read dispatches adoc file and produces at least one chunk`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("sample.adoc").toFile()
        file.writeText("= My Document\n\nThis is the body of the document.")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `read dispatches asciidoc file and produces at least one chunk`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("sample.asciidoc").toFile()
        file.writeText("= My Document\n\nThis is the body of the document.")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `adoc registry with small chunkSize produces multiple chunks for large section`(@TempDir tempDir: Path) {
        val loremBody = (1..30).joinToString("\n\n") { "Paragraph $it with enough words to consume token budget." }
        val file = tempDir.resolve("large.adoc").toFile()
        file.writeText("= Big Section\n\n$loremBody")

        val registry = DocumentReaderRegistry(chunkSize = 50, chunkOverlap = 10)
        val documents = registry.read(file)

        assertThat(documents.size).isGreaterThan(1)
    }

    @Test
    fun `supports returns true for rst`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        assertThat(registry.supports("rst")).isTrue()
    }

    @Test
    fun `read dispatches rst file and produces at least one chunk`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("sample.rst").toFile()
        file.writeText("My Document\n===========\n\nThis is the body of the document.")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        val documents = registry.read(file)

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `rst registry with small chunkSize produces multiple chunks for large section`(@TempDir tempDir: Path) {
        val loremBody = (1..30).joinToString("\n\n") { "Paragraph $it with enough words to consume token budget." }
        val file = tempDir.resolve("large.rst").toFile()
        file.writeText("Big Section\n===========\n\n$loremBody")

        val registry = DocumentReaderRegistry(chunkSize = 50, chunkOverlap = 10)
        val documents = registry.read(file)

        assertThat(documents.size).isGreaterThan(1)
    }

    @Test
    fun `supports returns false for asc`() {
        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)
        assertThat(registry.supports("asc")).isFalse()
    }

    @Test
    fun `mixed format directory produces at least one chunk from each of adoc, asciidoc, rst, and md files`(@TempDir tempDir: Path) {
        val adocFile = tempDir.resolve("doc.adoc").toFile()
        adocFile.writeText("= AsciiDoc Document\n\nThis is an AsciiDoc file with some content.")

        val asciidocFile = tempDir.resolve("doc.asciidoc").toFile()
        asciidocFile.writeText("= AsciiDoc Extended\n\nThis is an asciidoc file with some content.")

        val rstFile = tempDir.resolve("doc.rst").toFile()
        rstFile.writeText("RST Document\n============\n\nThis is a reStructuredText file with some content.")

        val mdFile = tempDir.resolve("doc.md").toFile()
        mdFile.writeText("# Markdown Document\n\nThis is a Markdown file with some content.")

        val registry = DocumentReaderRegistry(chunkSize = 1000, chunkOverlap = 200)

        val adocChunks = registry.read(adocFile)
        val asciidocChunks = registry.read(asciidocFile)
        val rstChunks = registry.read(rstFile)
        val mdChunks = registry.read(mdFile)

        assertThat(adocChunks).isNotEmpty()
        assertThat(asciidocChunks).isNotEmpty()
        assertThat(rstChunks).isNotEmpty()
        assertThat(mdChunks).isNotEmpty()
    }
}
