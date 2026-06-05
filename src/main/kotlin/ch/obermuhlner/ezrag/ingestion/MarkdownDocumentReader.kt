package ch.obermuhlner.ezrag.ingestion

import org.springframework.ai.document.Document
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import java.io.File

class MarkdownDocumentReader {

    private val content: String
    private val chunkSize: Int
    private val chunkOverlap: Int

    constructor(file: File, chunkSize: Int = 1000, chunkOverlap: Int = 200) {
        this.content = stripYamlFrontMatter(file.readText())
        this.chunkSize = chunkSize
        this.chunkOverlap = chunkOverlap
    }

    constructor(markdown: String, chunkSize: Int = 1000, chunkOverlap: Int = 200) {
        this.content = stripYamlFrontMatter(markdown)
        this.chunkSize = chunkSize
        this.chunkOverlap = chunkOverlap
    }

    private val headingRegex = Regex("""^(#{1,6})\s+(.+)$""")
    private val sectionSplitter by lazy { SectionSplitter(chunkSize, chunkOverlap, TokenCounter::countTokens) }

    fun read(): List<Document> {
        val chunks = splitByHeadings(content)
        if (chunks.isEmpty()) {
            return fallbackTokenSplit(content)
        }
        return chunks
    }

    private fun splitByHeadings(content: String): List<Document> {
        val lines = content.lines()

        val headingStack = mutableListOf<Pair<Int, String>>()
        val buffer = StringBuilder()
        val result = mutableListOf<Document>()

        fun flushBuffer(currentStack: List<Pair<Int, String>>) {
            val bodyText = buffer.toString().trim()
            buffer.clear()
            if (bodyText.isEmpty()) return

            if (currentStack.isEmpty()) {
                val subChunks = sectionSplitter.splitSection(bodyText, "")
                for (chunk in subChunks) {
                    result.add(Document.builder().text(chunk).build())
                }
            } else {
                val headingPrefix = currentStack.joinToString("\n") { (level, title) ->
                    "#".repeat(level) + " " + title
                }
                val immediateHeading = currentStack.last()
                val headingPath = currentStack.map { it.second }
                val metadata = mapOf(
                    "heading_title" to immediateHeading.second,
                    "heading_level" to immediateHeading.first,
                    "heading_path" to headingPath
                )
                val subChunks = sectionSplitter.splitSection(bodyText, headingPrefix)
                for (chunk in subChunks) {
                    result.add(
                        Document.builder()
                            .text(chunk)
                            .metadata(metadata)
                            .build()
                    )
                }
            }
        }

        var hasHeadings = false

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

        if (hasHeadings) {
            flushBuffer(headingStack.toList())
        }

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

    companion object {
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
}
