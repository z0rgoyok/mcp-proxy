package dev.mcp.proxy.infrastructure.scenario

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import dev.mcp.proxy.domain.ScenarioName
import dev.mcp.proxy.domain.scenario.MockRule
import dev.mcp.proxy.domain.scenario.MockScenario
import dev.mcp.proxy.domain.scenario.ScenarioRepository

class FileScenarioRepository(
    private val rootDirectory: Path,
    private val json: Json,
) : ScenarioRepository {

    override fun list(): List<ScenarioName> {
        val scenariosDirectory = rootDirectory.resolve(SCENARIOS_DIRECTORY)
        if (!Files.exists(scenariosDirectory)) {
            return emptyList()
        }
        return Files.list(scenariosDirectory).use { files ->
            files
                .filter { file -> file.fileName.toString().endsWith(SCENARIO_EXTENSION) }
                .map { file -> ScenarioName(file.fileName.toString().removeSuffix(SCENARIO_EXTENSION)) }
                .sorted { left, right -> left.value.compareTo(right.value) }
                .toList()
        }
    }

    override fun load(scenarioName: ScenarioName): MockScenario {
        val scenarioFile = rootDirectory
            .resolve(SCENARIOS_DIRECTORY)
            .resolve("${scenarioName.value}.json")
        check(Files.exists(scenarioFile)) {
            "Scenario file not found: $scenarioFile"
        }
        return json.decodeFromString(
            deserializer = MockScenario.serializer(),
            string = Files.readString(scenarioFile),
        )
    }

    override fun loadFixture(rule: MockRule): String {
        val fixture = requireNotNull(rule.fixture) {
            "Fixture is required for rule ${rule.method} ${rule.path}"
        }
        val fixtureFile = rootDirectory
            .resolve(FIXTURES_DIRECTORY)
            .resolve(fixture)
            .normalize()
        check(fixtureFile.startsWith(rootDirectory.resolve(FIXTURES_DIRECTORY).normalize())) {
            "Fixture path escapes fixtures directory: $fixture"
        }
        check(Files.exists(fixtureFile)) {
            "Fixture file not found: $fixtureFile"
        }
        return Files.readString(fixtureFile)
    }

    private companion object {
        const val SCENARIOS_DIRECTORY = "scenarios"
        const val FIXTURES_DIRECTORY = "fixtures"
        const val SCENARIO_EXTENSION = ".json"
    }
}
