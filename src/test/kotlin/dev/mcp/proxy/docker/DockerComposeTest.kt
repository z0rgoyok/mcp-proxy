package dev.mcp.proxy.docker

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class DockerComposeTest {
    @Test
    fun `docker compose defines single proxy service with mcp and proxy ports`() {
        val compose = Files.readString(Path.of("docker-compose.yml"))

        assertContains(compose, "mcp-proxy:")
        assertFalse("mcp-proxy-control:" in compose)
        assertFalse("mcp-proxy-runtime:" in compose)
        assertFalse("- runtime" in compose)
        assertContains(compose, "mcp")
        assertContains(compose, "127.0.0.1:18081:18081")
        assertContains(compose, "127.0.0.1:18082:18082")
        assertContains(compose, "--host")
        assertContains(compose, "0.0.0.0")
        assertContains(compose, "http://127.0.0.1:18082/mcp")
        assertContains(compose, "healthcheck:")
        assertContains(compose, "./scenarios:/app/scenarios:ro")
        assertContains(compose, "./fixtures:/app/fixtures:ro")
    }
}
