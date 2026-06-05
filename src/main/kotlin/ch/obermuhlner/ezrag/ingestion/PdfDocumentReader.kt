package ch.obermuhlner.ezrag.ingestion

import ch.obermuhlner.ezrag.ingestion.pdf.ConversionOptions
import ch.obermuhlner.ezrag.ingestion.pdf.PdfMarkdown
import org.springframework.ai.document.Document
import java.io.File

class PdfDocumentReader(
    private val file: File,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
) {

    fun read(): List<Document> {
        val markdown = PdfMarkdown.toMarkdown(file, options = ConversionOptions.RAG)
        return MarkdownDocumentReader(markdown, chunkSize, chunkOverlap).read()
    }
}
