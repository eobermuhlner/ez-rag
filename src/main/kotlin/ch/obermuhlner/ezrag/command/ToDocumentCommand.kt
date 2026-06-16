package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.DocumentReaderRegistry
import ch.obermuhlner.ezrag.ingestion.HtmlDocumentReader
import ch.obermuhlner.ezrag.ingestion.JsoupUrlFetcher
import ch.obermuhlner.ezrag.ingestion.PdfDocumentReader
import ch.obermuhlner.ezrag.ingestion.UrlFetcher
import ch.obermuhlner.ezrag.ingestion.pdf.ConversionOptions
import ch.obermuhlner.ezrag.ingestion.pdf.PdfMarkdown
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.Spec
import java.io.File
import java.io.PrintWriter
import java.util.concurrent.Callable

@Command(
    name = "to-document",
    mixinStandardHelpOptions = true,
    description = ["Convert any supported file (or URL) to its document text and write the result to stdout."]
)
@Component
class ToDocumentCommand(
    private val outputWriter: PrintWriter = PrintWriter(System.out, true),
    private val errorWriter: PrintWriter = PrintWriter(System.err, true),
    private val urlFetcher: UrlFetcher = JsoupUrlFetcher(),
) : Callable<Int> {

    @Spec
    var spec: CommandSpec? = null

    @Parameters(index = "0", description = ["File path or HTTP/HTTPS URL to convert."])
    lateinit var input: String

    @Option(names = ["--pdf-mode"], description = ["PDF conversion mode: readable (default), rag. PDF-only."])
    var pdfMode: String = "readable"

    @Option(names = ["--pdf-max-pages"], description = ["Maximum number of PDF pages to convert (0 = unlimited). PDF-only."])
    var pdfMaxPages: Int = 0

    override fun call(): Int {
        return when {
            input.startsWith("http://") || input.startsWith("https://") -> handleUrl()
            else -> handleLocalFile()
        }
    }

    private fun checkPdfOnlyOptions(): Int {
        val pr = spec?.commandLine()?.parseResult ?: return 0
        if (pr.hasMatchedOption("--pdf-mode")) {
            errorWriter.println("Error: --pdf-mode is only valid for PDF input.")
            return 1
        }
        if (pr.hasMatchedOption("--pdf-max-pages")) {
            errorWriter.println("Error: --pdf-max-pages is only valid for PDF input.")
            return 1
        }
        return 0
    }

    private fun handleUrl(): Int {
        return try {
            val result = urlFetcher.fetch(input)
            when {
                result.contentType.startsWith("text/html") ||
                result.contentType.startsWith("application/xhtml+xml") -> {
                    val pdfOnlyError = checkPdfOnlyOptions()
                    if (pdfOnlyError != 0) return pdfOnlyError
                    val html = result.bytes.toString(Charsets.UTF_8)
                    val chunks = HtmlDocumentReader(html).read()
                    outputWriter.println(chunks.joinToString("\n\n") { it.text ?: "" })
                    0
                }
                result.contentType.startsWith("application/pdf") -> {
                    val tempFile = File.createTempFile("to-document-", ".pdf")
                    try {
                        tempFile.writeBytes(result.bytes)
                        convertPdf(tempFile)
                    } finally {
                        tempFile.delete()
                    }
                }
                else -> {
                    errorWriter.println("Error: Unsupported content type '${result.contentType}'.")
                    1
                }
            }
        } catch (e: Exception) {
            errorWriter.println("Error: Failed to fetch '$input': ${e.message}")
            1
        }
    }

    private fun handleLocalFile(): Int {
        val file = File(input)
        val extension = file.extension.lowercase()

        return when (extension) {
            "pdf" -> {
                if (!file.exists()) {
                    errorWriter.println("Error: File not found: '${file.path}'.")
                    return 1
                }
                convertPdf(file)
            }
            else -> {
                val registry = DocumentReaderRegistry(chunkSize = Int.MAX_VALUE / 2, chunkOverlap = 0)
                if (!registry.supports(extension)) {
                    errorWriter.println("Error: Unsupported file extension '.$extension'.")
                    return 1
                }
                val pdfOnlyError = checkPdfOnlyOptions()
                if (pdfOnlyError != 0) return pdfOnlyError
                if (!file.exists()) {
                    errorWriter.println("Error: File not found: '${file.path}'.")
                    return 1
                }
                try {
                    val chunks = registry.read(file)
                    outputWriter.println(chunks.joinToString("\n\n") { it.text ?: "" })
                    0
                } catch (e: Exception) {
                    errorWriter.println("Error: Failed to convert '${file.path}': ${e.message}")
                    1
                }
            }
        }
    }

    private fun convertPdf(file: File): Int {
        if (pdfMaxPages < 0) {
            errorWriter.println("Error: --pdf-max-pages must be >= 0, got $pdfMaxPages.")
            return 1
        }
        val resolvedMaxPages = if (pdfMaxPages == 0) Int.MAX_VALUE else pdfMaxPages
        return try {
            val options = when (pdfMode) {
                "readable" -> ConversionOptions.READABLE
                "rag" -> ConversionOptions.RAG
                else -> {
                    errorWriter.println("Error: Unknown --pdf-mode '$pdfMode'. Valid values: readable, rag.")
                    return 1
                }
            }
            val output = PdfMarkdown.toMarkdown(file, maxPages = resolvedMaxPages, options = options)
            outputWriter.println(output)
            0
        } catch (e: Exception) {
            errorWriter.println("Error: Failed to convert '${file.path}': ${e.message}")
            1
        }
    }
}
