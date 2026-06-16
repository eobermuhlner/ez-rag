package ch.obermuhlner.ezrag.ingestion

/**
 * Row-aware batching for tabular data. Converts a header row + data rows into a list of
 * Markdown table strings, each beginning with the full header row and separator line,
 * followed by as many complete data rows as fit within [chunkSize] tokens.
 *
 * No row ever spans two chunks. Batches are strictly non-overlapping.
 * A single row that alone exceeds [chunkSize] is emitted as its own one-row chunk.
 */
class TableChunker(
    private val chunkSize: Int = 1000,
    private val tokenCounter: (String) -> Int = TokenCounter::countTokens,
) {
    /**
     * Returns a list of Markdown table strings.
     * Each string starts with the header row and separator, followed by N complete data rows.
     * Returns an empty list when [rows] is empty.
     */
    fun chunk(header: List<String>, rows: List<List<String>>): List<String> {
        if (rows.isEmpty()) return emptyList()

        val headerLine = "| ${header.joinToString(" | ")} |"
        val separatorLine = "| ${header.joinToString(" | ") { "---" }} |"
        val headerBlock = "$headerLine\n$separatorLine"

        val result = mutableListOf<String>()
        val currentRows = mutableListOf<String>()

        fun formatRow(row: List<String>): String = "| ${row.joinToString(" | ")} |"

        fun flush() {
            if (currentRows.isNotEmpty()) {
                result.add("$headerBlock\n${currentRows.joinToString("\n")}")
                currentRows.clear()
            }
        }

        for (row in rows) {
            val rowLine = formatRow(row)
            val candidate = if (currentRows.isEmpty()) {
                "$headerBlock\n$rowLine"
            } else {
                "$headerBlock\n${currentRows.joinToString("\n")}\n$rowLine"
            }

            when {
                tokenCounter(candidate) <= chunkSize -> currentRows.add(rowLine)
                currentRows.isEmpty() -> {
                    // Single row exceeds budget — emit it alone anyway
                    result.add("$headerBlock\n$rowLine")
                }
                else -> {
                    flush()
                    // Try adding this row to a fresh chunk
                    val freshCandidate = "$headerBlock\n$rowLine"
                    if (tokenCounter(freshCandidate) <= chunkSize) {
                        currentRows.add(rowLine)
                    } else {
                        // Single row exceeds budget — emit it alone
                        result.add(freshCandidate)
                    }
                }
            }
        }

        flush()
        return result
    }
}
