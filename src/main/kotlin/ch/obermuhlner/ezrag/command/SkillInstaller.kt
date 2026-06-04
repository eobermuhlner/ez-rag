package ch.obermuhlner.ezrag.command

import java.nio.file.Files
import java.nio.file.Path

data class SkillInstallResult(val path: Path, val wasUpdated: Boolean)

class SkillInstaller(
    private val projectDir: Path,
    private val homeDir: Path,
) {
    fun install(tool: AgentTool, isGlobal: Boolean): SkillInstallResult {
        val bodyStream = javaClass.getResourceAsStream("/skills/ez-rag-skill-body.md")
            ?: throw IllegalStateException("Skill body resource not found: /skills/ez-rag-skill-body.md")
        val skillBody = bodyStream.bufferedReader().readText()

        val content = buildFrontmatter(tool) + "\n\n" + skillBody

        val path = resolvePath(tool, isGlobal)
        val wasUpdated = path.toFile().exists()

        Files.createDirectories(path.parent)
        Files.writeString(path, content)

        return SkillInstallResult(path, wasUpdated)
    }

    private fun buildFrontmatter(tool: AgentTool): String {
        val lines = mutableListOf(
            "---",
            "name: ez-rag",
            "description: >",
            "  Maintain and query a local document knowledge base using ez-rag. Use this skill whenever",
            "  the user wants to index files or a directory so Claude can answer questions from them,",
            "  search for relevant information across ingested documents, check what documents are",
            "  already in the store, or asks \"what do the docs say about X?\". Trigger on: \"ingest these",
            "  files\", \"add to the knowledge base\", \"search for X in the docs\", \"what does the",
            "  README say about Y?\", \"index this folder\", or any document-retrieval or RAG workflow.",
            "  Do NOT use this for the `ez-rag mcp-server` subcommand.",
        )
        if (tool == AgentTool.CLAUDE_CODE) {
            lines.add("allowed-tools: [Bash]")
        }
        lines.add("---")
        return lines.joinToString("\n")
    }

    private fun resolvePath(tool: AgentTool, isGlobal: Boolean): Path = when (tool) {
        AgentTool.CLAUDE_CODE -> if (isGlobal) {
            homeDir.resolve(".claude/skills/ez-rag/SKILL.md")
        } else {
            projectDir.resolve(".claude/skills/ez-rag/SKILL.md")
        }
        AgentTool.OPENCODE -> if (isGlobal) {
            homeDir.resolve(".config/opencode/skills/ez-rag/SKILL.md")
        } else {
            projectDir.resolve(".agents/skills/ez-rag/SKILL.md")
        }
        AgentTool.GENERIC -> if (isGlobal) {
            homeDir.resolve(".agents/skills/ez-rag/SKILL.md")
        } else {
            projectDir.resolve(".agents/skills/ez-rag/SKILL.md")
        }
    }
}
