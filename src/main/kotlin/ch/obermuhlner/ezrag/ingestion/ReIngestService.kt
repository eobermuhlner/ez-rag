package ch.obermuhlner.ezrag.ingestion

import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import java.io.File
import java.io.PrintWriter
import java.nio.file.Path

/**
 * Service that re-ingests stale or all documents already present in the unified Lucene index.
 *
 * For each candidate source it:
 * 1. Checks whether the source file still exists on disk. If not, emits a warning and skips.
 * 2. Deletes old chunks from the unified index.
 * 3. Delegates to [IngestService] to re-ingest the file.
 */
open class ReIngestService(
    private val embeddingModel: EmbeddingModel,
    private val storeDir: Path,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
    private val warningWriter: PrintWriter = PrintWriter(System.err, true),
    private val analyzerName: String = "standard",
) {

    var onFileReIngesting: ((Path) -> Unit)? = null

    open fun reIngest(forceAll: Boolean = false): ReIngestResult {
        // Use a stub model (dimension=0) for reading metadata and deleting — no embedding needed.
        // When forceAll=true the stored dimension may differ from the new model's dimension, so
        // bypassing validation here avoids a false dimension-mismatch error.
        val repository = LuceneRepository.open(stubEmbeddingModel(), storeDir, analyzerName)

        val metadata = repository.getMetadata()
        val allDocuments = metadata.documents

        val candidates: List<StoreDocumentInfo>
        val staleFound: Int?

        if (forceAll) {
            candidates = allDocuments
            staleFound = null
        } else {
            val staleDocuments = allDocuments.filter { it.stale }
            staleFound = staleDocuments.size
            candidates = staleDocuments
        }

        var filesReIngested = 0
        var chunksCreated = 0
        var filesSkipped = 0
        val filesToReIngest = mutableListOf<File>()

        repository.use {
            for (doc in candidates) {
                val sourceFile = File(doc.path)
                if (!sourceFile.exists()) {
                    warningWriter.println("WARN: source file not found, skipping: ${doc.path}")
                    filesSkipped++
                    continue
                }
                // Delete old chunks from the unified index
                repository.delete(doc.path)
                onFileReIngesting?.invoke(sourceFile.toPath())
                filesToReIngest.add(sourceFile)
            }
        }

        if (filesToReIngest.isNotEmpty()) {
            // When all documents are being re-ingested the embedding model may have changed.
            // Reset the stored dimension so IngestService can write the new one.
            if (forceAll) {
                LuceneRepository.resetStoredDimension(storeDir)
            }
            val ingestService = IngestService(embeddingModel, storeDir, chunkSize, chunkOverlap, warningWriter, analyzerName)
            val ingestResult = ingestService.ingest(filesToReIngest)
            filesReIngested = ingestResult.filesIngested
            chunksCreated = ingestResult.chunksCreated
        }

        return ReIngestResult(
            staleFound = staleFound,
            filesReIngested = filesReIngested,
            chunksCreated = chunksCreated,
            filesSkipped = filesSkipped,
        )
    }

    private fun stubEmbeddingModel(): EmbeddingModel = object : EmbeddingModel {
        override fun call(request: EmbeddingRequest) =
            EmbeddingResponse(request.instructions.mapIndexed { i, _ -> Embedding(FloatArray(0), i) })
        override fun embed(document: Document): FloatArray = FloatArray(0)
        override fun embed(text: String): FloatArray = FloatArray(0)
        override fun embedForResponse(texts: List<String>) =
            EmbeddingResponse(texts.mapIndexed { i, _ -> Embedding(FloatArray(0), i) })
        override fun dimensions(): Int = 0
    }
}
