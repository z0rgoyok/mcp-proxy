package dev.mcp.proxy.application

import dev.mcp.proxy.domain.ProxyRuntimeController
import dev.mcp.proxy.domain.ProxyRuntimeSettings
import dev.mcp.proxy.domain.ProxyRuntimeState

class RunProxyUseCase(
    private val proxyRuntimeController: ProxyRuntimeController,
) {
    suspend fun execute(settings: ProxyRuntimeSettings): ProxyRuntimeState {
        return proxyRuntimeController.start(settings)
    }
}
