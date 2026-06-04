package ch.obermuhlner.ezrag.ingestion

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.springframework.ai.document.Document
import org.springframework.ai.transformer.splitter.TokenTextSplitter

class HtmlDocumentReader(
    private val html: String,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
) {
    private val headingRegex = Regex("""^(#{1,6})\s+(.+)$""")
    private val sectionSplitter = SectionSplitter(chunkSize, chunkOverlap, TokenCounter::countTokens)

    fun read(): List<Document> {
        val doc = Jsoup.parse(html)
        val pageTitle = doc.title()
        val markdown = htmlBodyToMarkdown(doc.body())
        val chunks = splitByHeadings(markdown)
        val result = if (chunks.isEmpty() && doc.body().text().isNotBlank()) {
            fallbackTokenSplit(doc.body().text())
        } else {
            chunks
        }
        return result.map { chunk ->
            Document.builder()
                .id(chunk.id)
                .text(chunk.text)
                .metadata(chunk.metadata + mapOf("page_title" to pageTitle))
                .build()
        }
    }

    private fun htmlBodyToMarkdown(body: Element): String {
        val sb = StringBuilder()
        for (child in body.children()) {
            processElement(child, sb)
        }
        return sb.toString()
    }

    private fun processElement(element: Element, sb: StringBuilder) {
        when (element.tagName()) {
            "h1" -> sb.appendLine("# ${element.text()}")
            "h2" -> sb.appendLine("## ${element.text()}")
            "h3" -> sb.appendLine("### ${element.text()}")
            "h4" -> sb.appendLine("#### ${element.text()}")
            "h5" -> sb.appendLine("##### ${element.text()}")
            "h6" -> sb.appendLine("###### ${element.text()}")
            "p" -> {
                val text = element.text().trim()
                if (text.isNotEmpty()) {
                    sb.appendLine(text)
                    sb.appendLine()
                }
            }
            "li" -> {
                val text = element.text().trim()
                if (text.isNotEmpty()) sb.appendLine("- $text")
            }
            "pre" -> {
                sb.appendLine("```")
                sb.appendLine(element.text())
                sb.appendLine("```")
                sb.appendLine()
            }
            "script", "style", "noscript" -> { /* skip */ }
            else -> {
                for (child in element.children()) {
                    processElement(child, sb)
                }
            }
        }
    }

    private fun splitByHeadings(content: String): List<Document> {
        val lines = content.lines()
        val headingStack = mutableListOf<Pair<Int, String>>()
        val buffer = StringBuilder()
        val result = mutableListOf<Document>()
        var hasHeadings = false

        fun flushBuffer(currentStack: List<Pair<Int, String>>) {
            val bodyText = buffer.toString().trim()
            buffer.clear()
            if (bodyText.isEmpty()) return

            if (currentStack.isEmpty()) {
                sectionSplitter.splitSection(bodyText, "").forEach { chunk ->
                    result.add(Document.builder().text(chunk).build())
                }
            } else {
                val headingPrefix = currentStack.joinToString("\n") { (level, title) ->
                    "#".repeat(level) + " " + title
                }
                val immediateHeading = currentStack.last()
                val metadata = mapOf(
                    "heading_title" to immediateHeading.second,
                    "heading_level" to immediateHeading.first,
                    "heading_path" to currentStack.map { it.second }
                )
                sectionSplitter.splitSection(bodyText, headingPrefix).forEach { chunk ->
                    result.add(Document.builder().text(chunk).metadata(metadata).build())
                }
            }
        }

        for (line in lines) {
            val match = headingRegex.matchEntire(line)
            if (match != null) {
                hasHeadings = true
                val level = match.groupValues[1].length
                val title = match.groupValues[2].trim()
                flushBuffer(headingStack.toList())
                while (headingStack.isNotEmpty() && headingStack.last().first >= level) {
                    headingStack.removeLast()
                }
                headingStack.add(Pair(level, title))
            } else {
                buffer.appendLine(line)
            }
        }

        if (hasHeadings) flushBuffer(headingStack.toList())

        return result
    }

    private fun fallbackTokenSplit(text: String): List<Document> {
        val doc = Document.builder().text(text).build()
        val splitter = TokenTextSplitter.builder()
            .withChunkSize(chunkSize)
            .withMinChunkSizeChars(chunkOverlap)
            .withMinChunkLengthToEmbed(5)
            .withMaxNumChunks(10000)
            .withKeepSeparator(true)
            .build()
        return splitter.apply(listOf(doc)).map { d ->
            Document.builder()
                .id(d.id)
                .text(d.text)
                .metadata(d.metadata.toMutableMap().apply { remove("source") })
                .build()
        }
    }
}
