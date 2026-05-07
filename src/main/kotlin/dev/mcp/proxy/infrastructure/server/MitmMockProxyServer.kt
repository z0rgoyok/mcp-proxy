package dev.mcp.proxy.infrastructure.server

import io.github.cfraser.mitmproxy.Interceptor
import io.github.cfraser.mitmproxy.Proxier
import io.github.cfraser.mitmproxy.Request
import io.github.cfraser.mitmproxy.Response
import io.github.cfraser.mitmproxy.Server
import java.net.InetSocketAddress
import java.net.URI
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import dev.mcp.proxy.domain.ProxyPort
import dev.mcp.proxy.domain.UpstreamBaseUrl
import dev.mcp.proxy.domain.UpstreamProxyUrl
import dev.mcp.proxy.domain.scenario.MockRule
import dev.mcp.proxy.domain.scenario.MockRuleBodyMode
import dev.mcp.proxy.domain.scenario.MockRuleMode
import dev.mcp.proxy.domain.scenario.MockScenario
import dev.mcp.proxy.domain.scenario.ScenarioRepository
import dev.mcp.proxy.infrastructure.ca.LocalCaManager
import dev.mcp.proxy.infrastructure.logging.ProxyEventLogger
import dev.mcp.proxy.infrastructure.logging.ProxyLogEvent
import dev.mcp.proxy.infrastructure.logging.StdoutProxyEventLogger
import dev.mcp.proxy.infrastructure.runtime.PersistedRuntimeState

class MitmMockProxyServer(
    private val scenarioRepository: ScenarioRepository,
    private val eventLogger: ProxyEventLogger = StdoutProxyEventLogger(),
    private val clock: () -> Instant = Instant::now,
    private val json: Json = Json { prettyPrint = false; encodeDefaults = true },
) {
    private val requestJournal = ProxyRequestJournal()

    fun start(
        activeScenario: ActiveScenarioSettings?,
        proxyPort: ProxyPort,
        upstreamBaseUrl: UpstreamBaseUrl,
        stateDirectory: Path,
    ): ProxyServerHandle {
        return start(
            activeScenario = activeScenario,
            proxyPort = proxyPort,
            trafficSettings = ProxyTrafficSettings(upstreamBaseUrl = upstreamBaseUrl),
            stateDirectory = stateDirectory,
        )
    }

    fun start(
        activeScenario: ActiveScenarioSettings?,
        proxyPort: ProxyPort,
        trafficSettings: ProxyTrafficSettings = ProxyTrafficSettings(upstreamBaseUrl = UpstreamBaseUrl.Default),
        stateDirectory: Path,
    ): ProxyServerHandle {
        val journalFile = requestJournal.journalFile(stateDirectory)
        Files.createDirectories(journalFile.parent)
        val caState = LocalCaManager(stateDirectory = stateDirectory).generate()
        val certificatePath = Path.of(requireNotNull(caState.certificatePath))
        val privateKeyPath = certificatePath.parent.resolve(ROOT_CA_KEY_FILE)
        val executor = Executors.newCachedThreadPool()
        val proxier = ScenarioProxier(
            activeScenario = ActiveScenarioHolder(activeScenario),
            trafficSettings = TrafficSettingsHolder(trafficSettings),
            stateDirectory = stateDirectory,
            proxyPort = proxyPort.value,
            journalFile = journalFile,
            scenarioRepository = scenarioRepository,
            requestJournal = requestJournal,
            eventLogger = eventLogger,
            clock = clock,
            json = json,
            mirrorExecutor = executor,
        )
        val server = Server.create(
            interceptors = emptyArray<Interceptor>(),
            proxier = proxier,
            executor = executor,
            certificatePath = certificatePath,
            privateKeyPath = privateKeyPath,
        ).start(proxyPort.value)
        eventLogger.log(
            ProxyLogEvent.Started(
                timestamp = clock(),
                scenario = activeScenario?.scenario?.name ?: PASSTHROUGH_SCENARIO,
                bindHost = BIND_HOST,
                port = proxyPort.value,
                upstreamBaseUrl = trafficSettings.upstreamBaseUrl.value,
                stateDirectory = stateDirectory,
            ),
        )
        return object : ProxyServerHandle {
            override fun activateScenario(settings: ActiveScenarioSettings) {
                proxier.activateScenario(settings)
            }

            override fun configureTraffic(settings: ProxyTrafficSettings) {
                proxier.configureTraffic(settings)
            }

            override fun deactivateScenario() {
                proxier.deactivateScenario()
            }

            override fun stop() {
                server.stop()
                executor.shutdownNow()
            }
        }
    }

    fun start(
        scenario: MockScenario,
        proxyPort: ProxyPort,
        upstreamBaseUrl: UpstreamBaseUrl,
        stateDirectory: Path,
    ): ProxyServerHandle {
        return start(
            activeScenario = ActiveScenarioSettings(
                scenario = scenario,
                trafficSettings = ProxyTrafficSettings(upstreamBaseUrl = upstreamBaseUrl),
            ),
            proxyPort = proxyPort,
            trafficSettings = ProxyTrafficSettings(upstreamBaseUrl = upstreamBaseUrl),
            stateDirectory = stateDirectory,
        )
    }

    private class ScenarioProxier(
        private val activeScenario: ActiveScenarioHolder,
        private val trafficSettings: TrafficSettingsHolder,
        private val stateDirectory: Path,
        private val proxyPort: Int,
        private val journalFile: Path,
        private val scenarioRepository: ScenarioRepository,
        private val requestJournal: ProxyRequestJournal,
        private val eventLogger: ProxyEventLogger,
        private val clock: () -> Instant,
        private val json: Json,
        private val mirrorExecutor: ExecutorService,
        private val directProxier: Proxier = Proxier.create(),
    ) : Proxier {
        private val proxiers = ConcurrentHashMap<String, Proxier>()
        private val mirrorResponses = ConcurrentHashMap<String, ByteArray>()
        private val adminHtml = ProxyAdminHtml(json)
        private val stateStoreReader = StateStoreAdminReader()
        private val requestState = AtomicReference(ScenarioRequestState())

        fun activateScenario(settings: ActiveScenarioSettings) {
            requestState.set(ScenarioRequestState())
            activeScenario.update(settings)
        }

        fun configureTraffic(settings: ProxyTrafficSettings) {
            trafficSettings.update(settings)
        }

        fun deactivateScenario() {
            requestState.set(ScenarioRequestState())
            activeScenario.clear()
        }

        override fun execute(request: Request): Response {
            val ruleKey = RuleKey(request.method.uppercase(), normalizeProxyPath(request.uri.rawPath ?: request.uri.path))
            val requestBody = request.body?.toString(Charsets.UTF_8).orEmpty()
            val scenarioSettings = activeScenario.current()
            val traffic = scenarioSettings?.trafficSettings ?: trafficSettings.current()
            val adminResponse = handleAdmin(request, ruleKey)
            if (adminResponse != null) return adminResponse
            val mirrorResponse = handleMirror(request, ruleKey)
            if (mirrorResponse != null) return mirrorResponse
            val fixtureResponse = handleFixture(request, ruleKey, requestBody, scenarioSettings, traffic)
            if (fixtureResponse != null) return fixtureResponse
            return handlePassthrough(request, ruleKey, requestBody, scenarioSettings, traffic)
        }

        private fun handleAdmin(
            request: Request,
            ruleKey: RuleKey,
        ): Response? {
            if (ruleKey.method != "GET") return null
            val body = when (ruleKey.path) {
                ProxyAdminApi.ADMIN_BASE_PATH -> adminHtml.render(buildAdminStatus()).toByteArray()
                "${ProxyAdminApi.ADMIN_BASE_PATH}/assets/admin.css" -> adminResource("admin.css") ?: return notFound(request)
                "${ProxyAdminApi.ADMIN_BASE_PATH}/assets/admin.js" -> adminResource("admin.js") ?: return notFound(request)
                "${ProxyAdminApi.ADMIN_BASE_PATH}/api/status" -> json.encodeToString(AdminStatusResponse.serializer(), buildAdminStatus()).toByteArray()
                "${ProxyAdminApi.ADMIN_BASE_PATH}/api/state" -> json.encodeToString(AdminStateResponse.serializer(), buildAdminState()).toByteArray()
                "${ProxyAdminApi.ADMIN_BASE_PATH}/api/journal" -> json.encodeToString(AdminJournalResponse.serializer(), buildAdminJournal(request)).toByteArray()
                "${ProxyAdminApi.ADMIN_BASE_PATH}/api/body" -> adminBody(request) ?: return notFound(request)
                else -> return null
            }
            return Response(request, 200, adminHeaders(ruleKey.path, body.size), body)
        }

        private fun buildAdminStatus(): AdminStatusResponse {
            val runtimeFile = stateDirectory.resolve(RUNTIME_FILE)
            val persistedRuntime = readIfExists(runtimeFile)?.let { raw ->
                json.decodeFromString<PersistedRuntimeState>(raw)
            }
            val scenarioSettings = activeScenario.current()
            val traffic = scenarioSettings?.trafficSettings ?: trafficSettings.current()
            return AdminStatusResponse(
                running = persistedRuntime?.running ?: true,
                scenario = persistedRuntime?.scenario ?: scenarioSettings?.scenario?.name,
                proxyPort = persistedRuntime?.proxyPort ?: proxyPort,
                upstreamBaseUrl = persistedRuntime?.upstreamBaseUrl ?: traffic.upstreamBaseUrl.value,
                stateDirectory = stateDirectory.toAbsolutePath().normalize().toString(),
                runtimeFile = runtimeFile.toAbsolutePath().normalize().toString(),
                journalFile = stateDirectory.resolve(JOURNAL_FILE).toAbsolutePath().normalize().toString(),
                stateStoreDirectory = stateDirectory.resolve(StateStoreAdminReader.DEFAULT_STATE_DIRECTORY_NAME).toAbsolutePath().normalize().toString(),
                availableScenarios = scenarioRepository.list().map { it.value },
                adminBasePath = ProxyAdminApi.ADMIN_BASE_PATH,
            )
        }

        private fun buildAdminState(): AdminStateResponse {
            return stateStoreReader.read(stateDirectory)
        }

        private fun buildAdminJournal(request: Request): AdminJournalResponse {
            val limit = request.uri.rawQuery
                ?.split("&")
                ?.firstOrNull { parameter -> parameter.startsWith("limit=") }
                ?.substringAfter("=")
                ?.toIntOrNull()
                ?: DEFAULT_ADMIN_LIMIT
            val journalItems = readIfExists(stateDirectory.resolve(JOURNAL_FILE))
                ?.lineSequence()
                ?.filter(String::isNotBlank)
                ?.toList()
                ?.takeLast(limit)
                ?.map { line -> json.decodeFromString<AdminJournalItem>(line) }
                ?.reversed()
                .orEmpty()
            return AdminJournalResponse(limit = limit, count = journalItems.size, items = journalItems)
        }

        private fun adminBody(request: Request): ByteArray? {
            val requestedPath = request.uri.rawQuery
                ?.split("&")
                ?.firstOrNull { parameter -> parameter.startsWith("path=") }
                ?.substringAfter("=")
                ?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
                ?.takeIf(String::isNotBlank)
                ?: return null
            val resolved = stateDirectory.resolve(requestedPath).normalize()
            if (!resolved.startsWith(stateDirectory.normalize()) || !Files.exists(resolved)) return null
            return Files.readAllBytes(resolved)
        }

        private fun adminResource(assetPath: String): ByteArray? {
            return javaClass.classLoader.getResource("admin/$assetPath")?.readBytes()
        }

        private fun readIfExists(file: Path): String? {
            return if (Files.exists(file)) Files.readString(file) else null
        }

        private fun adminHeaders(
            path: String,
            contentLength: Int,
        ): Map<String, String> {
            val contentType = when {
                path.endsWith(".css") -> "text/css"
                path.endsWith(".js") -> "application/javascript"
                path.endsWith("/api/body") -> "text/plain"
                path.contains("/api/") -> "application/json"
                else -> "text/html"
            }
            return mapOf("content-type" to contentType, "content-length" to contentLength.toString())
        }

        private fun notFound(request: Request): Response {
            val body = "Not found".toByteArray()
            return Response(request, 404, mapOf("content-type" to "text/plain", "content-length" to body.size.toString()), body)
        }

        private fun handleMirror(
            request: Request,
            ruleKey: RuleKey,
        ): Response? {
            if (!ruleKey.path.startsWith(MIRROR_PATH)) return null
            val mirrorId = request.headers[MIRROR_ID_HEADER]
            val body = mirrorId?.let(mirrorResponses::remove) ?: ByteArray(0)
            return Response(
                request,
                200,
                mapOf(
                    "content-type" to (request.headers["content-type"] ?: "application/json"),
                    "content-length" to body.size.toString(),
                ),
                body,
            )
        }
        private fun handleFixture(
            request: Request,
            ruleKey: RuleKey,
            requestBody: String,
            scenarioSettings: ActiveScenarioSettings?,
            traffic: ProxyTrafficSettings,
        ): Response? {
            if (scenarioSettings == null) return null
            val rules = scenarioSettings.rules()
            val rule = requestState.get().selectRule(ruleKey, rules, requestBody) ?: return null
            val body = responseBody(rule)
            val mode = rule.mode.scenarioValue
            val status = rule.effectiveStatus()
            journalRequest(
                request = request,
                ruleKey = ruleKey,
                mode = mode,
                status = status,
                fixture = rule.fixture,
                requestBody = requestBody,
                responseBody = body,
                bodyMode = rule.bodyMode.scenarioValue,
                delayMillis = rule.delayMillis,
                timeoutMillis = rule.timeoutMillis,
                effectiveDelayMillis = rule.responseDelayMillis,
            )
            mirrorIfNeeded(request, ruleKey, mode, status, rule.fixture, requestBody, body, scenarioSettings, traffic)
            if (rule.responseDelayMillis > 0) Thread.sleep(rule.responseDelayMillis)
            return jsonResponse(request, status, body, rule.bodyMode)
        }

        private fun handlePassthrough(
            request: Request,
            ruleKey: RuleKey,
            requestBody: String,
            scenarioSettings: ActiveScenarioSettings?,
            traffic: ProxyTrafficSettings,
        ): Response {
            val selectedUpstreamBaseUrl = traffic.upstreamBaseUrl
            val upstreamUri = if (request.uri.isAbsolute) {
                request.uri
            } else {
                URI.create(selectedUpstreamBaseUrl.value.trimEnd('/') + normalizeProxyPath(request.uri.rawPath ?: request.uri.path) + querySuffix(request.uri))
            }
            val upstreamRequest = Request(
                upstreamUri,
                request.method,
                request.headers.filterKeys { name -> name.lowercase() !in ProxyRequestJournal.SKIPPED_UPSTREAM_HEADERS },
                request.body,
            )
            val response = proxier(traffic.upstreamProxyUrl).execute(upstreamRequest)
            val body = response.body ?: ByteArray(0)
            journalRequest(request, ruleKey, PASSTHROUGH_MODE, response.statusCode, null, requestBody, body, null, upstreamRequest.uri.toString(), scenarioSettings = scenarioSettings)
            return response
        }

        private fun mirrorIfNeeded(
            request: Request,
            ruleKey: RuleKey,
            mode: String,
            status: Int,
            fixture: String?,
            requestBody: String,
            responseBody: ByteArray,
            scenarioSettings: ActiveScenarioSettings?,
            traffic: ProxyTrafficSettings,
        ) {
            val upstreamProxyUrl = traffic.upstreamProxyUrl ?: return
            if (!traffic.mirrorMockRequests) return
            val mirrorBaseUrl = traffic.mirrorBaseUrl?.value ?: "http://127.0.0.1:$proxyPort$MIRROR_PATH"
            val mirrorUri = URI.create(mirrorBaseUrl.trimEnd('/') + ruleKey.path + querySuffix(request.uri))
            val mirrorId = UUID.randomUUID().toString()
            mirrorResponses[mirrorId] = responseBody
            val headers = mapOf(
                MIRROR_ID_HEADER to mirrorId,
                "x-proxy-original-url" to request.uri.toString(),
                "x-proxy-scenario" to (scenarioSettings?.scenario?.name ?: PASSTHROUGH_SCENARIO),
                "x-proxy-mode" to mode,
                "x-proxy-status" to status.toString(),
                "x-proxy-fixture" to fixture.orEmpty(),
                "x-proxy-response-bytes" to responseBody.size.toString(),
                "content-type" to "application/json",
            ).filterValues(String::isNotEmpty)
            mirrorExecutor.execute {
                runCatching {
                    proxier(upstreamProxyUrl).execute(
                        Request(
                            mirrorUri,
                            ruleKey.method,
                            headers,
                            requestBody.toByteArray(),
                        ),
                    )
                }.onFailure {
                    mirrorResponses.remove(mirrorId)
                }
            }
        }

        private fun proxier(upstreamProxyUrl: UpstreamProxyUrl?): Proxier {
            if (upstreamProxyUrl == null) return directProxier
            return proxiers.getOrPut(upstreamProxyUrl.value) {
                Proxier.create(
                    OkHttpClient.Builder()
                        .proxy(upstreamProxyUrl.toJavaProxy())
                        .build(),
                )
            }
        }

        private fun jsonResponse(
            request: Request,
            status: Int,
            body: ByteArray,
            bodyMode: MockRuleBodyMode = MockRuleBodyMode.Fixture,
        ): Response {
            val headers = mutableMapOf(
                "content-type" to "application/json",
                "content-length" to body.size.toString(),
            )
            if (bodyMode == MockRuleBodyMode.ConnectionClose) {
                headers["connection"] = "close"
            }
            return Response(
                request,
                status,
                headers,
                body,
            )
        }

        private fun responseBody(rule: MockRule): ByteArray {
            if (rule.mode == MockRuleMode.Forbidden) {
                return buildForbiddenResponse(rule).toByteArray()
            }
            return when (rule.bodyMode) {
                MockRuleBodyMode.Fixture -> scenarioRepository.loadFixture(rule).toByteArray()
                MockRuleBodyMode.Empty,
                MockRuleBodyMode.ConnectionClose,
                -> ByteArray(0)
            }
        }

        private fun MockRule.effectiveStatus(): Int {
            return if (mode == MockRuleMode.Forbidden && status == MockRule.SUCCESS_STATUS) {
                FORBIDDEN_RULE_STATUS
            } else {
                status
            }
        }

        private fun buildForbiddenResponse(rule: MockRule): String {
            return json.encodeToString(
                ForbiddenRuleResponse.serializer(),
                ForbiddenRuleResponse(
                    error = "forbidden_proxy_rule",
                    method = rule.method.uppercase(),
                    path = normalizeProxyPath(rule.path),
                    message = "Request matched a forbidden scenario rule",
                ),
            )
        }

        private fun journalRequest(
            request: Request,
            ruleKey: RuleKey,
            mode: String,
            status: Int,
            fixture: String?,
            requestBody: String,
            responseBody: ByteArray,
            upstreamUrl: String? = null,
            bodyMode: String? = null,
            delayMillis: Long? = null,
            timeoutMillis: Long? = null,
            effectiveDelayMillis: Long? = null,
            scenarioSettings: ActiveScenarioSettings? = activeScenario.current(),
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
                    uri = request.uri.toString(),
                    scenario = scenarioSettings?.scenario?.name ?: PASSTHROUGH_SCENARIO,
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
                    scenario = scenarioSettings?.scenario?.name ?: PASSTHROUGH_SCENARIO,
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

        private fun requestBodySuffix(mode: String): String = if (mode == PASSTHROUGH_MODE) "request.txt" else "request.json"

        private fun responseBodySuffix(mode: String): String = if (mode == PASSTHROUGH_MODE) "response.bin" else "response.json"

        private fun querySuffix(uri: URI): String = uri.rawQuery?.let { "?$it" }.orEmpty()
    }

    private companion object {
        const val BIND_HOST = "0.0.0.0"
        const val ROOT_CA_KEY_FILE = "mcp-proxy-root-ca.key"
        const val PASSTHROUGH_MODE = "passthrough"
        const val PASSTHROUGH_SCENARIO = "passthrough"
        const val FORBIDDEN_RULE_STATUS = 599
        const val MIRROR_PATH = "/__proxy_mirror"
        const val MIRROR_ID_HEADER = "x-proxy-mirror-id"
        const val DEFAULT_ADMIN_LIMIT = 50
        const val RUNTIME_FILE = "runtime.json"
        const val JOURNAL_FILE = "journal/events.jsonl"
    }
}

private class ActiveScenarioHolder(
    initial: ActiveScenarioSettings?,
) {
    @Volatile
    private var value: ActiveScenarioSettings? = initial

    fun current(): ActiveScenarioSettings? = value

    fun update(settings: ActiveScenarioSettings) {
        value = settings
    }

    fun clear() {
        value = null
    }
}

private class TrafficSettingsHolder(
    initial: ProxyTrafficSettings,
) {
    @Volatile
    private var value: ProxyTrafficSettings = initial

    fun current(): ProxyTrafficSettings = value

    fun update(settings: ProxyTrafficSettings) {
        value = settings
    }
}

private fun UpstreamProxyUrl.toJavaProxy(): Proxy {
    val uri = URI.create(value)
    return Proxy(
        Proxy.Type.HTTP,
        InetSocketAddress(
            requireNotNull(uri.host) { "Upstream proxy URL host is required" },
            if (uri.port > 0) uri.port else 80,
        ),
    )
}

private fun ActiveScenarioSettings.rules(): List<RuleEntry> {
    return scenario.rules.map { rule -> RuleEntry(rule.method.uppercase(), normalizeProxyPath(rule.path), rule) }
}
