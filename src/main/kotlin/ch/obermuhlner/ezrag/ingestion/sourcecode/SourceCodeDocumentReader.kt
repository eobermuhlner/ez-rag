package ch.obermuhlner.ezrag.ingestion.sourcecode

import ch.obermuhlner.ezrag.ingestion.SectionSplitter
import ch.obermuhlner.ezrag.ingestion.TokenCounter
import org.springframework.ai.document.Document
import org.springframework.ai.transformer.splitter.TokenTextSplitter

class SourceCodeDocumentReader(
    private val source: String,
    private val parser: SourceCodeParser,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
) {

    fun read(): List<Document> {
        val declarations = parser.parse(source)
        val result = mutableListOf<Document>()

        for (decl in declarations) {
            val headingPrefix = buildHeadingPrefix(decl)
            val codePrefix = buildCodePrefix(decl)
            val codeFenceOpen = "```${parser.language}\n"
            val codeFenceClose = "\n```"

            // Compute the overhead tokens: heading + code fence header + codePrefix + code fence close
            // This overhead is subtracted from chunkSize to compute body budget.
            val overhead = "$headingPrefix\n$codeFenceOpen$codePrefix$codeFenceClose"
            val overheadTokens = TokenCounter.countTokens(overhead)
            val bodyBudget = (chunkSize - overheadTokens).coerceAtLeast(1)

            // Split the raw declaration fullText at body budget
            val bodyParts = splitText(decl.fullText, bodyBudget)

            for (bodyPart in bodyParts) {
                val finalText = "$headingPrefix\n$codeFenceOpen$codePrefix$bodyPart$codeFenceClose"
                val metadata = buildMetadata(decl)
                result.add(
                    Document.builder()
                        .text(finalText)
                        .metadata(metadata)
                        .build()
                )
            }
        }

        return result
    }

    /**
     * Split text into parts each fitting within [budget] tokens.
     * Uses paragraph-aware splitting: blank lines act as natural split points.
     * Falls back to token-based splitting for individual oversized paragraphs.
     */
    private fun splitText(text: String, budget: Int): List<String> {
        if (TokenCounter.countTokens(text) <= budget) {
            return listOf(text)
        }

        val paragraphs = splitIntoParagraphs(text)
        val result = mutableListOf<String>()
        var current = ""

        for (para in paragraphs) {
            val candidate = if (current.isEmpty()) para else "$current\n\n$para"
            when {
                TokenCounter.countTokens(candidate) <= budget -> current = candidate
                current.isNotEmpty() -> {
                    result.add(current)
                    current = if (TokenCounter.countTokens(para) <= budget) {
                        para
                    } else {
                        // Paragraph itself too big — use token splitter
                        val parts = fallbackSplit(para, budget)
                        result.addAll(parts.dropLast(1))
                        parts.last()
                    }
                }
                else -> {
                    // First paragraph already too big
                    val parts = fallbackSplit(para, budget)
                    result.addAll(parts.dropLast(1))
                    current = parts.last()
                }
            }
        }
        if (current.isNotEmpty()) {
            result.add(current)
        }
        return result.ifEmpty { listOf(text) }
    }

    private fun splitIntoParagraphs(text: String): List<String> {
        // Split at blank lines
        return text.split(Regex("\n{2,}")).filter { it.isNotBlank() }.map { it.trim() }
    }

    private fun fallbackSplit(text: String, budget: Int): List<String> {
        val doc = Document.builder().text(text).build()
        val splitter = TokenTextSplitter.builder()
            .withChunkSize(budget)
            .withMinChunkSizeChars(chunkOverlap)
            .withMinChunkLengthToEmbed(1)
            .withMaxNumChunks(10000)
            .withKeepSeparator(true)
            .build()
        val parts = splitter.apply(listOf(doc)).map { it.text ?: "" }.filter { it.isNotBlank() }
        return parts.ifEmpty { listOf(text) }
    }

    /**
     * Build the markdown heading prefix for this declaration.
     */
    private fun buildHeadingPrefix(decl: SourceDeclaration): String {
        return when (decl.declarationType) {
            DeclarationType.CLASS -> "## ${decl.declarationName}"
            DeclarationType.FUNCTION -> "## ${decl.declarationName}"
            DeclarationType.METHOD, DeclarationType.CONSTRUCTOR -> {
                if (decl.className != null) {
                    "## ${decl.className}\n### ${decl.declarationName}"
                } else {
                    "## ${decl.declarationName}"
                }
            }
        }
    }

    /**
     * Build the code prefix (package + imports + blank line) to include inside the fenced code block.
     */
    private fun buildCodePrefix(decl: SourceDeclaration): String {
        val sb = StringBuilder()
        if (decl.packageName != null) {
            sb.appendLine("package ${decl.packageName}")
        }
        for (import in decl.imports) {
            sb.appendLine(import)
        }
        if (decl.packageName != null || decl.imports.isNotEmpty()) {
            sb.appendLine()
        }
        return sb.toString()
    }

    private fun buildMetadata(decl: SourceDeclaration): Map<String, Any> {
        val headingTitle = decl.declarationName
        val headingPath: List<String> = when (decl.declarationType) {
            DeclarationType.CLASS, DeclarationType.FUNCTION -> listOf(decl.declarationName)
            DeclarationType.METHOD, DeclarationType.CONSTRUCTOR -> {
                if (decl.className != null) {
                    listOf(decl.className, decl.declarationName)
                } else {
                    listOf(decl.declarationName)
                }
            }
        }

        val metadata = mutableMapOf<String, Any>(
            "heading_title" to headingTitle,
            "heading_path" to headingPath,
            "language" to parser.language,
            "declaration_type" to decl.declarationType.name.lowercase(),
            "declaration_name" to decl.declarationName,
        )
        if (decl.className != null) {
            metadata["class_name"] = decl.className
        }
        return metadata
    }
}
