package dev.mcp.proxy.infrastructure.mcp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.mcp.proxy.app.AppComponents
import dev.mcp.proxy.domain.ca.CaManager
import dev.mcp.proxy.domain.ca.CaState

class StdioMcpServerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `stdio server accepts content length framed initialize`() {
        val request = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""
        val input = ByteArrayInputStream(
            "Content-Length: ${request.toByteArray().size}\r\n\r\n$request".toByteArray(),
        )
        val output = ByteArrayOutputStream()

        StdioMcpServer(
            mcpServer = mcpServer(),
            input = input,
            output = output,
        ).run()

        val rawResponse = output.toString(Charsets.UTF_8)
        assertContains(rawResponse, "Content-Length:")
        val response = rawResponse.substringAfter("\r\n\r\n")
        val result = json.parseToJsonElement(response).jsonObject.getValue("result").jsonObject
        assertEquals("mcp-proxy", result.getValue("serverInfo").jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `stdio server ignores initialized notification`() {
        val notification = """{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}"""
        val input = ByteArrayInputStream(
            "Content-Length: ${notification.toByteArray().size}\r\n\r\n$notification".toByteArray(),
        )
        val output = ByteArrayOutputStream()

        StdioMcpServer(
            mcpServer = mcpServer(),
            input = input,
            output = output,
        ).run()

        assertEquals("", output.toString(Charsets.UTF_8))
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
            root.resolve("fixtures/demo/resource.json"),
            """
                {
                  "data": [],
                  "groups": []
                }
            """.trimIndent(),
        )
        return root
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
