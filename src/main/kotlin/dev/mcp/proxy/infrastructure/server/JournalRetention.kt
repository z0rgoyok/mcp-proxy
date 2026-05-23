package dev.mcp.proxy.infrastructure.server

import dev.mcp.proxy.application.BoundedFileTailReader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

data class JournalRetentionPolicy(
    val maxJournalBytes: Long = 16L * 1024 * 1024,
    val maxJournalEvents: Int = 10_000,
    val maxBodyFiles: Int = 5_000,
    val maintenanceIntervalEvents: Int = 100,
) {
    init {
        require(maxJournalBytes > 0)
        require(maxJournalEvents > 0)
        require(maxBodyFiles >= 0)
        require(maintenanceIntervalEvents > 0)
    }
}

class JournalRetention(
    private val policy: JournalRetentionPolicy = JournalRetentionPolicy(),
    private val tailReader: BoundedFileTailReader = BoundedFileTailReader(),
) {
    fun shouldRun(eventNumber: Int): Boolean {
        return eventNumber % policy.maintenanceIntervalEvents == 0
    }

    fun enforce(journalFile: Path) {
        trimJournal(journalFile)
        trimBodies(journalFile.parent.resolve(ProxyRequestJournal.BODIES_DIRECTORY_NAME))
    }

    private fun trimJournal(journalFile: Path) {
        if (!Files.isRegularFile(journalFile) || Files.size(journalFile) <= policy.maxJournalBytes) {
            return
        }
        val lines = tailReader.readLastLines(journalFile, policy.maxJournalEvents)
        val tempFile = Files.createTempFile(journalFile.parent, "${journalFile.fileName}.", ".tmp")
        try {
            Files.writeString(
                tempFile,
                lines.joinToString(separator = "\n", postfix = if (lines.isEmpty()) "" else "\n"),
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            moveReplacing(tempFile, journalFile)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    private fun trimBodies(bodiesDirectory: Path) {
        if (!Files.isDirectory(bodiesDirectory)) {
            return
        }
        val files = Files.list(bodiesDirectory).use { stream ->
            stream
                .filter(Files::isRegularFile)
                .sorted(compareBy<Path> { Files.getLastModifiedTime(it).toMillis() }.thenBy { it.fileName.toString() })
                .toList()
        }
        val excess = files.size - policy.maxBodyFiles
        if (excess <= 0) {
            return
        }
        files.take(excess).forEach(Files::deleteIfExists)
    }

    private fun moveReplacing(
        source: Path,
        target: Path,
    ) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
