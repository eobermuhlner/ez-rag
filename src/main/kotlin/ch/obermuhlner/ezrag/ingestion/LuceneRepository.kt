package ch.obermuhlner.ezrag.ingestion

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.en.EnglishAnalyzer
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.codecs.KnnVectorsFormat
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsFormat
import org.apache.lucene.codecs.lucene912.Lucene912Codec
import org.apache.lucene.document.Field
import org.apache.lucene.document.KnnFloatVectorField
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.SegmentReadState
import org.apache.lucene.index.SegmentWriteState
import org.apache.lucene.index.Term
import org.apache.lucene.index.VectorSimilarityFunction
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.KnnFloatVectorQuery
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.NIOFSDirectory
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

/**
 * Unified on-disk Lucene index combining HNSW embedding vectors and BM25 text search.
 *
 * Each Lucene document stores:
 * - KnnFloatVectorField("vector", ...) — HNSW index, COSINE similarity, M=16
 * - TextField("content", ...) — BM25 index, analysed by the configured analyser
 * - StoredFields for id, source, mtime, chunk_index, heading_title, heading_level, heading_path
 *
 * The source→mtime cache is built on open and kept in memory for O(1) isAlreadyIngested checks.
 */
class LuceneRepository private constructor(
    private val embeddingModel: EmbeddingModel,
    private val storeDir: Path,
    private val analyzer: Analyzer,
    private val writer: IndexWriter,
) : Closeable {

    private val luceneDir: Path = storeDir.resolve("lucene")
    private val objectMapper = ObjectMapper()

    // source → mtime cache built on open, updated on add/delete
    private val sourceMtimeCache: MutableMap<String, Long> = mutableMapOf()

    // source → ingest_time cache built on open, updated on add/delete
    private val sourceIngestTimeCache: MutableMap<String, Long> = mutableMapOf()

    companion object {
        private const val CONFIG_FILE = "config.properties"
        private const val PROP_DIMENSION = "embedding.dimension"
        private const val FIELD_VECTOR = "vector"
        private const val FIELD_CONTENT = "content"
        private const val FIELD_ID = "id"
        private const val FIELD_SOURCE = "source"
        private const val FIELD_MTIME = "mtime"
        private const val FIELD_CHUNK_INDEX = "chunk_index"
        private const val FIELD_HEADING_TITLE = "heading_title"
        private const val FIELD_HEADING_LEVEL = "heading_level"
        private const val FIELD_HEADING_PATH = "heading_path"
        private const val FIELD_CONTENT_HASH = "content_hash"
        private const val FIELD_INGEST_TIME = "ingest_time"

        /**
         * Opens (or creates) the unified Lucene index at storeDir/lucene/.
         * Validates or writes the embedding dimension in config.properties.
         * Builds the source→mtime in-memory cache from stored fields.
         */
        fun open(embeddingModel: EmbeddingModel, storeDir: Path, analyzerName: String): LuceneRepository {
            val luceneDir = storeDir.resolve("lucene")
            Files.createDirectories(luceneDir)

            val dimension = embeddingModel.dimensions()
            validateOrWriteDimension(luceneDir, dimension)

            val analyzer = createAnalyzer(analyzerName)
            val directory = NIOFSDirectory.open(luceneDir)
            val config = IndexWriterConfig(analyzer).apply {
                openMode = IndexWriterConfig.OpenMode.CREATE_OR_APPEND
                codec = HighDimCodec()
            }
            val writer = IndexWriter(directory, config)

            val repo = LuceneRepository(embeddingModel, storeDir, analyzer, writer)
            repo.buildCache()
            return repo
        }

        /**
         * Deletes the stored embedding dimension config so the next open() accepts any dimension.
         * Use before re-ingesting all documents with a new embedding model.
         */
        fun resetStoredDimension(storeDir: Path) {
            storeDir.resolve("lucene").resolve(CONFIG_FILE).toFile().delete()
        }

        /**
         * Checks whether the index at storeDir/lucene/ contains committed data,
         * without opening a writer.
         */
        fun storeExists(storeDir: Path): Boolean {
            val luceneDir = storeDir.resolve("lucene")
            if (!Files.exists(luceneDir)) return false
            return try {
                NIOFSDirectory.open(luceneDir).use { dir ->
                    DirectoryReader.indexExists(dir)
                }
            } catch (_: Exception) {
                false
            }
        }

        private fun createAnalyzer(name: String): Analyzer = when (name.lowercase()) {
            "english" -> EnglishAnalyzer()
            else -> StandardAnalyzer()
        }

        // Subclass retains the "Lucene912" codec name so existing segments remain readable
        // via the service-loader-registered Lucene912Codec. The only change is relaxing the
        // HNSW max-dimension limit to support embedding models with > 1024 dimensions (e.g. OpenAI 1536).
        private class HighDimCodec : Lucene912Codec() {
            private val highDimFormat = object : KnnVectorsFormat("Lucene99HnswVectorsFormat") {
                private val delegate = Lucene99HnswVectorsFormat()
                override fun fieldsWriter(state: SegmentWriteState) = delegate.fieldsWriter(state)
                override fun fieldsReader(state: SegmentReadState) = delegate.fieldsReader(state)
                override fun getMaxDimensions(fieldName: String) = 4096
            }
            override fun getKnnVectorsFormatForField(field: String) = highDimFormat
        }

        private fun validateOrWriteDimension(luceneDir: Path, dimension: Int) {
            // Skip validation when dimension is 0 (stub/read-only mode with no real embedding model)
            if (dimension == 0) return
            val configFile = luceneDir.resolve(CONFIG_FILE).toFile()
            if (configFile.exists()) {
                val props = Properties()
                configFile.inputStream().use { props.load(it) }
                val storedDim = props.getProperty(PROP_DIMENSION)?.toIntOrNull()
                if (storedDim != null && storedDim != dimension) {
                    throw IllegalStateException(
                        "Embedding dimension mismatch: stored=$storedDim, current=$dimension. " +
                        "Please re-ingest all documents to use the new embedding model."
                    )
                }
            } else {
                val props = Properties()
                props.setProperty(PROP_DIMENSION, dimension.toString())
                configFile.outputStream().use { props.store(it, null) }
            }
        }
    }

    /**
     * Writes all documents to the index, commits, and updates the source→mtime cache.
     * Synchronized to prevent interleaved commits and cache races from concurrent MCP calls.
     */
    @Synchronized
    fun add(documents: List<Document>) {
        if (documents.isEmpty()) return

        for (doc in documents) {
            val text = doc.text ?: ""
            val source = doc.metadata["source"] as? String ?: ""
            val mtime = toLong(doc.metadata["mtime"]) ?: 0L
            val chunkIndex = toLong(doc.metadata["chunk_index"])?.toInt() ?: 0
            val id = doc.id ?: java.util.UUID.randomUUID().toString()
            val headingTitle = doc.metadata["heading_title"] as? String
            val headingLevel = doc.metadata["heading_level"]?.let { toInt(it) }
            val headingPath = when (val raw = doc.metadata["heading_path"]) {
                is List<*> -> raw.filterIsInstance<String>()
                else -> null
            }

            val vector = embeddingModel.embed(text)

            val contentHash = doc.metadata["content_hash"] as? String
            val ingestTime = toLong(doc.metadata["ingest_time"])

            val luceneDoc = org.apache.lucene.document.Document().apply {
                add(KnnFloatVectorField(FIELD_VECTOR, vector, VectorSimilarityFunction.COSINE))
                add(TextField(FIELD_CONTENT, text, Field.Store.YES))
                add(StringField(FIELD_ID, id, Field.Store.YES))
                add(StringField(FIELD_SOURCE, source, Field.Store.YES))
                add(StoredField(FIELD_MTIME, mtime))
                add(StoredField(FIELD_CHUNK_INDEX, chunkIndex))
                if (headingTitle != null) add(StoredField(FIELD_HEADING_TITLE, headingTitle))
                if (headingLevel != null) add(StoredField(FIELD_HEADING_LEVEL, headingLevel))
                if (headingPath != null) {
                    add(StoredField(FIELD_HEADING_PATH, objectMapper.writeValueAsString(headingPath)))
                }
                if (contentHash != null) add(StoredField(FIELD_CONTENT_HASH, contentHash))
                if (ingestTime != null) add(StoredField(FIELD_INGEST_TIME, ingestTime))
            }
            writer.addDocument(luceneDoc)
            sourceMtimeCache[source] = mtime
            if (ingestTime != null) {
                val existing = sourceIngestTimeCache[source]
                if (existing == null || ingestTime > existing) {
                    sourceIngestTimeCache[source] = ingestTime
                }
            }
        }

        writer.commit()
    }

    /**
     * Runs HNSW KNN query against the index for the given query text.
     * Returns Spring AI Documents with score in metadata.
     * Requests numCandidates = topK * 4 candidates to improve recall (fetches more, returns topK).
     */
    fun semanticSearch(query: String, topK: Int): List<Document> {
        val queryVector = embeddingModel.embed(query)
        val numCandidates = topK * 4
        return withSearcher(emptyList()) { searcher ->
            val knnQuery = KnnFloatVectorQuery(FIELD_VECTOR, queryVector, numCandidates)
            val hits = searcher.search(knnQuery, topK)
            hits.scoreDocs.map { scoreDoc ->
                val luceneDoc = searcher.storedFields().document(scoreDoc.doc)
                luceneDocToSpringDoc(luceneDoc, scoreDoc.score.toDouble())
            }
        }
    }

    /**
     * Runs BM25 keyword search against the content field.
     * Returns Spring AI Documents with score in metadata.
     */
    fun bm25Search(query: String, topK: Int): List<Document> {
        return withSearcher(emptyList()) { searcher ->
            val parser = QueryParser(FIELD_CONTENT, analyzer)
            val parsedQuery = try {
                parser.parse(QueryParser.escape(query))
            } catch (_: Exception) {
                return@withSearcher emptyList()
            }
            val hits = searcher.search(parsedQuery, topK)
            if (hits.scoreDocs.isEmpty()) return@withSearcher emptyList()
            val maxScore = hits.scoreDocs[0].score  // sorted descending; position 0 is max
            hits.scoreDocs.map { scoreDoc ->
                val luceneDoc = searcher.storedFields().document(scoreDoc.doc)
                val normalizedScore = if (maxScore > 0f) scoreDoc.score / maxScore else 0.0
                luceneDocToSpringDoc(luceneDoc, normalizedScore.toDouble())
            }
        }
    }

    /**
     * Returns true if the source file with the given mtime is already in the index (O(1) cache check).
     */
    fun isAlreadyIngested(source: String, mtime: Long): Boolean {
        return sourceMtimeCache[source] == mtime
    }

    /**
     * Two-step staleness check using mtime (fast path) and SHA-256 content hash (slow path).
     * Returns true when the stored content is identical to the incoming [contentHash], meaning
     * the source should be skipped. Returns false when the content has changed and re-ingestion
     * is needed.
     *
     * Step 1 (fast path, no I/O): if the cached mtime matches [mtime], return true immediately.
     * Step 2 (slow path): read the stored content_hash from the index and compare. Returns true
     * only when the hashes match; returns false when no hash is stored or the hash differs.
     */
    fun isContentUnchanged(source: String, mtime: Long, contentHash: String): Boolean {
        // Fast path only when mtime != 0; mtime=0 (no Last-Modified) must always do hash comparison
        if (mtime != 0L && sourceMtimeCache[source] == mtime) return true
        val storedHash = getContentHashFromIndex(source) ?: return false
        return storedHash == contentHash
    }

    /**
     * Deletes all Lucene documents for the given source path.
     * Commits the writer. Removes from cache. Returns the number of documents deleted.
     * Synchronized to prevent interleaved commits and cache races from concurrent MCP calls.
     */
    @Synchronized
    fun delete(source: String): Int {
        val countBefore = countDocumentsForSource(source)
        if (countBefore == 0) return 0

        writer.deleteDocuments(Term(FIELD_SOURCE, source))
        writer.commit()
        sourceMtimeCache.remove(source)
        sourceIngestTimeCache.remove(source)
        return countBefore
    }

    /**
     * Updates the ingest_time for all chunks of the given source without altering content.
     * Used by ReIngestService to reset the freshness timer when content is unchanged.
     */
    @Synchronized
    fun updateIngestTime(source: String, ingestTime: Long) {
        val chunks = getChunksForFile(source)
        if (chunks.isEmpty()) return
        delete(source)
        val docs = chunks.map { chunk ->
            Document.builder()
                .id("${source}#${chunk.chunkIndex}")
                .text(chunk.text)
                .metadata(mutableMapOf<String, Any>(
                    "source" to source,
                    "mtime" to chunk.mtime,
                    "chunk_index" to chunk.chunkIndex,
                    "ingest_time" to ingestTime,
                ))
                .build()
        }
        add(docs)
    }

    /**
     * Drops all documents from the index and clears the cache.
     * Uses [IndexWriter.deleteAll] to wipe all segments (including their field-schema),
     * which is required before writing documents with a different embedding dimension.
     * After this call the writer is committed and the in-memory cache is empty.
     */
    fun dropAllDocuments() {
        writer.deleteAll()
        writer.commit()
        sourceMtimeCache.clear()
        sourceIngestTimeCache.clear()
    }

    /**
     * Returns chunk info for all documents with the given source path, sorted by chunk_index.
     */
    fun getChunksForFile(source: String): List<DocumentChunkInfo> {
        return withSearcher(emptyList()) { searcher ->
            val termQuery = TermQuery(Term(FIELD_SOURCE, source))
            val hits = searcher.search(termQuery, Int.MAX_VALUE)
            hits.scoreDocs.map { scoreDoc ->
                val luceneDoc = searcher.storedFields().document(scoreDoc.doc)
                val text = luceneDoc.get(FIELD_CONTENT) ?: ""
                val mtime = luceneDoc.getField(FIELD_MTIME)?.numericValue()?.toLong() ?: 0L
                val chunkIndex = luceneDoc.getField(FIELD_CHUNK_INDEX)?.numericValue()?.toInt() ?: 0
                val headingTitle = luceneDoc.get(FIELD_HEADING_TITLE)
                val headingLevel = luceneDoc.getField(FIELD_HEADING_LEVEL)?.numericValue()?.toInt()
                val headingPathJson = luceneDoc.get(FIELD_HEADING_PATH)
                val headingPath = if (headingPathJson != null) {
                    objectMapper.readValue(headingPathJson, List::class.java)
                        .filterIsInstance<String>()
                        .takeIf { it.isNotEmpty() }
                } else {
                    null
                }
                DocumentChunkInfo(
                    chunkIndex = chunkIndex,
                    charCount = text.length,
                    mtime = mtime,
                    text = text,
                    headingTitle = headingTitle,
                    headingLevel = headingLevel,
                    headingPath = headingPath
                )
            }
        }.sortedBy { it.chunkIndex }
    }

    /**
     * Returns chunks for the given source whose chunkIndex falls in [fromIndex, toIndex], sorted ascending.
     * Returns an empty list when no matching document exists or when fromIndex > toIndex.
     */
    fun getChunkRange(source: String, fromIndex: Int, toIndex: Int): List<DocumentChunkInfo> {
        if (fromIndex > toIndex) return emptyList()
        return getChunksForFile(source).filter { it.chunkIndex in fromIndex..toIndex }
    }

    /**
     * Aggregates metadata across all documents in the index.
     * filesystemProbe is called with each source path to get current mtime for staleness check.
     */
    fun getMetadata(
        filesystemProbe: (String) -> Long? = { path ->
            try {
                Files.getLastModifiedTime(Paths.get(path)).toMillis()
            } catch (_: Exception) {
                null
            }
        },
        urlFreshnessThresholdMs: Long = 24 * 3_600_000L,
        currentTimeMs: Long = System.currentTimeMillis(),
    ): StoreMetadata {
        val chunkCountBySource = mutableMapOf<String, Int>()
        val maxMtimeBySource = mutableMapOf<String, Long>()
        val contentHashBySource = mutableMapOf<String, String>()

        withSearcher(Unit) { searcher ->
            val reader = searcher.indexReader
            val storedFields = reader.storedFields()
            for (i in 0 until reader.maxDoc()) {
                val doc = storedFields.document(i)
                val src = doc.get(FIELD_SOURCE) ?: continue
                val mtime = doc.getField(FIELD_MTIME)?.numericValue()?.toLong() ?: 0L
                chunkCountBySource[src] = (chunkCountBySource[src] ?: 0) + 1
                maxMtimeBySource[src] = maxOf(maxMtimeBySource[src] ?: 0L, mtime)
                if (!contentHashBySource.containsKey(src)) {
                    val hash = doc.get(FIELD_CONTENT_HASH)
                    if (hash != null) contentHashBySource[src] = hash
                }
            }
        }

        val totalChunks = chunkCountBySource.values.sum()
        val lastIngestTime = if (maxMtimeBySource.isEmpty()) 0L else maxMtimeBySource.values.max()
        val documents = chunkCountBySource.entries
            .sortedBy { it.key }
            .map { (src, count) ->
                val storedMtime = maxMtimeBySource[src] ?: 0L
                val status = if (src.startsWith("http://") || src.startsWith("https://")) {
                    val ingestTime = sourceIngestTimeCache[src] ?: 0L
                    if (ingestTime > 0 && currentTimeMs - ingestTime < urlFreshnessThresholdMs) "FRESH" else "STALE"
                } else {
                    val currentMtime = filesystemProbe(src)
                    if (currentMtime != null && currentMtime == storedMtime) "FRESH" else "STALE"
                }
                StoreDocumentInfo(
                    path = src,
                    chunkCount = count,
                    mtime = storedMtime,
                    status = status,
                    contentHash = contentHashBySource[src]
                )
            }
        val staleDocumentCount = documents.count { it.status == "STALE" }

        val storeSizeBytes = if (Files.exists(luceneDir)) {
            luceneDir.toFile().walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else 0L

        return StoreMetadata(
            storeDirPath = luceneDir.toAbsolutePath().toString(),
            chunkCount = totalChunks,
            documents = documents,
            documentCount = documents.size,
            storeSizeBytes = storeSizeBytes,
            lastIngestTime = lastIngestTime,
            staleDocumentCount = staleDocumentCount
        )
    }

    override fun close() {
        writer.commit()
        writer.close()
        analyzer.close()
    }

    // --- Private helpers ---

    private fun buildCache() {
        withSearcher(Unit) { searcher ->
            val reader = searcher.indexReader
            val storedFields = reader.storedFields()
            for (i in 0 until reader.maxDoc()) {
                val doc = storedFields.document(i)
                val src = doc.get(FIELD_SOURCE) ?: continue
                val mtime = doc.getField(FIELD_MTIME)?.numericValue()?.toLong() ?: continue
                // Keep the maximum mtime seen for this source (matches add() behaviour)
                val existing = sourceMtimeCache[src]
                if (existing == null || mtime > existing) {
                    sourceMtimeCache[src] = mtime
                }
                val ingestTime = doc.getField(FIELD_INGEST_TIME)?.numericValue()?.toLong()
                if (ingestTime != null) {
                    val existingIngestTime = sourceIngestTimeCache[src]
                    if (existingIngestTime == null || ingestTime > existingIngestTime) {
                        sourceIngestTimeCache[src] = ingestTime
                    }
                }
            }
        }
    }

    private fun countDocumentsForSource(source: String): Int {
        return withSearcher(0) { searcher ->
            searcher.count(TermQuery(Term(FIELD_SOURCE, source)))
        }
    }

    private fun <T> withSearcher(defaultIfEmpty: T, block: (IndexSearcher) -> T): T {
        if (!Files.exists(luceneDir)) return defaultIfEmpty
        NIOFSDirectory.open(luceneDir).use { dir ->
            if (!DirectoryReader.indexExists(dir)) return defaultIfEmpty
            DirectoryReader.open(dir).use { reader ->
                val searcher = IndexSearcher(reader)
                return block(searcher)
            }
        }
    }

    private fun luceneDocToSpringDoc(luceneDoc: org.apache.lucene.document.Document, score: Double): Document {
        val text = luceneDoc.get(FIELD_CONTENT) ?: ""
        val source = luceneDoc.get(FIELD_SOURCE) ?: ""
        val id = luceneDoc.get(FIELD_ID) ?: ""
        val mtime = luceneDoc.getField(FIELD_MTIME)?.numericValue()?.toLong() ?: 0L
        val chunkIndex = luceneDoc.getField(FIELD_CHUNK_INDEX)?.numericValue()?.toInt() ?: 0
        val headingTitle = luceneDoc.get(FIELD_HEADING_TITLE)
        val headingLevel = luceneDoc.getField(FIELD_HEADING_LEVEL)?.numericValue()?.toInt()
        val headingPathJson = luceneDoc.get(FIELD_HEADING_PATH)
        val headingPath = if (headingPathJson != null) {
            objectMapper.readValue(headingPathJson, List::class.java).filterIsInstance<String>()
        } else null

        val metadata = mutableMapOf<String, Any>(
            "source" to source,
            "mtime" to mtime,
            "chunk_index" to chunkIndex,
            "score" to score
        )
        if (headingTitle != null) metadata["heading_title"] = headingTitle
        if (headingLevel != null) metadata["heading_level"] = headingLevel
        if (headingPath != null) metadata["heading_path"] = headingPath

        return Document.builder()
            .id(id)
            .text(text)
            .metadata(metadata)
            .score(score)
            .build()
    }

    private fun getContentHashFromIndex(source: String): String? {
        return withSearcher(null) { searcher ->
            val hits = searcher.search(TermQuery(Term(FIELD_SOURCE, source)), 1)
            if (hits.totalHits.value == 0L) null
            else searcher.storedFields().document(hits.scoreDocs[0].doc).get(FIELD_CONTENT_HASH)
        }
    }

    private fun toLong(value: Any?): Long? = when (value) {
        is Long -> value
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    private fun toInt(value: Any?): Int? = when (value) {
        is Int -> value
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
}
