package dev.mcp.proxy.infrastructure.server

interface ProxyServerHandle {
    fun activateScenario(settings: ActiveScenarioSettings)
    fun configureTraffic(settings: ProxyTrafficSettings)
    fun deactivateScenario()
    fun stop()
}
