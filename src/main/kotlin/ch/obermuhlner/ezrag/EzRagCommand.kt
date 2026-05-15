package ch.obermuhlner.ezrag

import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import java.util.concurrent.Callable

@Command(
    name = "ez-rag",
    mixinStandardHelpOptions = true,
    commandListHeading = "Subcommands:%n",
    description = ["A command-line tool for RAG (retrieval-augmented generation)."]
)
@Component
class EzRagCommand : Callable<Int> {

    override fun call(): Int = 0
}
