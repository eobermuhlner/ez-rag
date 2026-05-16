package ch.obermuhlner.ezrag.ingestion

import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import java.io.File
import java.io.PrintWriter
import java.nio.file.Path

/**
 * Reusable service that ingests files into a vector store.
 *
 * Returns results as [IngestResult]; has no dependency on picocli.
 * Used by both [ch.obermuhlner.ezrag.command.IngestCommand] and the MCP ingest tool.
 */
open class IngestService(
    private val embeddingModel: EmbeddingModel,
    private val storePath: Path,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
    private val warningWriter: PrintWriter = PrintWriter(System.err, true),
) {

    /**
     * Optional callback invoked for each file loaded and its chunks, for verbose output.
     * Parameters: (filePath, chunks)
     */
    var onFileLoaded: ((Path, List<Document>) -> Unit)? = null

    open fun ingest(files: List<File>): IngestResult {
        val loader = DocumentLoader()
        val chunker = DocumentChunker(chunkSize, chunkOverlap)
        val repository = VectorStoreRepository(embeddingModel, storePath)
        val directoryWalker = DirectoryWalker(warningWriter)
        repository.load()

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

        for (path in resolvedPaths) {
            val mtime = path.toFile().lastModified()
            val sourceKey = path.toString()
            if (repository.isAlreadyIngested(sourceKey, mtime)) {
                skipped++
                continue
            }
            val documents = loader.load(path)
            val chunks = withMtime(chunker.split(documents), mtime)
            onFileLoaded?.invoke(path, chunks)
            if (chunks.isEmpty()) {
                warningWriter.println("Warning: No chunks produced for: $path")
                continue
            }
            repository.add(chunks)
            filesIngested++
            chunksCreated += chunks.size
        }

        repository.save()

        return IngestResult(
            filesIngested = filesIngested,
            chunksCreated = chunksCreated,
            skipped = skipped,
        )
    }

    private fun withMtime(documents: List<Document>, mtime: Long): List<Document> {
        return documents.map { doc ->
            Document.builder()
                .id(doc.id)
                .text(doc.text)
                .metadata(doc.metadata + mapOf("mtime" to mtime))
                .build()
        }
    }
}
