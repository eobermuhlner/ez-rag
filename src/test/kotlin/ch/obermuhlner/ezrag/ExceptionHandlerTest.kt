package ch.obermuhlner.ezrag

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import picocli.CommandLine
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.Callable

class ExceptionHandlerTest {

    @CommandLine.Command(name = "fail")
    private class ThrowingCommand(val exception: Exception) : Callable<Int> {
        override fun call(): Int = throw exception
    }

    private fun execute(vararg args: String, exception: Exception = RuntimeException("test error")): Pair<Int, String> {
        val err = StringWriter()
        val ezRagCommand = EzRagCommand()
        val commandLine = CommandLine(ezRagCommand).also { cl ->
            cl.addSubcommand("fail", ThrowingCommand(exception))
            cl.err = PrintWriter(err, true)
            cl.setExecutionExceptionHandler(EzRagExecutionExceptionHandler(ezRagCommand))
        }
        val exitCode = commandLine.execute(*args)
        return exitCode to err.toString()
    }

    @Test
    fun `unhandled exception with message prints error to stderr`() {
        val (exitCode, err) = execute("fail")
        assertThat(err).contains("Error: test error")
        assertThat(exitCode).isEqualTo(1)
    }

    @Test
    fun `unhandled exception with null message prints class name and no message`() {
        val (exitCode, err) = execute("fail", exception = NullPointerException())
        assertThat(err).contains("NullPointerException (no message)")
        assertThat(exitCode).isEqualTo(1)
    }

    @Test
    fun `unhandled exception with blank message prints class name and no message`() {
        val (exitCode, err) = execute("fail", exception = RuntimeException("   "))
        assertThat(err).contains("RuntimeException (no message)")
        assertThat(exitCode).isEqualTo(1)
    }

    @Test
    fun `hint line always appears in stderr`() {
        val (_, err) = execute("fail")
        assertThat(err).contains("Use --stack-trace for full details.")
    }

    @Test
    fun `no stacktrace frames appear in stderr by default`() {
        val (_, err) = execute("fail")
        assertThat(err).doesNotContain("\tat ")
    }

    @Test
    fun `normal command execution is unaffected`() {
        val ezRagCommand = EzRagCommand()
        val commandLine = CommandLine(ezRagCommand).also {
            it.setExecutionExceptionHandler(EzRagExecutionExceptionHandler(ezRagCommand))
        }
        val exitCode = commandLine.execute("--help")
        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    fun `stack-trace option appears in help output`() {
        val out = StringWriter()
        val ezRagCommand = EzRagCommand()
        val commandLine = CommandLine(ezRagCommand).also {
            it.out = PrintWriter(out, true)
        }
        commandLine.execute("--help")
        assertThat(out.toString()).contains("--stack-trace")
    }

    @Test
    fun `stack-trace before subcommand prints stacktrace frames to stderr`() {
        val (_, err) = execute("--stack-trace", "fail")
        assertThat(err).contains("\tat ")
    }

    @Test
    fun `stack-trace after subcommand prints stacktrace frames to stderr`() {
        val (_, err) = execute("fail", "--stack-trace")
        assertThat(err).contains("\tat ")
    }

    @Test
    fun `hint still present when stack-trace is set`() {
        val (_, err) = execute("--stack-trace", "fail")
        assertThat(err).contains("Use --stack-trace for full details.")
    }
}
