package ch.obermuhlner.ezrag.ingestion

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

class HtmlToMarkdownConverter {

    fun convert(html: String): String {
        val doc = Jsoup.parse(html)
        val sb = StringBuilder()
        processChildren(doc.body(), sb)
        return sb.toString()
    }

    private fun processChildren(parent: Element, sb: StringBuilder) {
        for (child in parent.childNodes()) {
            processNode(child, sb)
        }
    }

    private fun processNode(node: Node, sb: StringBuilder) {
        when (node) {
            is TextNode -> {
                val text = node.text()
                if (text.isNotBlank()) {
                    sb.append(text)
                }
            }
            is Element -> processElement(node, sb)
        }
    }

    private fun processTable(table: Element, sb: StringBuilder) {
        // Collect all <tr> rows (may be nested inside <thead>, <tbody>, <tfoot>)
        val rows = table.select("tr")
        if (rows.isEmpty()) return

        var separatorEmitted = false
        for (row in rows) {
            val cells = row.children().filter { it.tagName() == "th" || it.tagName() == "td" }
            if (cells.isEmpty()) continue

            val isHeaderRow = cells.any { it.tagName() == "th" }

            // Build the pipe row
            val cellTexts = cells.map { it.text().trim() }
            sb.append("| ")
            sb.append(cellTexts.joinToString(" | "))
            sb.appendLine(" |")

            // Emit separator after the first header row
            if (isHeaderRow && !separatorEmitted) {
                sb.append("| ")
                sb.append(cells.joinToString(" | ") { "---" })
                sb.appendLine(" |")
                separatorEmitted = true
            }
        }
        sb.appendLine()
    }

    /**
     * Returns the heading level (1–6) if the element's CSS class list contains a token
     * matching `h1`–`h6` or `heading-1`–`heading-6`, or null otherwise.
     */
    private fun cssHeadingLevel(element: Element): Int? {
        val classes = element.classNames()
        for (cls in classes) {
            val direct = Regex("^h([1-6])$").find(cls)
            if (direct != null) return direct.groupValues[1].toInt()
            val longForm = Regex("^heading-([1-6])$").find(cls)
            if (longForm != null) return longForm.groupValues[1].toInt()
        }
        return null
    }

    private fun headingPrefix(level: Int): String = "#".repeat(level)

    private fun processElement(element: Element, sb: StringBuilder) {
        when (element.tagName()) {
            "h1" -> sb.appendLine("# ${element.text()}")
            "h2" -> sb.appendLine("## ${element.text()}")
            "h3" -> sb.appendLine("### ${element.text()}")
            "h4" -> sb.appendLine("#### ${element.text()}")
            "h5" -> sb.appendLine("##### ${element.text()}")
            "h6" -> sb.appendLine("###### ${element.text()}")
            "p" -> {
                val content = StringBuilder()
                processChildren(element, content)
                val text = content.toString().trim()
                if (text.isNotEmpty()) {
                    sb.append(text)
                    sb.append("\n\n")
                }
            }
            "br" -> sb.append("\n")
            "pre" -> {
                sb.appendLine("```")
                sb.appendLine(element.text())
                sb.appendLine("```")
                sb.appendLine()
            }
            "img" -> {
                val alt = element.attr("alt")
                if (alt.isNotBlank()) {
                    sb.append(alt)
                }
            }
            "ul" -> {
                for (child in element.children()) {
                    if (child.tagName() == "li") {
                        val text = child.text().trim()
                        if (text.isNotEmpty()) sb.appendLine("- $text")
                    }
                }
                sb.appendLine()
            }
            "ol" -> {
                var counter = 1
                for (child in element.children()) {
                    if (child.tagName() == "li") {
                        val text = child.text().trim()
                        if (text.isNotEmpty()) sb.appendLine("${counter++}. $text")
                    }
                }
                sb.appendLine()
            }
            "blockquote" -> {
                val content = StringBuilder()
                processChildren(element, content)
                val lines = content.toString().split("\n")
                for (line in lines) {
                    if (line.isNotBlank()) {
                        sb.appendLine("> $line")
                    }
                }
                sb.appendLine()
            }
            "hr" -> sb.appendLine("---")
            "table" -> {
                processTable(element, sb)
            }
            "script", "style", "noscript", "nav", "header", "footer", "aside" -> {
                // skip entirely
            }
            else -> {
                val cssLevel = cssHeadingLevel(element)
                if (cssLevel != null) {
                    sb.appendLine("${headingPrefix(cssLevel)} ${element.text()}")
                } else {
                    processChildren(element, sb)
                }
            }
        }
    }
}
