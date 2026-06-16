package ch.obermuhlner.ezrag.rag

import org.springframework.ai.document.Document

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

    fun formatText(chunks: List<Document>): String {
        if (chunks.isEmpty()) return ""
        return chunks.mapIndexed { idx, doc ->
            val chunkIndex = doc.metadata["chunk_index"]
            val headingPath = headingPathString(doc)
            val metaParts = buildList {
                if (chunkIndex != null) add("chunk=$chunkIndex")
                if (headingPath != null) add("heading_path=$headingPath")
            }
            val header = "[${idx + 1}] ${metaParts.joinToString("  ")}"
            "$header\n${doc.text}"
        }.joinToString("\n\n")
    }

    fun formatJson(chunks: List<Document>): String {
        val chunksJson = chunks.mapIndexed { idx, doc ->
            val chunkIndex = doc.metadata["chunk_index"]
            val headingPath = headingPathString(doc)
            val escapedContent = escapeJsonString(doc.text ?: "")
            val fields = buildList {
                add("\"chunkIndex\": $chunkIndex")
                if (headingPath != null) add("\"headingPath\": \"${escapeJsonString(headingPath)}\"")
                add("\"content\": \"$escapedContent\"")
            }
            "{${fields.joinToString(", ")}}"
        }.joinToString(",\n    ")
        val chunksArray = if (chunks.isEmpty()) "[]" else "[\n    $chunksJson\n  ]"
        return """{
  "chunks": $chunksArray
}"""
    }

    fun formatXml(chunks: List<Document>): String {
        val sb = StringBuilder()
        sb.append("<results>")
        chunks.forEachIndexed { idx, doc ->
            val chunkIndex = doc.metadata["chunk_index"]
            val headingPath = headingPathString(doc)
            val attrs = buildList {
                add("index=\"${idx + 1}\"")
                if (chunkIndex != null) add("chunk=\"$chunkIndex\"")
                if (headingPath != null) add("heading_path=\"${escapeXmlAttribute(headingPath)}\"")
            }
            sb.append("\n<result ${attrs.joinToString(" ")}>")
            sb.append("\n${doc.text ?: ""}")
            sb.append("\n</result>")
        }
        if (chunks.isNotEmpty()) sb.append("\n")
        sb.append("</results>")
        return sb.toString()
    }

    private fun headingPathString(doc: Document): String? {
        val raw = doc.metadata["heading_path"] ?: return null
        return when (raw) {
            is List<*> -> raw.joinToString(" > ")
            else -> raw.toString()
        }
    }

    private fun escapeXmlAttribute(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
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
