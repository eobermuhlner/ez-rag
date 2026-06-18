package ch.obermuhlner.ezrag.ingestion

import org.treesitter.TSNode
import org.treesitter.TSParser
import org.treesitter.TreeSitterJson

/**
 * Tree-sitter–based chunker for JSON, JSONL, and JSONC source text.
 *
 * Handles all three JSON variants. Parses the raw source text
 * into a concrete syntax tree (CST) using the Tree-sitter JSON grammar. Comments
 * are treated as first-class CST nodes and rendered as prose alongside the
 * key-value pairs they annotate.
 *
 * Heading formats:
 * - Root object: `##` (bare)
 * - Nested object: `## k1 -> k2` (arrow-separated ancestor path)
 * - Multi-element array batch: `## Items N-M` (1-based)
 * - Single-element array batch: `## Item N`
 * - Nested array: `## parent -> Items N-M`
 */
class JsoncChunker(
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
    private val tokenCounter: (String) -> Int = TokenCounter::countTokens,
) {

    private val tsLanguage = TreeSitterJson()

    /**
     * Chunk [source] text (JSON, JSONL synthetic array, or JSONC) into Markdown chunk strings.
     * Returns an empty list for blank or empty input, or for an empty root node.
     */
    fun chunk(source: String): List<String> {
        if (source.isBlank()) return emptyList()

        val parser = TSParser()
        parser.setLanguage(tsLanguage)
        val tree = parser.parseString(null, source)
        val root = tree.rootNode

        // Find the actual document node (first named child of the program node)
        val documentNode = findDocumentNode(root) ?: return emptyList()

        // Collect file-level comments: all comment nodes before the document node
        val fileLevelComments = collectFileLevelComments(root, documentNode, source)
        val preamble = if (fileLevelComments.isNotEmpty()) fileLevelComments.joinToString(" ") { normaliseComment(it, source) } else null
        val effectiveBudget = if (preamble != null) chunkSize - tokenCounter(preamble) else chunkSize

        val rawChunks = when (documentNode.type) {
            "object" -> chunkObjectWithBudget(documentNode, source, emptyList(), effectiveBudget)
            "array" -> chunkArrayWithBudget(documentNode, source, emptyList(), effectiveBudget)
            else -> emptyList()
        }

        return if (preamble != null) {
            rawChunks.map { "$preamble\n\n$it" }
        } else {
            rawChunks
        }
    }

    // ---- Object chunking ----

    private fun chunkObjectWithBudget(node: TSNode, source: String, headingPath: List<String>, budget: Int): List<String> {
        // Collect pairs with their preceding and trailing comments
        val pairsWithComments = collectPairsWithComments(node, source)
        if (pairsWithComments.isEmpty()) return emptyList()

        val result = mutableListOf<String>()
        val heading = buildObjectHeading(headingPath)
        val accumulatorLines = mutableListOf<String>()

        fun currentText(): String =
            if (accumulatorLines.isEmpty()) heading
            else "$heading\n\n${accumulatorLines.joinToString("\n\n")}"

        fun flush() {
            if (accumulatorLines.isNotEmpty()) {
                result.add(currentText())
                accumulatorLines.clear()
            }
        }

        for ((pair, precedingComments, trailingComment) in pairsWithComments) {
            val key = extractPairKey(pair, source)
            val valueNode = findValueNode(pair) ?: continue

            when {
                valueNode.type == "object" -> {
                    flush()
                    val subChunks = chunkObjectWithBudget(valueNode, source, headingPath + key, budget)
                    val commentProse = renderPrecedingComments(precedingComments, source)
                    if (commentProse != null && subChunks.isNotEmpty()) {
                        result.add("$commentProse\n\n${subChunks.first()}")
                        result.addAll(subChunks.drop(1))
                    } else {
                        result.addAll(subChunks)
                    }
                }
                valueNode.type == "array" -> {
                    flush()
                    val subChunks = chunkArrayWithBudget(valueNode, source, headingPath + key, budget)
                    val commentProse = renderPrecedingComments(precedingComments, source)
                    if (commentProse != null && subChunks.isNotEmpty()) {
                        result.add("$commentProse\n\n${subChunks.first()}")
                        result.addAll(subChunks.drop(1))
                    } else {
                        result.addAll(subChunks)
                    }
                }
                else -> {
                    val commentProse = renderPrecedingComments(precedingComments, source)
                    val fieldLine = renderPair(key, valueNode, source, trailingComment)
                    val line = if (commentProse != null) "$commentProse\n$fieldLine" else fieldLine

                    val textWithLine = if (accumulatorLines.isEmpty()) "$heading\n\n$line"
                    else "$heading\n\n${accumulatorLines.joinToString("\n\n")}\n\n$line"

                    when {
                        tokenCounter(textWithLine) <= budget -> accumulatorLines.add(line)
                        accumulatorLines.isEmpty() -> result.add("$heading\n\n$line")
                        else -> {
                            flush()
                            val fresh = "$heading\n\n$line"
                            if (tokenCounter(fresh) <= budget) accumulatorLines.add(line)
                            else result.add(fresh)
                        }
                    }
                }
            }
        }

        flush()
        return result
    }

    // ---- Array chunking ----

    private fun chunkArrayWithBudget(node: TSNode, source: String, headingPath: List<String>, budget: Int): List<String> {
        val elementsWithComments = collectArrayElementsWithComments(node, source)
        if (elementsWithComments.isEmpty()) return emptyList()

        val result = mutableListOf<String>()
        val currentBatch = mutableListOf<Pair<Int, String>>() // (1-based index, rendered text)
        var nextIndex = 1

        fun buildBatchText(batch: List<Pair<Int, String>>): String {
            val heading = buildArrayHeading(headingPath, batch)
            val body = batch.joinToString("\n\n") { (_, text) -> text }
            return "$heading\n\n$body"
        }

        fun flush() {
            if (currentBatch.isNotEmpty()) {
                result.add(buildBatchText(currentBatch))
                currentBatch.clear()
            }
        }

        for ((element, precedingComments, trailingComment) in elementsWithComments) {
            val commentProse = renderPrecedingComments(precedingComments, source)
            val elemText = renderArrayElement(element, source, trailingComment)
            val fullElemText = if (commentProse != null) "$commentProse\n$elemText" else elemText
            val elemIndex = nextIndex++

            if (element.type == "object") {
                flush()
                result.add(buildBatchText(listOf(elemIndex to fullElemText)))
                continue
            }

            val candidate = if (currentBatch.isEmpty()) {
                buildBatchText(listOf(elemIndex to fullElemText))
            } else {
                buildBatchText(currentBatch + (elemIndex to fullElemText))
            }

            when {
                tokenCounter(candidate) <= budget -> currentBatch.add(elemIndex to fullElemText)
                currentBatch.isEmpty() -> result.add(buildBatchText(listOf(elemIndex to fullElemText)))
                else -> {
                    flush()
                    val fresh = buildBatchText(listOf(elemIndex to fullElemText))
                    if (tokenCounter(fresh) <= budget) currentBatch.add(elemIndex to fullElemText)
                    else result.add(fresh)
                }
            }
        }

        flush()
        return result
    }

    // ---- Rendering helpers ----

    private fun renderPair(key: String, valueNode: TSNode, source: String, trailingComment: TSNode? = null): String {
        val valueText = nodeText(valueNode, source)
        val base = when (valueNode.type) {
            "string" -> "**$key**: ${unquoteString(valueText)}"
            "number" -> "**$key**: $valueText"
            "true", "false" -> "**$key**: $valueText"
            "null" -> "**$key**: null"
            "object", "array" -> "**$key**:\n```json\n$valueText\n```"
            else -> "**$key**: $valueText"
        }
        return if (trailingComment != null) "$base - ${normaliseComment(trailingComment, source)}" else base
    }

    private fun renderArrayElement(element: TSNode, source: String, trailingComment: TSNode? = null): String {
        val base = when (element.type) {
            "object" -> renderObjectInline(element, source)
            "array" -> nodeText(element, source)
            "string" -> unquoteString(nodeText(element, source))
            else -> nodeText(element, source)
        }
        if (trailingComment == null) return base
        // Append trailing comment to the last line of the rendered element
        val lines = base.lines().toMutableList()
        if (lines.isNotEmpty()) {
            lines[lines.lastIndex] = "${lines.last()} - ${normaliseComment(trailingComment, source)}"
        }
        return lines.joinToString("\n")
    }

    private fun renderObjectInline(objNode: TSNode, source: String): String {
        val pairs = collectNamedChildrenOfType(objNode, "pair")
        if (pairs.isEmpty()) return "{}"
        return pairs.joinToString("\n") { pair ->
            val key = extractPairKey(pair, source)
            val valueNode = findValueNode(pair)
            if (valueNode != null) renderPair(key, valueNode, source)
            else "**$key**: (unknown)"
        }
    }

    // ---- Comment rendering helpers ----

    /**
     * Normalises a comment node's text into readable prose.
     * - Line comment (`// text`): strips `//` prefix and trims.
     * - Block comment (`/* text */`): strips delimiters, collapses all internal whitespace runs to a single space, trims.
     */
    private fun normaliseComment(commentNode: TSNode, source: String): String {
        val raw = nodeText(commentNode, source).trim()
        return when {
            raw.startsWith("//") -> raw.removePrefix("//").trim()
            raw.startsWith("/*") -> raw
                .removePrefix("/*")
                .removeSuffix("*/")
                .replace(Regex("\\s+"), " ")
                .trim()
            else -> raw
        }
    }

    /**
     * Renders a list of preceding comment nodes as a single prose string, or null if empty.
     * Multiple comments are joined with a space.
     */
    private fun renderPrecedingComments(comments: List<TSNode>, source: String): String? {
        if (comments.isEmpty()) return null
        return comments.joinToString(" ") { normaliseComment(it, source) }
    }

    // ---- Tree-sitter navigation helpers ----

    /**
     * Collects all `comment` nodes that appear before [documentNode] in the root-level CST.
     * These are "file-level" comments that describe the entire file.
     */
    private fun collectFileLevelComments(root: TSNode, documentNode: TSNode, source: String): List<TSNode> {
        val result = mutableListOf<TSNode>()
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child.startByte >= documentNode.startByte) break
            if (child.type == "comment") result.add(child)
        }
        return result
    }

    /**
     * Finds the root document node (object or array) in the CST.
     * The Tree-sitter JSON grammar wraps content in a `document` or program-level node.
     */
    private fun findDocumentNode(root: TSNode): TSNode? {
        // Try direct: root might be "document" or its first named child
        if (root.type == "object" || root.type == "array") return root

        // Walk named children to find object or array
        for (i in 0 until root.namedChildCount) {
            val child = root.getNamedChild(i)
            if (child.type == "object" || child.type == "array") return child
        }

        // Try all children (unnamed included)
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child.type == "object" || child.type == "array") return child
        }

        return null
    }

    private fun collectNamedChildrenOfType(node: TSNode, type: String): List<TSNode> {
        val result = mutableListOf<TSNode>()
        for (i in 0 until node.namedChildCount) {
            val child = node.getNamedChild(i)
            if (child.type == type) result.add(child)
        }
        return result
    }

    /**
     * Data class holding a pair node together with its preceding and trailing comments.
     * [trailingComment] is non-null when a `comment` sibling immediately follows the pair
     * on the same source line as the pair's value end.
     */
    private data class NodeWithComments(
        val node: TSNode,
        val precedingComments: List<TSNode>,
        val trailingComment: TSNode?,
    )

    /**
     * Collects (pair, precedingComments, trailingComment?) from an object node by walking all
     * named children and associating comment nodes with the adjacent pairs.
     *
     * A trailing comment is a comment that immediately follows a pair on the same source line.
     */
    private fun collectPairsWithComments(objectNode: TSNode, source: String): List<NodeWithComments> {
        val namedChildren = (0 until objectNode.namedChildCount).map { objectNode.getNamedChild(it) }
        val result = mutableListOf<NodeWithComments>()
        val pendingComments = mutableListOf<TSNode>()
        var i = 0
        while (i < namedChildren.size) {
            val child = namedChildren[i]
            when (child.type) {
                "comment" -> pendingComments.add(child)
                "pair" -> {
                    val valueNode = findValueNode(child)
                    val trailingComment = if (valueNode != null && i + 1 < namedChildren.size) {
                        val next = namedChildren[i + 1]
                        if (next.type == "comment" && next.startPoint.row == valueNode.endPoint.row) next else null
                    } else null
                    result.add(NodeWithComments(child, pendingComments.toList(), trailingComment))
                    pendingComments.clear()
                    if (trailingComment != null) i++ // consume the trailing comment
                }
                else -> pendingComments.clear() // discard comments not immediately before a pair
            }
            i++
        }
        return result
    }

    /**
     * Collects (element, precedingComments, trailingComment?) from an array node by walking
     * all named children and associating comment nodes with the adjacent elements.
     *
     * A trailing comment is a comment that immediately follows an element on the same source line.
     */
    private fun collectArrayElementsWithComments(arrayNode: TSNode, source: String): List<NodeWithComments> {
        val namedChildren = (0 until arrayNode.namedChildCount).map { arrayNode.getNamedChild(it) }
        val result = mutableListOf<NodeWithComments>()
        val pendingComments = mutableListOf<TSNode>()
        var i = 0
        while (i < namedChildren.size) {
            val child = namedChildren[i]
            when (child.type) {
                "comment" -> pendingComments.add(child)
                else -> {
                    val trailingComment = if (i + 1 < namedChildren.size) {
                        val next = namedChildren[i + 1]
                        if (next.type == "comment" && next.startPoint.row == child.endPoint.row) next else null
                    } else null
                    result.add(NodeWithComments(child, pendingComments.toList(), trailingComment))
                    pendingComments.clear()
                    if (trailingComment != null) i++ // consume the trailing comment
                }
            }
            i++
        }
        return result
    }

    private fun extractPairKey(pair: TSNode, source: String): String {
        // In the JSON grammar, pair has: key (string) + ":" + value
        for (i in 0 until pair.childCount) {
            val child = pair.getChild(i)
            if (child.type == "string") {
                return unquoteString(nodeText(child, source))
            }
        }
        return "(unknown)"
    }

    private fun findValueNode(pair: TSNode): TSNode? {
        // In a pair node, the named children are: key string and value
        // The value is the second named child
        if (pair.namedChildCount >= 2) {
            return pair.getNamedChild(1)
        }
        return null
    }

    private fun nodeText(node: TSNode, source: String): String {
        val bytes = source.toByteArray(Charsets.UTF_8)
        return String(bytes, node.startByte, node.endByte - node.startByte, Charsets.UTF_8)
    }

    private fun unquoteString(raw: String): String {
        // Remove surrounding quotes and unescape basic sequences
        if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            return raw.substring(1, raw.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\r", "\r")
        }
        return raw
    }

    private fun fitsInBudget(text: String): Boolean = tokenCounter(text) <= chunkSize

    private fun buildObjectHeading(headingPath: List<String>): String =
        if (headingPath.isEmpty()) "##" else "## ${headingPath.joinToString(" -> ")}"

    private fun buildArrayHeading(headingPath: List<String>, batch: List<Pair<Int, String>>): String {
        val rangeLabel = if (batch.size == 1) "Item ${batch[0].first}"
        else "Items ${batch.first().first}-${batch.last().first}"
        return if (headingPath.isEmpty()) "## $rangeLabel"
        else "## ${headingPath.joinToString(" -> ")} -> $rangeLabel"
    }
}
