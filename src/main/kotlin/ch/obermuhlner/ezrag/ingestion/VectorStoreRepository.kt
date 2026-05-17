package ch.obermuhlner.ezrag.ingestion

import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.vectorstore.SimpleVectorStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

data class StoreDocumentInfo(val path: String, val chunkCount: Int, val mtime: Long = 0L, val stale: Boolean = false)

data class DocumentChunkInfo(
    val chunkIndex: Int,
    val charCount: Int,
    val mtime: Long,
    val text: String,
    val headingTitle: String? = null,
    val headingLevel: Int? = null,
    val headingPath: List<String>? = null
)

data class StoreMetadata(
    val storeFilePath: String,
    val chunkCount: Int,
    val documents: List<StoreDocumentInfo>,
    val documentCount: Int = 0,
    val storeSizeBytes: Long = 0L,
    val lastIngestTime: Long = 0L,
    val staleDocumentCount: Int = 0
)

class VectorStoreRepository(
    private val embeddingModel: EmbeddingModel,
    private val storeFilePath: Path,
) {

    private lateinit var vectorStore: SimpleVectorStore
    // Tracks (source, mtime) pairs of all documents in the store
    private val ingestedFiles: MutableSet<Pair<String, Long>> = mutableSetOf()

    fun load() {
        vectorStore = SimpleVectorStore.builder(embeddingModel).build()
        val file = storeFilePath.toFile()
        if (file.exists()) {
            vectorStore.load(file)
            populateIngestedFilesFromStore()
        }
    }

    fun add(documents: List<Document>) {
        vectorStore.add(documents)
        for (doc in documents) {
            val source = doc.metadata["source"] as? String ?: continue
            val mtime = doc.metadata["mtime"]?.let { toLong(it) } ?: continue
            ingestedFiles.add(Pair(source, mtime))
        }
    }

    fun save() {
        val dir = storeFilePath.parent?.toFile()
        if (dir != null && !dir.exists()) {
            dir.mkdirs()
        }
        vectorStore.save(storeFilePath.toFile())
    }

    fun isAlreadyIngested(sourcePath: String, mtime: Long): Boolean {
        return ingestedFiles.contains(Pair(sourcePath, mtime))
    }

    fun storeExists(): Boolean = storeFilePath.toFile().exists()

    fun getStore(): SimpleVectorStore = vectorStore

    fun delete(absoluteFilePath: String): Int {
        val storeField = SimpleVectorStore::class.java.getDeclaredField("store")
        storeField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val storeMap = storeField.get(vectorStore) as Map<String, Any>

        val matchingIds = mutableListOf<String>()
        for ((id, entry) in storeMap) {
            val metadataMethod = entry.javaClass.getMethod("getMetadata")
            @Suppress("UNCHECKED_CAST")
            val metadata = metadataMethod.invoke(entry) as? Map<String, Any> ?: continue
            val source = metadata["source"] as? String ?: continue
            if (source == absoluteFilePath) {
                matchingIds.add(id)
            }
        }

        if (matchingIds.isEmpty()) return 0

        vectorStore.delete(matchingIds)

        // Evict all (source, mtime) pairs for this file from the ingestedFiles cache
        ingestedFiles.removeIf { (source, _) -> source == absoluteFilePath }

        return matchingIds.size
    }

    fun getChunksForFile(absoluteFilePath: String): List<DocumentChunkInfo> {
        val storeField = SimpleVectorStore::class.java.getDeclaredField("store")
        storeField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val storeMap = storeField.get(vectorStore) as Map<String, Any>

        val chunks = mutableListOf<DocumentChunkInfo>()
        for (entry in storeMap.values) {
            val metadataMethod = entry.javaClass.getMethod("getMetadata")
            @Suppress("UNCHECKED_CAST")
            val metadata = metadataMethod.invoke(entry) as? Map<String, Any> ?: continue
            val source = metadata["source"] as? String ?: continue
            if (source != absoluteFilePath) continue

            val textMethod = entry.javaClass.getMethod("getText")
            val text = textMethod.invoke(entry) as? String ?: ""
            val mtime = metadata["mtime"]?.let { toLong(it) } ?: 0L
            val chunkIndex = metadata["chunk_index"]?.let { toLong(it)?.toInt() } ?: 0

            val headingTitle = metadata["heading_title"] as? String
            val headingLevel = metadata["heading_level"]?.let {
                when (it) {
                    is Int -> it
                    is Number -> it.toInt()
                    is String -> it.toIntOrNull()
                    else -> null
                }
            }
            @Suppress("UNCHECKED_CAST")
            val headingPath = when (val raw = metadata["heading_path"]) {
                is List<*> -> raw.filterIsInstance<String>().takeIf { it.isNotEmpty() }
                else -> null
            }

            chunks.add(DocumentChunkInfo(
                chunkIndex = chunkIndex,
                charCount = text.length,
                mtime = mtime,
                text = text,
                headingTitle = headingTitle,
                headingLevel = headingLevel,
                headingPath = headingPath
            ))
        }

        return chunks.sortedBy { it.chunkIndex }
    }

    fun getMetadata(
        filesystemProbe: (String) -> Long? = { path ->
            try {
                Files.getLastModifiedTime(Paths.get(path)).toMillis()
            } catch (_: Exception) {
                null
            }
        }
    ): StoreMetadata {
        val chunkCountBySource = mutableMapOf<String, Int>()
        val maxMtimeBySource = mutableMapOf<String, Long>()
        val storeField = SimpleVectorStore::class.java.getDeclaredField("store")
        storeField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val storeMap = storeField.get(vectorStore) as Map<String, Any>
        for (entry in storeMap.values) {
            val metadataMethod = entry.javaClass.getMethod("getMetadata")
            @Suppress("UNCHECKED_CAST")
            val metadata = metadataMethod.invoke(entry) as? Map<String, Any> ?: continue
            val source = metadata["source"] as? String ?: continue
            chunkCountBySource[source] = (chunkCountBySource[source] ?: 0) + 1
            val mtime = metadata["mtime"]?.let { toLong(it) } ?: 0L
            maxMtimeBySource[source] = maxOf(maxMtimeBySource[source] ?: 0L, mtime)
        }
        val totalChunks = chunkCountBySource.values.sum()
        val lastIngestTime = if (maxMtimeBySource.isEmpty()) 0L else maxMtimeBySource.values.max()
        val documents = chunkCountBySource.entries
            .sortedBy { it.key }
            .map { (source, count) ->
                val storedMtime = maxMtimeBySource[source] ?: 0L
                val currentMtime = filesystemProbe(source)
                val stale = currentMtime == null || currentMtime != storedMtime
                StoreDocumentInfo(path = source, chunkCount = count, mtime = storedMtime, stale = stale)
            }
        val staleDocumentCount = documents.count { it.stale }
        return StoreMetadata(
            storeFilePath = storeFilePath.toAbsolutePath().toString(),
            chunkCount = totalChunks,
            documents = documents,
            documentCount = documents.size,
            storeSizeBytes = storeFilePath.toFile().length(),
            lastIngestTime = lastIngestTime,
            staleDocumentCount = staleDocumentCount
        )
    }

    private fun populateIngestedFilesFromStore() {
        // Access the protected 'store' field via reflection to read existing metadata
        val storeField = SimpleVectorStore::class.java.getDeclaredField("store")
        storeField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val storeMap = storeField.get(vectorStore) as Map<String, Any>
        for (entry in storeMap.values) {
            val metadataMethod = entry.javaClass.getMethod("getMetadata")
            @Suppress("UNCHECKED_CAST")
            val metadata = metadataMethod.invoke(entry) as? Map<String, Any> ?: continue
            val source = metadata["source"] as? String ?: continue
            val mtime = metadata["mtime"]?.let { toLong(it) } ?: continue
            ingestedFiles.add(Pair(source, mtime))
        }
    }

    private fun toLong(value: Any): Long? = when (value) {
        is Long -> value
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
}
