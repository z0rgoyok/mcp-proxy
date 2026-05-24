package dev.mcp.proxy.infrastructure.server

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProxyRequestJournalTest {
    @Test
    fun `redacts sensitive request headers and keeps diagnostic headers`() {
        val journal = ProxyRequestJournal()

        val headers = journal.redactRequestHeaders(
            linkedMapOf(
                "X-city" to "3",
                "X-Pfm" to "1111",
                "X-Delivery-Type" to "store:1111",
                "Authorization" to "Bearer secret",
                "X-Api-Key" to "api-secret",
                "Cookie" to "sid=secret",
            ),
        )

        assertEquals(listOf("3"), headers.getValue("X-city"))
        assertEquals(listOf("1111"), headers.getValue("X-Pfm"))
        assertEquals(listOf("store:1111"), headers.getValue("X-Delivery-Type"))
        assertEquals(listOf("[REDACTED]"), headers.getValue("Authorization"))
        assertEquals(listOf("[REDACTED]"), headers.getValue("X-Api-Key"))
        assertEquals(listOf("[REDACTED]"), headers.getValue("Cookie"))
    }

    @Test
    fun `retention trims journal to last events when file is too large`() {
        val stateDirectory = createTempDirectory()
        val retention = JournalRetention(
            policy = JournalRetentionPolicy(
                maxJournalBytes = 1,
                maxJournalEvents = 2,
                maintenanceIntervalEvents = 1,
            ),
        )
        val journal = ProxyRequestJournal(retention = retention)
        val journalFile = journal.journalFile(stateDirectory)
        Files.createDirectories(journalFile.parent)
        Files.writeString(
            journalFile,
            (1..4).joinToString(separator = "\n", postfix = "\n") { index ->
                eventLine(path = "/old-$index")
            },
        )

        journal.writeEvent(journalFile, event(path = "/new"))

        val retained = Files.readString(journalFile)
        assertFalse(retained.contains("/old-1"))
        assertFalse(retained.contains("/old-2"))
        assertFalse(retained.contains("/old-3"))
        assertTrue(retained.contains("/old-4"))
        assertTrue(retained.contains("/new"))
        assertEquals(2, retained.lineSequence().filter(String::isNotBlank).count())
    }

    @Test
    fun `retention deletes oldest body files over max count`() {
        val stateDirectory = createTempDirectory()
        val retention = JournalRetention(
            policy = JournalRetentionPolicy(
                maxBodyFiles = 2,
                maintenanceIntervalEvents = 1,
            ),
        )
        val journal = ProxyRequestJournal(retention = retention)
        val journalFile = journal.journalFile(stateDirectory)
        val bodiesDirectory = stateDirectory.resolve(ProxyRequestJournal.BODIES_DIRECTORY)
        Files.createDirectories(journalFile.parent)
        Files.createDirectories(bodiesDirectory)
        (1..4).forEach { index ->
            val file = bodiesDirectory.resolve("body-$index.json")
            Files.writeString(file, "$index")
            Files.setLastModifiedTime(file, FileTime.fromMillis(index.toLong()))
        }

        journal.writeEvent(journalFile, event(path = "/new"))

        val remaining = Files.list(bodiesDirectory).use { stream ->
            stream.map { path -> path.fileName.toString() }.sorted().toList()
        }
        assertEquals(listOf("body-3.json", "body-4.json"), remaining)
    }

    private fun event(path: String): ProxyRequestJournal.JournalEvent {
        return ProxyRequestJournal.JournalEvent(
            timestamp = "2026-04-28T09:00:00Z",
            method = "GET",
            path = path,
            uri = path,
            scenario = "demo",
            mode = "mock",
            status = 200,
            fixture = null,
            requestBodyFile = null,
            responseBodyFile = null,
            requestBodyBytes = 0,
            responseBodyBytes = 0,
        )
    }

    private fun eventLine(path: String): String {
        return """{"timestamp":"2026-04-28T09:00:00Z","method":"GET","path":"$path","uri":"$path","scenario":"demo","mode":"mock","status":200,"fixture":null,"requestBodyFile":null,"responseBodyFile":null,"requestBodyBytes":0,"responseBodyBytes":0}"""
    }
}
