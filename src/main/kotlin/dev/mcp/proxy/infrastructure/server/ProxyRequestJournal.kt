package dev.mcp.proxy.infrastructure.server

import io.ktor.http.HttpHeaders
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ProxyRequestJournal(
    private val json: Json = Json {
        prettyPrint = false
        encodeDefaults = true
    },
    private val retention: JournalRetention = JournalRetention(),
) {
    private val eventCounter = AtomicInteger()

    fun journalFile(stateDirectory: Path): Path {
        return stateDirectory.resolve(JOURNAL_DIRECTORY).resolve(JOURNAL_FILE)
    }

    fun writeEvent(
        journalFile: Path,
        event: JournalEvent,
    ) {
        Files.writeString(
            journalFile,
            json.encodeToString(JournalEvent.serializer(), event) + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
        val eventNumber = eventCounter.incrementAndGet()
        if (retention.shouldRun(eventNumber)) {
            retention.enforce(journalFile)
        }
    }

    fun storeBody(
        stateDirectory: Path,
        body: ByteArray,
        suffix: String,
    ): String? {
        if (body.isEmpty()) {
            return null
        }
        val bodiesDirectory = stateDirectory.resolve(BODIES_DIRECTORY)
        Files.createDirectories(bodiesDirectory)
        val bodyFile = bodiesDirectory.resolve("${UUID.randomUUID()}-$suffix")
        Files.write(bodyFile, body)
        return stateDirectory.relativize(bodyFile).toString()
    }

    @Serializable
    data class JournalEvent(
        val timestamp: String,
        val method: String,
        val path: String,
        val uri: String,
        val scenario: String,
        val mode: String,
        val status: Int,
        val fixture: String?,
        val requestBodyFile: String?,
        val responseBodyFile: String?,
        val requestBodyBytes: Long? = null,
        val responseBodyBytes: Long? = null,
        val requestHeaders: Map<String, List<String>> = emptyMap(),
        val bodyMode: String? = null,
        val delayMillis: Long? = null,
        val timeoutMillis: Long? = null,
        val effectiveDelayMillis: Long? = null,
    )

    fun redactRequestHeaders(headers: Set<Map.Entry<String, List<String>>>): Map<String, List<String>> {
        return headers.associate { (name, values) ->
            name to redactHeaderValues(name, values)
        }
    }

    fun redactRequestHeaders(headers: Map<String, String>): Map<String, List<String>> {
        return headers.mapValues { (name, value) ->
            redactHeaderValues(name, listOf(value))
        }
    }

    private fun redactHeaderValues(name: String, values: List<String>): List<String> {
        return if (isSensitiveHeader(name)) {
            values.map { REDACTED_HEADER_VALUE }
        } else {
            values
        }
    }

    companion object {
        private const val REDACTED_HEADER_VALUE = "[REDACTED]"
        private val SENSITIVE_HEADER_NAME_FRAGMENTS = listOf(
            "authorization",
            "api-key",
            "apikey",
            "api_key",
            "cookie",
            "secret",
            "token",
        )
        const val JOURNAL_DIRECTORY = "journal"
        const val JOURNAL_FILE = "events.jsonl"
        const val BODIES_DIRECTORY_NAME = "bodies"
        const val BODIES_DIRECTORY = "$JOURNAL_DIRECTORY/$BODIES_DIRECTORY_NAME"
        val SKIPPED_UPSTREAM_HEADERS = setOf(
            HttpHeaders.Connection.lowercase(),
            HttpHeaders.ContentLength.lowercase(),
            HttpHeaders.Host.lowercase(),
            HttpHeaders.TransferEncoding.lowercase(),
            HttpHeaders.Upgrade.lowercase(),
        )

        private fun isSensitiveHeader(name: String): Boolean {
            val normalized = name.lowercase()
            return SENSITIVE_HEADER_NAME_FRAGMENTS.any(normalized::contains)
        }
    }
}
