package dev.mcp.proxy.application

import dev.mcp.proxy.domain.scenario.ScenarioRepository

class ListScenariosUseCase(
    private val scenarioRepository: ScenarioRepository,
) {
    fun execute(): List<String> {
        return scenarioRepository.list().map { scenarioName -> scenarioName.value }
    }
}
