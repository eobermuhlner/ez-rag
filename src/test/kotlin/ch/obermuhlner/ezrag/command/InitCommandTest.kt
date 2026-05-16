package ch.obermuhlner.ezrag.command

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path

class InitCommandTest {

    private fun makeWriter(): Pair<StringWriter, PrintWriter> {
        val sw = StringWriter()
        return sw to PrintWriter(sw, true)
    }

    @Test
    fun `creates ez-rag directory when it does not exist`(@TempDir tempDir: Path) {
        val (_, pw) = makeWriter()
        val cmd = InitCommand(cwdOverride = tempDir, outputWriter = pw)
        cmd.call()

        assertThat(tempDir.resolve(".ez-rag").toFile().isDirectory).isTrue()
    }

    @Test
    fun `prints success message containing absolute path`(@TempDir tempDir: Path) {
        val (sw, pw) = makeWriter()
        val cmd = InitCommand(cwdOverride = tempDir, outputWriter = pw)
        cmd.call()

        assertThat(sw.toString()).contains(tempDir.resolve(".ez-rag").toAbsolutePath().toString())
    }

    @Test
    fun `returns exit code 0 on success`(@TempDir tempDir: Path) {
        val (_, pw) = makeWriter()
        val cmd = InitCommand(cwdOverride = tempDir, outputWriter = pw)
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    fun `returns exit code 0 when ez-rag already exists`(@TempDir tempDir: Path) {
        tempDir.resolve(".ez-rag").toFile().mkdirs()
        val (_, pw) = makeWriter()
        val cmd = InitCommand(cwdOverride = tempDir, outputWriter = pw)
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    fun `prints informational message containing absolute path when ez-rag already exists`(@TempDir tempDir: Path) {
        tempDir.resolve(".ez-rag").toFile().mkdirs()
        val (sw, pw) = makeWriter()
        val cmd = InitCommand(cwdOverride = tempDir, outputWriter = pw)
        cmd.call()

        assertThat(sw.toString()).contains(tempDir.resolve(".ez-rag").toAbsolutePath().toString())
    }

    @Test
    fun `does not delete contents of existing ez-rag on re-run`(@TempDir tempDir: Path) {
        val ezRagDir = tempDir.resolve(".ez-rag").toFile()
        ezRagDir.mkdirs()
        val existingFile = ezRagDir.resolve("vector-store.json")
        existingFile.writeText("existing-content")

        val (_, pw) = makeWriter()
        val cmd = InitCommand(cwdOverride = tempDir, outputWriter = pw)
        cmd.call()

        assertThat(existingFile.exists()).isTrue()
        assertThat(existingFile.readText()).isEqualTo("existing-content")
    }

    @Test
    fun `adds vector-store-json entry to gitignore when gitignore exists`(@TempDir tempDir: Path) {
        val gitignore = tempDir.resolve(".gitignore").toFile()
        gitignore.writeText("target/\n*.log\n")

        val (_, pw) = makeWriter()
        val cmd = InitCommand(cwdOverride = tempDir, outputWriter = pw)
        cmd.call()

        assertThat(gitignore.readText()).contains(".ez-rag/vector-store.json")
    }

    @Test
    fun `exits with code 0 when gitignore does not exist`(@TempDir tempDir: Path) {
        val (_, pw) = makeWriter()
        val cmd = InitCommand(cwdOverride = tempDir, outputWriter = pw)
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(tempDir.resolve(".gitignore").toFile().exists()).isFalse()
    }

    @Test
    fun `does not add duplicate vector-store-json entry when already present in gitignore`(@TempDir tempDir: Path) {
        val gitignore = tempDir.resolve(".gitignore").toFile()
        gitignore.writeText(".ez-rag/vector-store.json\n")

        val (_, pw) = makeWriter()
        val cmd = InitCommand(cwdOverride = tempDir, outputWriter = pw)
        cmd.call()

        val count = gitignore.readLines().count { it.trim() == ".ez-rag/vector-store.json" }
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `both credentials and vector-store entries present after init when credentials already in gitignore`(@TempDir tempDir: Path) {
        val gitignore = tempDir.resolve(".gitignore").toFile()
        gitignore.writeText(".ez-rag/credentials.yml\n")

        val (_, pw) = makeWriter()
        val cmd = InitCommand(cwdOverride = tempDir, outputWriter = pw)
        cmd.call()

        val content = gitignore.readText()
        assertThat(content).contains(".ez-rag/credentials.yml")
        assertThat(content).contains(".ez-rag/vector-store.json")
        // Ensure neither is duplicated
        assertThat(content.lines().count { it.trim() == ".ez-rag/credentials.yml" }).isEqualTo(1)
        assertThat(content.lines().count { it.trim() == ".ez-rag/vector-store.json" }).isEqualTo(1)
    }
}
