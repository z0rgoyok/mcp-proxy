package dev.mcp.proxy.domain

import java.nio.file.Path

data class ProxyRuntimeState(
    val running: Boolean,
    val stateDirectory: Path,
    val message: String,
)
