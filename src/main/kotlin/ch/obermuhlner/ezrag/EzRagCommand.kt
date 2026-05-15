package ch.obermuhlner.ezrag

import ch.obermuhlner.ezrag.command.IngestCommand
import ch.obermuhlner.ezrag.command.McpServerCommand
import ch.obermuhlner.ezrag.command.QueryCommand
import ch.obermuhlner.ezrag.command.SearchCommand
import ch.obermuhlner.ezrag.command.ShellCommand
import ch.obermuhlner.ezrag.command.StatusCommand
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ScopeType
import java.util.concurrent.Callable

@Command(
    name = "ez-rag",
    mixinStandardHelpOptions = true,
    commandListHeading = "Subcommands:%n",
    description = ["A command-line tool for RAG (retrieval-augmented generation)."],
    subcommands = [
        IngestCommand::class,
        QueryCommand::class,
        SearchCommand::class,
        StatusCommand::class,
        McpServerCommand::class,
        ShellCommand::class,
    ]
)
@Component
class EzRagCommand : Callable<Int> {

    @Option(names = ["--verbose", "-v"], description = ["Enable verbose/debug logging."], scope = ScopeType.INHERIT)
    var verbose: Boolean = false

    override fun call(): Int = 0
}
