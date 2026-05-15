package ch.obermuhlner.ezrag

import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.ApplicationContext
import picocli.CommandLine
import kotlin.system.exitProcess

@SpringBootApplication
class EzRagApplication(
    private val applicationContext: ApplicationContext,
    private val ezRagCommand: EzRagCommand,
) : CommandLineRunner {

    override fun run(vararg args: String) {
        val factory = SpringPicocliFactory(applicationContext)
        val commandLine = CommandLine(ezRagCommand, factory)
        val exitCode = commandLine.execute(*args)
        exitProcess(exitCode)
    }
}

fun main(args: Array<String>) {
    runApplication<EzRagApplication>(*args)
}
