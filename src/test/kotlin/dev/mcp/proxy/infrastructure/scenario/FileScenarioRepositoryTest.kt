package dev.mcp.proxy.infrastructure.scenario

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import dev.mcp.proxy.domain.ScenarioName
import dev.mcp.proxy.domain.scenario.MockRule
import dev.mcp.proxy.domain.scenario.MockRuleBodyMode
import dev.mcp.proxy.domain.scenario.MockRuleMode
import dev.mcp.proxy.domain.scenario.MockRuleRequestBodyJsonRoot

class FileScenarioRepositoryTest {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Test
    fun `lists scenario files sorted by name`() {
        val root = createTempDirectory()
        Files.createDirectories(root.resolve("scenarios"))
        Files.writeString(root.resolve("scenarios/b.json"), """{"name":"b","rules":[]}""")
        Files.writeString(root.resolve("scenarios/a.json"), """{"name":"a","rules":[]}""")

        val scenarios = FileScenarioRepository(rootDirectory = root, json = json).list()

        assertEquals(listOf("a", "b"), scenarios.map { scenarioName -> scenarioName.value })
    }

    @Test
    fun `loads scenario by name`() {
        val root = createTempDirectory()
        Files.createDirectories(root.resolve("scenarios"))
        Files.writeString(
            root.resolve("scenarios/demo.json"),
            """{"name":"demo","rules":[{"method":"GET","path":"/v1/resource","mode":"forbidden","sequence":2,"requestBodyContains":["legacy"],"requestBodyJsonRoot":"array","status":500,"delayMillis":150,"timeoutMillis":5000,"bodyMode":"empty","fixture":"demo/resource.json"}]}""",
        )

        val scenario = FileScenarioRepository(rootDirectory = root, json = json).load(ScenarioName("demo"))

        assertEquals("demo", scenario.name)
        val rule = scenario.rules.single()
        assertEquals("/v1/resource", rule.path)
        assertEquals(MockRuleMode.Forbidden, rule.mode)
        assertEquals(2, rule.sequence)
        assertEquals(listOf("legacy"), rule.requestBodyContains)
        assertEquals(MockRuleRequestBodyJsonRoot.Array, rule.requestBodyJsonRoot)
        assertEquals(500, rule.status)
        assertEquals(150, rule.delayMillis)
        assertEquals(5000, rule.timeoutMillis)
        assertEquals(MockRuleBodyMode.Empty, rule.bodyMode)
    }

    @Test
    fun `rejects fixture path escaping fixtures directory`() {
        val root = createTempDirectory()
        Files.createDirectories(root.resolve("fixtures"))
        val repository = FileScenarioRepository(rootDirectory = root, json = json)

        assertFailsWith<IllegalStateException> {
            repository.loadFixture(
                MockRule(
                    method = "GET",
                    path = "/v1/resource",
                    fixture = "../secret.json",
                ),
            )
        }
    }

    @Test
    fun `renders today token in fixture response`() {
        val root = createTempDirectory()
        Files.createDirectories(root.resolve("fixtures/delivery"))
        Files.writeString(
            root.resolve("fixtures/delivery/calc.json"),
            """{"deliveryDate":[{"date":"{{today}}"}]}""",
        )
        val repository = FileScenarioRepository(
            rootDirectory = root,
            json = json,
            fixtureTemplateRenderer = FixtureTemplateRenderer(
                clock = Clock.fixed(Instant.parse("2026-05-25T10:15:30Z"), ZoneOffset.UTC),
            ),
        )

        val fixture = repository.loadFixture(
            MockRule(
                method = "POST",
                path = "/buyer/v1/order/delivery/calc/combined",
                fixture = "delivery/calc.json",
            ),
        )

        assertEquals("""{"deliveryDate":[{"date":"2026-05-25"}]}""", fixture)
    }

    @Test
    fun `renders today and tomorrow tokens in fixture response`() {
        val root = createTempDirectory()
        Files.createDirectories(root.resolve("fixtures/delivery"))
        Files.writeString(
            root.resolve("fixtures/delivery/calc.json"),
            """{"deliveryDate":[{"date":"{{today}}"},{"date":"{{tomorrow}}"}]}""",
        )
        val repository = FileScenarioRepository(
            rootDirectory = root,
            json = json,
            fixtureTemplateRenderer = FixtureTemplateRenderer(
                clock = Clock.fixed(Instant.parse("2026-05-25T10:15:30Z"), ZoneOffset.UTC),
            ),
        )

        val fixture = repository.loadFixture(
            MockRule(
                method = "POST",
                path = "/buyer/v1/order/delivery/calc/combined",
                fixture = "delivery/calc.json",
            ),
        )

        assertEquals("""{"deliveryDate":[{"date":"2026-05-25"},{"date":"2026-05-26"}]}""", fixture)
    }

    @Test
    fun `rejects unknown fixture template token`() {
        val root = createTempDirectory()
        Files.createDirectories(root.resolve("fixtures"))
        Files.writeString(root.resolve("fixtures/unknown.json"), """{"date":"{{nextWeek}}"}""")
        val repository = FileScenarioRepository(rootDirectory = root, json = json)

        val error = assertFailsWith<IllegalStateException> {
            repository.loadFixture(
                MockRule(
                    method = "GET",
                    path = "/v1/resource",
                    fixture = "unknown.json",
                ),
            )
        }

        assertEquals("Unsupported fixture template token(s) in unknown.json: nextWeek", error.message)
    }
}
