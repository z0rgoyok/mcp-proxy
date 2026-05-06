package dev.mcp.proxy.infrastructure.logging

import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class StdoutProxyEventLoggerTest {
    private val timestamp = Instant.parse("2026-04-28T09:00:00Z")

    @Test
    fun `formats proxy start as readable one-line operator message`() {
        val logger = StdoutProxyEventLogger()

        val line = logger.format(
            ProxyLogEvent.Started(
                timestamp = timestamp,
                scenario = "demo",
                bindHost = "0.0.0.0",
                port = 18081,
                upstreamBaseUrl = "https://example.test",
                stateDirectory = Path.of("/app/var/state"),
            ),
        )

        assertEquals(
            "2026-04-28T09:00:00Z | INFO  | START   | proxy listening | scenario=demo | " +
                "bind=0.0.0.0:18081 | upstream=https://example.test | state=/app/var/state",
            line,
        )
    }

    @Test
    fun `formats request with route mode and useful troubleshooting details`() {
        val logger = StdoutProxyEventLogger()

        val line = logger.format(
            ProxyLogEvent.RequestHandled(
                timestamp = timestamp,
                scenario = "demo",
                method = "POST",
                path = "/v1/resource",
                mode = "mock",
                status = 200,
                fixture = "demo/resource.json",
                upstreamUrl = null,
                requestBodyFile = "journal/bodies/request.json",
                responseBodyFile = "journal/bodies/response.json",
                bodyMode = "fixture",
                delayMillis = 100,
                timeoutMillis = 5000,
                effectiveDelayMillis = 5000,
            ),
        )

        assertContains(line, "2026-04-28T09:00:00Z | INFO  | MOCK     | POST /v1/resource -> 200")
        assertContains(line, "mode=mock")
        assertContains(line, "fixture=demo/resource.json")
        assertContains(line, "bodyMode=fixture")
        assertContains(line, "delayMillis=100")
        assertContains(line, "timeoutMillis=5000")
        assertContains(line, "effectiveDelayMillis=5000")
        assertContains(line, "requestBody=journal/bodies/request.json")
        assertContains(line, "responseBody=journal/bodies/response.json")
    }
}
