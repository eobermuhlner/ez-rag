package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.FetchResult
import ch.obermuhlner.ezrag.ingestion.UrlFetcher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Paths

class ToMarkdownCommandTest {

    private fun samplePdfFile() =
        Paths.get(javaClass.getResource("/documents/sample.pdf")!!.toURI()).toFile()

    private fun sampleHtmlFile() =
        Paths.get(javaClass.getResource("/documents/sample.html")!!.toURI()).toFile()

    private fun sampleHtmFile() =
        Paths.get(javaClass.getResource("/documents/sample.htm")!!.toURI()).toFile()

    private fun machineLearningPdfFile() =
        Paths.get(javaClass.getResource("/eval/complex-pdf/machine_learning.pdf")!!.toURI()).toFile()

    private fun command(
        out: StringWriter = StringWriter(),
        err: StringWriter = StringWriter(),
        urlFetcher: UrlFetcher = NoopUrlFetcher(),
    ): Triple<ToMarkdownCommand, StringWriter, StringWriter> {
        val cmd = ToMarkdownCommand(
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
        val cmd = ToMarkdownCommand(
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
            urlFetcher = urlFetcher,
        )
        val exitCode = CommandLine(cmd).execute(*args)
        return Triple(exitCode, out, err)
    }

    // ── Local PDF tests ──────────────────────────────────────────────────────

    @Test
    fun `to-markdown pdf produces non-empty output and exits 0`() {
        val (cmd, out, _) = command()
        cmd.input = samplePdfFile().absolutePath
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString().trim()).isNotEmpty()
    }

    @Test
    fun `to-markdown pdf mode rag produces output without bold markers and exits 0`() {
        val (cmd, out, _) = command()
        cmd.input = machineLearningPdfFile().absolutePath
        cmd.mode = "rag"
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString()).doesNotContain("**")
    }

    @Test
    fun `to-markdown pdf max-pages 1 exits 0 and output shorter than full`() {
        val (cmdFull, outFull, _) = command()
        cmdFull.input = machineLearningPdfFile().absolutePath
        assertThat(cmdFull.call()).isEqualTo(0)
        val fullOutput = outFull.toString()

        val (cmdOne, outOne, _) = command()
        cmdOne.input = machineLearningPdfFile().absolutePath
        cmdOne.maxPages = 1
        assertThat(cmdOne.call()).isEqualTo(0)

        assertThat(outOne.toString().trim()).isNotEmpty()
        assertThat(outOne.toString().length).isLessThan(fullOutput.length)
    }

    @Test
    fun `to-markdown pdf output-format xml exits 0 and output starts with lt`() {
        val (cmd, out, _) = command()
        cmd.input = samplePdfFile().absolutePath
        cmd.outputFormat = "xml"
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString().trim()).startsWith("<")
    }

    @Test
    fun `to-markdown pdf mode invalid exits 1 with non-empty stderr`() {
        val (cmd, _, err) = command()
        cmd.input = samplePdfFile().absolutePath
        cmd.mode = "invalid"
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(1)
        assertThat(err.toString().trim()).isNotEmpty()
    }

    @Test
    fun `to-markdown pdf output-format invalid exits 1 with non-empty stderr`() {
        val (cmd, _, err) = command()
        cmd.input = samplePdfFile().absolutePath
        cmd.outputFormat = "invalid"
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(1)
        assertThat(err.toString().trim()).isNotEmpty()
    }

    @Test
    fun `to-markdown pdf max-pages negative exits 1 with non-empty stderr`() {
        val (cmd, _, err) = command()
        cmd.input = samplePdfFile().absolutePath
        cmd.maxPages = -1
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(1)
        assertThat(err.toString().trim()).isNotEmpty()
    }

    @Test
    fun `to-markdown nonexistent file exits 1 with non-empty stderr and no Exception in stdout`() {
        val (cmd, out, err) = command()
        cmd.input = "/nonexistent/file.pdf"
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(1)
        assertThat(err.toString().trim()).isNotEmpty()
        assertThat(out.toString()).doesNotContain("Exception")
    }

    @Test
    fun `to-markdown unknown extension exits 1 with non-empty stderr`() {
        val (cmd, _, err) = command()
        cmd.input = "file.docx"
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(1)
        assertThat(err.toString().trim()).isNotEmpty()
    }

    // ── Local HTML tests ─────────────────────────────────────────────────────

    @Test
    fun `to-markdown html produces non-empty output and exits 0`() {
        val (exitCode, out, _) = executeViaPicocli(arrayOf(sampleHtmlFile().absolutePath))
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString().trim()).isNotEmpty()
    }

    @Test
    fun `to-markdown htm produces non-empty output and exits 0`() {
        val (exitCode, out, _) = executeViaPicocli(arrayOf(sampleHtmFile().absolutePath))
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString().trim()).isNotEmpty()
    }

    @Test
    fun `to-markdown html with mode option exits 1 with stderr identifying mode as PDF-only`() {
        val (exitCode, _, err) = executeViaPicocli(arrayOf(sampleHtmlFile().absolutePath, "--mode", "readable"))
        assertThat(exitCode).isEqualTo(1)
        assertThat(err.toString()).contains("--mode")
    }

    @Test
    fun `to-markdown html with output-format option exits 1 with stderr identifying output-format as PDF-only`() {
        val (exitCode, _, err) = executeViaPicocli(arrayOf(sampleHtmlFile().absolutePath, "--output-format", "xml"))
        assertThat(exitCode).isEqualTo(1)
        assertThat(err.toString()).contains("--output-format")
    }

    @Test
    fun `to-markdown html with max-pages option exits 1 with stderr identifying max-pages as PDF-only`() {
        val (exitCode, _, err) = executeViaPicocli(arrayOf(sampleHtmlFile().absolutePath, "--max-pages", "1"))
        assertThat(exitCode).isEqualTo(1)
        assertThat(err.toString()).contains("--max-pages")
    }

    @Test
    fun `to-markdown htm with mode option exits 1 with non-empty stderr`() {
        val (exitCode, _, err) = executeViaPicocli(arrayOf(sampleHtmFile().absolutePath, "--mode", "readable"))
        assertThat(exitCode).isEqualTo(1)
        assertThat(err.toString().trim()).isNotEmpty()
    }

    // ── URL tests ────────────────────────────────────────────────────────────

    @Test
    fun `to-markdown url returning text-html exits 0 with non-empty stdout`() {
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
    fun `to-markdown url returning application-pdf exits 0 with non-empty stdout`() {
        val pdfBytes = samplePdfFile().readBytes()
        val fetcher = FakeUrlFetcher(FetchResult(pdfBytes, "application/pdf", 0L, 200))
        val (cmd, out, _) = command(urlFetcher = fetcher)
        cmd.input = "https://example.com/doc.pdf"
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString().trim()).isNotEmpty()
    }

    @Test
    fun `to-markdown url returning unsupported content type exits 1 with non-empty stderr`() {
        val zipBytes = ByteArray(10)
        val fetcher = FakeUrlFetcher(FetchResult(zipBytes, "application/zip", 0L, 200))
        val (cmd, _, err) = command(urlFetcher = fetcher)
        cmd.input = "https://example.com/archive.zip"
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(1)
        assertThat(err.toString().trim()).isNotEmpty()
    }

    @Test
    fun `to-markdown url returning text-html with mode option exits 1 with stderr identifying mode as PDF-only`() {
        val htmlBytes = "<html><body><h1>Hello</h1></body></html>".toByteArray()
        val fetcher = FakeUrlFetcher(FetchResult(htmlBytes, "text/html", 0L, 200))
        val (exitCode, _, err) = executeViaPicocli(
            arrayOf("https://example.com/page", "--mode", "readable"),
            urlFetcher = fetcher,
        )
        assertThat(exitCode).isEqualTo(1)
        assertThat(err.toString()).contains("--mode")
    }

    // ── Helper stubs ─────────────────────────────────────────────────────────

    private class FakeUrlFetcher(private val result: FetchResult) : UrlFetcher {
        override fun fetch(url: String): FetchResult = result
    }

    private class NoopUrlFetcher : UrlFetcher {
        override fun fetch(url: String): FetchResult =
            throw UnsupportedOperationException("No URL fetching in this test")
    }
}
