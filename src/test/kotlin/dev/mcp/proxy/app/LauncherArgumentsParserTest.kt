package dev.mcp.proxy.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LauncherArgumentsParserTest {
    private val parser = LauncherArgumentsParser()

    @Test
    fun `mcp mode parses`() {
        assertEquals(
            LauncherCommand.Mcp(
                host = "127.0.0.1",
                port = 18082,
            ),
            parser.parse(listOf("mcp", "--host", "127.0.0.1", "--port", "18082")),
        )
    }

    @Test
    fun `runtime mode parses runtime options`() {
        val command = parser.parse(
            listOf(
                "runtime",
                "demo",
                "--proxy-port",
                "19090",
                "--upstream-base-url",
                "https://backend.example",
                "--upstream-proxy-url",
                "http://host.docker.internal:8888",
                "--mirror-mock-requests",
                "true",
                "--mirror-base-url",
                "http://127.0.0.1:18081/__proxy_mirror",
                "--state-dir",
                "tmp/state",
            ),
        )

        assertEquals(
            LauncherCommand.Runtime(
                scenarioName = "demo",
                proxyPort = 19090,
                upstreamBaseUrl = "https://backend.example",
                upstreamProxyUrl = "http://host.docker.internal:8888",
                mirrorMockRequests = true,
                mirrorBaseUrl = "http://127.0.0.1:18081/__proxy_mirror",
                stateDirectory = java.nio.file.Path.of("tmp/state"),
                overrides = emptyList(),
            ),
            command,
        )
    }

    @Test
    fun `runtime mode parses scenario overrides`() {
        val command = parser.parse(
            listOf(
                "runtime",
                "demo",
                "--override",
                "GET:/v1/resource=demo/empty.json",
            ),
        )

        assertEquals(
            "demo/empty.json",
            (command as LauncherCommand.Runtime).overrides.single().fixture,
        )
    }

    @Test
    fun `runtime mode requires scenario`() {
        assertFailsWith<IllegalArgumentException> {
            parser.parse(listOf("runtime"))
        }
    }

    @Test
    fun `user-facing commands are not exposed`() {
        listOf("help", "run", "start", "status", "stop", "scenario", "journal", "android", "ca").forEach { command ->
            assertFailsWith<IllegalArgumentException> {
                parser.parse(listOf(command))
            }
        }
    }
}
