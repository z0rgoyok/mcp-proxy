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
    fun `missing journal returns empty list`() {
        assertEquals(emptyList(), useCase.execute(stateDirectory = createTempDirectory(), limit = 2))
    }
}
