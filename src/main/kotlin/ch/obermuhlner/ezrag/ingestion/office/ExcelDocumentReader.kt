package ch.obermuhlner.ezrag.ingestion.office

import ch.obermuhlner.ezrag.ingestion.TableChunker
import org.springframework.ai.document.Document
import java.io.File

class ExcelDocumentReader(
    private val file: File,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
    private val passwords: List<String> = emptyList(),
) {

    fun read(): List<Document> {
        val converter = ExcelToMarkdownConverter()
        val sheets = converter.extractSheets(file, passwords)
        val chunker = TableChunker(chunkSize)
        val documents = mutableListOf<Document>()
        for ((sheetName, header, dataRows) in sheets) {
            val chunks = chunker.chunk(header, dataRows)
            for (chunk in chunks) {
                val text = "## $sheetName\n$chunk"
                documents.add(Document(text))
            }
        }
        return documents
    }
}
