package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.HtmlToMarkdownConverter
import ch.obermuhlner.ezrag.ingestion.JsoupUrlFetcher
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
    name = "to-markdown",
    mixinStandardHelpOptions = true,
    description = ["Convert a PDF or HTML file (or URL) to Markdown and write the result to stdout."]
)
@Component
class ToMarkdownCommand(
    private val outputWriter: PrintWriter = PrintWriter(System.out, true),
    private val errorWriter: PrintWriter = PrintWriter(System.err, true),
    private val urlFetcher: UrlFetcher = JsoupUrlFetcher(),
) : Callable<Int> {

    @Spec
    lateinit var spec: CommandSpec

    @Parameters(index = "0", description = ["File path (PDF, HTML, HTM) or HTTP/HTTPS URL to convert."])
    lateinit var input: String

    @Option(names = ["--mode"], description = ["Conversion mode: readable (default), rag. PDF-only."])
    var mode: String = "readable"

    @Option(names = ["--max-pages"], description = ["Maximum number of pages to convert (0 = unlimited). PDF-only."])
    var maxPages: Int = 0

    @Option(names = ["--output-format"], description = ["Output format: markdown (default), xml. PDF-only."])
    var outputFormat: String = "markdown"

    override fun call(): Int {
        return when {
            input.startsWith("http://") || input.startsWith("https://") -> handleUrl()
            else -> handleLocalFile()
        }
    }

    private fun checkPdfOnlyOptions(): Int {
        val pr = spec.commandLine().parseResult
        if (pr.hasMatchedOption("--mode")) {
            errorWriter.println("Error: --mode is only valid for PDF input.")
            return 1
        }
        if (pr.hasMatchedOption("--output-format")) {
            errorWriter.println("Error: --output-format is only valid for PDF input.")
            return 1
        }
        if (pr.hasMatchedOption("--max-pages")) {
            errorWriter.println("Error: --max-pages is only valid for PDF input.")
            return 1
        }
        return 0
    }

    private fun handleUrl(): Int {
        return try {
            val result = urlFetcher.fetch(input)
            when {
                result.contentType.startsWith("text/html") -> {
                    val pdfOnlyError = checkPdfOnlyOptions()
                    if (pdfOnlyError != 0) return pdfOnlyError
                    val html = result.bytes.toString(Charsets.UTF_8)
                    val markdown = HtmlToMarkdownConverter().convert(html)
                    outputWriter.println(markdown)
                    0
                }
                result.contentType.startsWith("application/pdf") -> {
                    val tempFile = File.createTempFile("to-markdown-", ".pdf")
                    try {
                        tempFile.writeBytes(result.bytes)
                        convertPdf(tempFile)
                    } finally {
                        tempFile.delete()
                    }
                }
                else -> {
                    errorWriter.println("Error: Unsupported content type '${result.contentType}'. Supported: text/html, application/pdf.")
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
            "html", "htm" -> {
                if (!file.exists()) {
                    errorWriter.println("Error: File not found: '${file.path}'.")
                    return 1
                }
                val pdfOnlyError = checkPdfOnlyOptions()
                if (pdfOnlyError != 0) return pdfOnlyError
                val html = file.readText()
                val markdown = HtmlToMarkdownConverter().convert(html)
                outputWriter.println(markdown)
                0
            }
            else -> {
                errorWriter.println("Error: Unsupported file extension '.$extension'. Supported extensions: .pdf, .html, .htm.")
                1
            }
        }
    }

    private fun convertPdf(file: File): Int {
        if (maxPages < 0) {
            errorWriter.println("Error: --max-pages must be >= 0, got $maxPages.")
            return 1
        }
        val resolvedMaxPages = if (maxPages == 0) Int.MAX_VALUE else maxPages
        return try {
            val output = when (outputFormat) {
                "markdown" -> {
                    val options = when (mode) {
                        "readable" -> ConversionOptions.READABLE
                        "rag" -> ConversionOptions.RAG
                        else -> {
                            errorWriter.println("Error: Unknown mode '$mode'. Valid values: readable, rag.")
                            return 1
                        }
                    }
                    PdfMarkdown.toMarkdown(file, maxPages = resolvedMaxPages, options = options)
                }
                "xml" -> PdfMarkdown.toXml(file, maxPages = resolvedMaxPages)
                else -> {
                    errorWriter.println("Error: Unknown output format '$outputFormat'. Valid values: markdown, xml.")
                    return 1
                }
            }
            outputWriter.println(output)
            0
        } catch (e: Exception) {
            errorWriter.println("Error: Failed to convert '${file.path}': ${e.message}")
            1
        }
    }
}
