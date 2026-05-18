package ch.obermuhlner.ezrag.ingestion

import org.springframework.ai.embedding.EmbeddingModel
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
        val repository = LuceneRepository.open(embeddingModel, storeDir, analyzerName)

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
}
