package dev.mcp.proxy.infrastructure.scenario

import java.time.Clock
import java.time.LocalDate

class FixtureTemplateRenderer(
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    fun render(
        fixtureName: String,
        content: String,
    ): String {
        val today = LocalDate.now(clock)
        val values = mapOf(
            TODAY_TOKEN to today.toString(),
            TOMORROW_TOKEN to today.plusDays(1).toString(),
        )
        val unknownTokens = TEMPLATE_TOKEN_REGEX.findAll(content)
            .map { match -> match.groupValues[1] }
            .filter { token -> token !in values }
            .distinct()
            .sorted()
            .toList()
        check(unknownTokens.isEmpty()) {
            "Unsupported fixture template token(s) in $fixtureName: ${unknownTokens.joinToString()}"
        }
        val rendered = TEMPLATE_TOKEN_REGEX.replace(content) { match ->
            values.getValue(match.groupValues[1])
        }
        check(UNRESOLVED_TEMPLATE_START !in rendered && UNRESOLVED_TEMPLATE_END !in rendered) {
            "Malformed fixture template syntax in $fixtureName"
        }
        return rendered
    }

    private companion object {
        const val TODAY_TOKEN = "today"
        const val TOMORROW_TOKEN = "tomorrow"
        const val UNRESOLVED_TEMPLATE_START = "{{"
        const val UNRESOLVED_TEMPLATE_END = "}}"
        val TEMPLATE_TOKEN_REGEX = Regex("""\{\{\s*([A-Za-z0-9_.-]+)\s*}}""")
    }
}
