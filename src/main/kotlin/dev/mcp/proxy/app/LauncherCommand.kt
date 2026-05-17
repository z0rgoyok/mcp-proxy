package dev.mcp.proxy.app

import java.nio.file.Path
import dev.mcp.proxy.domain.scenario.ScenarioOverride

sealed interface LauncherCommand {
    data class Mcp(
        val host: String?,
        val port: Int?,
    ) : LauncherCommand

    data class Runtime(
        val scenarioName: String,
        val proxyPort: Int?,
        val upstreamBaseUrl: String?,
        val externalNetwork: String? = null,
        val upstreamProxyUrl: String? = null,
        val mirrorMockRequests: Boolean? = null,
        val mirrorBaseUrl: String? = null,
        val stateDirectory: Path?,
        val overrides: List<ScenarioOverride>,
    ) : LauncherCommand
}
