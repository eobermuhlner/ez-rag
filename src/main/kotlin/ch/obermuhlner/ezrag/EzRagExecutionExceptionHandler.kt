package ch.obermuhlner.ezrag

import picocli.CommandLine

class EzRagExecutionExceptionHandler(private val rootCommand: EzRagCommand) : CommandLine.IExecutionExceptionHandler {

    override fun handleExecutionException(
        ex: Exception,
        commandLine: CommandLine,
        parseResult: CommandLine.ParseResult,
    ): Int {
        val message = ex.message?.takeUnless { it.isBlank() }
            ?: "${ex::class.simpleName} (no message)"
        val err = commandLine.err
        err.println("Error: $message")
        err.println("Use --stack-trace for full details.")
        if (rootCommand.stackTrace) {
            ex.printStackTrace(err)
        }
        err.flush()
        return 1
    }
}
