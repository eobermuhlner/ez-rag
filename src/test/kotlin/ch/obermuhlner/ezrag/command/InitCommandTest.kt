package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.config.readConfigRaw
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
    fun `adds ez-rag directory entry to gitignore when gitignore exists`(@TempDir tempDir: Path) {
        val gitignore = tempDir.resolve(".gitignore").toFile()
        gitignore.writeText("target/\n*.log\n")

        val (_, pw) = makeWriter()
        val cmd = InitCommand(cwdOverride = tempDir, outputWriter = pw)
        cmd.call()

        assertThat(gitignore.readText()).contains(".ez-rag/")
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
    fun `does not add duplicate ez-rag directory entry when already present in gitignore`(@TempDir tempDir: Path) {
        val gitignore = tempDir.resolve(".gitignore").toFile()
        gitignore.writeText(".ez-rag/\n")

        val (_, pw) = makeWriter()
        val cmd = InitCommand(cwdOverride = tempDir, outputWriter = pw)
        cmd.call()

        val count = gitignore.readLines().count { it.trim() == ".ez-rag/" }
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `both credentials and ez-rag directory entries present after init when credentials already in gitignore`(@TempDir tempDir: Path) {
        val gitignore = tempDir.resolve(".gitignore").toFile()
        gitignore.writeText(".ez-rag/credentials.yml\n")

        val (_, pw) = makeWriter()
        val cmd = InitCommand(cwdOverride = tempDir, outputWriter = pw)
        cmd.call()

        val content = gitignore.readText()
        assertThat(content).contains(".ez-rag/credentials.yml")
        assertThat(content).contains(".ez-rag/")
        // Ensure .ez-rag/ directory entry is not duplicated
        assertThat(content.lines().count { it.trim() == ".ez-rag/credentials.yml" }).isEqualTo(1)
        assertThat(content.lines().count { it.trim() == ".ez-rag/" }).isEqualTo(1)
    }

    @Test
    fun `prints install-skill tip after workspace init`(@TempDir tempDir: Path) {
        val (sw, pw) = makeWriter()
        val cmd = InitCommand(cwdOverride = tempDir, outputWriter = pw)
        cmd.call()

        assertThat(sw.toString()).contains("ez-rag install-skill")
    }

    @Test
    fun `does not write any SKILL-md file when install-skill not set`(@TempDir tempDir: Path) {
        val (_, pw) = makeWriter()
        val cmd = InitCommand(cwdOverride = tempDir, outputWriter = pw)
        cmd.call()

        assertThat(tempDir.resolve(".claude/skills/ez-rag/SKILL.md").toFile().exists()).isFalse()
        assertThat(tempDir.resolve(".agents/skills/ez-rag/SKILL.md").toFile().exists()).isFalse()
    }

    @Test
    fun `writes SKILL-md for detected tool when install-skill flag is set`(@TempDir tempDir: Path) {
        tempDir.resolve(".claude").toFile().mkdirs()
        val homeDir = tempDir.resolve("home").also { it.toFile().mkdirs() }
        val (_, pw) = makeWriter()
        val cmd = InitCommand(cwdOverride = tempDir, homeOverride = homeDir, outputWriter = pw, installSkill = true)
        cmd.call()

        assertThat(tempDir.resolve(".claude/skills/ez-rag/SKILL.md").toFile().exists()).isTrue()
    }

    @Test
    fun `prints install line when install-skill flag is set`(@TempDir tempDir: Path) {
        tempDir.resolve(".claude").toFile().mkdirs()
        val homeDir = tempDir.resolve("home").also { it.toFile().mkdirs() }
        val (sw, pw) = makeWriter()
        val cmd = InitCommand(cwdOverride = tempDir, homeOverride = homeDir, outputWriter = pw, installSkill = true)
        cmd.call()

        assertThat(sw.toString()).contains("ez-rag skill for claude-code")
    }

    @Test
    fun `install-skill with global flag writes to home path`(@TempDir tempDir: Path) {
        val homeDir = tempDir.resolve("home").also { it.toFile().mkdirs() }
        homeDir.resolve(".claude").toFile().mkdirs()
        val (_, pw) = makeWriter()
        val cmd = InitCommand(cwdOverride = tempDir, homeOverride = homeDir, outputWriter = pw, installSkill = true, isGlobal = true)
        cmd.call()

        assertThat(homeDir.resolve(".claude/skills/ez-rag/SKILL.md").toFile().exists()).isTrue()
    }

    @Test
    fun `install-skill with tool flag installs for specified tool regardless of detection`(@TempDir tempDir: Path) {
        val homeDir = tempDir.resolve("home").also { it.toFile().mkdirs() }
        val (_, pw) = makeWriter()
        val cmd = InitCommand(cwdOverride = tempDir, homeOverride = homeDir, outputWriter = pw, installSkill = true, explicitToolNames = listOf("opencode"))
        cmd.call()

        assertThat(tempDir.resolve(".agents/skills/ez-rag/SKILL.md").toFile().exists()).isTrue()
    }

    // ---- config-writing tests ----

    @Test
    fun `writes embeddingProvider to config yml when embedding-provider option is supplied`(@TempDir tempDir: Path) {
        val (_, pw) = makeWriter()
        val cmd = InitCommand(cwdOverride = tempDir, outputWriter = pw, embeddingProvider = "openai")
        cmd.call()

        val configFile = tempDir.resolve(".ez-rag/config.yml")
        assertThat(configFile.toFile().exists()).isTrue()
        val raw = readConfigRaw(configFile.toString())
        assertThat(raw).isNotNull()
        assertThat(raw!!["embeddingProvider"]).isEqualTo("openai")
    }

    @Test
    fun `updates embeddingProvider when config yml already contains a different value`(@TempDir tempDir: Path) {
        val ezRagDir = tempDir.resolve(".ez-rag").toFile().also { it.mkdirs() }
        ezRagDir.resolve("config.yml").writeText("embeddingProvider: onnx\n")
        val (_, pw) = makeWriter()
        val cmd = InitCommand(cwdOverride = tempDir, outputWriter = pw, embeddingProvider = "openai")
        cmd.call()

        val raw = readConfigRaw(tempDir.resolve(".ez-rag/config.yml").toString())
        assertThat(raw).isNotNull()
        assertThat(raw!!["embeddingProvider"]).isEqualTo("openai")
    }

    @Test
    fun `does not create or modify config yml when no config options are provided`(@TempDir tempDir: Path) {
        val (_, pw) = makeWriter()
        val cmd = InitCommand(cwdOverride = tempDir, outputWriter = pw)
        cmd.call()

        val configFile = tempDir.resolve(".ez-rag/config.yml")
        assertThat(configFile.toFile().exists()).isFalse()
    }

    @Test
    fun `preserves existing embeddingProvider when only chunkSize is written`(@TempDir tempDir: Path) {
        val ezRagDir = tempDir.resolve(".ez-rag").toFile().also { it.mkdirs() }
        ezRagDir.resolve("config.yml").writeText("embeddingProvider: openai\n")
        val (_, pw) = makeWriter()
        val cmd = InitCommand(cwdOverride = tempDir, outputWriter = pw, chunkSize = 500)
        cmd.call()

        val raw = readConfigRaw(tempDir.resolve(".ez-rag/config.yml").toString())
        assertThat(raw).isNotNull()
        assertThat(raw!!["embeddingProvider"]).isEqualTo("openai")
        assertThat(raw["chunkSize"]).isEqualTo(500)
    }
}
