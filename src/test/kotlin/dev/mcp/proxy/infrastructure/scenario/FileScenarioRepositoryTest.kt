package dev.mcp.proxy.infrastructure.scenario

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import dev.mcp.proxy.domain.ScenarioName
import dev.mcp.proxy.domain.scenario.MockRule
import dev.mcp.proxy.domain.scenario.MockRuleBodyMode

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
            """{"name":"demo","rules":[{"method":"GET","path":"/v1/resource","status":500,"delayMillis":150,"timeoutMillis":5000,"bodyMode":"empty","fixture":"demo/resource.json"}]}""",
        )

        val scenario = FileScenarioRepository(rootDirectory = root, json = json).load(ScenarioName("demo"))

        assertEquals("demo", scenario.name)
        val rule = scenario.rules.single()
        assertEquals("/v1/resource", rule.path)
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
}
