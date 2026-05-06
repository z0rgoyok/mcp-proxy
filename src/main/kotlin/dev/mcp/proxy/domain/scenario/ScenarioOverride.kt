package dev.mcp.proxy.domain.scenario

data class ScenarioOverride(
    val method: String,
    val path: String,
    val fixture: String,
)
