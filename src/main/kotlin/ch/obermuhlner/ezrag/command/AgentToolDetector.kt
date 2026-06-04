package ch.obermuhlner.ezrag.command

import java.nio.file.Path

class AgentToolDetector {
    fun detect(projectDir: Path, homeDir: Path, explicitTools: List<AgentTool> = emptyList()): List<AgentTool> {
        if (explicitTools.isNotEmpty()) {
            return explicitTools
        }

        val detected = mutableListOf<AgentTool>()

        if (projectDir.resolve(".claude").toFile().isDirectory || homeDir.resolve(".claude").toFile().isDirectory) {
            detected.add(AgentTool.CLAUDE_CODE)
        }
        if (projectDir.resolve(".opencode").toFile().isDirectory || homeDir.resolve(".config/opencode").toFile().isDirectory) {
            detected.add(AgentTool.OPENCODE)
        }

        return if (detected.isEmpty()) listOf(AgentTool.GENERIC) else detected
    }
}
