package dev.mcp.proxy.domain

@JvmInline
value class ProxyPort(val value: Int) {
    init {
        require(value in MIN_PORT..MAX_PORT) {
            "Proxy port must be in $MIN_PORT..$MAX_PORT, got $value"
        }
    }

    companion object {
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535
        val Default = ProxyPort(18081)
    }
}
