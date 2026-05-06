package dev.mcp.proxy.app

import dev.mcp.proxy.domain.ProxyRuntimeState

class RuntimeStateOutput {
    fun format(state: ProxyRuntimeState): String {
        val status = if (state.running) "RUNNING" else "STOPPED "
        return listOf(
            "runtime | $status",
            "state=${state.stateDirectory}",
            "message=${state.message}",
        ).joinToString(separator = " | ")
    }
}
