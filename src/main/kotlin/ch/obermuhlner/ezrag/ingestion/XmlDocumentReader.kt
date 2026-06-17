package ch.obermuhlner.ezrag.ingestion

import org.springframework.ai.document.Document
import java.io.File
import java.io.PrintWriter

class XmlDocumentReader private constructor(
    private val content: String?,
    private val file: File?,
    private val chunkSize: Int,
    private val chunkOverlap: Int,
    private val warningWriter: PrintWriter,
    private val boundaryTags: List<String>,
) {

    constructor(
        content: String,
        chunkSize: Int = 1000,
        chunkOverlap: Int = 200,
        warningWriter: PrintWriter = PrintWriter(System.err, true),
        boundaryTags: List<String> = emptyList(),
    ) : this(content = content, file = null, chunkSize = chunkSize, chunkOverlap = chunkOverlap, warningWriter = warningWriter, boundaryTags = boundaryTags)

    constructor(
        file: File,
        chunkSize: Int = 1000,
        chunkOverlap: Int = 200,
        warningWriter: PrintWriter = PrintWriter(System.err, true),
        boundaryTags: List<String> = emptyList(),
    ) : this(content = null, file = file, chunkSize = chunkSize, chunkOverlap = chunkOverlap, warningWriter = warningWriter, boundaryTags = boundaryTags)

    fun read(): List<Document> {
        try {
            val xmlContent = content ?: file!!.readText(Charsets.UTF_8)
            val markdown = XmlToMarkdownConverter(chunkSize).convert(xmlContent, boundaryTags)
            if (markdown.isBlank()) return emptyList()
            return MarkdownDocumentReader(markdown, chunkSize, chunkOverlap).read()
        } catch (e: Exception) {
            warningWriter.println("WARNING: Failed to read XML content: ${e.message}")
            return emptyList()
        }
    }
}
