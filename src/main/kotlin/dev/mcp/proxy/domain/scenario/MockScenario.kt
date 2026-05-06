package dev.mcp.proxy.domain.scenario

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class MockScenario(
    val name: String,
    val rules: List<MockRule>,
)

@Serializable
data class MockRule(
    val method: String,
    val path: String,
    val status: Int = SUCCESS_STATUS,
    val delayMillis: Long = NO_DELAY_MILLIS,
    val timeoutMillis: Long? = null,
    val bodyMode: MockRuleBodyMode = MockRuleBodyMode.Fixture,
    val fixture: String,
) {
    val responseDelayMillis: Long
        get() = timeoutMillis ?: delayMillis

    companion object {
        const val SUCCESS_STATUS = 200
        const val NO_DELAY_MILLIS = 0L
    }
}

@Serializable
enum class MockRuleBodyMode {
    @SerialName("fixture")
    Fixture,

    @SerialName("empty")
    Empty,

    @SerialName("connectionClose")
    ConnectionClose,
    ;

    val scenarioValue: String
        get() = when (this) {
            Fixture -> "fixture"
            Empty -> "empty"
            ConnectionClose -> "connectionClose"
        }
}
