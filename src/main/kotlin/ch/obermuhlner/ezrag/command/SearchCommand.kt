package ch.obermuhlner.ezrag.command

import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.util.concurrent.Callable

@Command(
    name = "search",
    mixinStandardHelpOptions = true,
    description = ["Search the vector store for similar documents."]
)
@Component
class SearchCommand : Callable<Int> {

    @Option(names = ["--question", "-q"], description = ["Question to search for. Reads from stdin if omitted."])
    var question: String? = null

    override fun call(): Int {
        println("not yet implemented")
        return 0
    }
}
