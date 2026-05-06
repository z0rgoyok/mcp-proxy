package dev.mcp.proxy.infrastructure.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request as OkHttpRequest
import dev.mcp.proxy.domain.ProxyPort
import dev.mcp.proxy.domain.UpstreamBaseUrl
import dev.mcp.proxy.domain.UpstreamProxyUrl
import dev.mcp.proxy.domain.scenario.MockRule
import dev.mcp.proxy.domain.scenario.MockRuleBodyMode
import dev.mcp.proxy.domain.scenario.MockScenario
import dev.mcp.proxy.domain.scenario.ScenarioRepository
import dev.mcp.proxy.infrastructure.logging.ProxyLogEvent
import dev.mcp.proxy.infrastructure.runtime.PersistedRuntimeState

class MockProxyServerTest {
    private val httpClient = HttpClient.newHttpClient()
    private val fixedTime = Instant.parse("2026-04-28T09:00:00Z")

    @Test
    fun `mock rule returns fixture and writes journal and readable log events`() = runTest {
        val port = freePort()
        val stateDirectory = createTempDirectory()
        val eventLogger = RecordingProxyEventLogger()
        val server = MockProxyServer(
            scenarioRepository = InMemoryScenarioRepository(
                fixtures = mapOf("health.json" to """{"status":"mock"}"""),
            ),
            eventLogger = eventLogger,
            clock = { fixedTime },
        ).start(
            scenario = MockScenario(
                name = "demo",
                rules = listOf(
                    MockRule(
                        method = "GET",
                        path = "/health",
                        fixture = "health.json",
                    ),
                ),
            ),
            proxyPort = ProxyPort(port),
            upstreamBaseUrl = UpstreamBaseUrl("http://127.0.0.1:${freePort()}"),
            stateDirectory = stateDirectory,
        )

        try {
            val response = get("http://127.0.0.1:$port/health")

            assertEquals(200, response.statusCode())
            assertEquals("""{"status":"mock"}""", response.body())
            assertContains(stateDirectory.resolve("journal/events.jsonl").toFile().readText(), """"mode":"mock"""")
            assert(stateDirectory.resolve("journal/bodies").toFile().listFiles().orEmpty().isNotEmpty())
            assertIs<ProxyLogEvent.Started>(eventLogger.events[0])
            val requestLog = assertIs<ProxyLogEvent.RequestHandled>(eventLogger.events[1])
            assertEquals(fixedTime, requestLog.timestamp)
            assertEquals("demo", requestLog.scenario)
            assertEquals("GET", requestLog.method)
            assertEquals("/health", requestLog.path)
            assertEquals("mock", requestLog.mode)
            assertEquals(200, requestLog.status)
            assertEquals("health.json", requestLog.fixture)
            assertEquals(null, requestLog.upstreamUrl)
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 0)
        }
    }

    @Test
    fun `mock rule waits long enough for client timeout`() = runTest {
        val port = freePort()
        val stateDirectory = createTempDirectory()
        val server = MockProxyServer(
            scenarioRepository = InMemoryScenarioRepository(
                fixtures = mapOf("feedback.json" to """{"status":"slow"}"""),
            ),
            eventLogger = RecordingProxyEventLogger(),
            clock = { fixedTime },
        ).start(
            scenario = MockScenario(
                name = "demo",
                rules = listOf(
                    MockRule(
                        method = "GET",
                        path = "/v1/order/feedback/form/info",
                        status = 500,
                        timeoutMillis = 500,
                        fixture = "feedback.json",
                    ),
                ),
            ),
            proxyPort = ProxyPort(port),
            upstreamBaseUrl = UpstreamBaseUrl("http://127.0.0.1:${freePort()}"),
            stateDirectory = stateDirectory,
        )

        try {
            assertFailsWith<HttpTimeoutException> {
                get("http://127.0.0.1:$port/v1/order/feedback/form/info", Duration.ofMillis(100))
            }
            assertContains(stateDirectory.resolve("journal/events.jsonl").toFile().readText(), """"status":500""")
            assertContains(stateDirectory.resolve("journal/events.jsonl").toFile().readText(), """"bodyMode":"fixture"""")
            assertContains(stateDirectory.resolve("journal/events.jsonl").toFile().readText(), """"timeoutMillis":500""")
            assertContains(stateDirectory.resolve("journal/events.jsonl").toFile().readText(), """"effectiveDelayMillis":500""")
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 0)
        }
    }

    @Test
    fun `mock rule can return empty body and connection close header`() = runTest {
        val port = freePort()
        val stateDirectory = createTempDirectory()
        val eventLogger = RecordingProxyEventLogger()
        val server = MockProxyServer(
            scenarioRepository = InMemoryScenarioRepository(
                fixtures = mapOf("unused.json" to """{"status":"unused"}"""),
            ),
            eventLogger = eventLogger,
            clock = { fixedTime },
        ).start(
            scenario = MockScenario(
                name = "demo",
                rules = listOf(
                    MockRule(
                        method = "GET",
                        path = "/empty",
                        status = 204,
                        bodyMode = MockRuleBodyMode.Empty,
                        fixture = "unused.json",
                    ),
                    MockRule(
                        method = "GET",
                        path = "/connection-close",
                        status = 500,
                        bodyMode = MockRuleBodyMode.ConnectionClose,
                        fixture = "unused.json",
                    ),
                ),
            ),
            proxyPort = ProxyPort(port),
            upstreamBaseUrl = UpstreamBaseUrl("http://127.0.0.1:${freePort()}"),
            stateDirectory = stateDirectory,
        )

        try {
            val emptyResponse = get("http://127.0.0.1:$port/empty")
            val closeResponse = get("http://127.0.0.1:$port/connection-close")

            assertEquals(204, emptyResponse.statusCode())
            assertEquals("", emptyResponse.body())
            assertEquals(500, closeResponse.statusCode())
            assertEquals("", closeResponse.body())
            assertEquals("close", closeResponse.headers().firstValue("connection").orElse(""))
            val journal = stateDirectory.resolve("journal/events.jsonl").toFile().readText()
            assertContains(journal, """"bodyMode":"empty"""")
            assertContains(journal, """"bodyMode":"connectionClose"""")
            val closeLog = assertIs<ProxyLogEvent.RequestHandled>(eventLogger.events.last())
            assertEquals("connectionClose", closeLog.bodyMode)
            assertEquals(null, closeLog.responseBodyFile)
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 0)
        }
    }

    @Test
    fun `unknown request passes through to upstream and writes journal event`() = runTest {
        val upstreamPort = freePort()
        val upstream = embeddedServer(
            factory = Netty,
            host = "127.0.0.1",
            port = upstreamPort,
        ) {
            routing {
                get("/real") {
                    call.respondText(
                        text = """{"status":"upstream"}""",
                        contentType = ContentType.Application.Json,
                        status = HttpStatusCode.Accepted,
                    )
                }
            }
        }.start(wait = false)
        val proxyPort = freePort()
        val stateDirectory = createTempDirectory()
        val eventLogger = RecordingProxyEventLogger()
        val proxy = MockProxyServer(
            scenarioRepository = InMemoryScenarioRepository(fixtures = emptyMap()),
            eventLogger = eventLogger,
            clock = { fixedTime },
        ).start(
            scenario = MockScenario(name = "demo", rules = emptyList()),
            proxyPort = ProxyPort(proxyPort),
            upstreamBaseUrl = UpstreamBaseUrl("http://127.0.0.1:$upstreamPort"),
            stateDirectory = stateDirectory,
        )

        try {
            val response = get("http://127.0.0.1:$proxyPort/real")

            assertEquals(202, response.statusCode())
            assertEquals("""{"status":"upstream"}""", response.body())
            assertContains(stateDirectory.resolve("journal/events.jsonl").toFile().readText(), """"mode":"passthrough"""")
            val requestLog = assertIs<ProxyLogEvent.RequestHandled>(eventLogger.events[1])
            assertEquals("passthrough", requestLog.mode)
            assertEquals(202, requestLog.status)
            assertEquals("http://127.0.0.1:$upstreamPort/real", requestLog.upstreamUrl)
        } finally {
            proxy.stop(gracePeriodMillis = 0, timeoutMillis = 0)
            upstream.stop(gracePeriodMillis = 0, timeoutMillis = 0)
        }
    }

    @Test
    fun `connect request returns diagnostic instead of passthrough failure`() = runTest {
        val proxyPort = freePort()
        val stateDirectory = createTempDirectory()
        val eventLogger = RecordingProxyEventLogger()
        val proxy = MockProxyServer(
            scenarioRepository = InMemoryScenarioRepository(fixtures = emptyMap()),
            eventLogger = eventLogger,
            clock = { fixedTime },
        ).start(
            scenario = MockScenario(name = "demo", rules = emptyList()),
            proxyPort = ProxyPort(proxyPort),
            upstreamBaseUrl = UpstreamBaseUrl("http://127.0.0.1:${freePort()}"),
            stateDirectory = stateDirectory,
        )

        try {
            val response = sendRawConnect(proxyPort)

            assertContains(response, "501 Not Implemented")
            assertContains(response, "https_connect_unsupported")
            assertContains(response, "CONNECT MITM layer")
            assertContains(stateDirectory.resolve("journal/events.jsonl").toFile().readText(), """"mode":"https_connect_unsupported"""")
            val requestLog = assertIs<ProxyLogEvent.RequestHandled>(eventLogger.events[1])
            assertEquals("CONNECT", requestLog.method)
            assertEquals("/api.example.com:443", requestLog.path)
            assertEquals("https_connect_unsupported", requestLog.mode)
            assertEquals(501, requestLog.status)
        } finally {
            proxy.stop(gracePeriodMillis = 0, timeoutMillis = 0)
        }
    }

    @Test
    fun `mitm proxy decrypts https request and returns fixture`() = runTest {
        val proxyPort = freePort()
        val stateDirectory = createTempDirectory()
        val eventLogger = RecordingProxyEventLogger()
        val proxy = MitmMockProxyServer(
            scenarioRepository = InMemoryScenarioRepository(
                fixtures = mapOf("health.json" to """{"status":"mitm"}"""),
            ),
            eventLogger = eventLogger,
            clock = { fixedTime },
        ).start(
            scenario = MockScenario(
                name = "demo",
                rules = listOf(
                    MockRule(
                        method = "GET",
                        path = "/health",
                        fixture = "health.json",
                    ),
                ),
            ),
            proxyPort = ProxyPort(proxyPort),
            upstreamBaseUrl = UpstreamBaseUrl("https://backend.example"),
            stateDirectory = stateDirectory,
        )

        try {
            val dashboard = get("http://127.0.0.1:$proxyPort/admin")
            val status = get("http://127.0.0.1:$proxyPort/admin/api/status")
            val client = trustedProxyClient(
                proxyPort = proxyPort,
                caCertificate = stateDirectory.resolve("ca/mcp-proxy-root-ca.pem"),
            )
            val response = client.newCall(
                OkHttpRequest.Builder()
                    .url("https://api.example.com/health")
                    .build(),
            ).execute()

            response.use {
                assertEquals(200, it.code)
                assertEquals("""{"status":"mitm"}""", it.body?.string())
            }
            assertEquals(200, dashboard.statusCode())
            assertContains(dashboard.body(), "Generic Proxy Admin")
            assertContains(status.body(), """"scenario":"demo"""")
            assertContains(stateDirectory.resolve("journal/events.jsonl").toFile().readText(), """"mode":"mock"""")
            val requestLog = assertIs<ProxyLogEvent.RequestHandled>(eventLogger.events[1])
            assertEquals("GET", requestLog.method)
            assertEquals("/health", requestLog.path)
            assertEquals("mock", requestLog.mode)
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun `mitm proxy keeps port alive while active scenario changes`() = runTest {
        val upstreamPort = freePort()
        val upstream = embeddedServer(
            factory = Netty,
            host = "127.0.0.1",
            port = upstreamPort,
        ) {
            routing {
                get("/health") {
                    call.respondText(
                        text = """{"status":"upstream"}""",
                        contentType = ContentType.Application.Json,
                    )
                }
            }
        }.start(wait = false)
        val proxyPort = freePort()
        val stateDirectory = createTempDirectory()
        val eventLogger = RecordingProxyEventLogger()
        val proxy = MitmMockProxyServer(
            scenarioRepository = InMemoryScenarioRepository(
                fixtures = mapOf("health.json" to """{"status":"mock"}"""),
            ),
            eventLogger = eventLogger,
            clock = { fixedTime },
        ).start(
            activeScenario = null,
            proxyPort = ProxyPort(proxyPort),
            upstreamBaseUrl = UpstreamBaseUrl("http://127.0.0.1:$upstreamPort"),
            stateDirectory = stateDirectory,
        )

        try {
            assertEquals("""{"status":"upstream"}""", get("http://127.0.0.1:$proxyPort/health").body())

            proxy.activateScenario(
                ActiveScenarioSettings(
                    scenario = MockScenario(
                        name = "demo",
                        rules = listOf(MockRule(method = "GET", path = "/health", fixture = "health.json")),
                    ),
                    upstreamBaseUrl = UpstreamBaseUrl("http://127.0.0.1:$upstreamPort"),
                ),
            )
            assertEquals("""{"status":"mock"}""", get("http://127.0.0.1:$proxyPort/health").body())

            proxy.deactivateScenario()
            assertEquals("""{"status":"upstream"}""", get("http://127.0.0.1:$proxyPort/health").body())
        } finally {
            proxy.stop()
            upstream.stop(gracePeriodMillis = 0, timeoutMillis = 0)
        }
    }

    @Test
    fun `passthrough request can be sent through upstream proxy`() = runTest {
        RecordingHttpProxy().use { upstreamProxy ->
            val proxyPort = freePort()
            val stateDirectory = createTempDirectory()
            val proxy = MitmMockProxyServer(
                scenarioRepository = InMemoryScenarioRepository(fixtures = emptyMap()),
                eventLogger = RecordingProxyEventLogger(),
                clock = { fixedTime },
            ).start(
                activeScenario = null,
                proxyPort = ProxyPort(proxyPort),
                trafficSettings = ProxyTrafficSettings(
                    upstreamBaseUrl = UpstreamBaseUrl("http://backend.example"),
                    upstreamProxyUrl = UpstreamProxyUrl("http://127.0.0.1:${upstreamProxy.port}"),
                ),
                stateDirectory = stateDirectory,
            )

            try {
                val response = get("http://127.0.0.1:$proxyPort/real")
                val captured = upstreamProxy.awaitRequest()

                assertEquals(202, response.statusCode())
                assertEquals("""{"status":"upstreamProxy"}""", response.body())
                assertEquals("GET http://backend.example/real HTTP/1.1", captured.requestLine)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun `mock response can be mirrored to upstream proxy without changing app response`() = runTest {
        RecordingHttpProxy().use { upstreamProxy ->
            val proxyPort = freePort()
            val stateDirectory = createTempDirectory()
            val proxy = MitmMockProxyServer(
                scenarioRepository = InMemoryScenarioRepository(
                    fixtures = mapOf("health.json" to """{"status":"mock"}"""),
                ),
                eventLogger = RecordingProxyEventLogger(),
                clock = { fixedTime },
            ).start(
                activeScenario = ActiveScenarioSettings(
                    scenario = MockScenario(
                        name = "demo",
                        rules = listOf(MockRule(method = "POST", path = "/health", fixture = "health.json")),
                    ),
                    trafficSettings = ProxyTrafficSettings(
                        upstreamBaseUrl = UpstreamBaseUrl("http://backend.example"),
                        upstreamProxyUrl = UpstreamProxyUrl("http://127.0.0.1:${upstreamProxy.port}"),
                        mirrorMockRequests = true,
                        mirrorBaseUrl = dev.mcp.proxy.domain.MirrorBaseUrl("http://127.0.0.1:$proxyPort/__proxy_mirror"),
                    ),
                ),
                proxyPort = ProxyPort(proxyPort),
                trafficSettings = ProxyTrafficSettings(
                    upstreamBaseUrl = UpstreamBaseUrl("http://backend.example"),
                    upstreamProxyUrl = UpstreamProxyUrl("http://127.0.0.1:${upstreamProxy.port}"),
                    mirrorMockRequests = true,
                    mirrorBaseUrl = dev.mcp.proxy.domain.MirrorBaseUrl("http://127.0.0.1:$proxyPort/__proxy_mirror"),
                ),
                stateDirectory = stateDirectory,
            )

            try {
                val response = post("http://127.0.0.1:$proxyPort/health")
                val captured = upstreamProxy.awaitRequest()

                assertEquals(200, response.statusCode())
                assertEquals("""{"status":"mock"}""", response.body())
                assertEquals("POST http://127.0.0.1:$proxyPort/__proxy_mirror/health HTTP/1.1", captured.requestLine)
                assertEquals("mock", captured.headers.getValue("x-proxy-mode"))
                assertEquals("demo", captured.headers.getValue("x-proxy-scenario"))
                assertEquals("health.json", captured.headers.getValue("x-proxy-fixture"))
                assertEquals("""{"status":"app"}""", captured.body)
            } finally {
                proxy.stop()
            }
        }
    }

    @Test
    fun `admin dashboard exposes html status state journal and body file`() = runTest {
        val proxyPort = freePort()
        val stateDirectory = createTempDirectory()
        Files.createDirectories(stateDirectory.resolve("journal/bodies"))
        Files.writeString(
            stateDirectory.resolve("runtime.json"),
            Json.encodeToString(
                PersistedRuntimeState.serializer(),
                PersistedRuntimeState("demo", proxyPort, "https://example.com", true),
            ),
        )
        Files.createDirectories(stateDirectory.resolve("kv"))
        Files.writeString(stateDirectory.resolve("kv/session.json"), """{"screen":"dashboard"}""")
        Files.writeString(stateDirectory.resolve("journal/bodies/request.json"), """{"ping":"request"}""")
        Files.createDirectories(stateDirectory.resolve("journal"))
        Files.writeString(
            stateDirectory.resolve("journal/events.jsonl"),
            """{"timestamp":"2026-04-28T09:00:00Z","method":"POST","path":"/v1/resource","uri":"/v1/resource","scenario":"demo","mode":"mock","status":200,"fixture":"resource.json","requestBodyFile":"journal/bodies/request.json","responseBodyFile":null}""" + "\n",
        )
        val proxy = MockProxyServer(
            scenarioRepository = InMemoryScenarioRepository(fixtures = emptyMap(), scenarios = listOf("demo", "profile")),
            eventLogger = RecordingProxyEventLogger(),
            clock = { fixedTime },
        ).start(
            scenario = MockScenario(name = "demo", rules = emptyList()),
            proxyPort = ProxyPort(proxyPort),
            upstreamBaseUrl = UpstreamBaseUrl("http://127.0.0.1:${freePort()}"),
            stateDirectory = stateDirectory,
        )

        try {
            val html = get("http://127.0.0.1:$proxyPort/admin")
            val status = get("http://127.0.0.1:$proxyPort/admin/api/status")
            val state = get("http://127.0.0.1:$proxyPort/admin/api/state")
            val journal = get("http://127.0.0.1:$proxyPort/admin/api/journal?limit=10")
            val body = get("http://127.0.0.1:$proxyPort/admin/api/body?path=journal/bodies/request.json")

            assertEquals(200, html.statusCode())
            assertContains(html.body(), "Generic Proxy Admin")
            assertContains(html.body(), "/admin/assets/admin.js")
            assertContains(status.body(), """"scenario":"demo"""")
            assertContains(status.body(), """"availableScenarios":["demo","profile"]""")
            assertContains(state.body(), """"key":"session"""")
            assertContains(state.body(), """"rawJson":"{\"screen\":\"dashboard\"}"""")
            assertContains(journal.body(), """"mode":"mock"""")
            assertEquals("""{"ping":"request"}""", body.body())
        } finally {
            proxy.stop(gracePeriodMillis = 0, timeoutMillis = 0)
        }
    }

    private fun get(url: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun get(
        url: String,
        timeout: Duration,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun post(url: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .POST(HttpRequest.BodyPublishers.ofString("""{"status":"app"}"""))
            .header("Content-Type", "application/json")
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun sendRawConnect(port: Int): String {
        val target = "api.example.com:443"
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 2_000
            socket.getOutputStream().write(
                "CONNECT $target HTTP/1.1\r\nHost: $target\r\n\r\n".toByteArray(),
            )
            val reader = socket.getInputStream().bufferedReader()
            val headers = generateSequence { reader.readLine() }
                .takeWhile { line -> line.isNotEmpty() }
                .toList()
            val contentLength = headers.firstNotNullOfOrNull { line ->
                line.substringAfter("Content-Length: ", missingDelimiterValue = "")
                    .takeIf { it.isNotBlank() }
                    ?.toInt()
            } ?: 0
            val body = CharArray(contentLength)
            reader.read(body)
            return (headers + body.concatToString()).joinToString(separator = "\n")
        }
    }

    private fun trustedProxyClient(
        proxyPort: Int,
        caCertificate: java.nio.file.Path,
    ): OkHttpClient {
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(Files.newInputStream(caCertificate))
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("mcp-proxy", certificate)
        }
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }
        val trustManager = trustManagerFactory.trustManagers.filterIsInstance<X509TrustManager>().single()
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), null)
        }
        return OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)))
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    }

    private fun freePort(): Int {
        return ServerSocket(0).use { socket -> socket.localPort }
    }

    private class InMemoryScenarioRepository(
        private val fixtures: Map<String, String>,
        private val scenarios: List<String> = emptyList(),
    ) : ScenarioRepository {
        override fun list(): List<dev.mcp.proxy.domain.ScenarioName> {
            return scenarios.map { value -> dev.mcp.proxy.domain.ScenarioName(value) }
        }

        override fun load(scenarioName: dev.mcp.proxy.domain.ScenarioName): MockScenario {
            return MockScenario(name = scenarioName.value, rules = emptyList())
        }

        override fun loadFixture(rule: MockRule): String {
            return fixtures.getValue(rule.fixture)
        }
    }

    private class RecordingProxyEventLogger : dev.mcp.proxy.infrastructure.logging.ProxyEventLogger {
        val events = mutableListOf<ProxyLogEvent>()

        override fun log(event: ProxyLogEvent) {
            events += event
        }
    }

    private class RecordingHttpProxy : AutoCloseable {
        private val serverSocket = ServerSocket(0)
        private val request = AtomicReference<CapturedRequest>()
        private val latch = CountDownLatch(1)
        private val worker = Thread {
            serverSocket.use { socket ->
                socket.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    val requestLine = reader.readLine()
                    val headers = mutableMapOf<String, String>()
                    generateSequence { reader.readLine() }
                        .takeWhile(String::isNotEmpty)
                        .forEach { line ->
                            val name = line.substringBefore(":").lowercase(Locale.ROOT)
                            val value = line.substringAfter(":", "").trim()
                            headers[name] = value
                        }
                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    val requestBody = CharArray(contentLength)
                    if (contentLength > 0) {
                        reader.read(requestBody)
                    }
                    request.set(
                        CapturedRequest(
                            requestLine = requestLine,
                            headers = headers,
                            body = requestBody.concatToString(),
                        ),
                    )
                    latch.countDown()
                    val responseBody = """{"status":"upstreamProxy"}"""
                    client.getOutputStream().write(
                        (
                            "HTTP/1.1 202 Accepted\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${responseBody.toByteArray().size}\r\n" +
                                "\r\n" +
                                responseBody
                            ).toByteArray(),
                    )
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val port: Int = serverSocket.localPort

        fun awaitRequest(): CapturedRequest {
            check(latch.await(2, TimeUnit.SECONDS)) {
                "Expected upstream proxy request"
            }
            return requireNotNull(request.get())
        }

        override fun close() {
            runCatching { serverSocket.close() }
            worker.join(500)
        }
    }

    private data class CapturedRequest(
        val requestLine: String,
        val headers: Map<String, String>,
        val body: String,
    )
}
