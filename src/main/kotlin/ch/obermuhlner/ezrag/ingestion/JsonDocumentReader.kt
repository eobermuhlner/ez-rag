package ch.obermuhlner.ezrag.ingestion

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.ai.document.Document
import org.treesitter.TSParser
import org.treesitter.TreeSitterJson
import java.io.File

/**
 * Reads JSON/JSONL/JSONC files and produces a list of [Document] instances using [JsoncChunker].
 *
 * - `.json`: raw text is validated with Jackson then passed directly to [JsoncChunker].
 * - `.jsonl`: each non-blank line is validated with Jackson; invalid lines are skipped with a warning
 *   to `System.err`. Valid lines are assembled into a synthetic JSON array string and passed to [JsoncChunker].
 * - `.jsonc`: raw file text is passed directly to [JsoncChunker] which uses Tree-sitter to parse
 *   JSONC natively, preserving comments as first-class content.
 *
 * An empty file returns an empty list without throwing.
 * A syntactically invalid file throws [IllegalArgumentException] whose message contains the filename.
 */
class JsonDocumentReader(
    private val file: File,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
) {

    private val mapper = ObjectMapper()
    private val tsLanguage = TreeSitterJson()

    fun read(): List<Document> {
        val raw = if (file.exists()) file.readText(Charsets.UTF_8) else ""

        if (raw.isBlank()) return emptyList()

        val extension = file.name.substringAfterLast('.', "").lowercase()

        val source: String = when (extension) {
            "jsonc" -> {
                // Pass raw JSONC text directly to JsoncChunker (Tree-sitter parses comments natively).
                // Validate parse result for structural errors.
                validateJsoncTree(raw)
                raw
            }
            "jsonl" -> {
                // Collect non-blank lines, validate each with Jackson, skip malformed lines
                val validLines = mutableListOf<String>()
                raw.lines().forEachIndexed { index, line ->
                    val trimmed = line.trim()
                    if (trimmed.isBlank()) return@forEachIndexed
                    try {
                        val node = mapper.readTree(trimmed)
                        if (node != null && !node.isNull && !node.isMissingNode) {
                            validLines.add(trimmed)
                        }
                    } catch (e: JsonParseException) {
                        System.err.println("Warning: skipping malformed JSON on line ${index + 1} of '${file.name}': ${e.message}")
                    }
                }
                if (validLines.isEmpty()) return emptyList()
                // Assemble valid lines as a synthetic JSON array string
                "[${validLines.joinToString(",")}]"
            }
            else -> {
                // Plain .json: validate with Jackson, then pass raw text to JsoncChunker
                validateJson(raw)
                raw
            }
        }

        val chunker = JsoncChunker(chunkSize, chunkOverlap)
        val chunks = try {
            chunker.chunk(source)
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse JSON file '${file.name}': ${e.message}", e)
        }

        return chunks.map { chunkText ->
            Document.builder()
                .text(chunkText)
                .build()
        }
    }

    /**
     * Validates that [text] is syntactically valid JSONC by parsing with Tree-sitter
     * and checking for error nodes in the CST.
     * Throws [IllegalArgumentException] with the filename if the tree has errors.
     */
    private fun validateJsoncTree(text: String) {
        val parser = TSParser()
        parser.setLanguage(tsLanguage)
        val tree = parser.parseString(null, text)
        val root = tree.rootNode
        if (root.hasError()) {
            throw IllegalArgumentException("Failed to parse JSON file '${file.name}': syntax error in JSONC input")
        }
    }

    /**
     * Validates that [text] is syntactically valid JSON using Jackson.
     * Throws [IllegalArgumentException] with the filename if parsing fails.
     */
    private fun validateJson(text: String) {
        try {
            mapper.readTree(text)
        } catch (e: JsonParseException) {
            throw IllegalArgumentException("Failed to parse JSON file '${file.name}': ${e.message}", e)
        }
    }
}
