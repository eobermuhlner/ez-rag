package ch.obermuhlner.ezrag.ingestion

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.en.EnglishAnalyzer
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.store.NIOFSDirectory
import org.springframework.ai.document.Document
import ch.obermuhlner.ezrag.rag.ChunkMatch
import java.nio.file.Files
import java.nio.file.Path

data class BM25IndexMetadata(
    val chunkCount: Int,
    val indexSizeBytes: Long
)

class BM25Repository(
    private val storeDir: Path,
    private val analyzerName: String
) : AutoCloseable {

    private val luceneDir: Path = storeDir.resolve("lucene")
    private val metaFile: Path = luceneDir.resolve("meta.json")
    private val analyzer: Analyzer = createAnalyzer(analyzerName)
    private val objectMapper = ObjectMapper()

    // (source -> mtime) pairs tracked in meta.json
    private val indexedFiles: MutableMap<String, Long> = mutableMapOf()

    init {
        loadMeta()
    }

    private fun createAnalyzer(name: String): Analyzer = when (name.lowercase()) {
        "english" -> EnglishAnalyzer()
        else -> StandardAnalyzer()
    }

    private fun loadMeta() {
        if (!metaFile.toFile().exists()) return
        try {
            val root = objectMapper.readTree(metaFile.toFile()) as? ObjectNode ?: return
            root.fields().forEach { (source, mtimeNode) ->
                indexedFiles[source] = mtimeNode.asLong()
            }
        } catch (_: Exception) {
            // corrupt meta — start fresh
        }
    }

    private fun saveMeta() {
        Files.createDirectories(luceneDir)
        val root = objectMapper.createObjectNode()
        indexedFiles.forEach { (source, mtime) -> root.put(source, mtime) }
        objectMapper.writeValue(metaFile.toFile(), root)
    }

    fun index(documents: List<Document>) {
        if (documents.isEmpty()) return
        Files.createDirectories(luceneDir)
        val config = IndexWriterConfig(analyzer).apply {
            openMode = IndexWriterConfig.OpenMode.CREATE_OR_APPEND
        }
        NIOFSDirectory.open(luceneDir).use { dir ->
            IndexWriter(dir, config).use { writer ->
                for (doc in documents) {
                    val source = doc.metadata["source"] as? String ?: continue
                    val mtime = doc.metadata["mtime"]?.let { toLong(it) } ?: 0L
                    val content = doc.text ?: ""
                    val luceneDoc = org.apache.lucene.document.Document().apply {
                        add(StringField("source", source, Field.Store.YES))
                        add(TextField("content", content, Field.Store.YES))
                    }
                    writer.addDocument(luceneDoc)
                    indexedFiles[source] = mtime
                }
            }
        }
        saveMeta()
    }

    fun deleteBySource(sourcePath: String) {
        if (!Files.exists(luceneDir)) return
        val config = IndexWriterConfig(analyzer).apply {
            openMode = IndexWriterConfig.OpenMode.CREATE_OR_APPEND
        }
        NIOFSDirectory.open(luceneDir).use { dir ->
            IndexWriter(dir, config).use { writer ->
                writer.deleteDocuments(Term("source", sourcePath))
            }
        }
        indexedFiles.remove(sourcePath)
        saveMeta()
    }

    fun search(query: String, topK: Int): List<ChunkMatch> {
        if (!Files.exists(luceneDir)) return emptyList()
        if (!DirectoryReader.indexExists(NIOFSDirectory.open(luceneDir))) return emptyList()

        return NIOFSDirectory.open(luceneDir).use { dir ->
            val reader = DirectoryReader.open(dir)
            val searcher = IndexSearcher(reader)
            val parser = QueryParser("content", analyzer)
            val parsedQuery = try {
                parser.parse(QueryParser.escape(query))
            } catch (_: Exception) {
                return@use emptyList()
            }
            val hits = searcher.search(parsedQuery, topK)
            hits.scoreDocs.map { scoreDoc ->
                val luceneDoc = searcher.storedFields().document(scoreDoc.doc)
                val source = luceneDoc.get("source") ?: ""
                val content = luceneDoc.get("content") ?: ""
                ChunkMatch(
                    filePath = source,
                    chunkIndex = 0,
                    score = scoreDoc.score.toDouble(),
                    content = content
                )
            }
        }
    }

    fun isAlreadyIndexed(sourcePath: String, mtime: Long): Boolean {
        return indexedFiles[sourcePath] == mtime
    }

    fun getMetadata(): BM25IndexMetadata {
        if (!Files.exists(luceneDir)) return BM25IndexMetadata(0, 0)
        return try {
            NIOFSDirectory.open(luceneDir).use { dir ->
                if (!DirectoryReader.indexExists(dir)) return BM25IndexMetadata(0, 0)
                DirectoryReader.open(dir).use { reader ->
                    val docCount = reader.numDocs()
                    val sizeBytes = luceneDir.toFile()
                        .walkTopDown()
                        .filter { it.isFile }
                        .sumOf { it.length() }
                    BM25IndexMetadata(chunkCount = docCount, indexSizeBytes = sizeBytes)
                }
            }
        } catch (_: Exception) {
            BM25IndexMetadata(0, 0)
        }
    }

    override fun close() {
        analyzer.close()
    }

    private fun toLong(value: Any): Long? = when (value) {
        is Long -> value
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
}
