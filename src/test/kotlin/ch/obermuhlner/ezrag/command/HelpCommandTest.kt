package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.EzRagCommand
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.io.PrintWriter
import java.io.StringWriter

class HelpCommandTest {

    private data class TestContext(val out: StringWriter, val err: StringWriter, val commandLine: CommandLine)

    private fun buildCommandLine(): TestContext {
        val out = StringWriter()
        val err = StringWriter()
        val outWriter = PrintWriter(out, true)
        val errWriter = PrintWriter(err, true)
        val commandLine = CommandLine(EzRagCommand())
        commandLine.out = outWriter
        commandLine.err = errWriter
        commandLine.subcommands["help"]!!.out = outWriter
        commandLine.subcommands["help"]!!.err = errWriter
        return TestContext(out, err, commandLine)
    }

    @Test
    fun `ez-rag help exits with code 0`() {
        val (_, _, commandLine) = buildCommandLine()
        val exitCode = commandLine.execute("help")
        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    fun `ez-rag help output starts with root description`() {
        val (out, _, commandLine) = buildCommandLine()
        commandLine.execute("help")
        assertThat(out.toString()).contains("A command-line tool for RAG")
    }

    @Test
    fun `ez-rag help output contains all sibling subcommand names`() {
        val (out, _, commandLine) = buildCommandLine()
        commandLine.execute("help")
        val output = out.toString()
        assertThat(output).contains("init")
        assertThat(output).contains("ingest")
        assertThat(output).contains("chunk")
        assertThat(output).contains("reingest")
        assertThat(output).contains("delete")
        assertThat(output).contains("list")
        assertThat(output).contains("show")
        assertThat(output).contains("query")
        assertThat(output).contains("search")
        assertThat(output).contains("status")
        assertThat(output).contains("mcp-server")
        assertThat(output).contains("shell")
        assertThat(output).contains("eval")
        assertThat(output).contains("download-eval-corpus")
        assertThat(output).contains("install-skill")
    }

    @Test
    fun `ez-rag help output contains at least two occurrences of separator`() {
        val (out, _, commandLine) = buildCommandLine()
        commandLine.execute("help")
        val output = out.toString()
        val count = output.split("---").size - 1
        assertThat(count).isGreaterThanOrEqualTo(2)
    }

    @Test
    fun `ez-rag help output does not contain help command usage line`() {
        val (out, _, commandLine) = buildCommandLine()
        commandLine.execute("help")
        val output = out.toString()
        assertThat(output).doesNotContain("Usage:  ez-rag help")
    }

    // Task 02: targeted subcommand

    @Test
    fun `ez-rag help ingest exits 0 and output contains ingest and --store-dir`() {
        val (out, _, commandLine) = buildCommandLine()
        val exitCode = commandLine.execute("help", "ingest")
        assertThat(exitCode).isEqualTo(0)
        val output = out.toString()
        assertThat(output).contains("ingest")
        assertThat(output).contains("--store-dir")
    }

    @Test
    fun `ez-rag help status exits 0 and output contains status and --output-format`() {
        val (out, _, commandLine) = buildCommandLine()
        val exitCode = commandLine.execute("help", "status")
        assertThat(exitCode).isEqualTo(0)
        val output = out.toString()
        assertThat(output).contains("status")
        assertThat(output).contains("--output-format")
    }

    @Test
    fun `ez-rag help query exits 0 and output contains query`() {
        val (out, _, commandLine) = buildCommandLine()
        val exitCode = commandLine.execute("help", "query")
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString()).contains("query")
    }

    @Test
    fun `ez-rag help no-such-command exits with non-zero code`() {
        val (_, _, commandLine) = buildCommandLine()
        val exitCode = commandLine.execute("help", "no-such-command")
        assertThat(exitCode).isNotEqualTo(0)
    }

    @Test
    fun `ez-rag help no-such-command writes error message containing the unknown name`() {
        val (_, err, commandLine) = buildCommandLine()
        commandLine.execute("help", "no-such-command")
        assertThat(err.toString()).contains("no-such-command")
    }
}
