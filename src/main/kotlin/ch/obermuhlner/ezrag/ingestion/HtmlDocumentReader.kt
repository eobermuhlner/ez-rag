package ch.obermuhlner.ezrag.ingestion

import org.jsoup.Jsoup
import org.springframework.ai.document.Document
import java.io.File

class HtmlDocumentReader(
    private val html: String,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
) {

    constructor(file: File, chunkSize: Int = 1000, chunkOverlap: Int = 200) :
        this(file.readText(Charsets.UTF_8), chunkSize, chunkOverlap)

    fun read(): List<Document> {
        val doc = Jsoup.parse(html)
        val pageTitle = doc.title()
        val markdown = HtmlToMarkdownConverter().convert(html)
        val chunks = MarkdownDocumentReader(markdown, chunkSize, chunkOverlap).read()
        return chunks.map { chunk ->
            Document.builder()
                .id(chunk.id)
                .text(chunk.text)
                .metadata(chunk.metadata + mapOf("page_title" to pageTitle))
                .build()
        }
    }
}
