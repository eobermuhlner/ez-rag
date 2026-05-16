package ch.obermuhlner.ezrag.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path

class GitIgnoreUpdaterTest {

    private fun makeWriter(): Pair<StringWriter, PrintWriter> {
        val sw = StringWriter()
        return sw to PrintWriter(sw, true)
    }

    @Test
    fun `appends credentials entry when gitignore exists and entry is absent`(@TempDir tempDir: Path) {
        val gitignore = tempDir.resolve(".gitignore").toFile()
        gitignore.writeText("target/\n*.log\n")
        val (sw, pw) = makeWriter()

        GitIgnoreUpdater(pw).update(tempDir.toFile())

        assertThat(gitignore.readText()).contains(".ez-rag/credentials.yml")
    }

    @Test
    fun `prints a notice when adding the entry`(@TempDir tempDir: Path) {
        val gitignore = tempDir.resolve(".gitignore").toFile()
        gitignore.writeText("target/\n")
        val (sw, pw) = makeWriter()

        GitIgnoreUpdater(pw).update(tempDir.toFile())

        assertThat(sw.toString()).isNotBlank()
    }

    @Test
    fun `does not duplicate entry when already present`(@TempDir tempDir: Path) {
        val gitignore = tempDir.resolve(".gitignore").toFile()
        gitignore.writeText("target/\n.ez-rag/credentials.yml\n")
        val (sw, pw) = makeWriter()

        GitIgnoreUpdater(pw).update(tempDir.toFile())

        val content = gitignore.readText()
        val count = content.lines().count { it.trim() == ".ez-rag/credentials.yml" }
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `does nothing when gitignore does not exist`(@TempDir tempDir: Path) {
        val gitignore = tempDir.resolve(".gitignore").toFile()
        assertThat(gitignore.exists()).isFalse()
        val (sw, pw) = makeWriter()

        GitIgnoreUpdater(pw).update(tempDir.toFile())

        assertThat(gitignore.exists()).isFalse()
        assertThat(sw.toString()).isEmpty()
    }

    @Test
    fun `does not print notice when entry is already present`(@TempDir tempDir: Path) {
        val gitignore = tempDir.resolve(".gitignore").toFile()
        gitignore.writeText(".ez-rag/credentials.yml\n")
        val (sw, pw) = makeWriter()

        GitIgnoreUpdater(pw).update(tempDir.toFile())

        assertThat(sw.toString()).isEmpty()
    }
}
