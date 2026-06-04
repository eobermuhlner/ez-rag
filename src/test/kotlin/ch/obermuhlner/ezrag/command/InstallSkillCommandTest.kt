package ch.obermuhlner.ezrag.command

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path

class InstallSkillCommandTest {

    private fun makeWriter(): Pair<StringWriter, PrintWriter> {
        val sw = StringWriter()
        return sw to PrintWriter(sw, true)
    }

    private fun fakeHome(@TempDir tempDir: Path) = tempDir.resolve("home")

    @Test
    fun `prints Installed line for claude-code when dot-claude exists`(@TempDir tempDir: Path) {
        tempDir.resolve(".claude").toFile().mkdir()
        val (sw, pw) = makeWriter()
        InstallSkillCommand(cwdOverride = tempDir, homeOverride = fakeHome(tempDir), outputWriter = pw).call()
        assertThat(sw.toString()).contains("Installed ez-rag skill for claude-code:")
    }

    @Test
    fun `prints Updated line when skill file already exists`(@TempDir tempDir: Path) {
        tempDir.resolve(".claude").toFile().mkdir()
        val (_, pw1) = makeWriter()
        InstallSkillCommand(cwdOverride = tempDir, homeOverride = fakeHome(tempDir), outputWriter = pw1).call()

        val (sw2, pw2) = makeWriter()
        InstallSkillCommand(cwdOverride = tempDir, homeOverride = fakeHome(tempDir), outputWriter = pw2).call()
        assertThat(sw2.toString()).contains("Updated ez-rag skill for claude-code:")
    }

    @Test
    fun `prints generic fallback message when no tool detected`(@TempDir tempDir: Path) {
        val (sw, pw) = makeWriter()
        InstallSkillCommand(cwdOverride = tempDir, homeOverride = fakeHome(tempDir), outputWriter = pw).call()
        assertThat(sw.toString()).contains("No known agentic coding tools detected, installing generic skill.")
    }

    @Test
    fun `prints generic install line after fallback message`(@TempDir tempDir: Path) {
        val (sw, pw) = makeWriter()
        InstallSkillCommand(cwdOverride = tempDir, homeOverride = fakeHome(tempDir), outputWriter = pw).call()
        assertThat(sw.toString()).contains("Installed ez-rag skill for generic:")
    }

    @Test
    fun `prints two output lines when both claude and opencode detected`(@TempDir tempDir: Path) {
        tempDir.resolve(".claude").toFile().mkdir()
        tempDir.resolve(".opencode").toFile().mkdir()
        val (sw, pw) = makeWriter()
        InstallSkillCommand(cwdOverride = tempDir, homeOverride = fakeHome(tempDir), outputWriter = pw).call()
        val output = sw.toString()
        assertThat(output).contains("claude-code")
        assertThat(output).contains("opencode")
    }

    @Test
    fun `returns exit code 0 on success`(@TempDir tempDir: Path) {
        val (_, pw) = makeWriter()
        val exitCode = InstallSkillCommand(cwdOverride = tempDir, homeOverride = fakeHome(tempDir), outputWriter = pw).call()
        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    fun `writes skill file to dot-claude when dot-claude exists`(@TempDir tempDir: Path) {
        tempDir.resolve(".claude").toFile().mkdir()
        val (_, pw) = makeWriter()
        InstallSkillCommand(cwdOverride = tempDir, homeOverride = fakeHome(tempDir), outputWriter = pw).call()
        assertThat(tempDir.resolve(".claude/skills/ez-rag/SKILL.md").toFile().exists()).isTrue()
    }

    @Test
    fun `--global with Claude Code detected writes to home dot-claude path`(@TempDir tempDir: Path) {
        val home = fakeHome(tempDir)
        tempDir.resolve(".claude").toFile().mkdir()
        val (_, pw) = makeWriter()
        InstallSkillCommand(cwdOverride = tempDir, homeOverride = home, outputWriter = pw, isGlobal = true).call()
        assertThat(home.resolve(".claude/skills/ez-rag/SKILL.md").toFile().exists()).isTrue()
    }

    @Test
    fun `--global with OpenCode detected writes to home dot-config opencode path`(@TempDir tempDir: Path) {
        val home = fakeHome(tempDir)
        tempDir.resolve(".opencode").toFile().mkdir()
        val (_, pw) = makeWriter()
        InstallSkillCommand(cwdOverride = tempDir, homeOverride = home, outputWriter = pw, isGlobal = true).call()
        assertThat(home.resolve(".config/opencode/skills/ez-rag/SKILL.md").toFile().exists()).isTrue()
    }

    @Test
    fun `--global with generic fallback writes to home dot-agents path`(@TempDir tempDir: Path) {
        val home = fakeHome(tempDir)
        val (_, pw) = makeWriter()
        InstallSkillCommand(cwdOverride = tempDir, homeOverride = home, outputWriter = pw, isGlobal = true).call()
        assertThat(home.resolve(".agents/skills/ez-rag/SKILL.md").toFile().exists()).isTrue()
    }

    @Test
    fun `without --global project-level paths are used`(@TempDir tempDir: Path) {
        tempDir.resolve(".claude").toFile().mkdir()
        val (_, pw) = makeWriter()
        InstallSkillCommand(cwdOverride = tempDir, homeOverride = fakeHome(tempDir), outputWriter = pw, isGlobal = false).call()
        assertThat(tempDir.resolve(".claude/skills/ez-rag/SKILL.md").toFile().exists()).isTrue()
    }

    @Test
    fun `--tool claude-code writes file even without dot-claude directory`(@TempDir tempDir: Path) {
        val (_, pw) = makeWriter()
        InstallSkillCommand(cwdOverride = tempDir, homeOverride = fakeHome(tempDir), outputWriter = pw, explicitToolNames = listOf("claude-code")).call()
        assertThat(tempDir.resolve(".claude/skills/ez-rag/SKILL.md").toFile().exists()).isTrue()
    }

    @Test
    fun `--tool claude-code --tool opencode writes both paths and prints two output lines`(@TempDir tempDir: Path) {
        val (sw, pw) = makeWriter()
        InstallSkillCommand(cwdOverride = tempDir, homeOverride = fakeHome(tempDir), outputWriter = pw, explicitToolNames = listOf("claude-code", "opencode")).call()
        val output = sw.toString()
        assertThat(output).contains("claude-code")
        assertThat(output).contains("opencode")
        assertThat(tempDir.resolve(".claude/skills/ez-rag/SKILL.md").toFile().exists()).isTrue()
        assertThat(tempDir.resolve(".agents/skills/ez-rag/SKILL.md").toFile().exists()).isTrue()
    }

    @Test
    fun `--tool suppresses generic fallback message`(@TempDir tempDir: Path) {
        val (sw, pw) = makeWriter()
        InstallSkillCommand(cwdOverride = tempDir, homeOverride = fakeHome(tempDir), outputWriter = pw, explicitToolNames = listOf("claude-code")).call()
        assertThat(sw.toString()).doesNotContain("No known agentic coding tools detected")
    }

    @Test
    fun `--tool generic suppresses generic fallback notice`(@TempDir tempDir: Path) {
        val (sw, pw) = makeWriter()
        InstallSkillCommand(cwdOverride = tempDir, homeOverride = fakeHome(tempDir), outputWriter = pw, explicitToolNames = listOf("generic")).call()
        assertThat(sw.toString()).doesNotContain("No known agentic coding tools detected")
    }

    @Test
    fun `unknown tool name prints error message and returns exit code 1`(@TempDir tempDir: Path) {
        val (sw, pw) = makeWriter()
        val exitCode = InstallSkillCommand(cwdOverride = tempDir, homeOverride = fakeHome(tempDir), outputWriter = pw, explicitToolNames = listOf("foobar")).call()
        assertThat(exitCode).isEqualTo(1)
        assertThat(sw.toString()).contains("Error: unknown tool 'foobar'")
    }

    @Test
    fun `--tool and --global can be combined`(@TempDir tempDir: Path) {
        val home = fakeHome(tempDir)
        val (_, pw) = makeWriter()
        InstallSkillCommand(cwdOverride = tempDir, homeOverride = home, outputWriter = pw, explicitToolNames = listOf("claude-code"), isGlobal = true).call()
        assertThat(home.resolve(".claude/skills/ez-rag/SKILL.md").toFile().exists()).isTrue()
    }
}
