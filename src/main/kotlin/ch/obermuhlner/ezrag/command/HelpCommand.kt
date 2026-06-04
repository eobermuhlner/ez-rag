package ch.obermuhlner.ezrag.command

import org.springframework.stereotype.Component
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Spec
import java.io.PrintWriter
import java.util.concurrent.Callable

@Command(
    name = "help",
    mixinStandardHelpOptions = true,
    description = ["Display help for all subcommands or a specific subcommand."]
)
@Component
class HelpCommand(
    private val outputWriter: PrintWriter = PrintWriter(System.out, true),
    private val errorWriter: PrintWriter = PrintWriter(System.err, true),
) : Callable<Int> {

    @Spec
    private lateinit var spec: CommandSpec

    @CommandLine.Parameters(arity = "0..1", description = ["Subcommand name to show help for."])
    var subcommandName: String? = null

    override fun call(): Int {
        val out = spec.commandLine().out ?: outputWriter
        val err = spec.commandLine().err ?: errorWriter
        val name = subcommandName
        if (name != null) {
            return showSingleSubcommand(name, out, err)
        }
        return showAllSubcommands(out)
    }

    private fun showAllSubcommands(out: PrintWriter): Int {
        val parent = spec.parent() ?: spec
        out.print(renderHelp(parent.commandLine()))
        for ((cmdName, sub) in parent.subcommands()) {
            if (cmdName == "help") continue
            out.println("---")
            out.print(renderHelp(sub))
        }
        return 0
    }

    private fun showSingleSubcommand(name: String, out: PrintWriter, err: PrintWriter): Int {
        val parent = spec.parent() ?: spec
        val sub = parent.subcommands()[name]
        if (sub == null) {
            err.println("Unknown subcommand: $name")
            return 1
        }
        out.print(renderHelp(sub))
        return 0
    }

    private fun renderHelp(commandLine: CommandLine): String =
        CommandLine.Help.Ansi.AUTO.string(commandLine.usageMessage)
}
