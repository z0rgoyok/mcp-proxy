package dev.mcp.proxy.domain

@JvmInline
value class UpstreamBaseUrl(val value: String) {
    init {
        require(value.startsWith("http://") || value.startsWith("https://")) {
            "Upstream base URL must start with http:// or https://"
        }
    }

    companion object {
        val Default = UpstreamBaseUrl("https://example.com")
    }
}
