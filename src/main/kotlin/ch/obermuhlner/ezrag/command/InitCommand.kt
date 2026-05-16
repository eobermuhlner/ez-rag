package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.config.GitIgnoreUpdater
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
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
    private val outputWriter: PrintWriter = PrintWriter(System.out, true),
) : Callable<Int> {

    companion object {
        private const val VECTOR_STORE_ENTRY = ".ez-rag/vector-store.json"
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

        return 0
    }
}
