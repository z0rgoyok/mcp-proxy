package dev.mcp.proxy.app

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import dev.mcp.proxy.domain.ProxyRuntimeState

class RuntimeStateOutputTest {
    @Test
    fun `formats runtime state as single readable operator line`() {
        val output = RuntimeStateOutput().format(
            ProxyRuntimeState(
                running = true,
                stateDirectory = Path.of("/app/var/state"),
                message = "Mock proxy started",
            ),
        )

        assertEquals(
            "runtime | RUNNING | state=/app/var/state | message=Mock proxy started",
            output,
        )
    }
}
