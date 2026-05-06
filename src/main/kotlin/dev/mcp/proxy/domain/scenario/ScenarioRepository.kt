package dev.mcp.proxy.domain.scenario

import dev.mcp.proxy.domain.ScenarioName

interface ScenarioRepository {
    fun list(): List<ScenarioName>
    fun load(scenarioName: ScenarioName): MockScenario
    fun loadFixture(rule: MockRule): String
}
