package ch.obermuhlner.ezrag.ingestion

import org.springframework.ai.document.Document
import java.io.File

/**
 * Reads a CSV file and produces a list of [Document] instances using [TableChunker].
 *
 * The first row is treated as the header. Each produced chunk is a Markdown table string
 * beginning with the full header row and separator, followed by as many complete data rows
 * as fit within [chunkSize] tokens. No row ever spans two chunks.
 *
 * [chunkOverlap] is accepted for API consistency with other readers but is not used
 * (row overlap is out of scope for tabular data).
 */
class CsvDocumentReader(
    private val file: File,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
) {

    fun read(): List<Document> {
        val lines = file.readLines(Charsets.UTF_8)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (lines.isEmpty()) return emptyList()

        val header = parseCsvLine(lines[0])
        val dataRows = lines.drop(1).map { parseCsvLine(it) }

        if (dataRows.isEmpty()) return emptyList()

        val chunker = TableChunker(chunkSize)
        val tableStrings = chunker.chunk(header, dataRows)

        return tableStrings.map { tableText ->
            Document.builder()
                .text(tableText)
                .build()
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false

        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && !inQuotes -> inQuotes = true
                c == '"' && inQuotes -> {
                    // Check for escaped quote ("")
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++ // skip next quote
                    } else {
                        inQuotes = false
                    }
                }
                c == ',' && !inQuotes -> {
                    fields.add(current.toString())
                    current = StringBuilder()
                }
                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString())

        return fields
    }
}
