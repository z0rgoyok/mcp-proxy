package dev.mcp.proxy.application

import java.nio.file.Path
import dev.mcp.proxy.domain.MirrorBaseUrl
import dev.mcp.proxy.domain.ProxyPort
import dev.mcp.proxy.domain.ProxyRuntimeSettings
import dev.mcp.proxy.domain.ScenarioName
import dev.mcp.proxy.domain.UpstreamBaseUrl
import dev.mcp.proxy.domain.UpstreamProxyUrl
import dev.mcp.proxy.domain.scenario.ScenarioOverride

class BuildRuntimeSettingsUseCase {
    fun execute(
        scenarioName: String,
        proxyPort: Int?,
        upstreamBaseUrl: String?,
        upstreamProxyUrl: String? = null,
        mirrorMockRequests: Boolean? = null,
        mirrorBaseUrl: String? = null,
        stateDirectory: Path?,
        overrides: List<ScenarioOverride> = emptyList(),
    ): ProxyRuntimeSettings {
        return ProxyRuntimeSettings(
            scenarioName = ScenarioName(scenarioName),
            proxyPort = proxyPort?.let(::ProxyPort) ?: ProxyPort.Default,
            upstreamBaseUrl = upstreamBaseUrl?.let(::UpstreamBaseUrl) ?: UpstreamBaseUrl.Default,
            upstreamProxyUrl = upstreamProxyUrl?.let(::UpstreamProxyUrl),
            mirrorMockRequests = mirrorMockRequests,
            mirrorBaseUrl = mirrorBaseUrl?.let(::MirrorBaseUrl),
            stateDirectory = (stateDirectory ?: Path.of("var/state")).toAbsolutePath().normalize(),
            overrides = overrides,
        )
    }
}
