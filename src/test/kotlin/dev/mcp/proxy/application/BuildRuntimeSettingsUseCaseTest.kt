package dev.mcp.proxy.application

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BuildRuntimeSettingsUseCaseTest {
    private val useCase = BuildRuntimeSettingsUseCase()

    @Test
    fun `builds default runtime settings`() {
        val settings = useCase.execute(
            scenarioName = "demo",
            proxyPort = null,
            upstreamBaseUrl = null,
            stateDirectory = null,
        )

        assertEquals("demo", settings.scenarioName.value)
        assertEquals(18081, settings.proxyPort.value)
        assertEquals("https://example.com", settings.upstreamBaseUrl.value)
        assertEquals("forbidden", settings.externalNetworkPolicy.value)
        assertEquals(null, settings.upstreamProxyUrl)
        assertEquals(null, settings.mirrorMockRequests)
        assertEquals(null, settings.mirrorBaseUrl)
        assertEquals(Path.of("var/state").toAbsolutePath().normalize(), settings.stateDirectory)
        assertEquals(emptyList(), settings.overrides)
    }

    @Test
    fun `builds upstream proxy debug settings`() {
        val settings = useCase.execute(
            scenarioName = "demo",
            proxyPort = null,
            upstreamBaseUrl = "https://backend.example",
            externalNetwork = "allowed",
            upstreamProxyUrl = "http://host.docker.internal:8888",
            mirrorMockRequests = true,
            mirrorBaseUrl = "http://127.0.0.1:18081/__proxy_mirror",
            stateDirectory = null,
        )

        assertEquals("allowed", settings.externalNetworkPolicy.value)
        assertEquals("http://host.docker.internal:8888", settings.upstreamProxyUrl?.value)
        assertEquals(true, settings.mirrorMockRequests)
        assertEquals("http://127.0.0.1:18081/__proxy_mirror", settings.mirrorBaseUrl?.value)
    }

    @Test
    fun `rejects invalid port`() {
        assertFailsWith<IllegalArgumentException> {
            useCase.execute(
                scenarioName = "demo",
                proxyPort = 70000,
                upstreamBaseUrl = null,
                stateDirectory = null,
            )
        }
    }

    @Test
    fun `rejects upstream without http scheme`() {
        assertFailsWith<IllegalArgumentException> {
            useCase.execute(
                scenarioName = "demo",
                proxyPort = null,
                upstreamBaseUrl = "example.com",
                stateDirectory = null,
            )
        }
    }

    @Test
    fun `rejects upstream proxy without http scheme`() {
        assertFailsWith<IllegalArgumentException> {
            useCase.execute(
                scenarioName = "demo",
                proxyPort = null,
                upstreamBaseUrl = null,
                upstreamProxyUrl = "https://host.docker.internal:8888",
                stateDirectory = null,
            )
        }
    }
}
