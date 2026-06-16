package ch.obermuhlner.ezrag.ingestion

import org.springframework.ai.document.Document
import java.io.File
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Reusable service that ingests files and URLs into the unified Lucene index.
 *
 * Returns results as [IngestResult]; has no dependency on picocli.
 * Used by both [ch.obermuhlner.ezrag.command.IngestCommand] and the MCP ingest tool.
 */
open class IngestService(
    private val repository: LuceneRepository,
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
    private val warningWriter: PrintWriter = PrintWriter(System.err, true),
    private val urlFetcher: UrlFetcher = JsoupUrlFetcher(),
    private val tempDirProvider: () -> Path = { Files.createTempDirectory("ez-rag-url-") },
    private val passwords: List<String> = emptyList(),
) {

    var onFileIngesting: ((Path) -> Unit)? = null
    var onFileSkipped: ((Path, String) -> Unit)? = null
    var onFileLoaded: ((Path, List<Document>) -> Unit)? = null

    open fun ingest(files: List<File>): IngestResult = ingest(files.map { FileSource(it) })

    open fun ingest(sources: Iterable<IngestSource>): IngestResult {
        val registry = DocumentReaderRegistry(chunkSize, chunkOverlap, passwords)
        val directoryWalker = DirectoryWalker(warningWriter)

        var filesIngested = 0
        var chunksCreated = 0
        var skipped = 0

        val expandedSources: List<IngestSource> = sources.flatMap { source ->
            when (source) {
                is FileSource -> {
                    val file = source.file
                    when {
                        file.isDirectory -> directoryWalker.walk(file.toPath()).map { FileSource(it.toFile()) }
                        !file.exists() -> {
                            warningWriter.println("Warning: Path does not exist: $file")
                            emptyList()
                        }
                        else -> listOf(source)
                    }
                }
                is UrlSource -> listOf(source)
            }
        }

        for (source in expandedSources) {
            when (source) {
                is FileSource -> {
                    val absolutePath = source.file.toPath().toAbsolutePath().normalize()
                    val mtime = absolutePath.toFile().lastModified()
                    val sourceKey = absolutePath.toString()

                    if (repository.isAlreadyIngested(sourceKey, mtime)) {
                        onFileSkipped?.invoke(absolutePath, "already ingested")
                        skipped++
                        continue
                    }

                    val fileBytes = absolutePath.toFile().readBytes()
                    val contentHash = computeSha256(fileBytes)
                    if (repository.isContentUnchanged(sourceKey, mtime, contentHash)) {
                        onFileSkipped?.invoke(absolutePath, "already ingested")
                        skipped++
                        continue
                    }

                    onFileIngesting?.invoke(absolutePath)
                    val rawChunks = try {
                        registry.read(absolutePath.toFile())
                    } catch (e: IllegalArgumentException) {
                        warningWriter.println("Warning: Skipping binary file: $absolutePath — ${e.message}")
                        skipped++
                        continue
                    } catch (e: IllegalStateException) {
                        if (e.message?.contains("Cannot open encrypted file") == true) {
                            warningWriter.println("WARN: Cannot open encrypted file, skipping: $absolutePath")
                            skipped++
                            continue
                        }
                        throw e
                    }
                    val chunks = withSourceAndMtime(rawChunks, sourceKey, mtime, contentHash)
                    onFileLoaded?.invoke(absolutePath, chunks)

                    if (chunks.isEmpty()) {
                        warningWriter.println("Warning: No chunks produced for: $absolutePath")
                        continue
                    }

                    repository.add(chunks)
                    filesIngested++
                    chunksCreated += chunks.size
                }
                is UrlSource -> {
                    val url = source.url

                    val fetchResult = try {
                        urlFetcher.fetch(url)
                    } catch (e: Exception) {
                        warningWriter.println("Warning: Failed to fetch URL $url: ${e.message}")
                        skipped++
                        continue
                    }

                    if (fetchResult.statusCode >= 400) {
                        warningWriter.println("Warning: HTTP ${fetchResult.statusCode} fetching URL: $url")
                        skipped++
                        continue
                    }

                    val mtime = fetchResult.lastModifiedEpochMs
                    val contentHash = computeSha256(fetchResult.bytes)

                    if (repository.isContentUnchanged(url, mtime, contentHash)) {
                        skipped++
                        continue
                    }

                    val rawChunks = when {
                        fetchResult.contentType.startsWith("text/html") ||
                        fetchResult.contentType.startsWith("application/xhtml+xml") ->
                            HtmlDocumentReader(
                                fetchResult.bytes.toString(Charsets.UTF_8),
                                chunkSize,
                                chunkOverlap,
                            ).read()
                        fetchResult.contentType.startsWith("application/pdf") -> {
                            val tempDir = tempDirProvider()
                            val tempFile = Files.createTempFile(tempDir, "ez-rag-pdf-", ".pdf").toFile()
                            try {
                                tempFile.writeBytes(fetchResult.bytes)
                                PdfDocumentReader(tempFile, chunkSize, chunkOverlap).read()
                            } finally {
                                tempFile.delete()
                            }
                        }
                        fetchResult.contentType.startsWith("text/plain") -> {
                            val tempDir = tempDirProvider()
                            val tempFile = Files.createTempFile(tempDir, "ez-rag-txt-", ".txt").toFile()
                            try {
                                tempFile.writeBytes(fetchResult.bytes)
                                PlainTextDocumentReader(tempFile, chunkSize, chunkOverlap).read()
                            } finally {
                                tempFile.delete()
                            }
                        }
                        else -> {
                            // Fallback: binary detection on the raw bytes
                            if (BinaryDetector.isBinary(fetchResult.bytes)) {
                                warningWriter.println(
                                    "Warning: Skipping binary content at URL: $url (content-type: ${fetchResult.contentType})"
                                )
                                skipped++
                                continue
                            }
                            // Text content — ingest as plain text using a temp file
                            val tempDir = tempDirProvider()
                            val tempFile = Files.createTempFile(tempDir, "ez-rag-txt-", ".txt").toFile()
                            try {
                                tempFile.writeBytes(fetchResult.bytes)
                                PlainTextDocumentReader(tempFile, chunkSize, chunkOverlap).read()
                            } finally {
                                tempFile.delete()
                            }
                        }
                    }

                    val chunks = withSourceAndMtime(rawChunks, url, mtime, contentHash)

                    if (chunks.isEmpty()) {
                        warningWriter.println("Warning: No chunks produced for: $url")
                        continue
                    }

                    val chunksWithIngestTime = chunks.map { chunk ->
                        Document.builder()
                            .id(chunk.id)
                            .text(chunk.text)
                            .metadata(chunk.metadata + mapOf("ingest_time" to System.currentTimeMillis()))
                            .build()
                    }

                    repository.add(chunksWithIngestTime)
                    filesIngested++
                    chunksCreated += chunks.size
                }
            }
        }

        return IngestResult(
            filesIngested = filesIngested,
            chunksCreated = chunksCreated,
            skipped = skipped,
        )
    }

    private fun withSourceAndMtime(
        documents: List<Document>,
        source: String,
        mtime: Long,
        contentHash: String,
    ): List<Document> {
        return documents.mapIndexed { index, doc ->
            Document.builder()
                .id(doc.id)
                .text(doc.text)
                .metadata(
                    doc.metadata + mapOf(
                        "source" to source,
                        "mtime" to mtime,
                        "chunk_index" to index,
                        "content_hash" to contentHash,
                    )
                )
                .build()
        }
    }

    private fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
