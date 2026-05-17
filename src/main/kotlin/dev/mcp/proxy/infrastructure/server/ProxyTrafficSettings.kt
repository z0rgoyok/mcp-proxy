package dev.mcp.proxy.infrastructure.server

import dev.mcp.proxy.domain.ExternalNetworkPolicy
import dev.mcp.proxy.domain.MirrorBaseUrl
import dev.mcp.proxy.domain.UpstreamBaseUrl
import dev.mcp.proxy.domain.UpstreamProxyUrl

data class ProxyTrafficSettings(
    val upstreamBaseUrl: UpstreamBaseUrl,
    val externalNetworkPolicy: ExternalNetworkPolicy = ExternalNetworkPolicy.Allowed,
    val upstreamProxyUrl: UpstreamProxyUrl? = null,
    val mirrorMockRequests: Boolean = false,
    val mirrorBaseUrl: MirrorBaseUrl? = null,
)
