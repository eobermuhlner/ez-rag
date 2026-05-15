package ch.obermuhlner.ezrag.command

import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import java.util.concurrent.Callable

@Command(
    name = "search",
    mixinStandardHelpOptions = true,
    description = ["Search the vector store for similar documents."]
)
@Component
class SearchCommand : Callable<Int> {
    override fun call(): Int {
        println("not yet implemented")
        return 0
    }
}
