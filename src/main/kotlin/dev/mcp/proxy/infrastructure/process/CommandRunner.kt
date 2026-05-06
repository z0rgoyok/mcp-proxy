package dev.mcp.proxy.infrastructure.process

interface CommandRunner {
    fun run(command: List<String>): CommandResult
}
