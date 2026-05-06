package dev.mcp.proxy.infrastructure.process

data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)
