package dev.mcp.proxy.infrastructure.logging

import java.nio.file.Path
import java.time.Instant

fun interface ProxyEventLogger {
    fun log(event: ProxyLogEvent)
}

sealed interface ProxyLogEvent {
    val timestamp: Instant
    val scenario: String

    data class Started(
        override val timestamp: Instant,
        override val scenario: String,
        val bindHost: String,
        val port: Int,
        val upstreamBaseUrl: String,
        val stateDirectory: Path,
    ) : ProxyLogEvent

    data class RequestHandled(
        override val timestamp: Instant,
        override val scenario: String,
        val method: String,
        val path: String,
        val mode: String,
        val status: Int,
        val fixture: String?,
        val upstreamUrl: String?,
        val requestBodyFile: String?,
        val responseBodyFile: String?,
        val bodyMode: String? = null,
        val delayMillis: Long? = null,
        val timeoutMillis: Long? = null,
        val effectiveDelayMillis: Long? = null,
    ) : ProxyLogEvent
}

class StdoutProxyEventLogger(
    private val writeLine: (String) -> Unit = ::println,
) : ProxyEventLogger {
    override fun log(event: ProxyLogEvent) {
        writeLine(format(event))
    }

    fun format(event: ProxyLogEvent): String {
        return when (event) {
            is ProxyLogEvent.Started -> event.formatStarted()
            is ProxyLogEvent.RequestHandled -> event.formatRequestHandled()
        }
    }

    private fun ProxyLogEvent.Started.formatStarted(): String {
        return listOf(
            "$timestamp | INFO  | START   | proxy listening",
            "scenario=$scenario",
            "bind=$bindHost:$port",
            "upstream=$upstreamBaseUrl",
            "state=$stateDirectory",
        ).joinToString(separator = " | ")
    }

    private fun ProxyLogEvent.RequestHandled.formatRequestHandled(): String {
        return buildList {
            add("$timestamp | INFO  | ${mode.eventName()} | $method $path -> $status")
            add("scenario=$scenario")
            add("mode=$mode")
            fixture?.let { add("fixture=$it") }
            bodyMode?.let { add("bodyMode=$it") }
            delayMillis?.let { add("delayMillis=$it") }
            timeoutMillis?.let { add("timeoutMillis=$it") }
            effectiveDelayMillis?.let { add("effectiveDelayMillis=$it") }
            upstreamUrl?.let { add("upstream=$it") }
            requestBodyFile?.let { add("requestBody=$it") }
            responseBodyFile?.let { add("responseBody=$it") }
        }.joinToString(separator = " | ")
    }

    private fun String.eventName(): String {
        return when (this) {
            "mock" -> "MOCK    "
            "passthrough" -> "PASS    "
            "state" -> "STATE   "
            else -> uppercase().take(MAX_EVENT_NAME_LENGTH).padEnd(MAX_EVENT_NAME_LENGTH)
        }
    }

    private companion object {
        const val MAX_EVENT_NAME_LENGTH = 8
    }
}
