package dev.mcp.proxy.domain

@JvmInline
value class MirrorBaseUrl(val value: String) {
    init {
        require(value.startsWith("http://") || value.startsWith("https://")) {
            "Mirror base URL must start with http:// or https://"
        }
    }
}
