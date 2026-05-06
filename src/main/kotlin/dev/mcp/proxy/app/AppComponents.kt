package dev.mcp.proxy.app

import java.nio.file.Path
import kotlinx.serialization.json.Json
import dev.mcp.proxy.application.BuildRuntimeSettingsUseCase
import dev.mcp.proxy.application.GetProxyStatusUseCase
import dev.mcp.proxy.application.ListScenariosUseCase
import dev.mcp.proxy.application.ReadJournalTailUseCase
import dev.mcp.proxy.application.RunProxyUseCase
import dev.mcp.proxy.application.StopProxyUseCase
import dev.mcp.proxy.domain.ca.CaManager
import dev.mcp.proxy.domain.scenario.ScenarioRepository
import dev.mcp.proxy.infrastructure.ca.LocalCaManager
import dev.mcp.proxy.infrastructure.runtime.LocalProxyRuntimeController
import dev.mcp.proxy.infrastructure.scenario.FileScenarioRepository

class AppComponents(
    val json: Json,
    val scenarioRepository: ScenarioRepository,
    val runtimeController: LocalProxyRuntimeController,
    val buildRuntimeSettingsUseCase: BuildRuntimeSettingsUseCase,
    val runProxyUseCase: RunProxyUseCase,
    val getProxyStatusUseCase: GetProxyStatusUseCase,
    val stopProxyUseCase: StopProxyUseCase,
    val listScenariosUseCase: ListScenariosUseCase,
    val readJournalTailUseCase: ReadJournalTailUseCase,
    val caManager: CaManager,
) {
    companion object {
        fun create(
            rootDirectory: Path = Path.of("."),
            stateDirectory: Path = Path.of("var/state"),
            json: Json = Json {
                prettyPrint = true
                explicitNulls = false
                ignoreUnknownKeys = true
            },
            caManager: CaManager = LocalCaManager(stateDirectory = stateDirectory),
        ): AppComponents {
            val scenarioRepository = FileScenarioRepository(
                rootDirectory = rootDirectory,
                json = json,
            )
            val runtimeController = LocalProxyRuntimeController(
                json = json,
                rootDirectory = rootDirectory,
                scenarioRepository = scenarioRepository,
            )
            return AppComponents(
                json = json,
                scenarioRepository = scenarioRepository,
                runtimeController = runtimeController,
                buildRuntimeSettingsUseCase = BuildRuntimeSettingsUseCase(),
                runProxyUseCase = RunProxyUseCase(proxyRuntimeController = runtimeController),
                getProxyStatusUseCase = GetProxyStatusUseCase(proxyRuntimeController = runtimeController),
                stopProxyUseCase = StopProxyUseCase(proxyRuntimeController = runtimeController),
                listScenariosUseCase = ListScenariosUseCase(scenarioRepository = scenarioRepository),
                readJournalTailUseCase = ReadJournalTailUseCase(),
                caManager = caManager,
            )
        }
    }
}
