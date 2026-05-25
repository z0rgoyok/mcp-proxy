package dev.mcp.proxy.infrastructure.server

import kotlin.test.Test
import kotlin.test.assertEquals
import dev.mcp.proxy.domain.scenario.MockRule
import dev.mcp.proxy.domain.scenario.MockRuleRequestBodyJsonRoot

class ProxyRoutingSupportTest {
    @Test
    fun `selectRule matches request body json array root`() {
        val arrayRule = MockRule(
            method = "POST",
            path = "/v1/order/delivery/calc/combined",
            requestBodyContains = listOf("deliveryType", "store"),
            requestBodyJsonRoot = MockRuleRequestBodyJsonRoot.Array,
            fixture = "array.json",
        )
        val objectRule = MockRule(
            method = "POST",
            path = "/v1/order/delivery/calc/combined",
            requestBodyContains = listOf("deliveryType", "store"),
            requestBodyJsonRoot = MockRuleRequestBodyJsonRoot.Object,
            fixture = "object.json",
        )
        val rules = listOf(
            RuleEntry("POST", "/v1/order/delivery/calc/combined", arrayRule),
            RuleEntry("POST", "/v1/order/delivery/calc/combined", objectRule),
        )

        val chosen = ScenarioRequestState().selectRule(
            ruleKey = RuleKey("POST", "/v1/order/delivery/calc/combined"),
            rules = rules,
            requestBody = """[{"deliveryType":"store"}]""",
        )

        assertEquals(arrayRule, chosen)
    }

    @Test
    fun `selectRule rejects request body with wrong json root`() {
        val arrayRule = MockRule(
            method = "POST",
            path = "/v1/order/delivery/calc/combined",
            requestBodyContains = listOf("deliveryType", "store"),
            requestBodyJsonRoot = MockRuleRequestBodyJsonRoot.Array,
            fixture = "array.json",
        )
        val rules = listOf(RuleEntry("POST", "/v1/order/delivery/calc/combined", arrayRule))

        val chosen = ScenarioRequestState().selectRule(
            ruleKey = RuleKey("POST", "/v1/order/delivery/calc/combined"),
            rules = rules,
            requestBody = """{"deliveryType":"store"}""",
        )

        assertEquals(null, chosen)
    }
}
