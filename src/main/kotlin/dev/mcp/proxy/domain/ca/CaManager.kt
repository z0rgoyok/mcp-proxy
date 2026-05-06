package dev.mcp.proxy.domain.ca

interface CaManager {
    fun generate(): CaState
    fun install(udid: String?): CaState
}
