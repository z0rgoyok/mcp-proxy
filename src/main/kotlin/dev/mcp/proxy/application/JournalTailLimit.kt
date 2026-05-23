package dev.mcp.proxy.application

object JournalTailLimit {
    const val MCP_DEFAULT = 20
    const val ADMIN_DEFAULT = 50
    const val MAX = 500

    fun normalize(
        limit: Int?,
        defaultLimit: Int,
    ): Int {
        return (limit ?: defaultLimit).coerceIn(MIN, MAX)
    }

    private const val MIN = 1
}
