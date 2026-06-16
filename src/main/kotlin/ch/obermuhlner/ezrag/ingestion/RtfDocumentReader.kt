package ch.obermuhlner.ezrag.ingestion

import org.springframework.ai.document.Document
import org.springframework.ai.transformer.splitter.TokenTextSplitter
import java.io.ByteArrayInputStream
import java.io.File
import javax.swing.text.DefaultStyledDocument
import javax.swing.text.rtf.RTFEditorKit

class RtfDocumentReader(
    private val file: File,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
) {

    fun read(): List<Document> {
        val kit = RTFEditorKit()
        val styledDoc = DefaultStyledDocument()
        file.inputStream().use { stream ->
            kit.read(stream, styledDoc, 0)
        }
        val plainText = styledDoc.getText(0, styledDoc.length)

        val fullDoc = Document.builder()
            .text(plainText)
            .build()

        val splitter = TokenTextSplitter.builder()
            .withChunkSize(chunkSize)
            .withMinChunkSizeChars(chunkOverlap)
            .withMinChunkLengthToEmbed(5)
            .withMaxNumChunks(10000)
            .withKeepSeparator(true)
            .build()
        val chunks = splitter.apply(listOf(fullDoc))
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
