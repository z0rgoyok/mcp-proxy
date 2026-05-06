package dev.mcp.proxy.infrastructure.runtime

import kotlinx.serialization.Serializable

@Serializable
data class PersistedRuntimeState(
    val scenario: String? = null,
    val proxyPort: Int,
    val upstreamBaseUrl: String,
    val running: Boolean,
    val upstreamProxyUrl: String? = null,
    val mirrorMockRequests: Boolean = false,
    val mirrorBaseUrl: String? = null,
)
