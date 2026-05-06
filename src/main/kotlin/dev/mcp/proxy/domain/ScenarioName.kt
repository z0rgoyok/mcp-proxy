package dev.mcp.proxy.domain

@JvmInline
value class ScenarioName(val value: String) {
    init {
        require(value.isNotBlank()) {
            "Scenario name is required"
        }
    }
}
