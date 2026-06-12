package ch.obermuhlner.ezrag.rag

class OutputFormatter {

    fun formatText(result: SearchResult): String {
        if (result.chunks.isEmpty()) {
            return ""
        }
        return result.chunks.mapIndexed { idx, chunk ->
            val header = "[${idx + 1}] score=${"%.2f".format(chunk.score)}  source=${chunk.path}  chunk=${chunk.chunkIndex}"
            "$header\n${chunk.text}"
        }.joinToString("\n\n")
    }

    fun formatJson(result: SearchResult): String {
        val chunksJson = result.chunks.joinToString(",\n    ") { chunk ->
            val escapedPath = escapeJsonString(chunk.path)
            val escapedContent = escapeJsonString(chunk.text)
            """{"path": "$escapedPath", "chunkIndex": ${chunk.chunkIndex}, "score": ${chunk.score}, "content": "$escapedContent"}"""
        }
        val chunksArray = if (result.chunks.isEmpty()) "[]" else "[\n    $chunksJson\n  ]"
        return """{
  "mode": "${result.mode}",
  "chunks": $chunksArray
}"""
    }

    fun formatXml(result: SearchResult): String {
        val sb = StringBuilder()
        sb.append("<results mode=\"${result.mode}\">")
        result.chunks.forEachIndexed { idx, chunk ->
            sb.append("\n<result index=\"${idx + 1}\" score=\"${"%.2f".format(chunk.score)}\" source=\"${chunk.path}\" chunk=\"${chunk.chunkIndex}\">")
            sb.append("\n${chunk.text}")
            sb.append("\n</result>")
        }
        if (result.chunks.isNotEmpty()) {
            sb.append("\n")
        }
        sb.append("</results>")
        return sb.toString()
    }

    fun formatText(result: RagResult): String {
        val sb = StringBuilder()
        sb.append(result.answer)
        if (result.sources.isNotEmpty()) {
            sb.append("\n\n--- Sources ---\n")
            result.sources.forEach { source ->
                sb.append("${source.path} (score: ${source.score})\n")
            }
        }
        return sb.toString()
    }

    fun formatJson(result: RagResult): String {
        val sourcesJson = result.sources.joinToString(",\n    ") { source ->
            val escapedPath = escapeJsonString(source.path)
            val escapedExcerpt = escapeJsonString(source.text)
            """{"path": "$escapedPath", "score": ${source.score}, "excerpt": "$escapedExcerpt"}"""
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
