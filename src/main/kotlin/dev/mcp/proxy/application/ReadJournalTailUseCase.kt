package dev.mcp.proxy.application

import java.nio.file.Path

class ReadJournalTailUseCase(
    private val tailReader: BoundedFileTailReader = BoundedFileTailReader(),
) {
    fun execute(
        stateDirectory: Path?,
        limit: Int?,
    ): List<String> {
        val resolvedStateDirectory = (stateDirectory ?: Path.of("var/state")).toAbsolutePath().normalize()
        val journalFile = resolvedStateDirectory.resolve("journal/events.jsonl")
        val resolvedLimit = JournalTailLimit.normalize(limit, JournalTailLimit.MCP_DEFAULT)
        return tailReader.readLastLines(journalFile, resolvedLimit)
    }
}
