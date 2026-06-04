package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.config.GitIgnoreUpdater
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable

@Command(
    name = "init",
    mixinStandardHelpOptions = true,
    description = ["Initialize a .ez-rag/ workspace in the current directory."]
)
@Component
class InitCommand(
    private val cwdOverride: Path? = null,
    private val homeOverride: Path? = null,
    private val outputWriter: PrintWriter = PrintWriter(System.out, true),
    installSkill: Boolean = false,
    isGlobal: Boolean = false,
    explicitToolNames: List<String> = emptyList(),
) : Callable<Int> {

    @Option(names = ["--install-skill"], description = ["Install the ez-rag skill for your AI coding tool after init."])
    private var installSkillFlag: Boolean = installSkill

    @Option(names = ["--global"], description = ["Install skill into the user's home-level skill directory."])
    private var globalFlag: Boolean = isGlobal

    @Option(names = ["--tool"], description = ["Install skill for a specific tool (repeatable). Valid: claude-code, opencode, generic."])
    private var toolNames: List<String> = explicitToolNames

    companion object {
        private const val VECTOR_STORE_ENTRY = ".ez-rag/"
    }

    override fun call(): Int {
        val cwd = cwdOverride ?: Paths.get("").toAbsolutePath()
        val ezRagDir = cwd.resolve(".ez-rag")
        val alreadyExists = ezRagDir.toFile().isDirectory

        Files.createDirectories(ezRagDir)

        val gitIgnoreUpdater = GitIgnoreUpdater(outputWriter)
        gitIgnoreUpdater.ensureEntry(cwd.toFile(), VECTOR_STORE_ENTRY)

        if (alreadyExists) {
            outputWriter.println(".ez-rag/ already exists at ${ezRagDir.toAbsolutePath()}")
        } else {
            outputWriter.println("Initialized .ez-rag/ in ${ezRagDir.toAbsolutePath()}")
        }

        if (installSkillFlag) {
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
            val tools = detector.detect(projectDir = cwd, homeDir = homeDir, explicitTools = parsedExplicitTools)

            val isGenericFallback = parsedExplicitTools.isEmpty() && tools == listOf(AgentTool.GENERIC)
            if (isGenericFallback) {
                outputWriter.println("No known agentic coding tools detected, installing generic skill.")
            }

            val installer = SkillInstaller(projectDir = cwd, homeDir = homeDir)
            for (tool in tools) {
                try {
                    val result = installer.install(tool, isGlobal = globalFlag)
                    val action = if (result.wasUpdated) "Updated" else "Installed"
                    val basePath = if (globalFlag) homeDir else cwd
                    val relPath = try { basePath.relativize(result.path) } catch (_: Exception) { result.path }
                    outputWriter.println("$action ez-rag skill for ${tool.toolName}: $relPath")
                } catch (e: Exception) {
                    outputWriter.println("Error: ${e.message}")
                    return 1
                }
            }
        } else {
            outputWriter.println("Run 'ez-rag install-skill' to install the skill for your AI coding tool.")
        }

        return 0
    }
}
