package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.EzRagCommand
import ch.obermuhlner.ezrag.ingestion.pdf.ConversionOptions
import ch.obermuhlner.ezrag.ingestion.pdf.PdfMarkdown
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.io.File
import java.io.PrintWriter
import java.util.concurrent.Callable

@Command(
    name = "pdf-to-markdown",
    mixinStandardHelpOptions = true,
    description = ["Convert a PDF file to Markdown and write the result to stdout."]
)
@Component
class PdfToMarkdownCommand(
    private val outputWriter: PrintWriter = PrintWriter(System.out, true),
    private val errorWriter: PrintWriter = PrintWriter(System.err, true),
) : Callable<Int> {

    @ParentCommand
    private var parent: EzRagCommand? = null

    @Parameters(index = "0", description = ["PDF file to convert."])
    lateinit var file: File

    @Option(names = ["--mode"], description = ["Conversion mode: readable (default), rag."])
    var mode: String = "readable"

    @Option(names = ["--max-pages"], description = ["Maximum number of pages to convert (0 = unlimited)."])
    var maxPages: Int = 0

    @Option(names = ["--output-format"], description = ["Output format: markdown (default), xml. When xml, --mode is ignored."])
    var outputFormat: String = "markdown"

    override fun call(): Int {
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
            if (parent?.stackTrace == true) e.printStackTrace(errorWriter)
            1
        }
    }
}
