package dev.mcp.proxy.application

import java.nio.file.Path
import dev.mcp.proxy.domain.ProxyRuntimeController
import dev.mcp.proxy.domain.ProxyRuntimeState

class StopProxyUseCase(
    private val proxyRuntimeController: ProxyRuntimeController,
) {
    suspend fun execute(stateDirectory: Path?): ProxyRuntimeState {
        val resolvedStateDirectory = (stateDirectory ?: Path.of("var/state")).toAbsolutePath().normalize()
        return proxyRuntimeController.stop(resolvedStateDirectory)
    }
}
