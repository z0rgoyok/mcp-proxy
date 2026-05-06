package dev.mcp.proxy.infrastructure.mcp

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import dev.mcp.proxy.app.AppComponents
import dev.mcp.proxy.domain.ca.CaManager
import dev.mcp.proxy.domain.ca.CaState

class GenericProxyMcpServerTest {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Test
    fun `tools list exposes proxy controls`() {
        val server = mcpServer()

        val response = assertNotNull(
            server.handle("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}"""),
        )

        assertContains(response, "proxy_start")
        assertContains(response, "scenario_enable")
        assertContains(response, "scenario_disable")
        assertContains(response, "scenario_status")
        assertContains(response, "journal_tail")
        assertContains(response, "state_set")
        assertContains(response, "state_get")
        assertContains(response, "state_delete")
        assertContains(response, "ca_generate")
        assertContains(response, """"scenario"""")
        assertContains(response, """"proxyPort"""")
        assertContains(response, """"upstreamProxyUrl"""")
        assertContains(response, """"mirrorMockRequests"""")
        assertContains(response, """"mirrorBaseUrl"""")
    }

    @Test
    fun `state tools store list read and delete json values`() {
        val stateDirectory = createTempDirectory()
        val server = mcpServer()

        assertNotNull(
            server.handle(toolCall(id = 1, name = "state_set", arguments = """{"stateDirectory":"$stateDirectory","key":"session","value":"{\"step\":1}"}""")),
        )
        val keys = assertNotNull(
            server.handle(toolCall(id = 2, name = "state_list", arguments = """{"stateDirectory":"$stateDirectory"}""")),
        )
        val value = assertNotNull(
            server.handle(toolCall(id = 3, name = "state_get", arguments = """{"stateDirectory":"$stateDirectory","key":"session"}""")),
        )
        val deleted = assertNotNull(
            server.handle(toolCall(id = 4, name = "state_delete", arguments = """{"stateDirectory":"$stateDirectory","key":"session"}""")),
        )

        assertContains(keys, "session")
        assertContains(value, "\\\"step\\\":1")
        assertContains(deleted, "deleted=true")
    }

    @Test
    fun `proxy start and status keep runtime in mcp server`() {
        val root = createScenarioRoot()
        val stateDirectory = createTempDirectory()
        val server = mcpServer(rootDirectory = root)
        val port = freePort()

        server.handle(
            toolCall(
                id = 1,
                name = "proxy_start",
                arguments = """{"scenario":"demo","stateDirectory":"$stateDirectory","proxyPort":$port,"upstreamBaseUrl":"http://127.0.0.1:9"}""",
            ),
        )
        val response = assertNotNull(
            server.handle(
                toolCall(
                    id = 2,
                    name = "proxy_status",
                    arguments = """{"stateDirectory":"$stateDirectory"}""",
                ),
            ),
        )

        assertContains(response, "running=true")
        server.handle(
            toolCall(
                id = 3,
                name = "proxy_stop",
                arguments = """{"stateDirectory":"$stateDirectory"}""",
            ),
        )
    }

    @Test
    fun `proxy start accepts scenario argument`() {
        val root = createScenarioRoot()
        val stateDirectory = createTempDirectory()
        val server = mcpServer(rootDirectory = root)
        val port = freePort()

        val response = assertNotNull(
            server.handle(
                toolCall(
                    id = 1,
                    name = "proxy_start",
                    arguments = """{"scenario":"demo-alt","stateDirectory":"$stateDirectory","proxyPort":$port,"upstreamBaseUrl":"http://127.0.0.1:9"}""",
                ),
            ),
        )

        assertContains(response, "demo-alt")
        assertContains(
            Files.readString(stateDirectory.resolve("runtime.json")),
            """"scenario": "demo-alt"""",
        )
        server.handle(
            toolCall(
                id = 2,
                name = "proxy_stop",
                arguments = """{"stateDirectory":"$stateDirectory"}""",
            ),
        )
    }

    @Test
    fun `scenario tools switch active scenario without stopping runtime`() {
        val root = createScenarioRoot()
        val stateDirectory = createTempDirectory()
        val server = mcpServer(rootDirectory = root)
        val port = freePort()

        server.handle(
            toolCall(
                id = 1,
                name = "scenario_enable",
                arguments = """{"scenario":"demo","stateDirectory":"$stateDirectory","proxyPort":$port,"upstreamBaseUrl":"http://127.0.0.1:9"}""",
            ),
        )
        val active = assertNotNull(
            server.handle(
                toolCall(
                    id = 2,
                    name = "scenario_status",
                    arguments = """{"stateDirectory":"$stateDirectory"}""",
                ),
            ),
        )

        assertContains(active, "running=true")
        assertContains(active, "scenario=demo")

        server.handle(
            toolCall(
                id = 3,
                name = "scenario_disable",
                arguments = """{"stateDirectory":"$stateDirectory"}""",
            ),
        )
        val passthrough = assertNotNull(
            server.handle(
                toolCall(
                    id = 4,
                    name = "scenario_status",
                    arguments = """{"stateDirectory":"$stateDirectory"}""",
                ),
            ),
        )

        assertContains(passthrough, "running=true")
        assertContains(passthrough, "scenario=passthrough")
        server.handle(
            toolCall(
                id = 5,
                name = "proxy_stop",
                arguments = """{"stateDirectory":"$stateDirectory"}""",
            ),
        )
    }

    @Test
    fun `initialize returns mcp server info`() {
        val response = assertNotNull(
            mcpServer().handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""),
        )
        val result = json.parseToJsonElement(response).jsonObject.getValue("result").jsonObject

        assertContains(result.getValue("serverInfo").toString(), "mcp-proxy")
        assertContains(result.getValue("capabilities").toString(), "tools")
    }

    private fun mcpServer(
        rootDirectory: java.nio.file.Path = createScenarioRoot(),
    ): GenericProxyMcpServer {
        return GenericProxyMcpServer(
            components = AppComponents.create(
                rootDirectory = rootDirectory,
                caManager = FakeCaManager,
            ),
        )
    }

    private fun toolCall(
        id: Int,
        name: String,
        arguments: String,
    ): String {
        return """{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}"""
    }

    private fun createScenarioRoot(): java.nio.file.Path {
        val root = createTempDirectory()
        Files.createDirectories(root.resolve("scenarios"))
        Files.createDirectories(root.resolve("fixtures/demo"))
        Files.writeString(
            root.resolve("scenarios/demo.json"),
            """
                {
                  "name": "demo",
                  "rules": [
                    {
                      "method": "GET",
                      "path": "/v1/resource",
                      "fixture": "demo/resource.json"
                    }
                  ]
                }
            """.trimIndent(),
        )
        Files.writeString(
            root.resolve("scenarios/demo-alt.json"),
            """
                {
                  "name": "demo-alt",
                  "rules": [
                    {
                      "method": "GET",
                      "path": "/v1/alternate/{id}",
                      "fixture": "product.json"
                    }
                  ]
                }
            """.trimIndent(),
        )
        Files.writeString(
            root.resolve("fixtures/demo/resource.json"),
            """
                {
                  "data": [],
                  "groups": []
                }
            """.trimIndent(),
        )
        Files.writeString(
            root.resolve("fixtures/product.json"),
            """
                {
                  "id": 697816
                }
            """.trimIndent(),
        )
        return root
    }

    private fun freePort(): Int {
        return java.net.ServerSocket(0).use { socket -> socket.localPort }
    }

    private object FakeCaManager : CaManager {
        override fun generate(): CaState {
            return CaState(message = "generated", certificatePath = "ca.pem")
        }

        override fun install(udid: String?): CaState {
            return CaState(message = "installed ${udid ?: "auto"}", certificatePath = "ca.pem")
        }
    }
}
