package dev.mcp.proxy.domain

@JvmInline
value class UpstreamProxyUrl(val value: String) {
    init {
        require(value.startsWith("http://")) {
            "Upstream proxy URL must start with http://"
        }
    }
}
