package dev.mcp.proxy.performance

import dev.mcp.proxy.app.AppComponents
import dev.mcp.proxy.domain.ProxyPort
import dev.mcp.proxy.domain.ScenarioName
import dev.mcp.proxy.domain.UpstreamBaseUrl
import dev.mcp.proxy.domain.ca.CaManager
import dev.mcp.proxy.domain.ca.CaState
import dev.mcp.proxy.domain.scenario.MockRule
import dev.mcp.proxy.domain.scenario.MockScenario
import dev.mcp.proxy.domain.scenario.ScenarioRepository
import dev.mcp.proxy.infrastructure.mcp.GenericProxyMcpServer
import dev.mcp.proxy.infrastructure.server.MitmMockProxyServer
import dev.mcp.proxy.infrastructure.server.MockProxyServer
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class JournalPerformanceEvidenceTest {
    private val httpClient = HttpClient.newHttpClient()

    @Test
    fun `mcp journal tail reads bounded tail from large journal`() {
        val stateDirectory = createLargeJournalState()
        val server = GenericProxyMcpServer(
            components = AppComponents.create(
                rootDirectory = createScenarioRoot(),
                caManager = FakeCaManager,
            ),
        )

        val result = server.executeTool(
            name = "journal_tail",
            arguments = buildJsonObject {
                put("stateDirectory", JsonPrimitive(stateDirectory.toString()))
                put("limit", JsonPrimitive(20))
            },
        )

        assertContains(result, """"path":"/v1/items/24981"""")
        assertContains(result, """"path":"/v1/items/25000"""")
        assertFalse(result.contains("/v1/items/00001"))
        assertEquals(20, result.lineSequence().filter(String::isNotBlank).count())
    }

    @Test
    fun `ktor admin journal reads bounded tail from large journal`() {
        val stateDirectory = createLargeJournalState()
        val proxyPort = freePort()
        val proxy = MockProxyServer(
            scenarioRepository = EmptyScenarioRepository,
        ).start(
            scenario = MockScenario(name = "demo", rules = emptyList()),
            proxyPort = ProxyPort(proxyPort),
            upstreamBaseUrl = UpstreamBaseUrl("http://127.0.0.1:${freePort()}"),
            stateDirectory = stateDirectory,
        )

        try {
            val response = get("http://127.0.0.1:$proxyPort/admin/api/journal?limit=20")

            assertEquals(200, response.statusCode())
            assertContains(response.body(), """"count":20""")
            assertContains(response.body(), """"path":"/v1/items/25000"""")
            assertContains(response.body(), """"path":"/v1/items/24981"""")
            assertFalse(response.body().contains("/v1/items/00001"))
        } finally {
            proxy.stop(gracePeriodMillis = 0, timeoutMillis = 0)
        }
    }

    @Test
    fun `mitm admin journal reads bounded tail from large journal`() {
        val stateDirectory = createLargeJournalState()
        val proxyPort = freePort()
        val proxy = MitmMockProxyServer(
            scenarioRepository = EmptyScenarioRepository,
        ).start(
            activeScenario = null,
            proxyPort = ProxyPort(proxyPort),
            upstreamBaseUrl = UpstreamBaseUrl("http://127.0.0.1:${freePort()}"),
            stateDirectory = stateDirectory,
        )

        try {
            val response = get("http://127.0.0.1:$proxyPort/admin/api/journal?limit=20")

            assertEquals(200, response.statusCode())
            assertContains(response.body(), """"count":20""")
            assertContains(response.body(), """"path":"/v1/items/25000"""")
            assertContains(response.body(), """"path":"/v1/items/24981"""")
            assertFalse(response.body().contains("/v1/items/00001"))
        } finally {
            proxy.stop()
        }
    }

    private fun createLargeJournalState(): Path {
        val stateDirectory = createTempDirectory()
        val journalFile = stateDirectory.resolve("journal/events.jsonl")
        Files.createDirectories(journalFile.parent)
        Files.newBufferedWriter(journalFile).use { writer ->
            for (index in 1..LARGE_JOURNAL_EVENTS) {
                writer.appendLine(journalLine(index))
            }
        }
        return stateDirectory
    }

    private fun journalLine(index: Int): String {
        val padded = index.toString().padStart(5, '0')
        return """{"timestamp":"2026-04-28T09:00:00Z","method":"GET","path":"/v1/items/$padded","uri":"/v1/items/$padded","scenario":"demo","mode":"mock","status":200,"fixture":"items.json","requestBodyFile":null,"responseBodyFile":null,"requestBodyBytes":0,"responseBodyBytes":128}"""
    }

    private fun createScenarioRoot(): Path {
        val root = createTempDirectory()
        Files.createDirectories(root.resolve("scenarios"))
        Files.writeString(
            root.resolve("scenarios/demo.json"),
            """{"name":"demo","rules":[]}""",
        )
        return root
    }

    private fun get(url: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun freePort(): Int {
        return ServerSocket(0).use { socket -> socket.localPort }
    }

    private object EmptyScenarioRepository : ScenarioRepository {
        override fun list(): List<ScenarioName> = emptyList()

        override fun load(scenarioName: ScenarioName): MockScenario {
            return MockScenario(name = scenarioName.value, rules = emptyList())
        }

        override fun loadFixture(rule: MockRule): String {
            error("No fixtures are available in performance evidence tests")
        }
    }

    private object FakeCaManager : CaManager {
        override fun generate(): CaState {
            return CaState(message = "generated", certificatePath = "ca.pem")
        }

        override fun install(udid: String?): CaState {
            return CaState(message = "installed ${udid ?: "auto"}", certificatePath = "ca.pem")
        }
    }

    private companion object {
        const val LARGE_JOURNAL_EVENTS = 25_000
    }
}
