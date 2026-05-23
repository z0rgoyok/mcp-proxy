package dev.mcp.proxy.application

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadJournalTailUseCaseTest {
    private val useCase = ReadJournalTailUseCase()

    @Test
    fun `returns last journal lines`() {
        val stateDirectory = createTempDirectory()
        val journalFile = stateDirectory.resolve("journal/events.jsonl")
        Files.createDirectories(journalFile.parent)
        Files.writeString(journalFile, "one\ntwo\nthree\n")

        assertEquals(
            listOf("two", "three"),
            useCase.execute(stateDirectory = stateDirectory, limit = 2),
        )
    }

    @Test
    fun `returns all journal lines when file is smaller than limit`() {
        val stateDirectory = createTempDirectory()
        val journalFile = stateDirectory.resolve("journal/events.jsonl")
        Files.createDirectories(journalFile.parent)
        Files.writeString(journalFile, "one\ntwo\n")

        assertEquals(
            listOf("one", "two"),
            useCase.execute(stateDirectory = stateDirectory, limit = 20),
        )
    }

    @Test
    fun `returns utf8 lines across small read blocks`() {
        val stateDirectory = createTempDirectory()
        val journalFile = stateDirectory.resolve("journal/events.jsonl")
        Files.createDirectories(journalFile.parent)
        Files.writeString(journalFile, "один\r\nдва\nтри\n")
        val reader = BoundedFileTailReader(blockSize = 5)
        val useCase = ReadJournalTailUseCase(tailReader = reader)

        assertEquals(
            listOf("два", "три"),
            useCase.execute(stateDirectory = stateDirectory, limit = 2),
        )
    }

    @Test
    fun `empty journal returns empty list`() {
        val stateDirectory = createTempDirectory()
        val journalFile = stateDirectory.resolve("journal/events.jsonl")
        Files.createDirectories(journalFile.parent)
        Files.writeString(journalFile, "")

        assertEquals(emptyList(), useCase.execute(stateDirectory = stateDirectory, limit = 2))
    }

    @Test
    fun `missing journal returns empty list`() {
        assertEquals(emptyList(), useCase.execute(stateDirectory = createTempDirectory(), limit = 2))
    }

    @Test
    fun `limit is capped to safe maximum`() {
        val stateDirectory = createTempDirectory()
        val journalFile = stateDirectory.resolve("journal/events.jsonl")
        Files.createDirectories(journalFile.parent)
        Files.writeString(
            journalFile,
            (1..600).joinToString(separator = "\n", postfix = "\n") { index -> "line-$index" },
        )

        val lines = useCase.execute(stateDirectory = stateDirectory, limit = 10_000)

        assertEquals(500, lines.size)
        assertEquals("line-101", lines.first())
        assertEquals("line-600", lines.last())
    }
}
