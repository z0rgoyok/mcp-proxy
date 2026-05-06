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
    val mode: MockRuleMode = MockRuleMode.Mock,
    val sequence: Int? = null,
    val requestBodyContains: List<String> = emptyList(),
    val status: Int = SUCCESS_STATUS,
    val delayMillis: Long = NO_DELAY_MILLIS,
    val timeoutMillis: Long? = null,
    val bodyMode: MockRuleBodyMode = MockRuleBodyMode.Fixture,
    val fixture: String? = null,
) {
    val responseDelayMillis: Long
        get() = timeoutMillis ?: delayMillis

    companion object {
        const val SUCCESS_STATUS = 200
        const val NO_DELAY_MILLIS = 0L
    }
}

@Serializable
enum class MockRuleMode {
    @SerialName("mock")
    Mock,

    @SerialName("forbidden")
    Forbidden,
    ;

    val scenarioValue: String
        get() = when (this) {
            Mock -> "mock"
            Forbidden -> "forbidden"
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
