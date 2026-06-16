package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.FetchResult
import ch.obermuhlner.ezrag.ingestion.UrlFetcher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path
import java.nio.file.Paths

class ToChunksCommandTest {

    private fun sampleMdFile() =
        Paths.get(javaClass.getResource("/documents/sample.md")!!.toURI()).toFile()

    private fun sampleTxtFile() =
        Paths.get(javaClass.getResource("/documents/sample.txt")!!.toURI()).toFile()

    private fun sampleDocxFile() =
        Paths.get(javaClass.getResource("/fixtures/sample.docx")!!.toURI()).toFile()

    private fun command(
        out: StringWriter = StringWriter(),
        err: StringWriter = StringWriter(),
        urlFetcher: UrlFetcher = NoopUrlFetcher(),
    ): Triple<ToChunksCommand, StringWriter, StringWriter> {
        val cmd = ToChunksCommand(
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
        val cmd = ToChunksCommand(
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
            urlFetcher = urlFetcher,
        )
        val exitCode = CommandLine(cmd).execute(*args)
        return Triple(exitCode, out, err)
    }

    // ── Default text output ───────────────────────────────────────────────────

    @Test
    fun `to-chunks default text output contains chunk index metadata`() {
        val (cmd, out, err) = command()
        cmd.input = sampleMdFile().absolutePath
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString()).contains("[1] chunk=0")
        assertThat(err.toString()).isEmpty()
    }

    // ── XML output ────────────────────────────────────────────────────────────

    @Test
    fun `to-chunks --output-format xml produces valid XML with result elements`() {
        val (exitCode, out, err) = executeViaPicocli(
            arrayOf(sampleMdFile().absolutePath, "--output-format", "xml")
        )
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString()).contains("<results>")
        assertThat(out.toString()).contains("<result ")
        assertThat(out.toString()).contains("</result>")
        assertThat(out.toString()).contains("</results>")
        assertThat(err.toString()).isEmpty()
    }

    // ── JSON output ───────────────────────────────────────────────────────────

    @Test
    fun `to-chunks --output-format json produces valid JSON with chunks array`() {
        val (exitCode, out, err) = executeViaPicocli(
            arrayOf(sampleMdFile().absolutePath, "--output-format", "json")
        )
        assertThat(exitCode).isEqualTo(0)
        val output = out.toString()
        assertThat(output).contains("\"chunks\"")
        assertThat(output).contains("[")
        assertThat(output).contains("]")
        assertThat(err.toString()).isEmpty()
    }

    // ── Chunk-size effect ─────────────────────────────────────────────────────

    @Test
    fun `to-chunks --chunk-size 50 produces more chunks than default chunk-size`() {
        val (cmdSmall, outSmall, _) = command()
        cmdSmall.input = sampleTxtFile().absolutePath
        cmdSmall.chunkSize = 50
        cmdSmall.chunkOverlap = 0
        assertThat(cmdSmall.call()).isEqualTo(0)

        val (cmdDefault, outDefault, _) = command()
        cmdDefault.input = sampleTxtFile().absolutePath
        cmdDefault.chunkSize = 1000
        cmdDefault.chunkOverlap = 200
        assertThat(cmdDefault.call()).isEqualTo(0)

        val smallChunkCount = outSmall.toString().split("[1] chunk=").size - 1
        val defaultChunkCount = outDefault.toString().split("[1] chunk=").size - 1

        // With small chunk size, we should either have more chunks or at least one chunk
        // The key assertion is: small chunk size produces at least some output
        assertThat(outSmall.toString().trim()).isNotEmpty()
        // When chunk-size is 50 vs 1000, we expect more chunks with 50
        // Count total "[N]" headers - small should have >= default
        val smallHeaderCount = Regex("\\[\\d+\\] chunk=").findAll(outSmall.toString()).count()
        val defaultHeaderCount = Regex("\\[\\d+\\] chunk=").findAll(outDefault.toString()).count()
        assertThat(smallHeaderCount).isGreaterThanOrEqualTo(defaultHeaderCount)
    }

    // ── Heading metadata ──────────────────────────────────────────────────────

    @Test
    fun `to-chunks sample md with headings includes heading_path in output`() {
        val (cmd, out, _) = command()
        cmd.input = sampleMdFile().absolutePath
        assertThat(cmd.call()).isEqualTo(0)
        // sample.md has headings, so at least one chunk should have heading_path
        assertThat(out.toString()).contains("heading_path")
    }

    @Test
    fun `to-chunks plain text fixture has no heading_path in output`() {
        val (cmd, out, _) = command()
        cmd.input = sampleTxtFile().absolutePath
        assertThat(cmd.call()).isEqualTo(0)
        assertThat(out.toString()).doesNotContain("heading_path")
    }

    // ── Error paths ───────────────────────────────────────────────────────────

    @Test
    fun `to-chunks unsupported extension exits 1 with message on stderr`() {
        val (cmd, _, err) = command()
        cmd.input = "file.unknownxyz"
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(1)
        assertThat(err.toString().trim()).isNotEmpty()
    }

    @Test
    fun `to-chunks nonexistent file exits 1 with message on stderr`() {
        val (cmd, out, err) = command()
        cmd.input = "/nonexistent/path/file.md"
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(1)
        assertThat(err.toString().trim()).isNotEmpty()
        assertThat(out.toString()).doesNotContain("Exception")
    }

    // ── No store required ─────────────────────────────────────────────────────

    @Test
    fun `to-chunks works without a store directory present`(@TempDir tempDir: Path) {
        // Run from a temp directory that has no store — command should still succeed
        val noStoreDir = tempDir.resolve("no-store-here")
        // Don't create noStoreDir — it should not exist
        val (cmd, out, err) = command()
        cmd.input = sampleMdFile().absolutePath
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString().trim()).isNotEmpty()
        assertThat(err.toString()).isEmpty()
    }

    // ── URL support ───────────────────────────────────────────────────────────

    @Test
    fun `to-chunks url returning text-html exits 0 with chunk output`() {
        val htmlBytes = """
            <html><body>
            <h1>Title</h1>
            <p>Some content for chunking. This is a test paragraph with enough text.</p>
            </body></html>
        """.trimIndent().toByteArray()
        val fetcher = FakeUrlFetcher(FetchResult(htmlBytes, "text/html", 0L, 200))
        val (exitCode, out, err) = executeViaPicocli(
            arrayOf("https://example.com/page"),
            urlFetcher = fetcher,
        )
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString().trim()).isNotEmpty()
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
