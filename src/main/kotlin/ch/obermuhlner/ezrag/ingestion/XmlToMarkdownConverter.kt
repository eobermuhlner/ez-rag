package ch.obermuhlner.ezrag.ingestion

import org.jsoup.Jsoup
import org.jsoup.nodes.CDataNode
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser

class XmlToMarkdownConverter(private val chunkSize: Int = 1000) {

    fun convert(xmlContent: String, boundaryTags: List<String> = emptyList()): String {
        val doc = Jsoup.parse(xmlContent, "", Parser.xmlParser())

        // Find the actual document root element (skip Jsoup's synthetic #root wrapper)
        val rootElement = doc.children().firstOrNull() ?: return ""

        val sb = StringBuilder()
        if (boundaryTags.isNotEmpty()) {
            emitWithBoundaryTags(rootElement, emptyList(), boundaryTags, sb)
        } else {
            emit(rootElement, emptyList(), sb)
        }
        return sb.toString()
    }

    /**
     * Emit Markdown sections for elements matching [boundaryTags], walking the entire tree.
     * Full ancestor path is used as the heading prefix. Small sibling merging applies.
     * [ancestorPath] contains local names of ancestors above [element] (not including [element]).
     */
    private fun emitWithBoundaryTags(
        element: Element,
        ancestorPath: List<String>,
        boundaryTags: List<String>,
        sb: StringBuilder
    ) {
        val elementLocalName = localName(element.tagName())
        val currentPath = ancestorPath + elementLocalName

        val matchingChildren = element.children().filter { localName(it.tagName()) in boundaryTags }
        val nonMatchingChildren = element.children().filter { localName(it.tagName()) !in boundaryTags }

        if (matchingChildren.isNotEmpty()) {
            // Emit all matching children as boundary elements with merging
            val headingPrefix = currentPath.joinToString(" > ")
            emitRepeatedWithMerging(matchingChildren, headingPrefix, sb)
        }

        // Recurse into non-matching children to find deeper boundary-tag elements
        for (child in nonMatchingChildren) {
            emitWithBoundaryTags(child, currentPath, boundaryTags, sb)
        }
    }

    /**
     * Recursively emit Markdown sections for [element] and its descendants.
     * [ancestorPath] contains the local names of all ancestors above [element] (not including [element] itself).
     */
    private fun emit(element: Element, ancestorPath: List<String>, sb: StringBuilder) {
        val elementLocalName = localName(element.tagName())
        val currentPath = ancestorPath + elementLocalName

        // Partition direct children into repeated vs unique groups
        val directChildren = element.children().toList()
        val countByTag = directChildren.groupBy { localName(it.tagName()) }
        val repeatedTags = countByTag.filter { it.value.size >= 2 }.keys

        if (repeatedTags.isEmpty()) {
            // No repeated siblings at this level — either recurse into unique children
            // looking for repeated descendants, or emit a single fallback section.
            if (anyChildHasRepeatedDescendants(element)) {
                // Some child has repeated descendants deeper — recurse into each child
                for (child in directChildren) {
                    emit(child, currentPath, sb)
                }
            } else {
                // Truly flat — emit single fallback section
                val lines = mutableListOf<String>()
                walkDescendants(element, emptyList(), lines)
                if (lines.isEmpty()) return
                sb.appendLine("## ${currentPath.joinToString(" > ")}")
                lines.forEach { sb.appendLine(it) }
            }
            return
        }

        // There ARE repeated tags at this level.
        // Check if any of the repeated children themselves have repeated descendants.
        // If so, the repeated children are containers — recurse into them.
        // If not, this is the actual boundary level.

        val repeatedChildren = directChildren.filter { localName(it.tagName()) in repeatedTags }

        // Do any repeated children have repeated descendants?
        val repeatedChildrenWithRepeatedDesc = repeatedChildren.filter { hasRepeatedDescendants(it) }

        if (repeatedChildrenWithRepeatedDesc.isNotEmpty()) {
            // Repeated children are containers — recurse into each child (both repeated and unique)
            for (child in directChildren) {
                emit(child, currentPath, sb)
            }
            return
        }

        // This is the boundary level.
        // Split unique siblings into preamble (before/among the repeated block) and epilogue
        // (strictly after the last repeated element), so suffix content like <system-out> is
        // not mislabeled as preamble.
        val lastRepeatedIdx = directChildren.indexOfLast { localName(it.tagName()) in repeatedTags }
        val uniquesForPreamble = directChildren.filterIndexed { idx, el ->
            localName(el.tagName()) !in repeatedTags && idx < lastRepeatedIdx
        }
        val uniquesAfter = directChildren.filterIndexed { idx, el ->
            localName(el.tagName()) !in repeatedTags && idx > lastRepeatedIdx
        }

        // Emit preamble: parent element's own attributes first, then unique children before repeated
        val elementAttrSuffix = buildAttributeSuffix(element)
        val hasElementAttrs = elementAttrSuffix.isNotEmpty()

        if (hasElementAttrs || uniquesForPreamble.isNotEmpty()) {
            val preambleLines = mutableListOf<String>()
            // Include the boundary element's own attributes as the first body line
            if (hasElementAttrs) {
                val ownText = collectOwnText(element)
                val line = when {
                    ownText.isNotBlank() -> "$elementLocalName$elementAttrSuffix: $ownText"
                    else -> "$elementLocalName$elementAttrSuffix"
                }
                preambleLines.add(line)
            }
            for (child in uniquesForPreamble) {
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
                walkDescendants(child, listOf(childLocalName), preambleLines)
            }
            if (preambleLines.isNotEmpty()) {
                sb.appendLine("## ${currentPath.joinToString(" > ")}")
                preambleLines.forEach { sb.appendLine(it) }
            }
        }

        // Emit one section per repeated element, with small sibling merging
        val headingPrefix = currentPath.joinToString(" > ")
        emitRepeatedWithMerging(repeatedChildren, headingPrefix, sb)

        // Emit epilogue section for unique children after the last repeated element
        if (uniquesAfter.isNotEmpty()) {
            val epilogueLines = mutableListOf<String>()
            for (child in uniquesAfter) {
                val childLocalName = localName(child.tagName())
                val attrSuffix = buildAttributeSuffix(child)
                val ownText = collectOwnText(child)
                if (attrSuffix.isNotEmpty() || ownText.isNotBlank()) {
                    val line = when {
                        attrSuffix.isNotEmpty() && ownText.isNotBlank() -> "$childLocalName$attrSuffix: $ownText"
                        attrSuffix.isNotEmpty() -> "$childLocalName$attrSuffix"
                        else -> "$childLocalName: $ownText"
                    }
                    epilogueLines.add(line)
                }
                walkDescendants(child, listOf(childLocalName), epilogueLines)
            }
            if (epilogueLines.isNotEmpty()) {
                sb.appendLine("## ${currentPath.joinToString(" > ")}")
                epilogueLines.forEach { sb.appendLine(it) }
            }
        }
    }

    /**
     * Emit sections for repeated boundary elements, merging adjacent small elements
     * into a single Markdown section to avoid many tiny chunks.
     *
     * An element is "small" if its body text is below [chunkSize] × 3 characters.
     * A batch is flushed when accumulated body text reaches [chunkSize] × 12 characters
     * or when a non-small element is encountered.
     */
    private fun emitRepeatedWithMerging(
        repeatedChildren: List<Element>,
        headingPrefix: String,
        sb: StringBuilder
    ) {
        val smallThreshold = chunkSize * 3
        val flushThreshold = chunkSize * 12

        val batchLines = mutableListOf<String>()
        var batchText = 0
        var batchHeadingTag = ""

        fun flushBatch() {
            if (batchLines.isEmpty()) return
            sb.appendLine("## $headingPrefix > $batchHeadingTag")
            batchLines.forEach { sb.appendLine(it) }
            batchLines.clear()
            batchText = 0
        }

        for (child in repeatedChildren) {
            val childLocalName = localName(child.tagName())
            val attrSuffix = buildAttributeSuffix(child)
            val bodyLines = mutableListOf<String>()
            walkDescendants(child, emptyList(), bodyLines)

            // Compute the own text + body text for size estimation
            val ownText = collectOwnText(child)
            val bodyText = bodyLines.joinToString("\n")
            val elementText = if (ownText.isNotBlank()) "$ownText\n$bodyText" else bodyText
            val elementSize = elementText.length

            val isSmall = elementSize < smallThreshold

            if (!isSmall) {
                // Flush current batch first, then emit this large element alone
                flushBatch()
                val headingPath = "$headingPrefix > $childLocalName$attrSuffix"
                sb.appendLine("## $headingPath")
                bodyLines.forEach { sb.appendLine(it) }
            } else {
                // Flush if tag type changed or adding this element would exceed flush threshold
                if (batchText > 0 && (childLocalName != batchHeadingTag || batchText + elementSize >= flushThreshold)) {
                    flushBatch()
                }

                // Add element to batch using localName[attrs]: prefix line
                val prefixLine = when {
                    attrSuffix.isNotEmpty() && ownText.isNotBlank() -> "$childLocalName$attrSuffix: $ownText"
                    attrSuffix.isNotEmpty() -> "$childLocalName$attrSuffix"
                    ownText.isNotBlank() -> "$childLocalName: $ownText"
                    else -> "$childLocalName:"
                }
                batchLines.add(prefixLine)
                bodyLines.forEach { batchLines.add("  $it") }
                batchText += elementSize
                batchHeadingTag = childLocalName
            }
        }

        // Flush any remaining batch
        flushBatch()
    }

    /**
     * Returns true if [element] itself has any direct children that appear more than once (same tag),
     * or if any descendant does.
     */
    private fun hasRepeatedDescendants(element: Element): Boolean {
        val directChildren = element.children().toList()
        val countByTag = directChildren.groupBy { localName(it.tagName()) }
        if (countByTag.any { it.value.size >= 2 }) return true
        return directChildren.any { hasRepeatedDescendants(it) }
    }

    /**
     * Returns true if any direct child of [element] has repeated descendants (but not [element] itself).
     */
    private fun anyChildHasRepeatedDescendants(element: Element): Boolean {
        return element.children().any { hasRepeatedDescendants(it) }
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
