package ch.obermuhlner.ezrag.command

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AgentToolDetectorTest {

    private val fakeHome get() = Path.of("/nonexistent-home-for-task01")

    @Test
    fun `detects Claude Code when dot-claude dir exists in project`(@TempDir tempDir: Path) {
        tempDir.resolve(".claude").toFile().mkdir()
        val tools = AgentToolDetector().detect(projectDir = tempDir, homeDir = fakeHome)
        assertThat(tools).containsExactly(AgentTool.CLAUDE_CODE)
    }

    @Test
    fun `detects OpenCode when dot-opencode dir exists in project`(@TempDir tempDir: Path) {
        tempDir.resolve(".opencode").toFile().mkdir()
        val tools = AgentToolDetector().detect(projectDir = tempDir, homeDir = fakeHome)
        assertThat(tools).containsExactly(AgentTool.OPENCODE)
    }

    @Test
    fun `detects both Claude Code and OpenCode when both dirs exist`(@TempDir tempDir: Path) {
        tempDir.resolve(".claude").toFile().mkdir()
        tempDir.resolve(".opencode").toFile().mkdir()
        val tools = AgentToolDetector().detect(projectDir = tempDir, homeDir = fakeHome)
        assertThat(tools).containsExactlyInAnyOrder(AgentTool.CLAUDE_CODE, AgentTool.OPENCODE)
    }

    @Test
    fun `falls back to GENERIC when neither dot-claude nor dot-opencode exist`(@TempDir tempDir: Path) {
        val tools = AgentToolDetector().detect(projectDir = tempDir, homeDir = fakeHome)
        assertThat(tools).containsExactly(AgentTool.GENERIC)
    }

    @Test
    fun `dot-agents alone does not trigger OpenCode detection`(@TempDir tempDir: Path) {
        tempDir.resolve(".agents").toFile().mkdir()
        val tools = AgentToolDetector().detect(projectDir = tempDir, homeDir = fakeHome)
        assertThat(tools).containsExactly(AgentTool.GENERIC)
    }

    @Test
    fun `detects Claude Code when dot-claude dir exists in home`(@TempDir tempDir: Path) {
        val homeDir = tempDir.resolve("home")
        homeDir.resolve(".claude").toFile().mkdirs()
        val tools = AgentToolDetector().detect(projectDir = tempDir, homeDir = homeDir)
        assertThat(tools).containsExactly(AgentTool.CLAUDE_CODE)
    }

    @Test
    fun `detects OpenCode when dot-config-opencode dir exists in home`(@TempDir tempDir: Path) {
        val homeDir = tempDir.resolve("home")
        homeDir.resolve(".config/opencode").toFile().mkdirs()
        val tools = AgentToolDetector().detect(projectDir = tempDir, homeDir = homeDir)
        assertThat(tools).containsExactly(AgentTool.OPENCODE)
    }

    @Test
    fun `detects both tools when home markers for both exist`(@TempDir tempDir: Path) {
        val homeDir = tempDir.resolve("home")
        homeDir.resolve(".claude").toFile().mkdirs()
        homeDir.resolve(".config/opencode").toFile().mkdirs()
        val tools = AgentToolDetector().detect(projectDir = tempDir, homeDir = homeDir)
        assertThat(tools).containsExactlyInAnyOrder(AgentTool.CLAUDE_CODE, AgentTool.OPENCODE)
    }

    @Test
    fun `home dir dot-claude combined with project dir dot-opencode detects both tools`(@TempDir tempDir: Path) {
        val homeDir = tempDir.resolve("home")
        homeDir.resolve(".claude").toFile().mkdirs()
        tempDir.resolve(".opencode").toFile().mkdir()
        val tools = AgentToolDetector().detect(projectDir = tempDir, homeDir = homeDir)
        assertThat(tools).containsExactlyInAnyOrder(AgentTool.CLAUDE_CODE, AgentTool.OPENCODE)
    }

    @Test
    fun `explicit tool list bypasses auto-detection`(@TempDir tempDir: Path) {
        val tools = AgentToolDetector().detect(
            projectDir = tempDir,
            homeDir = tempDir.resolve("home"),
            explicitTools = listOf(AgentTool.CLAUDE_CODE)
        )
        assertThat(tools).containsExactly(AgentTool.CLAUDE_CODE)
    }

    @Test
    fun `explicit multiple tools returned in order as-is`(@TempDir tempDir: Path) {
        val tools = AgentToolDetector().detect(
            projectDir = tempDir,
            homeDir = tempDir.resolve("home"),
            explicitTools = listOf(AgentTool.CLAUDE_CODE, AgentTool.OPENCODE)
        )
        assertThat(tools).containsExactly(AgentTool.CLAUDE_CODE, AgentTool.OPENCODE)
    }
}
