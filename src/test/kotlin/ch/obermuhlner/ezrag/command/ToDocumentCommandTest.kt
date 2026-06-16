package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.FetchResult
import ch.obermuhlner.ezrag.ingestion.UrlFetcher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Paths

class ToDocumentCommandTest {

    private fun samplePdfFile() =
        Paths.get(javaClass.getResource("/documents/sample.pdf")!!.toURI()).toFile()

    private fun sampleHtmlFile() =
        Paths.get(javaClass.getResource("/documents/sample.html")!!.toURI()).toFile()

    private fun sampleMdFile() =
        Paths.get(javaClass.getResource("/documents/sample.md")!!.toURI()).toFile()

    private fun sampleTxtFile() =
        Paths.get(javaClass.getResource("/documents/sample.txt")!!.toURI()).toFile()

    private fun sampleDocxFile() =
        Paths.get(javaClass.getResource("/fixtures/sample.docx")!!.toURI()).toFile()

    private fun samplePptxFile() =
        Paths.get(javaClass.getResource("/fixtures/sample.pptx")!!.toURI()).toFile()

    private fun sampleXlsxFile() =
        Paths.get(javaClass.getResource("/fixtures/sample.xlsx")!!.toURI()).toFile()

    private fun sampleCsvFile() =
        Paths.get(javaClass.getResource("/fixtures/sample.csv")!!.toURI()).toFile()

    private fun sampleRtfFile() =
        Paths.get(javaClass.getResource("/fixtures/sample.rtf")!!.toURI()).toFile()

    private fun machineLearningPdfFile() =
        Paths.get(javaClass.getResource("/eval/complex-pdf/machine_learning.pdf")!!.toURI()).toFile()

    private fun command(
        out: StringWriter = StringWriter(),
        err: StringWriter = StringWriter(),
        urlFetcher: UrlFetcher = NoopUrlFetcher(),
    ): Triple<ToDocumentCommand, StringWriter, StringWriter> {
        val cmd = ToDocumentCommand(
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
            urlFetcher = urlFetcher,
        )
        return Triple(cmd, out, err)
    }

    private fun executeViaPicocli(
        args: Array<String>,
        out: StringWriter = StringWriter(),
        err: StringWriter = StringWriter(),
        urlFetcher: UrlFetcher = NoopUrlFetcher(),
    ): Triple<Int, StringWriter, StringWriter> {
        val cmd = ToDocumentCommand(
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
            urlFetcher = urlFetcher,
        )
        val exitCode = CommandLine(cmd).execute(*args)
        return Triple(exitCode, out, err)
    }

    // ── DOCX ────────────────────────────────────────────────────────────────

    @Test
    fun `to-document docx produces non-empty output with Markdown heading and exits 0`() {
        val (cmd, out, _) = command()
        cmd.input = sampleDocxFile().absolutePath
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString().trim()).isNotEmpty()
        assertThat(out.toString()).contains("#")
    }

    // ── PPTX ────────────────────────────────────────────────────────────────

    @Test
    fun `to-document pptx produces non-empty output and exits 0`() {
        val (cmd, out, _) = command()
        cmd.input = samplePptxFile().absolutePath
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString().trim()).isNotEmpty()
    }

    // ── XLSX ─────────────────────────────────────────────────────────────────

    @Test
    fun `to-document xlsx produces Markdown table syntax and exits 0`() {
        val (cmd, out, _) = command()
        cmd.input = sampleXlsxFile().absolutePath
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString()).contains("|")
    }

    // ── CSV ──────────────────────────────────────────────────────────────────

    @Test
    fun `to-document csv produces Markdown table syntax and exits 0`() {
        val (cmd, out, _) = command()
        cmd.input = sampleCsvFile().absolutePath
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString()).contains("|")
    }

    // ── RTF ──────────────────────────────────────────────────────────────────

    @Test
    fun `to-document rtf produces non-empty plain text and exits 0`() {
        val (cmd, out, _) = command()
        cmd.input = sampleRtfFile().absolutePath
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString().trim()).isNotEmpty()
    }

    // ── Plain text ────────────────────────────────────────────────────────────

    @Test
    fun `to-document txt produces non-empty output and exits 0`() {
        val (cmd, out, _) = command()
        cmd.input = sampleTxtFile().absolutePath
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString().trim()).isNotEmpty()
    }

    // ── Markdown ─────────────────────────────────────────────────────────────

    @Test
    fun `to-document md produces body text and exits 0`() {
        val (cmd, out, _) = command()
        cmd.input = sampleMdFile().absolutePath
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString()).contains("Sample Markdown Document")
    }

    // ── PDF ───────────────────────────────────────────────────────────────────

    @Test
    fun `to-document pdf produces non-empty output and exits 0`() {
        val (cmd, out, _) = command()
        cmd.input = samplePdfFile().absolutePath
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString().trim()).isNotEmpty()
    }

    @Test
    fun `to-document pdf with --pdf-mode rag produces different output than readable`() {
        val (cmdReadable, outReadable, _) = command()
        cmdReadable.input = machineLearningPdfFile().absolutePath
        cmdReadable.pdfMode = "readable"
        assertThat(cmdReadable.call()).isEqualTo(0)

        val (cmdRag, outRag, _) = command()
        cmdRag.input = machineLearningPdfFile().absolutePath
        cmdRag.pdfMode = "rag"
        assertThat(cmdRag.call()).isEqualTo(0)

        assertThat(outRag.toString()).isNotEqualTo(outReadable.toString())
    }

    @Test
    fun `to-document pdf with --pdf-max-pages 1 produces shorter output than full`() {
        val (cmdFull, outFull, _) = command()
        cmdFull.input = machineLearningPdfFile().absolutePath
        assertThat(cmdFull.call()).isEqualTo(0)
        val fullOutput = outFull.toString()

        val (cmdOne, outOne, _) = command()
        cmdOne.input = machineLearningPdfFile().absolutePath
        cmdOne.pdfMaxPages = 1
        assertThat(cmdOne.call()).isEqualTo(0)

        assertThat(outOne.toString().trim()).isNotEmpty()
        assertThat(outOne.toString().length).isLessThan(fullOutput.length)
    }

    // ── HTML ─────────────────────────────────────────────────────────────────

    @Test
    fun `to-document html produces non-empty output and exits 0`() {
        val (exitCode, out, _) = executeViaPicocli(arrayOf(sampleHtmlFile().absolutePath))
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString().trim()).isNotEmpty()
    }

    // ── Error paths ───────────────────────────────────────────────────────────

    @Test
    fun `to-document unknown extension exits 1 with message on stderr`() {
        val (cmd, _, err) = command()
        cmd.input = "file.unknownxyz"
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(1)
        assertThat(err.toString().trim()).isNotEmpty()
    }

    @Test
    fun `to-document nonexistent file exits 1 with message on stderr`() {
        val (cmd, out, err) = command()
        cmd.input = "/nonexistent/file.pdf"
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(1)
        assertThat(err.toString().trim()).isNotEmpty()
        assertThat(out.toString()).doesNotContain("Exception")
    }

    @Test
    fun `to-document --pdf-mode on non-PDF exits 1 with message on stderr`() {
        val (exitCode, _, err) = executeViaPicocli(
            arrayOf(sampleHtmlFile().absolutePath, "--pdf-mode", "rag")
        )
        assertThat(exitCode).isEqualTo(1)
        assertThat(err.toString()).contains("--pdf-mode")
    }

    // ── URL tests ─────────────────────────────────────────────────────────────

    @Test
    fun `to-document url returning text-html exits 0 with non-empty stdout`() {
        val htmlBytes = "<html><body><h1>Hello</h1><p>World</p></body></html>".toByteArray()
        val fetcher = FakeUrlFetcher(FetchResult(htmlBytes, "text/html", 0L, 200))
        val (exitCode, out, _) = executeViaPicocli(
            arrayOf("https://example.com/page"),
            urlFetcher = fetcher,
        )
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString().trim()).isNotEmpty()
    }

    @Test
    fun `to-document url returning application-pdf exits 0 with non-empty stdout`() {
        val pdfBytes = samplePdfFile().readBytes()
        val fetcher = FakeUrlFetcher(FetchResult(pdfBytes, "application/pdf", 0L, 200))
        val (cmd, out, _) = command(urlFetcher = fetcher)
        cmd.input = "https://example.com/doc.pdf"
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString().trim()).isNotEmpty()
    }

    @Test
    fun `to-document url returning unsupported content type exits 1 with non-empty stderr`() {
        val fetcher = FakeUrlFetcher(FetchResult(ByteArray(10), "application/zip", 0L, 200))
        val (cmd, _, err) = command(urlFetcher = fetcher)
        cmd.input = "https://example.com/archive.zip"
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(1)
        assertThat(err.toString().trim()).isNotEmpty()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private class FakeUrlFetcher(private val result: FetchResult) : UrlFetcher {
        override fun fetch(url: String): FetchResult = result
    }

    private class NoopUrlFetcher : UrlFetcher {
        override fun fetch(url: String): FetchResult =
            throw UnsupportedOperationException("No URL fetching in this test")
    }
}
