package dev.mcp.proxy.infrastructure.server

import dev.mcp.proxy.domain.scenario.MockScenario
import dev.mcp.proxy.domain.UpstreamBaseUrl

data class ActiveScenarioSettings(
    val scenario: MockScenario,
    val trafficSettings: ProxyTrafficSettings,
) {
    constructor(
        scenario: MockScenario,
        upstreamBaseUrl: UpstreamBaseUrl,
    ) : this(
        scenario = scenario,
        trafficSettings = ProxyTrafficSettings(upstreamBaseUrl = upstreamBaseUrl),
    )
}
