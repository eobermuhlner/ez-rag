package ch.obermuhlner.ezrag.ingestion

import org.springframework.ai.document.Document
import org.springframework.ai.reader.pdf.PagePdfDocumentReader
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import org.springframework.core.io.FileSystemResource
import java.io.File

class PdfDocumentReader(
    private val file: File,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
) {

    fun read(): List<Document> {
        val resource = FileSystemResource(file)
        val reader = PagePdfDocumentReader(resource)
        val documents = reader.get()
        val splitter = TokenTextSplitter.builder()
            .withChunkSize(chunkSize)
            .withMinChunkSizeChars(chunkOverlap)
            .withMinChunkLengthToEmbed(5)
            .withMaxNumChunks(10000)
            .withKeepSeparator(true)
            .build()
        val chunks = splitter.apply(documents)
        return chunks.map { doc ->
            val metaWithoutSource = doc.metadata.toMutableMap().apply { remove("source") }
            Document.builder()
                .id(doc.id)
                .text(doc.text)
                .metadata(metaWithoutSource)
                .build()
        }
    }
}
