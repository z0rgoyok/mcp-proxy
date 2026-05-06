package dev.mcp.proxy.app

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import dev.mcp.proxy.domain.MirrorBaseUrl
import dev.mcp.proxy.domain.UpstreamProxyUrl
import dev.mcp.proxy.infrastructure.mcp.HttpMcpServer

fun main(arguments: Array<String>) = runBlocking {
    val components = AppComponents.create()
    val parser = LauncherArgumentsParser()

    when (val command = parser.parse(arguments.toList())) {
        is LauncherCommand.Mcp -> {
            val trafficEnvironment = ProxyTrafficEnvironment.fromSystem()
            components.runtimeController.startPassthrough(
                upstreamProxyUrl = trafficEnvironment.upstreamProxyUrl,
                mirrorMockRequests = trafficEnvironment.mirrorMockRequests,
                mirrorBaseUrl = trafficEnvironment.mirrorBaseUrl,
            )
            HttpMcpServer(
                mcpServer = dev.mcp.proxy.infrastructure.mcp.GenericProxyMcpServer(components = components),
            ).run(
                host = command.host ?: DEFAULT_MCP_HOST,
                port = command.port ?: DEFAULT_MCP_PORT,
            )
        }
        is LauncherCommand.Runtime -> {
            val settings = components.buildRuntimeSettingsUseCase.execute(
                scenarioName = command.scenarioName,
                proxyPort = command.proxyPort,
                upstreamBaseUrl = command.upstreamBaseUrl,
                upstreamProxyUrl = command.upstreamProxyUrl,
                mirrorMockRequests = command.mirrorMockRequests,
                mirrorBaseUrl = command.mirrorBaseUrl,
                stateDirectory = command.stateDirectory,
                overrides = command.overrides,
            )
            println(RuntimeStateOutput().format(components.runProxyUseCase.execute(settings)))
            awaitCancellation()
        }
    }
}

private const val DEFAULT_MCP_HOST = "127.0.0.1"
private const val DEFAULT_MCP_PORT = 18082

private data class ProxyTrafficEnvironment(
    val upstreamProxyUrl: UpstreamProxyUrl?,
    val mirrorMockRequests: Boolean,
    val mirrorBaseUrl: MirrorBaseUrl?,
) {
    companion object {
        fun fromSystem(): ProxyTrafficEnvironment {
            val upstreamProxyEnabled = env("MCP_PROXY_UPSTREAM_PROXY_ENABLED")?.toBooleanStrictOrNull() ?: false
            val upstreamProxyUrl = env("MCP_PROXY_UPSTREAM_PROXY_URL")
                ?: if (upstreamProxyEnabled) DEFAULT_UPSTREAM_PROXY_URL else null
            return ProxyTrafficEnvironment(
                upstreamProxyUrl = upstreamProxyUrl?.let(::UpstreamProxyUrl),
                mirrorMockRequests = env("MCP_PROXY_MIRROR_MOCK_REQUESTS")?.toBooleanStrictOrNull() ?: upstreamProxyEnabled,
                mirrorBaseUrl = env("MCP_PROXY_MIRROR_BASE_URL")?.let(::MirrorBaseUrl),
            )
        }

        private fun env(name: String): String? {
            return System.getenv(name)?.takeIf(String::isNotBlank)
        }
    }
}

private const val DEFAULT_UPSTREAM_PROXY_URL = "http://host.docker.internal:8888"
