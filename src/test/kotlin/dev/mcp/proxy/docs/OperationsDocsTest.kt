package dev.mcp.proxy.docs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains

class OperationsDocsTest {
    @Test
    fun `runbook documents setup troubleshooting and safety`() {
        val runbook = Files.readString(Path.of("RUNBOOK.md"))

        assertContains(runbook, "Normal Workflow")
        assertContains(runbook, "State")
        assertContains(runbook, "Logs")
        assertContains(runbook, "Checks")
        assertContains(runbook, "journal")
        assertContains(runbook, "docker compose")
        assertContains(runbook, "mcp-proxy")
        assertContains(runbook, "127.0.0.1:18081")
                assertContains(runbook, "runtime.json")
    }
}
