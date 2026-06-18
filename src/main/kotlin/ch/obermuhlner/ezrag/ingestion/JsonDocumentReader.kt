package ch.obermuhlner.ezrag.ingestion

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import org.springframework.ai.document.Document
import java.io.File

/**
 * Reads JSON/JSONL/JSONC files and produces a list of [Document] instances using [JsonChunker].
 *
 * - `.json`: parsed as-is with Jackson; root array or object is chunked recursively.
 * - `.jsonl`: each non-blank line is parsed independently; the set of parsed nodes is
 *   treated like a root JSON array. Invalid lines are skipped with a warning to System.err.
 * - `.jsonc`: comment stripping is applied before parsing.
 *
 * An empty file returns an empty list without throwing.
 * A syntactically invalid file throws [IllegalArgumentException] whose message contains
 * the filename.
 */
class JsonDocumentReader(
    private val file: File,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
) {

    fun read(): List<Document> {
        val raw = if (file.exists()) file.readText(Charsets.UTF_8) else ""

        if (raw.isBlank()) return emptyList()

        val extension = file.name.substringAfterLast('.', "").lowercase()

        val mapper = ObjectMapper()

        val root = when (extension) {
            "jsonc" -> {
                val stripped = JsoncCommentStripper.strip(raw)
                if (stripped.isBlank()) return emptyList()
                try {
                    mapper.readTree(stripped)
                } catch (e: JsonParseException) {
                    throw IllegalArgumentException("Failed to parse JSON file '${file.name}': ${e.message}", e)
                }
            }
            "jsonl" -> {
                val arrayNode: ArrayNode = mapper.createArrayNode()
                raw.lines().forEachIndexed { index, line ->
                    val trimmed = line.trim()
                    if (trimmed.isBlank()) return@forEachIndexed
                    try {
                        val node = mapper.readTree(trimmed)
                        if (node != null && !node.isNull && !node.isMissingNode) {
                            arrayNode.add(node)
                        }
                    } catch (e: JsonParseException) {
                        System.err.println("Warning: skipping malformed JSON on line ${index + 1} of '${file.name}': ${e.message}")
                    }
                }
                if (arrayNode.isEmpty) return emptyList()
                arrayNode
            }
            else -> {
                try {
                    mapper.readTree(raw)
                } catch (e: JsonParseException) {
                    throw IllegalArgumentException("Failed to parse JSON file '${file.name}': ${e.message}", e)
                }
            }
        }

        if (root == null || root.isNull || root.isMissingNode) return emptyList()

        val chunker = JsonChunker(chunkSize, chunkOverlap)
        val chunks = chunker.chunk(root)

        return chunks.map { chunkText ->
            Document.builder()
                .text(chunkText)
                .build()
        }
    }
}
