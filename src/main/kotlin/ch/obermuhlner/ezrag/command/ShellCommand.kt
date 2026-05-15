package ch.obermuhlner.ezrag.command

import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import java.util.concurrent.Callable

@Command(
    name = "shell",
    mixinStandardHelpOptions = true,
    description = ["Start an interactive shell."]
)
@Component
class ShellCommand : Callable<Int> {
    override fun call(): Int {
        println("not yet implemented")
        return 0
    }
}
