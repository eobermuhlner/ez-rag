package ch.obermuhlner.ezrag.command

import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import java.util.concurrent.Callable

@Command(
    name = "ingest",
    mixinStandardHelpOptions = true,
    description = ["Ingest documents into the vector store."]
)
@Component
class IngestCommand : Callable<Int> {
    override fun call(): Int {
        println("not yet implemented")
        return 0
    }
}
