package ch.obermuhlner.ezrag.ingestion

import org.springframework.ai.document.Document
import org.springframework.ai.reader.TextReader
import org.springframework.ai.reader.pdf.PagePdfDocumentReader
import org.springframework.core.io.FileSystemResource
import java.nio.file.Path

class DocumentLoader {

    fun load(path: Path): List<Document> {
        val extension = path.fileName.toString().substringAfterLast('.', "")
        return when (extension.lowercase()) {
            "txt" -> loadTxt(path)
            "pdf" -> loadPdf(path)
            "md" -> loadMarkdown(path)
            else -> throw IllegalArgumentException("Unsupported file type: .$extension")
        }
    }

    private fun loadTxt(path: Path): List<Document> {
        val resource = FileSystemResource(path.toFile())
        val reader = TextReader(resource)
        val documents = reader.get()
        return documents.map { doc ->
            Document.builder()
                .id(doc.id)
                .text(doc.text)
                .metadata(doc.metadata + mapOf("source" to path.toString()))
                .build()
        }
    }

    private fun loadPdf(path: Path): List<Document> {
        val resource = FileSystemResource(path.toFile())
        val reader = PagePdfDocumentReader(resource)
        val documents = reader.get()
        return documents.map { doc ->
            Document.builder()
                .id(doc.id)
                .text(doc.text)
                .metadata(doc.metadata + mapOf("source" to path.toString()))
                .build()
        }
    }

    private fun loadMarkdown(path: Path): List<Document> {
        val raw = path.toFile().readText()
        val content = stripYamlFrontMatter(raw)
        val document = Document.builder()
            .text(content)
            .metadata(mapOf("source" to path.toString()))
            .build()
        return listOf(document)
    }

    private fun stripYamlFrontMatter(content: String): String {
        // YAML front-matter is delimited by --- at the start of the file
        val trimmed = content.trimStart()
        if (!trimmed.startsWith("---")) {
            return content
        }
        // Find the closing ---
        val afterFirst = trimmed.removePrefix("---")
        val closingIndex = afterFirst.indexOf("\n---")
        if (closingIndex == -1) {
            return content
        }
        return afterFirst.substring(closingIndex + 4).trimStart()
    }
}
