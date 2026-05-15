package ch.obermuhlner.ezrag.command

import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.util.concurrent.Callable

@Command(
    name = "query",
    mixinStandardHelpOptions = true,
    description = ["Query the vector store using RAG."]
)
@Component
class QueryCommand : Callable<Int> {

    @Option(names = ["--question", "-q"], description = ["Question to ask. Reads from stdin if omitted."])
    var question: String? = null

    override fun call(): Int {
        println("not yet implemented")
        return 0
    }
}
