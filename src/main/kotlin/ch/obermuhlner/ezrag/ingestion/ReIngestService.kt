package ch.obermuhlner.ezrag.ingestion

import java.io.File
import java.io.PrintWriter
import java.security.MessageDigest

/**
 * Service that re-ingests stale or all documents already present in the unified Lucene index.
 *
 * For each candidate source it:
 * 1. For file sources: checks existence, deletes old chunks, delegates to IngestService.
 * 2. For URL sources: fetches first, skips if hash unchanged, deletes and re-ingests if changed.
 *
 * When [reIngest] is called with forceAll=true, all existing documents are wiped via
 * [LuceneRepository.dropAllDocuments] before re-ingesting. This is necessary when the embedding
 * model's dimension has changed: Lucene does not allow mixing vector dimensions within a single
 * writer session unless all old segments are first fully removed.
 */
open class ReIngestService(
    private val repository: LuceneRepository,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
    private val warningWriter: PrintWriter = PrintWriter(System.err, true),
    private val urlFetcher: UrlFetcher = JsoupUrlFetcher(),
) {

    var onFileReIngesting: ((String) -> Unit)? = null

    open fun reIngest(forceAll: Boolean = false, urlFreshnessThresholdMs: Long = 24 * 3_600_000L): ReIngestResult {
        val metadata = repository.getMetadata(urlFreshnessThresholdMs = urlFreshnessThresholdMs)
        val allDocuments = metadata.documents

        val candidates: List<StoreDocumentInfo>
        val staleFound: Int?

        if (forceAll) {
            candidates = allDocuments
            staleFound = null
        } else {
            val staleDocuments = allDocuments.filter { it.status == "STALE" }
            staleFound = staleDocuments.size
            candidates = staleDocuments
        }

        var filesReIngested = 0
        var chunksCreated = 0
        var filesSkipped = 0
        val sourcesToReIngest = mutableListOf<IngestSource>()

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
                if (!forceAll && repository.isContentUnchanged(doc.path, fetchResult.lastModifiedEpochMs, contentHash)) {
                    filesSkipped++
                    continue
                }
                if (!forceAll) {
                    repository.delete(doc.path)
                }
                onFileReIngesting?.invoke(doc.path)
                sourcesToReIngest.add(UrlSource(doc.path))
            } else {
                val sourceFile = File(doc.path)
                if (!sourceFile.exists()) {
                    warningWriter.println("WARN: source file not found, skipping: ${doc.path}")
                    filesSkipped++
                    continue
                }
                if (!forceAll) {
                    repository.delete(doc.path)
                }
                onFileReIngesting?.invoke(sourceFile.absolutePath)
                sourcesToReIngest.add(FileSource(sourceFile))
            }
        }

        if (sourcesToReIngest.isNotEmpty()) {
            if (forceAll) {
                // Drop all segments so the writer can accept a potentially different embedding
                // dimension. Individual deletes are not sufficient because Lucene retains field-info
                // (including vector dimension) in existing segments even after all documents in
                // those segments have been marked for deletion.
                repository.dropAllDocuments()
            }
            val ingestResult = IngestService(repository, chunkSize, chunkOverlap, warningWriter, urlFetcher = urlFetcher).ingest(sourcesToReIngest)
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
}
