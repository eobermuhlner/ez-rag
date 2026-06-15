package ch.obermuhlner.ezrag.ingestion.office

import ch.obermuhlner.ezrag.ingestion.MarkdownDocumentReader
import org.springframework.ai.document.Document
import java.io.File

class ExcelDocumentReader(
    private val file: File,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
    private val passwords: List<String> = emptyList(),
) {

    fun read(): List<Document> {
        val markdown = ExcelToMarkdownConverter().convert(file, passwords)
        return MarkdownDocumentReader(markdown, chunkSize, chunkOverlap).read()
    }
}
