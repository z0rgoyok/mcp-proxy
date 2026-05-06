package dev.mcp.proxy.app

import java.nio.file.Path
import dev.mcp.proxy.domain.scenario.ScenarioOverride

class LauncherArgumentsParser {
    fun parse(arguments: List<String>): LauncherCommand {
        return when (arguments.first()) {
            "mcp" -> parseMcp(arguments)
            "runtime" -> parseRuntime(arguments)
            else -> throw IllegalArgumentException("Unknown launcher mode: ${arguments.joinToString(" ")}")
        }
    }

    private fun parseMcp(arguments: List<String>): LauncherCommand.Mcp {
        val options = parseOptions(arguments.drop(1))
        return LauncherCommand.Mcp(
            host = options["--host"],
            port = options["--port"]?.toIntOrNull()
                ?: options["--port"]?.let { throw IllegalArgumentException("MCP port must be an integer") },
        )
    }

    private fun parseRuntime(arguments: List<String>): LauncherCommand.Runtime {
        val scenarioName = arguments.getOrNull(1)
            ?: throw IllegalArgumentException("Scenario name is required: runtime <scenario>")
        val options = parseOptions(arguments.drop(2))
        return LauncherCommand.Runtime(
            scenarioName = scenarioName,
            proxyPort = options["--proxy-port"]?.toIntOrNull()
                ?: options["--proxy-port"]?.let { throw IllegalArgumentException("Proxy port must be an integer") },
            upstreamBaseUrl = options["--upstream-base-url"],
            upstreamProxyUrl = options["--upstream-proxy-url"],
            mirrorMockRequests = options["--mirror-mock-requests"]?.toBooleanStrictOrNull()
                ?: options["--mirror-mock-requests"]?.let { throw IllegalArgumentException("Mirror mock requests must be true or false") },
            mirrorBaseUrl = options["--mirror-base-url"],
            stateDirectory = options["--state-dir"]?.let(Path::of),
            overrides = parseOverrides(arguments.drop(2)),
        )
    }

    private fun parseOptions(arguments: List<String>): Map<String, String> {
        val options = mutableMapOf<String, String>()
        var index = 0
        while (index < arguments.size) {
            val option = arguments[index]
            if (!option.startsWith("--")) {
                throw IllegalArgumentException("Unexpected argument: $option")
            }
            val value = arguments.getOrNull(index + 1)
                ?: throw IllegalArgumentException("Value is required for option $option")
            if (value.startsWith("--")) {
                throw IllegalArgumentException("Value is required for option $option")
            }
            options[option] = value
            index += 2
        }
        return options
    }

    private fun parseOverrides(arguments: List<String>): List<ScenarioOverride> {
        return arguments.windowed(size = 2, step = 2, partialWindows = false)
            .filter { pair -> pair.first() == "--override" }
            .map { pair ->
                val parts = pair[1].split("=", limit = 2)
                require(parts.size == 2) {
                    "Override must use METHOD:PATH=fixture format"
                }
                val routeParts = parts[0].split(":", limit = 2)
                require(routeParts.size == 2) {
                    "Override must use METHOD:PATH=fixture format"
                }
                ScenarioOverride(
                    method = routeParts[0],
                    path = routeParts[1],
                    fixture = parts[1],
                )
            }
    }
}
