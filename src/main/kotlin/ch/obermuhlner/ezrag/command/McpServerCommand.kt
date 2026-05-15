package ch.obermuhlner.ezrag.command

import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import java.util.concurrent.Callable

@Command(
    name = "mcp-server",
    mixinStandardHelpOptions = true,
    description = ["Start the MCP server."]
)
@Component
class McpServerCommand : Callable<Int> {
    override fun call(): Int {
        println("not yet implemented")
        return 0
    }
}
