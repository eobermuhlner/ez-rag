package ch.obermuhlner.ezrag.command

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Paths

class PdfToMarkdownCommandTest {

    private fun samplePdfFile() =
        Paths.get(javaClass.getResource("/documents/sample.pdf")!!.toURI()).toFile()

    private fun machineLearningPdfFile() =
        Paths.get(javaClass.getResource("/eval/complex-pdf/machine_learning.pdf")!!.toURI()).toFile()

    private fun command(out: StringWriter = StringWriter(), err: StringWriter = StringWriter()): Triple<PdfToMarkdownCommand, StringWriter, StringWriter> {
        val cmd = PdfToMarkdownCommand(
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
        )
        return Triple(cmd, out, err)
    }

    @Test
    fun `default invocation produces non-empty markdown output and exits 0`() {
        val (cmd, out, _) = command()
        cmd.file = samplePdfFile()
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString().trim()).isNotEmpty()
    }

    @Test
    fun `mode rag produces output without inline bold markers`() {
        val (cmd, out, _) = command()
        cmd.file = machineLearningPdfFile()
        cmd.mode = "rag"
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString().trim()).isNotEmpty()
        assertThat(out.toString()).doesNotContain("**")
        assertThat(out.toString()).doesNotContainPattern("\\*[^*\n]+\\*")
    }

    @Test
    fun `mode invalid exits 1 and errorWriter contains error message`() {
        val (cmd, _, err) = command()
        cmd.file = samplePdfFile()
        cmd.mode = "invalid"
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(1)
        assertThat(err.toString().trim()).isNotEmpty()
    }

    @Test
    fun `max-pages 1 exits 0 and output is shorter than without the flag`() {
        val (cmdFull, outFull, _) = command()
        cmdFull.file = machineLearningPdfFile()
        assertThat(cmdFull.call()).isEqualTo(0)
        val fullOutput = outFull.toString()

        val (cmdOne, outOne, _) = command()
        cmdOne.file = machineLearningPdfFile()
        cmdOne.maxPages = 1
        assertThat(cmdOne.call()).isEqualTo(0)
        val onePageOutput = outOne.toString()

        assertThat(onePageOutput.trim()).isNotEmpty()
        assertThat(onePageOutput.length).isLessThan(fullOutput.length)
    }

    @Test
    fun `nonexistent file exits 1 and errorWriter contains error message`() {
        val (cmd, _, err) = command()
        cmd.file = java.io.File("/nonexistent/path/file.pdf")
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(1)
        assertThat(err.toString().trim()).isNotEmpty()
        assertThat(err.toString()).doesNotContain("Exception")
    }

    @Test
    fun `max-pages negative exits 1 and errorWriter contains error message`() {
        val (cmd, _, err) = command()
        cmd.file = samplePdfFile()
        cmd.maxPages = -1
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(1)
        assertThat(err.toString().trim()).isNotEmpty()
    }

    @Test
    fun `output-format xml exits 0 and output starts with xml tag`() {
        val (cmd, out, _) = command()
        cmd.file = samplePdfFile()
        cmd.outputFormat = "xml"
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString().trim()).startsWith("<")
    }

    @Test
    fun `output-format xml contains page element with child element with non-empty text`() {
        val (cmd, out, _) = command()
        cmd.file = samplePdfFile()
        cmd.outputFormat = "xml"
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        val xml = out.toString()
        assertThat(xml).contains("<page")
        val childPattern = Regex("<element[^>]*>[^<]+</element>")
        assertThat(childPattern.containsMatchIn(xml)).isTrue()
    }

    @Test
    fun `output-format invalid exits 1 and errorWriter contains error message`() {
        val (cmd, _, err) = command()
        cmd.file = samplePdfFile()
        cmd.outputFormat = "invalid"
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(1)
        assertThat(err.toString().trim()).isNotEmpty()
    }

    @Test
    fun `output-format markdown explicit exits 0 and produces markdown output`() {
        val (cmd, out, _) = command()
        cmd.file = samplePdfFile()
        cmd.outputFormat = "markdown"
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString().trim()).isNotEmpty()
    }

    @Test
    fun `output-format xml with mode rag exits 0 without error`() {
        val (cmd, out, err) = command()
        cmd.file = samplePdfFile()
        cmd.outputFormat = "xml"
        cmd.mode = "rag"
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(err.toString().trim()).isEmpty()
        assertThat(out.toString().trim()).startsWith("<")
    }
}
