package dev.mcp.proxy.domain

interface ProxyRuntimeController {
    suspend fun start(settings: ProxyRuntimeSettings): ProxyRuntimeState
    suspend fun status(stateDirectory: java.nio.file.Path): ProxyRuntimeState
    suspend fun stop(stateDirectory: java.nio.file.Path): ProxyRuntimeState
}
