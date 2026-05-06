package dev.mcp.proxy.application

import java.nio.file.Files
import java.nio.file.Path

class ReadJournalTailUseCase {
    fun execute(
        stateDirectory: Path?,
        limit: Int?,
    ): List<String> {
        val resolvedStateDirectory = (stateDirectory ?: Path.of("var/state")).toAbsolutePath().normalize()
        val journalFile = resolvedStateDirectory.resolve("journal/events.jsonl")
        if (!Files.exists(journalFile)) {
            return emptyList()
        }
        return Files.readAllLines(journalFile).takeLast(limit ?: DEFAULT_LIMIT)
    }

    private companion object {
        const val DEFAULT_LIMIT = 20
    }
}
