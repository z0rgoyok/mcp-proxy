package dev.mcp.proxy.infrastructure.server

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable
import dev.mcp.proxy.domain.scenario.MockRule
import dev.mcp.proxy.domain.scenario.MockRuleRequestBodyJsonRoot

data class RuleKey(
    val method: String,
    val path: String,
)

data class RuleEntry(
    val method: String,
    val path: String,
    val rule: MockRule,
)

class CapturedRequestBody(
    val bytes: ByteArray,
) {
    val text: String = bytes.toString(Charsets.UTF_8)

    companion object {
        val Empty = CapturedRequestBody(ByteArray(0))

        fun from(bytes: ByteArray?): CapturedRequestBody {
            return if (bytes == null) Empty else CapturedRequestBody(bytes)
        }
    }
}

@Serializable
data class ForbiddenRuleResponse(
    val error: String,
    val method: String,
    val path: String,
    val message: String,
)

class ScenarioRequestState {
    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    fun selectRule(
        ruleKey: RuleKey,
        rules: List<RuleEntry>,
        requestBody: String,
    ): MockRule? {
        val candidates = rules.filter { ruleEntry ->
            ruleEntry.method == ruleKey.method &&
                    pathMatches(ruleEntry.path, ruleKey.path) &&
                    ruleEntry.rule.matchesRequestBody(requestBody)
        }
        if (candidates.isEmpty()) {
            return null
        }
        val sequenceKey = "${ruleKey.method} ${ruleKey.path}"
        val nextSequence = counters.computeIfAbsent(sequenceKey) { AtomicInteger(0) }.incrementAndGet()
        return candidates.firstOrNull { ruleEntry -> ruleEntry.rule.sequence == nextSequence }?.rule
            ?: candidates.firstOrNull { ruleEntry -> ruleEntry.rule.sequence == null }?.rule
            ?: candidates.lastOrNull { ruleEntry ->
                val sequence = ruleEntry.rule.sequence
                sequence != null && sequence <= nextSequence
            }?.rule
            ?: candidates.first().rule
    }
}

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

fun methodRequiresRequestBody(method: String): Boolean {
    return method.uppercase() in METHODS_REQUIRING_REQUEST_BODY
}

private fun String.isPathVariable(): Boolean {
    return startsWith("{") && endsWith("}") && length > 2
}

private fun MockRule.matchesRequestBody(requestBody: String): Boolean {
    return requestBodyContains.all(requestBody::contains) &&
            requestBodyJsonRoot.matchesRequestBodyJsonRoot(requestBody)
}

private fun MockRuleRequestBodyJsonRoot?.matchesRequestBodyJsonRoot(requestBody: String): Boolean {
    return when (this) {
        null -> true
        MockRuleRequestBodyJsonRoot.Array -> requestBody.trimStart().firstOrNull() == JSON_ARRAY_START
        MockRuleRequestBodyJsonRoot.Object -> requestBody.trimStart().firstOrNull() == JSON_OBJECT_START
    }
}

private val METHODS_REQUIRING_REQUEST_BODY = setOf("POST", "PUT", "PATCH")
private const val JSON_ARRAY_START = '['
private const val JSON_OBJECT_START = '{'
