package ch.obermuhlner.ezrag.ingestion

/**
 * Metadata about a single chunk (document fragment) stored in the Lucene index.
 */
data class DocumentChunkInfo(
    val chunkIndex: Int,
    val charCount: Int,
    val mtime: Long,
    val text: String,
    val headingTitle: String? = null,
    val headingLevel: Int? = null,
    val headingPath: List<String>? = null
)

/**
 * Metadata about a single source document (file) as tracked in the Lucene index.
 */
data class StoreDocumentInfo(
    val path: String,
    val chunkCount: Int,
    val mtime: Long = 0L,
    val status: String = "STALE",
    val contentHash: String? = null
)

/**
 * Aggregated metadata about the entire Lucene store.
 */
data class StoreMetadata(
    val storeDirPath: String,
    val chunkCount: Int,
    val documents: List<StoreDocumentInfo>,
    val documentCount: Int = 0,
    val storeSizeBytes: Long = 0L,
    val lastIngestTime: Long = 0L,
    val staleDocumentCount: Int = 0
)
