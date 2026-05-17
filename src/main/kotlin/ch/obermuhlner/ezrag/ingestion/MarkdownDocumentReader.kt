package ch.obermuhlner.ezrag.ingestion

import org.springframework.ai.document.Document
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import java.io.File

class MarkdownDocumentReader(
    private val file: File,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
) {

    private val headingRegex = Regex("""^(#{1,6})\s+(.+)$""")

    fun read(): List<Document> {
        val raw = file.readText()
        val content = stripYamlFrontMatter(raw)
        val chunks = splitByHeadings(content)
        if (chunks.isEmpty()) {
            return fallbackTokenSplit(content)
        }
        return chunks
    }

    private fun splitByHeadings(content: String): List<Document> {
        val lines = content.lines()

        // heading stack: list of Pair(level, title)
        val headingStack = mutableListOf<Pair<Int, String>>()
        val buffer = StringBuilder()
        val result = mutableListOf<Document>()

        fun flushBuffer(currentStack: List<Pair<Int, String>>) {
            val bodyText = buffer.toString().trim()
            buffer.clear()
            if (bodyText.isEmpty()) return

            if (currentStack.isEmpty()) {
                // Pre-heading content: no heading metadata
                result.add(
                    Document.builder()
                        .text(bodyText)
                        .build()
                )
            } else {
                val headingPrefix = currentStack.joinToString("\n") { (level, title) ->
                    "#".repeat(level) + " " + title
                }
                val fullText = headingPrefix + "\n" + bodyText
                val immediateHeading = currentStack.last()
                val headingPath = currentStack.map { it.second }
                result.add(
                    Document.builder()
                        .text(fullText)
                        .metadata(mapOf(
                            "heading_title" to immediateHeading.second,
                            "heading_level" to immediateHeading.first,
                            "heading_path" to headingPath
                        ))
                        .build()
                )
            }
        }

        var hasHeadings = false

        for (line in lines) {
            val match = headingRegex.matchEntire(line)
            if (match != null) {
                hasHeadings = true
                val level = match.groupValues[1].length
                val title = match.groupValues[2].trim()

                // Flush accumulated content with current stack
                flushBuffer(headingStack.toList())

                // Update heading stack: remove entries at same or deeper level
                while (headingStack.isNotEmpty() && headingStack.last().first >= level) {
                    headingStack.removeLast()
                }
                headingStack.add(Pair(level, title))
            } else {
                buffer.appendLine(line)
            }
        }

        // Flush remaining buffer
        if (hasHeadings) {
            flushBuffer(headingStack.toList())
        }

        // If no headings were found, return empty list to trigger fallback
        if (!hasHeadings) {
            return emptyList()
        }

        return result
    }

    private fun fallbackTokenSplit(content: String): List<Document> {
        val document = Document.builder()
            .text(content)
            .build()
        val splitter = TokenTextSplitter.builder()
            .withChunkSize(chunkSize)
            .withMinChunkSizeChars(chunkOverlap)
            .withMinChunkLengthToEmbed(5)
            .withMaxNumChunks(10000)
            .withKeepSeparator(true)
            .build()
        val chunks = splitter.apply(listOf(document))
        return chunks.map { doc ->
            val metaWithoutSource = doc.metadata.toMutableMap().apply { remove("source") }
            Document.builder()
                .id(doc.id)
                .text(doc.text)
                .metadata(metaWithoutSource)
                .build()
        }
    }

    private fun stripYamlFrontMatter(content: String): String {
        val trimmed = content.trimStart()
        if (!trimmed.startsWith("---")) {
            return content
        }
        val afterFirst = trimmed.removePrefix("---")
        val closingIndex = afterFirst.indexOf("\n---")
        if (closingIndex == -1) {
            return content
        }
        return afterFirst.substring(closingIndex + 4).trimStart()
    }
}
