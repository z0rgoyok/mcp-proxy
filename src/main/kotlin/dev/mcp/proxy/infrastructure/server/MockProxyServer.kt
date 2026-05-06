package dev.mcp.proxy.infrastructure.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.PipelineCall
import io.ktor.server.application.call
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.util.pipeline.PipelineContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import dev.mcp.proxy.domain.ProxyPort
import dev.mcp.proxy.domain.UpstreamBaseUrl
import dev.mcp.proxy.domain.scenario.MockRule
import dev.mcp.proxy.domain.scenario.MockRuleBodyMode
import dev.mcp.proxy.domain.scenario.MockScenario
import dev.mcp.proxy.domain.scenario.ScenarioRepository
import dev.mcp.proxy.infrastructure.logging.ProxyEventLogger
import dev.mcp.proxy.infrastructure.logging.ProxyLogEvent
import dev.mcp.proxy.infrastructure.logging.StdoutProxyEventLogger

class MockProxyServer(
    private val scenarioRepository: ScenarioRepository,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val eventLogger: ProxyEventLogger = StdoutProxyEventLogger(),
    private val clock: () -> Instant = Instant::now,
    private val json: Json = Json { prettyPrint = false; encodeDefaults = true },
) {
    private val requestJournal = ProxyRequestJournal()
    private val adminApi = ProxyAdminApi(scenarioRepository = scenarioRepository, json = json)

    fun start(
        scenario: MockScenario,
        proxyPort: ProxyPort,
        upstreamBaseUrl: UpstreamBaseUrl,
        stateDirectory: Path,
    ): EmbeddedServer<*, *> {
        val journalFile = requestJournal.journalFile(stateDirectory)
        Files.createDirectories(journalFile.parent)
        val rules = scenario.rules.map { RuleEntry(it.method.uppercase(), normalizeProxyPath(it.path), it) }
        val server = embeddedServer(factory = Netty, host = BIND_HOST, port = proxyPort.value) {
            routing {
                get(ProxyAdminApi.ADMIN_BASE_PATH) { adminApi.respondDashboard(call, stateDirectory, proxyPort.value) }
                get("${ProxyAdminApi.ADMIN_BASE_PATH}/assets/admin.css") { adminApi.respondAsset(call, "admin.css") }
                get("${ProxyAdminApi.ADMIN_BASE_PATH}/assets/admin.js") { adminApi.respondAsset(call, "admin.js") }
                get("${ProxyAdminApi.ADMIN_BASE_PATH}/api/status") { adminApi.respondStatus(call, stateDirectory, proxyPort.value) }
                get("${ProxyAdminApi.ADMIN_BASE_PATH}/api/state") { adminApi.respondState(call, stateDirectory) }
                get("${ProxyAdminApi.ADMIN_BASE_PATH}/api/journal") {
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_ADMIN_LIMIT
                    adminApi.respondJournal(call, stateDirectory, limit)
                }
                get("${ProxyAdminApi.ADMIN_BASE_PATH}/api/body") {
                    adminApi.respondBodyFile(call, stateDirectory, call.request.queryParameters["path"])
                }
            }
            intercept(ApplicationCallPipeline.Call) {
                val ruleKey = RuleKey(call.request.httpMethod.value.uppercase(), normalizeProxyPath(call.request.path()))
                if (ruleKey.path.startsWith(ProxyAdminApi.ADMIN_BASE_PATH)) {
                    return@intercept
                }
                if (ruleKey.method == CONNECT_METHOD) {
                    handleUnsupportedConnect(this, ruleKey, scenario, stateDirectory, journalFile)
                    return@intercept
                }
                val requestBody = call.receiveText()
                handleFixture(this, ruleKey, rules, scenario, stateDirectory, journalFile, requestBody)
                    ?: handlePassthrough(this, ruleKey, upstreamBaseUrl, scenario, stateDirectory, journalFile, requestBody)
            }
        }.start(wait = false)
        eventLogger.log(
            ProxyLogEvent.Started(
                timestamp = clock(),
                scenario = scenario.name,
                bindHost = BIND_HOST,
                port = proxyPort.value,
                upstreamBaseUrl = upstreamBaseUrl.value,
                stateDirectory = stateDirectory,
            ),
        )
        return server
    }

    private suspend fun handleUnsupportedConnect(
        context: PipelineContext<Unit, PipelineCall>,
        ruleKey: RuleKey,
        scenario: MockScenario,
        stateDirectory: Path,
        journalFile: Path,
    ) {
        val responseBody = buildUnsupportedConnectResponse(ruleKey.path)
        context.journalRequest(
            ruleKey = ruleKey,
            scenario = scenario.name,
            mode = CONNECT_UNSUPPORTED_MODE,
            status = HttpStatusCode.NotImplemented.value,
            fixture = null,
            requestBody = "",
            responseBody = responseBody.toByteArray(),
            stateDirectory = stateDirectory,
            journalFile = journalFile,
        )
        context.respondJsonResponse(responseBody, HttpStatusCode.NotImplemented)
    }

    private suspend fun handleFixture(
        context: PipelineContext<Unit, PipelineCall>,
        ruleKey: RuleKey,
        rules: List<RuleEntry>,
        scenario: MockScenario,
        stateDirectory: Path,
        journalFile: Path,
        requestBody: String,
    ): Unit? {
        val rule = rules.firstOrNull { it.method == ruleKey.method && pathMatches(it.path, ruleKey.path) }?.rule ?: return null
        val status = HttpStatusCode.fromValue(rule.status)
        val responseBody = responseBody(rule)
        context.journalRequest(
            ruleKey = ruleKey,
            scenario = scenario.name,
            mode = MOCK_MODE,
            status = status.value,
            fixture = rule.fixture,
            requestBody = requestBody,
            responseBody = responseBody,
            stateDirectory = stateDirectory,
            journalFile = journalFile,
            bodyMode = rule.bodyMode.scenarioValue,
            delayMillis = rule.delayMillis,
            timeoutMillis = rule.timeoutMillis,
            effectiveDelayMillis = rule.responseDelayMillis,
        )
        if (rule.responseDelayMillis > 0) delay(rule.responseDelayMillis)
        context.respondRuleResponse(responseBody, status, rule.bodyMode)
        return Unit
    }

    private suspend fun handlePassthrough(
        context: PipelineContext<Unit, PipelineCall>,
        ruleKey: RuleKey,
        upstreamBaseUrl: UpstreamBaseUrl,
        scenario: MockScenario,
        stateDirectory: Path,
        journalFile: Path,
        requestBody: String,
    ) {
        val upstreamUrl = buildUpstreamUrl(upstreamBaseUrl, context.call.request.uri)
        val upstreamResponse = withContext(Dispatchers.IO) {
            httpClient.send(
                buildUpstreamRequest(ruleKey.method, upstreamUrl, context.call.request.headers.entries(), requestBody),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
        }
        context.journalRequest(ruleKey, scenario.name, PASSTHROUGH_MODE, upstreamResponse.statusCode(), null, requestBody, upstreamResponse.body(), stateDirectory, journalFile, upstreamUrl)
        context.call.respondBytes(
            bytes = upstreamResponse.body(),
            contentType = upstreamResponse.headers().firstValue(HttpHeaders.ContentType).map(ContentType::parse).orElse(ContentType.Application.Json),
            status = HttpStatusCode.fromValue(upstreamResponse.statusCode()),
        )
        context.finish()
    }

    private suspend fun PipelineContext<Unit, PipelineCall>.respondJsonResponse(
        body: String,
        status: HttpStatusCode,
    ) {
        call.respondText(text = body, contentType = ContentType.Application.Json, status = status)
        finish()
    }

    private fun PipelineContext<Unit, PipelineCall>.journalRequest(
        ruleKey: RuleKey,
        scenario: String,
        mode: String,
        status: Int,
        fixture: String?,
        requestBody: String,
        responseBody: ByteArray,
        stateDirectory: Path,
        journalFile: Path,
        upstreamUrl: String? = null,
        bodyMode: String? = null,
        delayMillis: Long? = null,
        timeoutMillis: Long? = null,
        effectiveDelayMillis: Long? = null,
    ) {
        val requestBodyFile = requestJournal.storeBody(stateDirectory, requestBody.toByteArray(), requestBodySuffix(mode))
        val responseBodyFile = requestJournal.storeBody(stateDirectory, responseBody, responseBodySuffix(mode))
        val timestamp = clock()
        requestJournal.writeEvent(
            journalFile = journalFile,
            event = ProxyRequestJournal.JournalEvent(
                timestamp = timestamp.toString(),
                method = ruleKey.method,
                path = ruleKey.path,
                uri = call.request.uri,
                scenario = scenario,
                mode = mode,
                status = status,
                fixture = fixture,
                requestBodyFile = requestBodyFile,
                responseBodyFile = responseBodyFile,
                bodyMode = bodyMode,
                delayMillis = delayMillis,
                timeoutMillis = timeoutMillis,
                effectiveDelayMillis = effectiveDelayMillis,
            ),
        )
        eventLogger.log(
            ProxyLogEvent.RequestHandled(
                timestamp = timestamp,
                scenario = scenario,
                method = ruleKey.method,
                path = ruleKey.path,
                mode = mode,
                status = status,
                fixture = fixture,
                upstreamUrl = upstreamUrl,
                requestBodyFile = requestBodyFile,
                responseBodyFile = responseBodyFile,
                bodyMode = bodyMode,
                delayMillis = delayMillis,
                timeoutMillis = timeoutMillis,
                effectiveDelayMillis = effectiveDelayMillis,
            ),
        )
    }

    private fun responseBody(rule: MockRule): ByteArray {
        return when (rule.bodyMode) {
            MockRuleBodyMode.Fixture -> scenarioRepository.loadFixture(rule).toByteArray()
            MockRuleBodyMode.Empty,
            MockRuleBodyMode.ConnectionClose,
            -> ByteArray(0)
        }
    }

    private suspend fun PipelineContext<Unit, PipelineCall>.respondRuleResponse(
        body: ByteArray,
        status: HttpStatusCode,
        bodyMode: MockRuleBodyMode,
    ) {
        if (bodyMode == MockRuleBodyMode.ConnectionClose) {
            call.response.headers.append(HttpHeaders.Connection, "close")
        }
        call.respondBytes(bytes = body, contentType = ContentType.Application.Json, status = status)
        finish()
    }

    private fun requestBodySuffix(mode: String): String = if (mode == PASSTHROUGH_MODE) "request.txt" else "request.json"

    private fun responseBodySuffix(mode: String): String = if (mode == PASSTHROUGH_MODE) "response.bin" else "response.json"

    private fun buildUpstreamUrl(upstreamBaseUrl: UpstreamBaseUrl, uri: String): String {
        return upstreamBaseUrl.value.trimEnd('/') + normalizeProxyPath(uri)
    }

    private fun buildUnsupportedConnectResponse(target: String): String {
        return json.encodeToString(
            UnsupportedConnectResponse.serializer(),
            UnsupportedConnectResponse(
                error = "https_connect_unsupported",
                target = target,
                message = "mcp-proxy received HTTPS CONNECT from Android system proxy. Scenario mocking for HTTPS requires a CONNECT MITM layer with host certificates from the local CA; the current runtime handles HTTP mock traffic and reports this boundary explicitly.",
            ),
        )
    }

    private fun buildUpstreamRequest(
        method: String,
        url: String,
        headers: Set<Map.Entry<String, List<String>>>,
        body: String,
    ): HttpRequest {
        val bodyPublisher = if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        val builder = HttpRequest.newBuilder(URI.create(url)).method(method, bodyPublisher)
        headers.forEach { (name, values) ->
            if (name.lowercase() !in ProxyRequestJournal.SKIPPED_UPSTREAM_HEADERS) values.forEach { builder.header(name, it) }
        }
        return builder.build()
    }

    companion object {
        private const val BIND_HOST = "0.0.0.0"
        private const val DEFAULT_ADMIN_LIMIT = 50
        private const val MOCK_MODE = "mock"
        private const val PASSTHROUGH_MODE = "passthrough"
        private const val CONNECT_METHOD = "CONNECT"
        private const val CONNECT_UNSUPPORTED_MODE = "https_connect_unsupported"
    }
}

@Serializable
private data class UnsupportedConnectResponse(
    val error: String,
    val target: String,
    val message: String,
)
