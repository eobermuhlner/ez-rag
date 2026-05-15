package ch.obermuhlner.ezrag

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class LoggingTest {

    private val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    private val originalLevel = rootLogger.level

    @AfterEach
    fun restoreLogLevel() {
        rootLogger.level = originalLevel
    }

    @Test
    fun `verbose flag sets root logger level to DEBUG`() {
        val command = EzRagCommand()
        command.verbose = true
        command.call()

        assertThat(rootLogger.level).isEqualTo(Level.DEBUG)
    }

    @Test
    fun `without verbose flag root logger level is unchanged`() {
        val initialLevel = rootLogger.level
        val command = EzRagCommand()
        command.verbose = false
        command.call()

        assertThat(rootLogger.level).isEqualTo(initialLevel)
    }
}
