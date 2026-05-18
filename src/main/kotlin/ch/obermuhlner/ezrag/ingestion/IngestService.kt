package ch.obermuhlner.ezrag.ingestion

import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import java.io.File
import java.io.PrintWriter
import java.nio.file.Path

/**
 * Reusable service that ingests files into the unified Lucene index.
 *
 * Returns results as [IngestResult]; has no dependency on picocli.
 * Used by both [ch.obermuhlner.ezrag.command.IngestCommand] and the MCP ingest tool.
 */
open class IngestService(
    private val embeddingModel: EmbeddingModel,
    private val storeDir: Path,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
    private val warningWriter: PrintWriter = PrintWriter(System.err, true),
    private val analyzerName: String = "standard",
) {

    var onFileIngesting: ((Path) -> Unit)? = null
    var onFileSkipped: ((Path, String) -> Unit)? = null
    var onFileLoaded: ((Path, List<Document>) -> Unit)? = null

    open fun ingest(files: List<File>): IngestResult {
        val registry = DocumentReaderRegistry(chunkSize, chunkOverlap)
        val repository = LuceneRepository.open(embeddingModel, storeDir, analyzerName)
        val directoryWalker = DirectoryWalker(warningWriter)

        var filesIngested = 0
        var chunksCreated = 0
        var skipped = 0

        val resolvedPaths = files.flatMap { file ->
            when {
                file.isDirectory -> directoryWalker.walk(file.toPath())
                !file.exists() -> {
                    warningWriter.println("Warning: Path does not exist: $file")
                    emptyList()
                }
                else -> {
                    val ext = file.name.substringAfterLast('.', "").lowercase()
                    if (ext !in DirectoryWalker.SUPPORTED_EXTENSIONS) {
                        warningWriter.println("Warning: Skipping unsupported file type: $file")
                        emptyList()
                    } else {
                        listOf(file.toPath())
                    }
                }
            }
        }

        repository.use {
            for (path in resolvedPaths) {
                val absolutePath = path.toAbsolutePath().normalize()
                val mtime = absolutePath.toFile().lastModified()
                val sourceKey = absolutePath.toString()
                if (repository.isAlreadyIngested(sourceKey, mtime)) {
                    onFileSkipped?.invoke(absolutePath, "already ingested")
                    skipped++
                    continue
                }
                onFileIngesting?.invoke(absolutePath)
                val rawChunks = registry.read(absolutePath.toFile())
                val chunks = withSourceAndMtime(rawChunks, sourceKey, mtime)
                onFileLoaded?.invoke(absolutePath, chunks)
                if (chunks.isEmpty()) {
                    warningWriter.println("Warning: No chunks produced for: $absolutePath")
                    continue
                }
                repository.add(chunks)
                filesIngested++
                chunksCreated += chunks.size
            }
        }

        return IngestResult(
            filesIngested = filesIngested,
            chunksCreated = chunksCreated,
            skipped = skipped,
        )
    }

    private fun withSourceAndMtime(documents: List<Document>, source: String, mtime: Long): List<Document> {
        return documents.mapIndexed { index, doc ->
            Document.builder()
                .id(doc.id)
                .text(doc.text)
                .metadata(doc.metadata + mapOf("source" to source, "mtime" to mtime, "chunk_index" to index))
                .build()
        }
    }
}
