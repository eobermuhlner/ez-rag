package ch.obermuhlner.ezrag.command

import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import java.util.concurrent.Callable

@Command(
    name = "status",
    mixinStandardHelpOptions = true,
    description = ["Show the status of the vector store."]
)
@Component
class StatusCommand : Callable<Int> {
    override fun call(): Int {
        println("not yet implemented")
        return 0
    }
}
