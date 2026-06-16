package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.DocumentReaderRegistry
import ch.obermuhlner.ezrag.ingestion.HtmlDocumentReader
import ch.obermuhlner.ezrag.ingestion.JsoupUrlFetcher
import ch.obermuhlner.ezrag.ingestion.PdfDocumentReader
import ch.obermuhlner.ezrag.ingestion.UrlFetcher
import ch.obermuhlner.ezrag.rag.OutputFormatter
import org.springframework.ai.document.Document
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File
import java.io.PrintWriter
import java.util.concurrent.Callable

@Command(
    name = "to-chunks",
    mixinStandardHelpOptions = true,
    description = ["Chunk any supported file (or URL) and display each chunk with its metadata."]
)
@Component
class ToChunksCommand(
    private val outputWriter: PrintWriter = PrintWriter(System.out, true),
    private val errorWriter: PrintWriter = PrintWriter(System.err, true),
    private val urlFetcher: UrlFetcher = JsoupUrlFetcher(),
) : Callable<Int> {

    @Parameters(index = "0", description = ["File path or HTTP/HTTPS URL to chunk."])
    lateinit var input: String

    @Option(names = ["--output-format"], description = ["Output format: text (default), json, xml."])
    var outputFormat: String = "text"

    @Option(names = ["--chunk-size"], description = ["Chunk size in tokens (default: 1000)."])
    var chunkSize: Int = 1000

    @Option(names = ["--chunk-overlap"], description = ["Chunk overlap in tokens (default: 200)."])
    var chunkOverlap: Int = 200

    @Option(names = ["--pdf-mode"], description = ["PDF conversion mode: readable (default), rag. PDF-only."])
    var pdfMode: String = "readable"

    @Option(names = ["--pdf-max-pages"], description = ["Maximum number of PDF pages to convert (0 = unlimited). PDF-only."])
    var pdfMaxPages: Int = 0

    override fun call(): Int {
        val rawChunks: List<Document> = try {
            loadChunks()
        } catch (e: IllegalArgumentException) {
            errorWriter.println("Error: ${e.message}")
            return 1
        } catch (e: Exception) {
            errorWriter.println("Error: Failed to read '$input': ${e.message}")
            return 1
        }
        // Inject chunk_index into each document so formatters can render it
        val chunks = rawChunks.mapIndexed { idx, doc ->
            if (doc.metadata.containsKey("chunk_index")) {
                doc
            } else {
                val meta = doc.metadata.toMutableMap()
                meta["chunk_index"] = idx
                Document.builder()
                    .id(doc.id)
                    .text(doc.text)
                    .metadata(meta)
                    .build()
            }
        }

        val formatter = OutputFormatter()
        val formatted = when (outputFormat) {
            "text" -> formatter.formatText(chunks)
            "json" -> formatter.formatJson(chunks)
            "xml" -> formatter.formatXml(chunks)
            else -> {
                errorWriter.println("Error: Unknown --output-format '$outputFormat'. Valid values: text, json, xml.")
                return 1
            }
        }
        outputWriter.println(formatted)
        return 0
    }

    private fun loadChunks(): List<Document> {
        if (input.startsWith("http://") || input.startsWith("https://")) {
            return loadFromUrl()
        }
        return loadFromFile()
    }

    private fun loadFromUrl(): List<Document> {
        val result = urlFetcher.fetch(input)
        return when {
            result.contentType.startsWith("text/html") ||
            result.contentType.startsWith("application/xhtml+xml") -> {
                val html = result.bytes.toString(Charsets.UTF_8)
                HtmlDocumentReader(html, chunkSize, chunkOverlap).read()
            }
            result.contentType.startsWith("application/pdf") -> {
                val tempFile = File.createTempFile("to-chunks-", ".pdf")
                try {
                    tempFile.writeBytes(result.bytes)
                    PdfDocumentReader(tempFile, chunkSize, chunkOverlap).read()
                } finally {
                    tempFile.delete()
                }
            }
            else -> throw IllegalArgumentException("Unsupported content type '${result.contentType}'.")
        }
    }

    private fun loadFromFile(): List<Document> {
        val file = File(input)
        if (!file.exists()) {
            throw IllegalArgumentException("File not found: '${file.path}'.")
        }
        val extension = file.extension.lowercase()
        val registry = DocumentReaderRegistry(chunkSize, chunkOverlap)
        if (!registry.supports(extension) && extension != "pdf") {
            throw IllegalArgumentException("Unsupported file extension '.$extension'.")
        }
        return registry.read(file)
    }
}
