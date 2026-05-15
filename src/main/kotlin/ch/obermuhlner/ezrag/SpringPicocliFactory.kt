package ch.obermuhlner.ezrag

import org.springframework.context.ApplicationContext
import picocli.CommandLine

class SpringPicocliFactory(private val ctx: ApplicationContext) : CommandLine.IFactory {
    override fun <K : Any> create(cls: Class<K>): K = try {
        ctx.getBean(cls)
    } catch (_: Exception) {
        CommandLine.defaultFactory().create(cls)
    }
}
