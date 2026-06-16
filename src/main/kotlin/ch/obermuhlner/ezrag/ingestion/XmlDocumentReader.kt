package ch.obermuhlner.ezrag.ingestion

import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.springframework.ai.document.Document
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import java.io.File

class XmlDocumentReader(
    private val content: String,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
) {

    constructor(file: File, chunkSize: Int = 1000, chunkOverlap: Int = 200) :
        this(file.readText(Charsets.UTF_8), chunkSize, chunkOverlap)

    fun read(): List<Document> {
        val doc = Jsoup.parse(content, "", Parser.xmlParser())

        // Find the actual document root element (skip Jsoup's synthetic #root wrapper)
        val rootElement = doc.children().firstOrNull() ?: return emptyList()
        val xmlRoot = localName(rootElement.tagName())

        val lines = mutableListOf<String>()
        walkElement(rootElement, emptyList(), lines)

        if (lines.isEmpty()) return emptyList()

        val fullText = lines.joinToString("\n")

        val document = Document.builder()
            .text(fullText)
            .metadata(mapOf("xml_root" to xmlRoot))
            .build()

        val splitter = TokenTextSplitter.builder()
            .withChunkSize(chunkSize)
            .withMinChunkSizeChars(chunkOverlap)
            .withMinChunkLengthToEmbed(5)
            .withMaxNumChunks(10000)
            .withKeepSeparator(true)
            .build()

        val chunks = splitter.apply(listOf(document))
        return chunks.map { chunk ->
            val metadata = chunk.metadata.toMutableMap().apply { remove("source") }
            metadata["xml_root"] = xmlRoot
            Document.builder()
                .id(chunk.id)
                .text(chunk.text)
                .metadata(metadata)
                .build()
        }
    }

    private fun walkElement(element: Element, path: List<String>, lines: MutableList<String>) {
        val localName = localName(element.tagName())
        val currentPath = path + localName

        // Build attribute suffix (excluding xmlns and xmlns:* attributes)
        val attrSuffix = buildAttributeSuffix(element)

        val pathStr = currentPath.joinToString(" > ")
        val ownText = element.ownText().trim()

        // Emit attributes and/or text for this element
        if (attrSuffix.isNotEmpty() || ownText.isNotBlank()) {
            val line = if (attrSuffix.isNotEmpty() && ownText.isNotBlank()) {
                "$pathStr$attrSuffix: $ownText"
            } else if (attrSuffix.isNotEmpty()) {
                "$pathStr$attrSuffix"
            } else {
                "$pathStr: $ownText"
            }
            lines.add(line)
        }

        // Recurse into child elements (skip comment nodes — Jsoup represents them as Comment objects)
        for (child in element.childNodes()) {
            if (child is Element) {
                walkElement(child, currentPath, lines)
            }
            // Comments are skipped (they are Comment nodes, not Element nodes)
        }
    }

    private fun buildAttributeSuffix(element: Element): String {
        val sb = StringBuilder()
        for (attr in element.attributes()) {
            val key = attr.key
            // Skip xmlns and xmlns:* declarations
            if (key == "xmlns" || key.startsWith("xmlns:")) continue
            sb.append("[${key}=${attr.value}]")
        }
        return sb.toString()
    }

    private fun localName(tagName: String): String {
        return if (tagName.contains(':')) tagName.substringAfter(':') else tagName
    }
}
