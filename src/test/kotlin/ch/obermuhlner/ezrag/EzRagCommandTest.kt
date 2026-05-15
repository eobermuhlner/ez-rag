package ch.obermuhlner.ezrag

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import picocli.CommandLine

class EzRagCommandTest {

    @Test
    fun `help exits 0`() {
        val commandLine = CommandLine(EzRagCommand())
        val exitCode = commandLine.execute("--help")
        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    fun `unknown subcommand exits non-zero`() {
        val commandLine = CommandLine(EzRagCommand())
        val exitCode = commandLine.execute("unknown-subcommand")
        assertThat(exitCode).isNotEqualTo(0)
    }
}
