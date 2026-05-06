package dev.mcp.proxy.infrastructure.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.net.ServerSocket
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import dev.mcp.proxy.domain.ProxyPort
import dev.mcp.proxy.domain.ProxyRuntimeSettings
import dev.mcp.proxy.domain.ScenarioName
import dev.mcp.proxy.domain.UpstreamBaseUrl
import dev.mcp.proxy.domain.UpstreamProxyUrl

class LocalProxyRuntimeControllerTest {
    @Test
    fun `start writes runtime state and stop removes it`() = kotlinx.coroutines.test.runTest {
        val stateDirectory = createTempDirectory()
        val rootDirectory = createTempDirectory()
        createScenarioFiles(rootDirectory)
        val controller = LocalProxyRuntimeController(
            json = Json {
                prettyPrint = true
            },
            rootDirectory = rootDirectory,
        )
        val settings = ProxyRuntimeSettings(
            scenarioName = ScenarioName("demo"),
            proxyPort = ProxyPort(freePort()),
            upstreamBaseUrl = UpstreamBaseUrl("https://backend.example"),
            stateDirectory = stateDirectory,
            overrides = emptyList(),
        )

        val started = controller.start(settings)

        assertTrue(started.running)
        assertTrue(Files.exists(stateDirectory.resolve("runtime.json")))

        val stopped = controller.stop(stateDirectory)

        assertFalse(stopped.running)
        assertFalse(Files.exists(stateDirectory.resolve("runtime.json")))
    }

    @Test
    fun `passthrough runtime stays running while scenario is activated and disabled`() = kotlinx.coroutines.test.runTest {
        val stateDirectory = createTempDirectory()
        val rootDirectory = createTempDirectory()
        createScenarioFiles(rootDirectory)
        val controller = LocalProxyRuntimeController(
            json = Json {
                prettyPrint = true
            },
            rootDirectory = rootDirectory,
        )
        val proxyPort = ProxyPort(freePort())

        val passthrough = controller.startPassthrough(
            proxyPort = proxyPort,
            upstreamBaseUrl = UpstreamBaseUrl("https://backend.example"),
            stateDirectory = stateDirectory,
        )
        val active = controller.start(
            ProxyRuntimeSettings(
                scenarioName = ScenarioName("demo"),
                proxyPort = proxyPort,
                upstreamBaseUrl = UpstreamBaseUrl("https://backend.example"),
                stateDirectory = stateDirectory,
            ),
        )
        val disabled = controller.disableScenario(stateDirectory)

        assertTrue(passthrough.running)
        assertTrue(active.running)
        assertTrue(disabled.running)
        val state = Files.readString(stateDirectory.resolve("runtime.json"))
        assertContains(state, """"proxyPort": ${proxyPort.value}""")
        assertContains(state, """"upstreamBaseUrl": "https://backend.example"""")
        assertContains(state, """"running": true""")

        controller.stop(stateDirectory)
    }

    @Test
    fun `scenario activation keeps existing proxy traffic settings when they are omitted`() = kotlinx.coroutines.test.runTest {
        val stateDirectory = createTempDirectory()
        val rootDirectory = createTempDirectory()
        createScenarioFiles(rootDirectory)
        val controller = LocalProxyRuntimeController(
            json = Json {
                prettyPrint = true
            },
            rootDirectory = rootDirectory,
        )
        val proxyPort = ProxyPort(freePort())

        controller.startPassthrough(
            proxyPort = proxyPort,
            upstreamBaseUrl = UpstreamBaseUrl("https://backend.example"),
            upstreamProxyUrl = UpstreamProxyUrl("http://host.docker.internal:8888"),
            mirrorMockRequests = true,
            stateDirectory = stateDirectory,
        )

        controller.start(
            ProxyRuntimeSettings(
                scenarioName = ScenarioName("demo"),
                proxyPort = proxyPort,
                upstreamBaseUrl = UpstreamBaseUrl("https://backend.example"),
                stateDirectory = stateDirectory,
            ),
        )

        val state = Files.readString(stateDirectory.resolve("runtime.json"))
        assertContains(state, """"scenario": "demo"""")
        assertContains(state, """"upstreamProxyUrl": "http://host.docker.internal:8888"""")
        assertContains(state, """"mirrorMockRequests": true""")

        controller.stop(stateDirectory)
    }

    private fun createScenarioFiles(rootDirectory: Path) {
        val scenariosDirectory = rootDirectory.resolve("scenarios")
        val fixturesDirectory = rootDirectory.resolve("fixtures")
        Files.createDirectories(scenariosDirectory)
        Files.createDirectories(fixturesDirectory)
        Files.writeString(
            scenariosDirectory.resolve("demo.json"),
            """
                {
                  "name": "demo",
                  "rules": [
                    {
                      "method": "GET",
                      "path": "/health",
                      "fixture": "health.json"
                    }
                  ]
                }
            """.trimIndent(),
        )
        Files.writeString(fixturesDirectory.resolve("health.json"), """{"status":"ok"}""")
    }

    private fun freePort(): Int {
        return ServerSocket(0).use { socket -> socket.localPort }
    }
}
