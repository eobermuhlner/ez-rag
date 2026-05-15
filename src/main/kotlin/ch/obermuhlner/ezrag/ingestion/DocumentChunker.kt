package ch.obermuhlner.ezrag.ingestion

import org.springframework.ai.document.Document
import org.springframework.ai.transformer.splitter.TokenTextSplitter

class DocumentChunker(
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
) {

    fun split(documents: List<Document>): List<Document> {
        val splitter = TokenTextSplitter.builder()
            .withChunkSize(chunkSize)
            .withMinChunkSizeChars(chunkOverlap)
            .withMinChunkLengthToEmbed(5)
            .withMaxNumChunks(10000)
            .withKeepSeparator(true)
            .build()
        return splitter.apply(documents)
    }
}
