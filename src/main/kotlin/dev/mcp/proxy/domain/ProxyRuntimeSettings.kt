package dev.mcp.proxy.domain

import java.nio.file.Path
import dev.mcp.proxy.domain.scenario.ScenarioOverride

data class ProxyRuntimeSettings(
    val scenarioName: ScenarioName,
    val proxyPort: ProxyPort,
    val upstreamBaseUrl: UpstreamBaseUrl,
    val upstreamProxyUrl: UpstreamProxyUrl? = null,
    val mirrorMockRequests: Boolean? = null,
    val mirrorBaseUrl: MirrorBaseUrl? = null,
    val stateDirectory: Path,
    val overrides: List<ScenarioOverride> = emptyList(),
)
