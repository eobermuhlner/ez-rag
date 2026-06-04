package ch.obermuhlner.ezrag.command

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SkillInstallerTest {

    private fun installer(@TempDir tempDir: Path) =
        SkillInstaller(projectDir = tempDir, homeDir = tempDir.resolve("home"))

    @Test
    fun `CLAUDE_CODE project-level installs to dot-claude skills ez-rag SKILL dot MD`(@TempDir tempDir: Path) {
        val result = installer(tempDir).install(AgentTool.CLAUDE_CODE, isGlobal = false)
        assertThat(result.path).isEqualTo(tempDir.resolve(".claude/skills/ez-rag/SKILL.md"))
    }

    @Test
    fun `OPENCODE project-level installs to dot-agents skills ez-rag SKILL dot MD`(@TempDir tempDir: Path) {
        val result = installer(tempDir).install(AgentTool.OPENCODE, isGlobal = false)
        assertThat(result.path).isEqualTo(tempDir.resolve(".agents/skills/ez-rag/SKILL.md"))
    }

    @Test
    fun `GENERIC project-level installs to dot-agents skills ez-rag SKILL dot MD`(@TempDir tempDir: Path) {
        val result = installer(tempDir).install(AgentTool.GENERIC, isGlobal = false)
        assertThat(result.path).isEqualTo(tempDir.resolve(".agents/skills/ez-rag/SKILL.md"))
    }

    @Test
    fun `returns wasUpdated false when file does not exist`(@TempDir tempDir: Path) {
        val result = installer(tempDir).install(AgentTool.CLAUDE_CODE, isGlobal = false)
        assertThat(result.wasUpdated).isFalse()
    }

    @Test
    fun `returns wasUpdated true when file already existed`(@TempDir tempDir: Path) {
        val inst = installer(tempDir)
        inst.install(AgentTool.CLAUDE_CODE, isGlobal = false)
        val result = inst.install(AgentTool.CLAUDE_CODE, isGlobal = false)
        assertThat(result.wasUpdated).isTrue()
    }

    @Test
    fun `creates parent directories automatically`(@TempDir tempDir: Path) {
        val result = installer(tempDir).install(AgentTool.CLAUDE_CODE, isGlobal = false)
        assertThat(result.path.parent.toFile().isDirectory).isTrue()
    }

    @Test
    fun `Claude Code frontmatter contains allowed-tools`(@TempDir tempDir: Path) {
        val result = installer(tempDir).install(AgentTool.CLAUDE_CODE, isGlobal = false)
        assertThat(result.path.toFile().readText()).contains("allowed-tools")
    }

    @Test
    fun `OpenCode frontmatter does not contain allowed-tools`(@TempDir tempDir: Path) {
        val result = installer(tempDir).install(AgentTool.OPENCODE, isGlobal = false)
        assertThat(result.path.toFile().readText()).doesNotContain("allowed-tools")
    }

    @Test
    fun `GENERIC frontmatter does not contain allowed-tools`(@TempDir tempDir: Path) {
        val result = installer(tempDir).install(AgentTool.GENERIC, isGlobal = false)
        assertThat(result.path.toFile().readText()).doesNotContain("allowed-tools")
    }

    @Test
    fun `frontmatter contains name ez-rag`(@TempDir tempDir: Path) {
        val result = installer(tempDir).install(AgentTool.CLAUDE_CODE, isGlobal = false)
        assertThat(result.path.toFile().readText()).contains("name: ez-rag")
    }

    @Test
    fun `frontmatter contains non-empty description`(@TempDir tempDir: Path) {
        val result = installer(tempDir).install(AgentTool.CLAUDE_CODE, isGlobal = false)
        val content = result.path.toFile().readText()
        assertThat(content).containsPattern("description:\\s+\\S")
    }

    @Test
    fun `written SKILL dot MD contains non-empty skill body text below frontmatter`(@TempDir tempDir: Path) {
        val result = installer(tempDir).install(AgentTool.CLAUDE_CODE, isGlobal = false)
        val content = result.path.toFile().readText()
        val endOfFrontmatter = content.indexOf("---", 3) + 3
        val body = content.substring(endOfFrontmatter).trim()
        assertThat(body).isNotEmpty()
    }

    @Test
    fun `CLAUDE_CODE global installs to home dot-claude skills ez-rag SKILL dot MD`(@TempDir tempDir: Path) {
        val home = tempDir.resolve("home")
        val result = SkillInstaller(projectDir = tempDir, homeDir = home).install(AgentTool.CLAUDE_CODE, isGlobal = true)
        assertThat(result.path).isEqualTo(home.resolve(".claude/skills/ez-rag/SKILL.md"))
    }

    @Test
    fun `OPENCODE global installs to home dot-config opencode skills ez-rag SKILL dot MD`(@TempDir tempDir: Path) {
        val home = tempDir.resolve("home")
        val result = SkillInstaller(projectDir = tempDir, homeDir = home).install(AgentTool.OPENCODE, isGlobal = true)
        assertThat(result.path).isEqualTo(home.resolve(".config/opencode/skills/ez-rag/SKILL.md"))
    }

    @Test
    fun `GENERIC global installs to home dot-agents skills ez-rag SKILL dot MD`(@TempDir tempDir: Path) {
        val home = tempDir.resolve("home")
        val result = SkillInstaller(projectDir = tempDir, homeDir = home).install(AgentTool.GENERIC, isGlobal = true)
        assertThat(result.path).isEqualTo(home.resolve(".agents/skills/ez-rag/SKILL.md"))
    }
}
