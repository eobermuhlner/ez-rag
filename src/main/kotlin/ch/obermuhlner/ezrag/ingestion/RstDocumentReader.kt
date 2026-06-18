package ch.obermuhlner.ezrag.ingestion

import org.springframework.ai.document.Document
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import java.io.File

class RstDocumentReader {

    private val content: String
    private val chunkSize: Int
    private val chunkOverlap: Int

    constructor(file: File, chunkSize: Int = 1000, chunkOverlap: Int = 200) {
        this.content = file.readText()
        this.chunkSize = chunkSize
        this.chunkOverlap = chunkOverlap
    }

    constructor(content: String, chunkSize: Int = 1000, chunkOverlap: Int = 200) {
        this.content = content
        this.chunkSize = chunkSize
        this.chunkOverlap = chunkOverlap
    }

    private val sectionSplitter by lazy { SectionSplitter(chunkSize, chunkOverlap, TokenCounter::countTokens) }

    // A RST punctuation underline character set
    private val rstPunctuationChars = setOf(
        '!', '"', '#', '$', '%', '&', '\'', '(', ')', '*', '+', ',', '-', '.', '/',
        ':', ';', '<', '=', '>', '?', '@', '[', '\\', ']', '^', '_', '`', '{', '|', '}', '~'
    )

    fun read(): List<Document> {
        val parsed = preprocess(content)
        val chunks = splitByHeadings(parsed.normalizedText, parsed.headingLevelMap)
        if (chunks.isEmpty()) {
            return fallbackTokenSplit(parsed.normalizedText)
        }
        return chunks
    }

    data class ParsedRst(
        val normalizedText: String,
        val headingLevelMap: Map<Char, Int>
    )

    /**
     * Pre-process RST content:
     * 1. Detect all headings (underline-only and overline+underline) and build a first-seen
     *    character -> level mapping.
     * 2. Replace RST headings with Markdown-style headings (`# Title`).
     * 3. Convert `.. code-block::` directives and `::` paragraph-ending shorthand to
     *    Markdown fenced blocks (```), so LayoutBlockParser treats them as FencedCodeBlock units.
     */
    private fun preprocess(text: String): ParsedRst {
        val lines = text.lines()
        val charLevelMap = mutableMapOf<Char, Int>()
        var nextLevel = 1

        // Pass 1: discover heading level assignments by scanning for heading patterns
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            // Check for overline+underline pattern: punctuation line, text, same punctuation line
            if (i + 2 < lines.size) {
                val overline = lines[i]
                val titleLine = lines[i + 1]
                val underline = lines[i + 2]
                if (isRstPunctuationLine(overline) && titleLine.isNotBlank() &&
                    isRstPunctuationLine(underline) && overline[0] == underline[0] &&
                    overline.length >= titleLine.trim().length &&
                    underline.length >= titleLine.trim().length) {
                    val ch = overline[0]
                    if (ch !in charLevelMap) {
                        charLevelMap[ch] = nextLevel++
                    }
                    i += 3
                    continue
                }
            }
            // Check for underline-only pattern: text line, punctuation line
            if (i + 1 < lines.size) {
                val titleLine = lines[i]
                val underline = lines[i + 1]
                if (titleLine.isNotBlank() && isRstPunctuationLine(underline) &&
                    underline.length >= titleLine.trim().length &&
                    !isRstPunctuationLine(titleLine)) {
                    val ch = underline[0]
                    if (ch !in charLevelMap) {
                        charLevelMap[ch] = nextLevel++
                    }
                    i += 2
                    continue
                }
            }
            i++
        }

        // Pass 2: convert RST to normalized text with Markdown headings and fenced code blocks
        val result = mutableListOf<String>()
        i = 0
        while (i < lines.size) {
            val line = lines[i]

            // Check for overline+underline heading
            if (i + 2 < lines.size) {
                val overline = lines[i]
                val titleLine = lines[i + 1]
                val underline = lines[i + 2]
                if (isRstPunctuationLine(overline) && titleLine.isNotBlank() &&
                    isRstPunctuationLine(underline) && overline[0] == underline[0] &&
                    overline.length >= titleLine.trim().length &&
                    underline.length >= titleLine.trim().length) {
                    val ch = overline[0]
                    val level = charLevelMap[ch] ?: 1
                    result.add("#".repeat(level) + " " + titleLine.trim())
                    i += 3
                    continue
                }
            }

            // Check for underline-only heading
            if (i + 1 < lines.size) {
                val titleLine = lines[i]
                val underline = lines[i + 1]
                if (titleLine.isNotBlank() && isRstPunctuationLine(underline) &&
                    underline.length >= titleLine.trim().length &&
                    !isRstPunctuationLine(titleLine)) {
                    val ch = underline[0]
                    val level = charLevelMap[ch] ?: 1
                    result.add("#".repeat(level) + " " + titleLine.trim())
                    i += 2
                    continue
                }
            }

            // Check for `.. code-block::` directive
            if (line.trimStart().startsWith(".. code-block::")) {
                result.add("```")
                i++
                // Skip blank lines after directive
                while (i < lines.size && lines[i].isBlank()) {
                    i++
                }
                // Collect indented block
                while (i < lines.size && (lines[i].isBlank() || lines[i].startsWith(" ") || lines[i].startsWith("\t"))) {
                    result.add(lines[i].trimIndent())
                    i++
                }
                result.add("```")
                continue
            }

            // Check for `::` paragraph-ending shorthand
            if (line.trimEnd().endsWith("::") && line.trim() != "::") {
                // Emit the line without the trailing `::` (replace with `:`)
                result.add(line.trimEnd().dropLast(1))
                result.add("```")
                i++
                // Skip blank lines
                while (i < lines.size && lines[i].isBlank()) {
                    i++
                }
                // Collect indented block
                while (i < lines.size && (lines[i].isBlank() || lines[i].startsWith(" ") || lines[i].startsWith("\t"))) {
                    result.add(lines[i].trimIndent())
                    i++
                }
                result.add("```")
                continue
            }

            // Check for standalone `::` which introduces a code block
            if (line.trim() == "::") {
                result.add("```")
                i++
                // Skip blank lines
                while (i < lines.size && lines[i].isBlank()) {
                    i++
                }
                // Collect indented block
                while (i < lines.size && (lines[i].isBlank() || lines[i].startsWith(" ") || lines[i].startsWith("\t"))) {
                    result.add(lines[i].trimIndent())
                    i++
                }
                result.add("```")
                continue
            }

            result.add(line)
            i++
        }

        return ParsedRst(result.joinToString("\n"), charLevelMap)
    }

    private fun isRstPunctuationLine(line: String): Boolean {
        if (line.isEmpty()) return false
        val ch = line[0]
        if (ch !in rstPunctuationChars) return false
        return line.all { it == ch }
    }

    private fun splitByHeadings(content: String, headingLevelMap: Map<Char, Int>): List<Document> {
        val markdownHeadingRegex = Regex("""^(#{1,6})\s+(.+)$""")
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
            val match = markdownHeadingRegex.matchEntire(line)
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
}
