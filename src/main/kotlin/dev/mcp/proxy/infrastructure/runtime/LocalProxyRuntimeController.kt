package dev.mcp.proxy.infrastructure.runtime

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import dev.mcp.proxy.domain.ProxyRuntimeController
import dev.mcp.proxy.domain.ProxyRuntimeSettings
import dev.mcp.proxy.domain.ProxyRuntimeState
import dev.mcp.proxy.domain.ProxyPort
import dev.mcp.proxy.domain.UpstreamBaseUrl
import dev.mcp.proxy.domain.MirrorBaseUrl
import dev.mcp.proxy.domain.UpstreamProxyUrl
import dev.mcp.proxy.domain.scenario.ScenarioRepository
import dev.mcp.proxy.infrastructure.scenario.FileScenarioRepository
import dev.mcp.proxy.infrastructure.server.ActiveScenarioSettings
import dev.mcp.proxy.infrastructure.server.MitmMockProxyServer
import dev.mcp.proxy.infrastructure.server.ProxyServerHandle
import dev.mcp.proxy.infrastructure.server.ProxyTrafficSettings

class LocalProxyRuntimeController(
    private val json: Json,
    private val rootDirectory: Path = Path.of("."),
    private val scenarioRepository: ScenarioRepository = FileScenarioRepository(
        rootDirectory = rootDirectory,
        json = json,
    ),
    private val mockProxyServer: MitmMockProxyServer = MitmMockProxyServer(
        scenarioRepository = scenarioRepository,
    ),
) : ProxyRuntimeController {
    private val engines = mutableMapOf<Path, RunningProxy>()

    override suspend fun start(settings: ProxyRuntimeSettings): ProxyRuntimeState {
        withContext(Dispatchers.IO) {
            Files.createDirectories(settings.stateDirectory)
        }
        val trafficSettings = resolveTrafficSettings(settings)
        val activeScenario = ActiveScenarioSettings(
            scenario = resolvedScenario(settings),
            trafficSettings = trafficSettings,
        )
        val running = ensureRunning(
            stateDirectory = settings.stateDirectory,
            proxyPort = settings.proxyPort,
            trafficSettings = trafficSettings,
            activeScenario = activeScenario,
        )
        running.handle.configureTraffic(trafficSettings)
        running.handle.activateScenario(activeScenario)
        persistState(
            stateDirectory = settings.stateDirectory,
            scenario = settings.scenarioName.value,
            proxyPort = settings.proxyPort,
            trafficSettings = trafficSettings,
            running = true,
        )
        return ProxyRuntimeState(
            running = true,
            stateDirectory = settings.stateDirectory,
            message = "Mock proxy scenario ${settings.scenarioName.value} active on port ${settings.proxyPort.value}",
        )
    }

    suspend fun startPassthrough(
        proxyPort: ProxyPort = ProxyPort.Default,
        upstreamBaseUrl: UpstreamBaseUrl = UpstreamBaseUrl.Default,
        upstreamProxyUrl: UpstreamProxyUrl? = null,
        mirrorMockRequests: Boolean = false,
        mirrorBaseUrl: MirrorBaseUrl? = null,
        stateDirectory: Path = Path.of("var/state").toAbsolutePath().normalize(),
    ): ProxyRuntimeState {
        withContext(Dispatchers.IO) {
            Files.createDirectories(stateDirectory)
        }
        val trafficSettings = ProxyTrafficSettings(
            upstreamBaseUrl = upstreamBaseUrl,
            upstreamProxyUrl = upstreamProxyUrl,
            mirrorMockRequests = mirrorMockRequests,
            mirrorBaseUrl = mirrorBaseUrl,
        )
        ensureRunning(
            stateDirectory = stateDirectory,
            proxyPort = proxyPort,
            trafficSettings = trafficSettings,
            activeScenario = null,
        )
        persistState(
            stateDirectory = stateDirectory,
            scenario = null,
            proxyPort = proxyPort,
            trafficSettings = trafficSettings,
            running = true,
        )
        return ProxyRuntimeState(
            running = true,
            stateDirectory = stateDirectory,
            message = "Mock proxy passthrough active on port ${proxyPort.value}",
        )
    }

    suspend fun disableScenario(
        stateDirectory: Path = Path.of("var/state").toAbsolutePath().normalize(),
    ): ProxyRuntimeState {
        val running = engines[stateDirectory]
        val persistedState = readPersistedState(stateDirectory)
        running?.handle?.deactivateScenario()
        val trafficSettings = ProxyTrafficSettings(
            upstreamBaseUrl = UpstreamBaseUrl(persistedState?.upstreamBaseUrl ?: running?.trafficSettings?.upstreamBaseUrl?.value ?: UpstreamBaseUrl.Default.value),
            upstreamProxyUrl = persistedState?.upstreamProxyUrl?.let(::UpstreamProxyUrl) ?: running?.trafficSettings?.upstreamProxyUrl,
            mirrorMockRequests = persistedState?.mirrorMockRequests ?: running?.trafficSettings?.mirrorMockRequests ?: false,
            mirrorBaseUrl = persistedState?.mirrorBaseUrl?.let(::MirrorBaseUrl) ?: running?.trafficSettings?.mirrorBaseUrl,
        )
        running?.handle?.configureTraffic(trafficSettings)
        persistState(
            stateDirectory = stateDirectory,
            scenario = null,
            proxyPort = ProxyPort(persistedState?.proxyPort ?: running?.proxyPort?.value ?: ProxyPort.Default.value),
            trafficSettings = trafficSettings,
            running = running != null || persistedState?.running == true,
        )
        return ProxyRuntimeState(
            running = running != null || persistedState?.running == true,
            stateDirectory = stateDirectory,
            message = "Proxy scenario disabled; passthrough active",
        )
    }

    override suspend fun status(stateDirectory: Path): ProxyRuntimeState {
        val stateFile = stateFile(stateDirectory)
        val running = Files.exists(stateFile)
        return ProxyRuntimeState(
            running = running,
            stateDirectory = stateDirectory,
            message = if (running) "Proxy lifecycle state exists" else "Proxy lifecycle state is empty",
        )
    }

    override suspend fun stop(stateDirectory: Path): ProxyRuntimeState {
        engines.remove(stateDirectory)?.handle?.stop()
        val stateFile = stateFile(stateDirectory)
        withContext(Dispatchers.IO) {
            Files.deleteIfExists(stateFile)
        }
        return ProxyRuntimeState(
            running = false,
            stateDirectory = stateDirectory,
            message = "Proxy lifecycle stopped",
        )
    }

    private fun stateFile(stateDirectory: Path): Path {
        return stateDirectory.resolve("runtime.json")
    }

    private fun resolvedScenario(settings: ProxyRuntimeSettings): dev.mcp.proxy.domain.scenario.MockScenario {
        val scenario = scenarioRepository.load(settings.scenarioName)
        return scenario.copy(
            rules = scenario.rules.map { rule ->
                val override = settings.overrides.firstOrNull { scenarioOverride ->
                    scenarioOverride.method.equals(rule.method, ignoreCase = true) &&
                        normalizePath(scenarioOverride.path) == normalizePath(rule.path)
                }
                if (override == null) {
                    rule
                } else {
                    rule.copy(fixture = override.fixture)
                }
            },
        )
    }

    private fun ensureRunning(
        stateDirectory: Path,
        proxyPort: ProxyPort,
        trafficSettings: ProxyTrafficSettings,
        activeScenario: ActiveScenarioSettings?,
    ): RunningProxy {
        val existing = engines[stateDirectory]
        if (existing != null && existing.proxyPort == proxyPort) {
            return existing
        }
        existing?.handle?.stop()
        val handle = mockProxyServer.start(
            activeScenario = activeScenario,
            proxyPort = proxyPort,
            trafficSettings = trafficSettings,
            stateDirectory = stateDirectory,
        )
        val running = RunningProxy(
            handle = handle,
            proxyPort = proxyPort,
            trafficSettings = trafficSettings,
        )
        engines[stateDirectory] = running
        return running
    }

    private suspend fun persistState(
        stateDirectory: Path,
        scenario: String?,
        proxyPort: ProxyPort,
        trafficSettings: ProxyTrafficSettings,
        running: Boolean,
    ) {
        val persistedState = PersistedRuntimeState(
            scenario = scenario,
            proxyPort = proxyPort.value,
            upstreamBaseUrl = trafficSettings.upstreamBaseUrl.value,
            upstreamProxyUrl = trafficSettings.upstreamProxyUrl?.value,
            mirrorMockRequests = trafficSettings.mirrorMockRequests,
            mirrorBaseUrl = trafficSettings.mirrorBaseUrl?.value,
            running = running,
        )
        withContext(Dispatchers.IO) {
            Files.writeString(
                stateFile(stateDirectory),
                json.encodeToString(PersistedRuntimeState.serializer(), persistedState),
            )
        }
    }

    private fun readPersistedState(stateDirectory: Path): PersistedRuntimeState? {
        val stateFile = stateFile(stateDirectory)
        return if (Files.exists(stateFile)) {
            json.decodeFromString<PersistedRuntimeState>(Files.readString(stateFile))
        } else {
            null
        }
    }

    private fun normalizePath(path: String): String {
        return if (path.startsWith("/")) path else "/$path"
    }

    private fun resolveTrafficSettings(settings: ProxyRuntimeSettings): ProxyTrafficSettings {
        val persistedState = readPersistedState(settings.stateDirectory)
        val running = engines[settings.stateDirectory]
        return ProxyTrafficSettings(
            upstreamBaseUrl = settings.upstreamBaseUrl,
            upstreamProxyUrl = settings.upstreamProxyUrl
                ?: persistedState?.upstreamProxyUrl?.let(::UpstreamProxyUrl)
                ?: running?.trafficSettings?.upstreamProxyUrl,
            mirrorMockRequests = settings.mirrorMockRequests
                ?: persistedState?.mirrorMockRequests
                ?: running?.trafficSettings?.mirrorMockRequests
                ?: false,
            mirrorBaseUrl = settings.mirrorBaseUrl
                ?: persistedState?.mirrorBaseUrl?.let(::MirrorBaseUrl)
                ?: running?.trafficSettings?.mirrorBaseUrl,
        )
    }

    private data class RunningProxy(
        val handle: ProxyServerHandle,
        val proxyPort: ProxyPort,
        val trafficSettings: ProxyTrafficSettings,
    )
}
