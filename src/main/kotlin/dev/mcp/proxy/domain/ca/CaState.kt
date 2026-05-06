package dev.mcp.proxy.domain.ca

data class CaState(
    val message: String,
    val certificatePath: String? = null,
)
