package ch.obermuhlner.ezrag.ingestion

import org.springframework.ai.document.Document
import java.io.File

class DocumentReaderRegistry(
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
) {

    private val readers: Map<String, (File) -> List<Document>> = mapOf(
        "txt" to { file -> PlainTextDocumentReader(file, chunkSize, chunkOverlap).read() },
        "pdf" to { file -> PdfDocumentReader(file, chunkSize, chunkOverlap).read() },
        "md"  to { file -> MarkdownDocumentReader(file, chunkSize, chunkOverlap).read() },
    )

    fun supports(extension: String): Boolean = extension.lowercase() in readers

    fun read(file: File): List<Document> {
        val extension = file.name.substringAfterLast('.', "").lowercase()
        val readerFn = readers[extension]
            ?: throw IllegalArgumentException("Unsupported file type: .$extension")
        return readerFn(file)
    }
}
