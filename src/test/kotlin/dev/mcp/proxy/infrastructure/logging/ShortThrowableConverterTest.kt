package dev.mcp.proxy.infrastructure.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.LoggingEvent
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import org.slf4j.LoggerFactory

class ShortThrowableConverterTest {
    @Test
    fun `renders certificate unknown as short russian cause`() {
        val converter = ShortThrowableConverter().apply { start() }
        val error = SSLHandshakeException("(certificate_unknown) Received fatal alert: certificate_unknown")
        val event = LoggingEvent(
            LOGGER_NAME,
            LoggerFactory.getLogger(LOGGER_NAME) as Logger,
            Level.WARN,
            "Uncaught exception",
            error,
            emptyArray(),
        )

        val text = converter.convert(event)

        assertContains(text, "клиент не доверяет CA proxy")
        assertFalse("SSLHandshakeException" in text)
    }

    private companion object {
        const val LOGGER_NAME = "test"
    }
}
