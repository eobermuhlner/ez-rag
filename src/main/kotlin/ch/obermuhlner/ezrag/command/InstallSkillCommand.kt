package ch.obermuhlner.ezrag.command

import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.PrintWriter
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable

@Command(
    name = "install-skill",
    mixinStandardHelpOptions = true,
    description = ["Install the ez-rag skill for your AI coding tool."]
)
@Component
class InstallSkillCommand(
    private val cwdOverride: Path? = null,
    private val homeOverride: Path? = null,
    private val outputWriter: PrintWriter = PrintWriter(System.out, true),
    isGlobal: Boolean = false,
    explicitToolNames: List<String> = emptyList(),
) : Callable<Int> {

    @Option(names = ["--global"], description = ["Install into the user's home-level skill directory."])
    private var globalFlag: Boolean = isGlobal

    @Option(names = ["--tool"], description = ["Install for a specific tool (repeatable). Valid: claude-code, opencode, generic."])
    private var toolNames: List<String> = explicitToolNames

    override fun call(): Int {
        val projectDir = cwdOverride ?: Paths.get("").toAbsolutePath()
        val homeDir = homeOverride ?: Paths.get(System.getProperty("user.home"))

        val parsedExplicitTools: List<AgentTool>
        if (toolNames.isNotEmpty()) {
            val resolved = mutableListOf<AgentTool>()
            for (name in toolNames) {
                val tool = AgentTool.fromName(name)
                if (tool == null) {
                    outputWriter.println("Error: unknown tool '$name'")
                    return 1
                }
                resolved.add(tool)
            }
            parsedExplicitTools = resolved
        } else {
            parsedExplicitTools = emptyList()
        }

        val detector = AgentToolDetector()
        val tools = detector.detect(projectDir = projectDir, homeDir = homeDir, explicitTools = parsedExplicitTools)

        val isGenericFallback = parsedExplicitTools.isEmpty() && tools == listOf(AgentTool.GENERIC)
        if (isGenericFallback) {
            outputWriter.println("No known agentic coding tools detected, installing generic skill.")
        }

        val installer = SkillInstaller(projectDir = projectDir, homeDir = homeDir)
        for (tool in tools) {
            try {
                val result = installer.install(tool, isGlobal = globalFlag)
                val action = if (result.wasUpdated) "Updated" else "Installed"
                val basePath = if (globalFlag) homeDir else projectDir
                val relPath = try { basePath.relativize(result.path) } catch (_: Exception) { result.path }
                outputWriter.println("$action ez-rag skill for ${tool.toolName}: $relPath")
            } catch (e: Exception) {
                outputWriter.println("Error: ${e.message}")
                return 1
            }
        }

        return 0
    }
}
