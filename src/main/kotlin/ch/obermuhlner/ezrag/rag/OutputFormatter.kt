package ch.obermuhlner.ezrag.rag

class OutputFormatter {

    fun formatText(result: RagResult): String {
        val sb = StringBuilder()
        sb.append(result.answer)
        if (result.sources.isNotEmpty()) {
            sb.append("\n\n--- Sources ---\n")
            result.sources.forEach { source ->
                sb.append("${source.filePath} (score: ${source.similarityScore})\n")
            }
        }
        return sb.toString()
    }

    fun formatJson(result: RagResult): String {
        val sourcesJson = result.sources.joinToString(",\n    ") { source ->
            val escapedFile = escapeJsonString(source.filePath)
            val escapedExcerpt = escapeJsonString(source.excerpt)
            """{"file": "$escapedFile", "score": ${source.similarityScore}, "excerpt": "$escapedExcerpt"}"""
        }
        val sourcesArray = if (result.sources.isEmpty()) "[]" else "[\n    $sourcesJson\n  ]"
        val escapedAnswer = escapeJsonString(result.answer)
        return """{
  "answer": "$escapedAnswer",
  "sources": $sourcesArray
}"""
    }

    private fun escapeJsonString(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
