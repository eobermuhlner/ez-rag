package ch.obermuhlner.ezrag.command

import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import java.util.concurrent.Callable

@Command(
    name = "query",
    mixinStandardHelpOptions = true,
    description = ["Query the vector store using RAG."]
)
@Component
class QueryCommand : Callable<Int> {
    override fun call(): Int {
        println("not yet implemented")
        return 0
    }
}
