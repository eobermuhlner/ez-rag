package ch.obermuhlner.ezrag

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.io.PrintWriter
import java.io.StringWriter

class SubcommandTest {

    private val out = StringWriter()
    private val commandLine = CommandLine(EzRagCommand()).also {
        it.out = PrintWriter(out)
    }

    @Test
    fun `help lists all subcommands`() {
        commandLine.execute("--help")
        val output = out.toString()
        assertThat(output).contains("ingest")
        assertThat(output).contains("query")
        assertThat(output).contains("search")
        assertThat(output).contains("status")
        assertThat(output).contains("mcp-server")
        assertThat(output).contains("shell")
    }

    @Test
    fun `ingest help exits 0`() {
        val exitCode = commandLine.execute("ingest", "--help")
        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    fun `query help exits 0`() {
        val exitCode = commandLine.execute("query", "--help")
        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    fun `search help exits 0`() {
        val exitCode = commandLine.execute("search", "--help")
        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    fun `status help exits 0`() {
        val exitCode = commandLine.execute("status", "--help")
        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    fun `mcp-server help exits 0`() {
        val exitCode = commandLine.execute("mcp-server", "--help")
        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    fun `shell help exits 0`() {
        val exitCode = commandLine.execute("shell", "--help")
        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    fun `verbose flag is accepted on top-level command`() {
        val exitCode = commandLine.execute("--verbose", "--help")
        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    fun `verbose flag is accepted on subcommand`() {
        val exitCode = commandLine.execute("ingest", "--verbose", "--help")
        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    fun `ingest accepts positional file path argument`() {
        // Picocli accepts the argument; the command exits non-zero because
        // no EmbeddingModel is wired in the non-Spring test context.
        val exitCode = commandLine.execute("ingest", "somefile.txt")
        assertThat(exitCode).isNotEqualTo(CommandLine.ExitCode.USAGE)
    }

    @Test
    fun `query accepts --question flag`() {
        // Picocli accepts the argument; the command exits non-zero because
        // no vector store exists in the non-Spring test context.
        val exitCode = commandLine.execute("query", "--question", "who are you?")
        assertThat(exitCode).isNotEqualTo(CommandLine.ExitCode.USAGE)
    }

    @Test
    fun `search accepts --question flag`() {
        // Picocli accepts the argument; the command exits non-zero because
        // no vector store exists in the non-Spring test context.
        val exitCode = commandLine.execute("search", "--question", "who are you?")
        assertThat(exitCode).isNotEqualTo(CommandLine.ExitCode.USAGE)
    }
}
