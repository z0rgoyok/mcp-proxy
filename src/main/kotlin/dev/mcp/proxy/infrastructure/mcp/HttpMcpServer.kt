package dev.mcp.proxy.infrastructure.mcp

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class HttpMcpServer(
    private val mcpServer: GenericProxyMcpServer,
) {
    fun run(
        host: String,
        port: Int,
    ) {
        val server = Server(
            serverInfo = Implementation(
                name = "mcp-proxy",
                version = "1.0.0",
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        )
        mcpServer.toolNames().forEach { toolName ->
            server.addTool(
                name = toolName,
                description = "Generic proxy tool $toolName",
                inputSchema = toolSchema(toolName),
            ) { request ->
                val text = mcpServer.executeTool(
                    name = toolName,
                    arguments = request.arguments ?: JsonObject(emptyMap()),
                )
                CallToolResult(
                    content = listOf(TextContent(text)),
                )
            }
        }
        embeddedServer(
            factory = Netty,
            host = host,
            port = port,
        ) {
            mcpStreamableHttp {
                server
            }
        }.start(wait = true)
    }

    private fun toolSchema(toolName: String): ToolSchema {
        val schema = mcpServer.toolInputSchema(toolName)
        return ToolSchema(
            properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap()),
        )
    }
}
