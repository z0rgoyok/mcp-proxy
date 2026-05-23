package dev.mcp.proxy.infrastructure.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.LocalPathContent
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import dev.mcp.proxy.application.BoundedFileTailReader
import dev.mcp.proxy.application.JournalTailLimit
import dev.mcp.proxy.domain.scenario.ScenarioRepository
import dev.mcp.proxy.infrastructure.runtime.PersistedRuntimeState

class ProxyAdminApi(
    private val scenarioRepository: ScenarioRepository,
    private val json: Json,
    private val tailReader: BoundedFileTailReader = BoundedFileTailReader(),
) {
    private val adminHtml = ProxyAdminHtml(json)
    private val stateStoreReader = StateStoreAdminReader()
    private val bodyFileResolver = AdminBodyFileResolver()

    suspend fun respondDashboard(
        call: ApplicationCall,
        stateDirectory: Path,
        proxyPort: Int,
    ) {
        val status = buildStatus(stateDirectory = stateDirectory, proxyPort = proxyPort)
        call.respondText(
            text = adminHtml.render(status),
            contentType = ContentType.Text.Html,
            status = HttpStatusCode.OK,
        )
    }

    suspend fun respondStatus(
        call: ApplicationCall,
        stateDirectory: Path,
        proxyPort: Int,
    ) {
        call.respondText(
            text = json.encodeToString(AdminStatusResponse.serializer(), buildStatus(stateDirectory, proxyPort)),
            contentType = ContentType.Application.Json,
            status = HttpStatusCode.OK,
        )
    }

    suspend fun respondJournal(
        call: ApplicationCall,
        stateDirectory: Path,
        limit: Int?,
    ) {
        val resolvedLimit = JournalTailLimit.normalize(limit, JournalTailLimit.ADMIN_DEFAULT)
        val items = readJournal(stateDirectory = stateDirectory, limit = resolvedLimit)
        call.respondText(
            text = json.encodeToString(
                AdminJournalResponse.serializer(),
                AdminJournalResponse(limit = resolvedLimit, count = items.size, items = items),
            ),
            contentType = ContentType.Application.Json,
            status = HttpStatusCode.OK,
        )
    }

    suspend fun respondState(
        call: ApplicationCall,
        stateDirectory: Path,
    ) {
        val state = withContext(Dispatchers.IO) { stateStoreReader.read(stateDirectory) }
        call.respondText(
            text = json.encodeToString(
                AdminStateResponse.serializer(),
                state,
            ),
            contentType = ContentType.Application.Json,
            status = HttpStatusCode.OK,
        )
    }

    suspend fun respondBodyFile(
        call: ApplicationCall,
        stateDirectory: Path,
        relativePath: String?,
    ) {
        when (val bodyFile = bodyFileResolver.resolve(stateDirectory, relativePath)) {
            AdminBodyFileResolver.Result.MissingPath -> {
                call.respondText("Body path is required", status = HttpStatusCode.BadRequest)
            }
            AdminBodyFileResolver.Result.NotFound -> {
                call.respondText("Body file not found", status = HttpStatusCode.NotFound)
            }
            is AdminBodyFileResolver.Result.Found -> {
                call.respond(LocalPathContent(bodyFile.path, ContentType.Text.Plain))
            }
        }
    }

    suspend fun respondAsset(
        call: ApplicationCall,
        assetPath: String,
    ) {
        val resource = javaClass.classLoader.getResource("admin/$assetPath")
        if (resource == null) {
            call.respondText("Asset not found", status = HttpStatusCode.NotFound)
            return
        }
        val contentType = when {
            assetPath.endsWith(".css") -> ContentType.Text.CSS
            assetPath.endsWith(".js") -> ContentType.Application.JavaScript
            else -> ContentType.Text.Plain
        }
        call.respondText(
            text = resource.readText(),
            contentType = contentType,
            status = HttpStatusCode.OK,
        )
    }

    private fun buildStatus(
        stateDirectory: Path,
        proxyPort: Int,
    ): AdminStatusResponse {
        val runtimeFile = stateDirectory.resolve(RUNTIME_FILE)
        val persistedRuntime = readIfExists(runtimeFile)?.let { raw ->
            json.decodeFromString<PersistedRuntimeState>(raw)
        }
        return AdminStatusResponse(
            running = persistedRuntime?.running ?: false,
            scenario = persistedRuntime?.scenario,
            proxyPort = persistedRuntime?.proxyPort ?: proxyPort,
            upstreamBaseUrl = persistedRuntime?.upstreamBaseUrl,
            stateDirectory = stateDirectory.toAbsolutePath().normalize().toString(),
            runtimeFile = runtimeFile.toAbsolutePath().normalize().toString(),
            journalFile = stateDirectory.resolve(JOURNAL_FILE).toAbsolutePath().normalize().toString(),
            stateStoreDirectory = stateDirectory.resolve(StateStoreAdminReader.DEFAULT_STATE_DIRECTORY_NAME).toAbsolutePath().normalize().toString(),
            availableScenarios = scenarioRepository.list().map { it.value },
            adminBasePath = ADMIN_BASE_PATH,
        )
    }

    private fun readJournal(
        stateDirectory: Path,
        limit: Int,
    ): List<AdminJournalItem> {
        val journalFile = stateDirectory.resolve(JOURNAL_FILE)
        return tailReader.readLastLines(journalFile, limit)
            .filter(String::isNotBlank)
            .map { line -> json.decodeFromString<AdminJournalItem>(line) }
            .reversed()
    }

    private fun readIfExists(file: Path): String? {
        return if (Files.exists(file)) Files.readString(file) else null
    }

    companion object {
        const val ADMIN_BASE_PATH = "/admin"
        private const val RUNTIME_FILE = "runtime.json"
        private const val JOURNAL_FILE = "journal/events.jsonl"
    }
}

@Serializable
data class AdminStatusResponse(
    val running: Boolean,
    val scenario: String?,
    val proxyPort: Int,
    val upstreamBaseUrl: String?,
    val stateDirectory: String,
    val runtimeFile: String,
    val journalFile: String,
    val stateStoreDirectory: String,
    val availableScenarios: List<String>,
    val adminBasePath: String,
)

@Serializable
data class AdminJournalResponse(
    val limit: Int,
    val count: Int,
    val items: List<AdminJournalItem>,
)

@Serializable
data class AdminJournalItem(
    val timestamp: String,
    val method: String,
    val path: String,
    val uri: String,
    val scenario: String,
    val mode: String,
    val status: Int,
    val fixture: String?,
    val requestBodyFile: String?,
    val responseBodyFile: String?,
    val requestBodyBytes: Long? = null,
    val responseBodyBytes: Long? = null,
    val bodyMode: String? = null,
    val delayMillis: Long? = null,
    val timeoutMillis: Long? = null,
    val effectiveDelayMillis: Long? = null,
)

@Serializable
data class AdminStateResponse(
    val directory: String,
    val items: List<AdminStateItem>,
)

@Serializable
data class AdminStateItem(
    val key: String,
    val path: String,
    val rawJson: String,
)
