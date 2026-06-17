package ch.obermuhlner.ezrag.ingestion

import ch.obermuhlner.ezrag.ingestion.office.ExcelDocumentReader
import ch.obermuhlner.ezrag.ingestion.office.PowerPointDocumentReader
import ch.obermuhlner.ezrag.ingestion.office.WordDocumentReader
import ch.obermuhlner.ezrag.ingestion.sourcecode.JavaSourceCodeParser
import ch.obermuhlner.ezrag.ingestion.sourcecode.KotlinSourceCodeParser
import ch.obermuhlner.ezrag.ingestion.sourcecode.SourceCodeDocumentReader
import ch.obermuhlner.ezrag.ingestion.sourcecode.TypeScriptSourceCodeParser
import org.springframework.ai.document.Document
import java.io.File

class DocumentReaderRegistry(
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
    private val passwords: List<String> = emptyList(),
    private val xmlBoundaryTags: List<String> = emptyList(),
) {

    private val readers: Map<String, (File) -> List<Document>> = mapOf(
        "txt"  to { file -> PlainTextDocumentReader(file, chunkSize, chunkOverlap).read() },
        "pdf"  to { file -> PdfDocumentReader(file, chunkSize, chunkOverlap).read() },
        "md"   to { file -> MarkdownDocumentReader(file, chunkSize, chunkOverlap).read() },
        "docx" to { file -> WordDocumentReader(file, chunkSize, chunkOverlap, passwords).read() },
        "doc"  to { file -> WordDocumentReader(file, chunkSize, chunkOverlap, passwords).read() },
        "pptx" to { file -> PowerPointDocumentReader(file, chunkSize, chunkOverlap, passwords).read() },
        "ppt"  to { file -> PowerPointDocumentReader(file, chunkSize, chunkOverlap, passwords).read() },
        "xlsx" to { file -> ExcelDocumentReader(file, chunkSize, chunkOverlap, passwords).read() },
        "xls"  to { file -> ExcelDocumentReader(file, chunkSize, chunkOverlap, passwords).read() },
        "html"  to { file -> HtmlDocumentReader(file, chunkSize, chunkOverlap).read() },
        "htm"   to { file -> HtmlDocumentReader(file, chunkSize, chunkOverlap).read() },
        "xhtml" to { file -> HtmlDocumentReader(file, chunkSize, chunkOverlap).read() },
        "rtf"   to { file -> RtfDocumentReader(file, chunkSize, chunkOverlap).read() },
        "csv"   to { file -> CsvDocumentReader(file, chunkSize, chunkOverlap).read() },
        "xml"   to { file -> XmlDocumentReader(file, chunkSize, chunkOverlap, boundaryTags = xmlBoundaryTags).read() },
        "svg"   to { file -> XmlDocumentReader(file, chunkSize, chunkOverlap, boundaryTags = xmlBoundaryTags).read() },
        "rss"   to { file -> XmlDocumentReader(file, chunkSize, chunkOverlap, boundaryTags = xmlBoundaryTags).read() },
        "atom"  to { file -> XmlDocumentReader(file, chunkSize, chunkOverlap, boundaryTags = xmlBoundaryTags).read() },
        "kt"    to { file -> SourceCodeDocumentReader(file.readText(), KotlinSourceCodeParser(), chunkSize, chunkOverlap).read() },
        "kts"   to { file -> SourceCodeDocumentReader(file.readText(), KotlinSourceCodeParser(), chunkSize, chunkOverlap).read() },
        "java"  to { file -> SourceCodeDocumentReader(file.readText(), JavaSourceCodeParser(), chunkSize, chunkOverlap).read() },
        "ts"    to { file -> SourceCodeDocumentReader(file.readText(), TypeScriptSourceCodeParser("typescript"), chunkSize, chunkOverlap).read() },
        "tsx"   to { file -> SourceCodeDocumentReader(file.readText(), TypeScriptSourceCodeParser("tsx"), chunkSize, chunkOverlap).read() },
        "js"    to { file -> SourceCodeDocumentReader(file.readText(), TypeScriptSourceCodeParser("javascript"), chunkSize, chunkOverlap).read() },
        "jsx"   to { file -> SourceCodeDocumentReader(file.readText(), TypeScriptSourceCodeParser("jsx"), chunkSize, chunkOverlap).read() },
    )

    fun supports(extension: String): Boolean = extension.lowercase() in readers

    fun read(file: File): List<Document> {
        val extension = file.name.substringAfterLast('.', "").lowercase()
        val readerFn = readers[extension]
        if (readerFn != null) return readerFn(file)

        // Fallback: binary detection — read up to 8 KB and check for null bytes
        val bytes = file.inputStream().use { it.readNBytes(8192) }
        if (BinaryDetector.isBinary(bytes)) {
            throw IllegalArgumentException("Binary file detected, skipping: ${file.name}")
        }
        return PlainTextDocumentReader(file, chunkSize, chunkOverlap).read()
    }
}
