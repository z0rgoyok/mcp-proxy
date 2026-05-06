package dev.mcp.proxy.infrastructure.server

import dev.mcp.proxy.domain.scenario.MockRule

data class RuleKey(
    val method: String,
    val path: String,
)

data class RuleEntry(
    val method: String,
    val path: String,
    val rule: MockRule,
)

fun normalizeProxyPath(path: String): String {
    val normalized = path.substringBefore("?").ifBlank { "/" }
    return if (normalized.startsWith("/")) normalized else "/$normalized"
}

fun pathMatches(
    pattern: String,
    path: String,
): Boolean {
    val patternParts = normalizeProxyPath(pattern).trim('/').split('/').filter(String::isNotBlank)
    val pathParts = normalizeProxyPath(path).trim('/').split('/').filter(String::isNotBlank)
    if (patternParts.size != pathParts.size) return false
    return patternParts.zip(pathParts).all { (patternPart, pathPart) ->
        patternPart.isPathVariable() || patternPart == pathPart
    }
}

private fun String.isPathVariable(): Boolean {
    return startsWith("{") && endsWith("}") && length > 2
}
