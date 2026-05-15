package ch.obermuhlner.ezrag.command

import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import java.io.File
import java.util.concurrent.Callable

@Command(
    name = "ingest",
    mixinStandardHelpOptions = true,
    description = ["Ingest documents into the vector store."]
)
@Component
class IngestCommand : Callable<Int> {

    @Parameters(arity = "1..*", description = ["Files or directories to ingest."])
    var paths: List<File> = emptyList()

    override fun call(): Int {
        println("not yet implemented")
        return 0
    }
}
