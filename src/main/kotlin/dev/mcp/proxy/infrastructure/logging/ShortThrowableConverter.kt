package dev.mcp.proxy.infrastructure.logging

import ch.qos.logback.classic.pattern.ThrowableHandlingConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy

class ShortThrowableConverter : ThrowableHandlingConverter() {
    override fun convert(event: ILoggingEvent): String {
        val throwable = event.throwableProxy ?: return ""
        val chain = throwable.chain()
        if (chain.any { item -> item.contains(CERTIFICATE_UNKNOWN, ignoreCase = true) }) {
            return " | причина: клиент не доверяет CA proxy; установи или обнови сертификат в эмуляторе"
        }
        val root = chain.lastOrNull().orEmpty()
        return if (root.isBlank()) "" else " | причина: $root"
    }

    private fun IThrowableProxy.chain(): List<String> {
        val result = mutableListOf<String>()
        var current: IThrowableProxy? = this
        while (current != null) {
            result += listOfNotNull(current.className, current.message)
                .joinToString(": ")
            current = current.cause
        }
        return result
    }

    private companion object {
        const val CERTIFICATE_UNKNOWN = "certificate_unknown"
    }
}
