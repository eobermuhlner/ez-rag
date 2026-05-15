package ch.obermuhlner.ezrag.ingestion

import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.vectorstore.SimpleVectorStore
import java.nio.file.Path

data class StoreDocumentInfo(val path: String, val chunkCount: Int)

data class StoreMetadata(
    val storePath: String,
    val chunkCount: Int,
    val documents: List<StoreDocumentInfo>
)

class VectorStoreRepository(
    private val embeddingModel: EmbeddingModel,
    private val storePath: Path,
) {

    private lateinit var vectorStore: SimpleVectorStore
    // Tracks (source, mtime) pairs of all documents in the store
    private val ingestedFiles: MutableSet<Pair<String, Long>> = mutableSetOf()

    fun load() {
        vectorStore = SimpleVectorStore.builder(embeddingModel).build()
        val file = storePath.toFile()
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
        val dir = storePath.parent?.toFile()
        if (dir != null && !dir.exists()) {
            dir.mkdirs()
        }
        vectorStore.save(storePath.toFile())
    }

    fun isAlreadyIngested(sourcePath: String, mtime: Long): Boolean {
        return ingestedFiles.contains(Pair(sourcePath, mtime))
    }

    fun storeExists(): Boolean = storePath.toFile().exists()

    fun getStore(): SimpleVectorStore = vectorStore

    fun getMetadata(): StoreMetadata {
        val chunkCountBySource = mutableMapOf<String, Int>()
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
        }
        val totalChunks = chunkCountBySource.values.sum()
        val documents = chunkCountBySource.entries
            .sortedBy { it.key }
            .map { StoreDocumentInfo(path = it.key, chunkCount = it.value) }
        return StoreMetadata(
            storePath = storePath.toAbsolutePath().toString(),
            chunkCount = totalChunks,
            documents = documents
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
