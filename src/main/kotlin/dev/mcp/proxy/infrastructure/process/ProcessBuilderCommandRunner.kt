package dev.mcp.proxy.infrastructure.process

class ProcessBuilderCommandRunner : CommandRunner {
    override fun run(command: List<String>): CommandResult {
        val process = ProcessBuilder(command)
            .redirectErrorStream(false)
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        return CommandResult(
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
        )
    }
}
