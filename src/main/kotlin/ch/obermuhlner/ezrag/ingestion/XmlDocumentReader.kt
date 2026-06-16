package ch.obermuhlner.ezrag.ingestion

import org.springframework.ai.document.Document
import java.io.File

class XmlDocumentReader(
    private val content: String,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
) {

    constructor(file: File, chunkSize: Int = 1000, chunkOverlap: Int = 200) :
        this(file.readText(Charsets.UTF_8), chunkSize, chunkOverlap)

    fun read(): List<Document> {
        val markdown = XmlToMarkdownConverter().convert(content)
        if (markdown.isBlank()) return emptyList()
        return MarkdownDocumentReader(markdown, chunkSize, chunkOverlap).read()
    }
}
