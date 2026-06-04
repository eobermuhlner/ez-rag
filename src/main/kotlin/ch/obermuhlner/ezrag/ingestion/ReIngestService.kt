package ch.obermuhlner.ezrag.ingestion

import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import java.io.File
import java.io.PrintWriter
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Service that re-ingests stale or all documents already present in the unified Lucene index.
 *
 * For each candidate source it:
 * 1. For file sources: checks existence, deletes old chunks, delegates to IngestService.
 * 2. For URL sources: fetches first, skips if hash unchanged, deletes and re-ingests if changed.
 */
open class ReIngestService(
    private val embeddingModel: EmbeddingModel,
    private val storeDir: Path,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
    private val warningWriter: PrintWriter = PrintWriter(System.err, true),
    private val analyzerName: String = "standard",
    private val urlFetcher: UrlFetcher = JsoupUrlFetcher(),
) {

    var onFileReIngesting: ((String) -> Unit)? = null

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
        val sourcesToReIngest = mutableListOf<IngestSource>()

        repository.use {
            for (doc in candidates) {
                val isUrl = doc.path.startsWith("http://") || doc.path.startsWith("https://")
                if (isUrl) {
                    val fetchResult = try {
                        urlFetcher.fetch(doc.path)
                    } catch (e: Exception) {
                        warningWriter.println("WARN: Failed to fetch URL, skipping: ${doc.path}")
                        filesSkipped++
                        continue
                    }
                    val contentHash = computeSha256(fetchResult.bytes)
                    if (repository.isContentUnchanged(doc.path, fetchResult.lastModifiedEpochMs, contentHash)) {
                        filesSkipped++
                        continue
                    }
                    repository.delete(doc.path)
                    onFileReIngesting?.invoke(doc.path)
                    sourcesToReIngest.add(UrlSource(doc.path))
                } else {
                    val sourceFile = File(doc.path)
                    if (!sourceFile.exists()) {
                        warningWriter.println("WARN: source file not found, skipping: ${doc.path}")
                        filesSkipped++
                        continue
                    }
                    repository.delete(doc.path)
                    onFileReIngesting?.invoke(sourceFile.absolutePath)
                    sourcesToReIngest.add(FileSource(sourceFile))
                }
            }
        }

        if (sourcesToReIngest.isNotEmpty()) {
            // When all documents are being re-ingested the embedding model may have changed.
            // Reset the stored dimension so IngestService can write the new one.
            if (forceAll) {
                LuceneRepository.resetStoredDimension(storeDir)
            }
            val ingestService = IngestService(embeddingModel, storeDir, chunkSize, chunkOverlap, warningWriter, analyzerName, urlFetcher)
            val ingestResult = ingestService.ingest(sourcesToReIngest)
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

    private fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
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
