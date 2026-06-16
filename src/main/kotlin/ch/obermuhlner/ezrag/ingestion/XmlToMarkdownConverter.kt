package ch.obermuhlner.ezrag.ingestion

import org.jsoup.Jsoup
import org.jsoup.nodes.CDataNode
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

class XmlToMarkdownConverter {

    fun convert(xmlContent: String): String {
        val doc = Jsoup.parse(xmlContent, "", Parser.xmlParser())

        // Find the actual document root element (skip Jsoup's synthetic #root wrapper)
        val rootElement = doc.children().firstOrNull() ?: return ""
        val rootName = localName(rootElement.tagName())

        // Partition root's direct children into repeated vs unique groups
        val directChildren = rootElement.children().toList()
        val countByTag = directChildren.groupBy { localName(it.tagName()) }
        val repeatedTags = countByTag.filter { it.value.size >= 2 }.keys
        val uniqueChildren = directChildren.filter { localName(it.tagName()) !in repeatedTags }
        val repeatedChildren = directChildren.filter { localName(it.tagName()) in repeatedTags }

        // If no repeated siblings anywhere, fallback to a single section
        if (repeatedTags.isEmpty()) {
            val lines = mutableListOf<String>()
            walkDescendants(rootElement, emptyList(), lines)
            if (lines.isEmpty()) return ""
            val sb = StringBuilder()
            sb.appendLine("## $rootName")
            lines.forEach { sb.appendLine(it) }
            return sb.toString()
        }

        val sb = StringBuilder()

        // Emit preamble section for unique children (if any)
        if (uniqueChildren.isNotEmpty()) {
            val preambleLines = mutableListOf<String>()
            for (child in uniqueChildren) {
                val childLocalName = localName(child.tagName())
                val attrSuffix = buildAttributeSuffix(child)
                val ownText = collectOwnText(child)
                if (attrSuffix.isNotEmpty() || ownText.isNotBlank()) {
                    val line = when {
                        attrSuffix.isNotEmpty() && ownText.isNotBlank() -> "$childLocalName$attrSuffix: $ownText"
                        attrSuffix.isNotEmpty() -> "$childLocalName$attrSuffix"
                        else -> "$childLocalName: $ownText"
                    }
                    preambleLines.add(line)
                }
                // Also include descendants of unique children with relative paths
                walkDescendants(child, listOf(childLocalName), preambleLines)
            }
            if (preambleLines.isNotEmpty()) {
                sb.appendLine("## $rootName")
                preambleLines.forEach { sb.appendLine(it) }
            }
        }

        // Emit one section per repeated element
        for (child in repeatedChildren) {
            val childLocalName = localName(child.tagName())
            val attrSuffix = buildAttributeSuffix(child)
            val headingPath = "$rootName > $childLocalName$attrSuffix"
            val bodyLines = mutableListOf<String>()
            walkDescendants(child, emptyList(), bodyLines)
            sb.appendLine("## $headingPath")
            bodyLines.forEach { sb.appendLine(it) }
        }

        return sb.toString()
    }

    /**
     * Walk the descendants of [element], collecting body lines with paths relative to [element].
     * [parentPath] contains path segments from [element] to the parent of the current node.
     */
    private fun walkDescendants(element: Element, parentPath: List<String>, lines: MutableList<String>) {
        for (child in element.childNodes()) {
            when (child) {
                is Element -> {
                    val childLocalName = localName(child.tagName())
                    val childPath = parentPath + childLocalName
                    val attrSuffix = buildAttributeSuffix(child)
                    val ownText = collectOwnText(child)

                    if (attrSuffix.isNotEmpty() || ownText.isNotBlank()) {
                        val pathStr = childPath.joinToString(" > ")
                        val line = when {
                            attrSuffix.isNotEmpty() && ownText.isNotBlank() -> "$pathStr$attrSuffix: $ownText"
                            attrSuffix.isNotEmpty() -> "$pathStr$attrSuffix"
                            else -> "$pathStr: $ownText"
                        }
                        lines.add(line)
                    }

                    // Recurse into children
                    walkDescendants(child, childPath, lines)
                }
                // Comments and other non-element, non-text nodes are skipped
            }
        }
    }

    /**
     * Collect the element's own text (excluding child element text),
     * including CDATA content, but skipping comment nodes and whitespace-only text.
     */
    private fun collectOwnText(element: Element): String {
        val parts = mutableListOf<String>()
        for (child in element.childNodes()) {
            when (child) {
                is CDataNode -> {
                    val text = child.text().trim()
                    if (text.isNotBlank()) parts.add(text)
                }
                is TextNode -> {
                    val text = child.text().trim()
                    if (text.isNotBlank()) parts.add(text)
                }
                is Comment -> { /* skip */ }
                // Child elements are not included in "own text"
            }
        }
        return parts.joinToString(" ")
    }

    private fun buildAttributeSuffix(element: Element): String {
        val sb = StringBuilder()
        for (attr in element.attributes()) {
            val key = attr.key
            // Skip xmlns and xmlns:* declarations
            if (key == "xmlns" || key.startsWith("xmlns:")) continue
            sb.append("[$key=${attr.value}]")
        }
        return sb.toString()
    }

    private fun localName(tagName: String): String {
        return if (tagName.contains(':')) tagName.substringAfter(':') else tagName
    }
}
