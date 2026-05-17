package dev.mcp.proxy.infrastructure.mcp

import java.nio.file.Path
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.mcp.proxy.app.AppComponents
import dev.mcp.proxy.infrastructure.runtime.PersistedRuntimeState

class GenericProxyMcpServer(
    private val components: AppComponents,
) {
    private val json: Json = components.json

    fun handle(line: String): String? {
        val request = json.parseToJsonElement(line).jsonObject
        val method = request.stringValue("method")
        val id = request["id"] ?: JsonNull
        if (id is JsonNull && method in NOTIFICATION_METHODS) {
            return null
        }
        return response(
            id = id,
            result = when (method) {
                "initialize" -> initializeResult()
                "tools/list" -> toolsListResult()
                "tools/call" -> callTool(request["params"]?.jsonObject ?: JsonObject(emptyMap()))
                else -> errorResult("Unsupported MCP method: ${request.stringValue("method")}")
            },
        )
    }

    private fun initializeResult(): JsonObject {
        return buildJsonObject {
            put("protocolVersion", JsonPrimitive("2024-11-05"))
            put(
                "serverInfo",
                buildJsonObject {
                    put("name", JsonPrimitive("mcp-proxy"))
                    put("version", JsonPrimitive("1.0.0"))
                },
            )
            put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
        }
    }

    private fun toolsListResult(): JsonObject {
        return buildJsonObject {
            put(
                "tools",
                buildJsonArray {
                    TOOL_NAMES.forEach { toolName ->
                        add(
                            buildJsonObject {
                                put("name", JsonPrimitive(toolName))
                                put("description", JsonPrimitive("Generic proxy tool $toolName"))
                                put("inputSchema", toolInputSchema(toolName))
                            },
                        )
                    }
                },
            )
        }
    }

    private fun callTool(params: JsonObject): JsonObject {
        val name = params.stringValue("name") ?: ""
        val arguments = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())
        return toolTextResult(executeTool(name = name, arguments = arguments))
    }

    fun executeTool(
        name: String,
        arguments: JsonObject,
    ): String {
        val text = try {
            runBlocking {
                executeToolInternal(name = name, arguments = arguments)
            }
        } catch (error: IllegalArgumentException) {
            error.message ?: "Invalid tool arguments"
        }
        return text
    }

    private suspend fun executeToolInternal(
        name: String,
        arguments: JsonObject,
    ): String {
        return when (name) {
            "scenario_list" -> components.listScenariosUseCase.execute().joinToString("\n")
            "scenario_enable" -> proxyStart(arguments)
            "scenario_status" -> scenarioStatus(arguments)
            "scenario_disable" -> scenarioDisable(arguments)
            "proxy_start" -> proxyStart(arguments)
            "proxy_status" -> proxyStatus(arguments)
            "proxy_stop" -> proxyStop(arguments)
            "journal_tail" -> journalTail(arguments)
            "state_get" -> stateGet(arguments)
            "state_set" -> stateSet(arguments)
            "state_delete" -> stateDelete(arguments)
            "state_list" -> stateList(arguments)
            "ca_generate" -> components.caManager.generate().message
            else -> "Unknown tool: $name"
        }
    }

    fun toolNames(): List<String> = TOOL_NAMES

    fun toolInputSchema(toolName: String): JsonObject {
        return when (toolName) {
            "proxy_start",
            "scenario_enable",
            -> objectSchema(
                stringProperty(
                    name = "scenario",
                    description = "Scenario name from scenarios/*.json.",
                ),
                proxyPortProperty(),
                stringProperty(
                    name = "upstreamBaseUrl",
                    description = "Fallback upstream base URL for passthrough requests.",
                ),
                stringProperty(
                    name = "externalNetwork",
                    description = "External network policy for unmatched requests: forbidden or allowed. Defaults to forbidden.",
                ),
                stringProperty(
                    name = "upstreamProxyUrl",
                    description = "Optional upstream proxy for passthrough and mirror requests.",
                ),
                "mirrorMockRequests" to buildJsonObject {
                    put("type", JsonPrimitive("boolean"))
                    put("description", JsonPrimitive("When true, mock and state responses are mirrored to upstreamProxyUrl for upstream proxy visibility."))
                },
                stringProperty(
                    name = "mirrorBaseUrl",
                    description = "Optional mirror target URL. Defaults to http://127.0.0.1:<proxyPort>/__proxy_mirror.",
                ),
                stringProperty(
                    name = "stateDirectory",
                    description = "Runtime state directory. Defaults to var/state.",
                ),
            )
            "proxy_status",
            "scenario_status",
            "scenario_disable",
            "proxy_stop",
            "journal_tail",
            "state_list",
            -> objectSchema(
                stringProperty(
                    name = "stateDirectory",
                    description = "Runtime state directory. Defaults to var/state.",
                ),
            )
            "state_get",
            "state_delete",
            -> objectSchema(
                stringProperty(
                    name = "stateDirectory",
                    description = "Runtime state directory. Defaults to var/state.",
                ),
                stringProperty(name = "key", description = "State key. Stored as var/state/kv/<key>.json."),
            )
            "state_set" -> objectSchema(
                stringProperty(
                    name = "stateDirectory",
                    description = "Runtime state directory. Defaults to var/state.",
                ),
                stringProperty(name = "key", description = "State key. Stored as var/state/kv/<key>.json."),
                stringProperty(name = "value", description = "Raw JSON state value."),
            )
            else -> objectSchema()
        }
    }

    private suspend fun proxyStart(arguments: JsonObject): String {
        val settings = components.buildRuntimeSettingsUseCase.execute(
            scenarioName = requireScenarioArgument(arguments),
            proxyPort = arguments.intValue("proxyPort"),
            upstreamBaseUrl = arguments.stringValue("upstreamBaseUrl"),
            externalNetwork = arguments.stringValue("externalNetwork"),
            upstreamProxyUrl = arguments.stringValue("upstreamProxyUrl"),
            mirrorMockRequests = arguments.booleanValue("mirrorMockRequests"),
            mirrorBaseUrl = arguments.stringValue("mirrorBaseUrl"),
            stateDirectory = arguments.stringValue("stateDirectory")?.let(Path::of),
        )
        val state = components.runProxyUseCase.execute(settings)
        return "running=${state.running}\nstateDirectory=${state.stateDirectory}\nmessage=${state.message}"
    }

    private suspend fun proxyStatus(arguments: JsonObject): String {
        val state = components.getProxyStatusUseCase.execute(arguments.stringValue("stateDirectory")?.let(Path::of))
        return "running=${state.running}\nstateDirectory=${state.stateDirectory}\nmessage=${state.message}"
    }

    private fun scenarioStatus(arguments: JsonObject): String {
        val stateDirectory = stateDirectory(arguments)
        val runtimeFile = stateDirectory.resolve("runtime.json")
        val persistedState = runCatching {
            json.decodeFromString<PersistedRuntimeState>(Files.readString(runtimeFile))
        }.getOrNull()
        if (persistedState == null) {
            return listOf(
                "running=false",
                "stateDirectory=$stateDirectory",
                "scenario=passthrough",
                "proxyPort=18081",
                "upstreamProxyUrl=",
                "externalNetwork=forbidden",
                "mirrorMockRequests=false",
                "mirrorBaseUrl=",
            ).joinToString("\n")
        }
        return listOf(
            "running=${persistedState.running}",
            "stateDirectory=$stateDirectory",
            "scenario=${persistedState.scenario ?: "passthrough"}",
            "proxyPort=${persistedState.proxyPort}",
            "upstreamProxyUrl=${persistedState.upstreamProxyUrl ?: ""}",
            "externalNetwork=${persistedState.externalNetwork}",
            "mirrorMockRequests=${persistedState.mirrorMockRequests}",
            "mirrorBaseUrl=${persistedState.mirrorBaseUrl ?: ""}",
        ).joinToString("\n")
    }

    private suspend fun proxyStop(arguments: JsonObject): String {
        val state = components.stopProxyUseCase.execute(arguments.stringValue("stateDirectory")?.let(Path::of))
        return "running=${state.running}\nstateDirectory=${state.stateDirectory}\nmessage=${state.message}"
    }

    private suspend fun scenarioDisable(arguments: JsonObject): String {
        val state = components.runtimeController.disableScenario(arguments.stringValue("stateDirectory")?.let(Path::of) ?: Path.of("var/state").toAbsolutePath().normalize())
        return "running=${state.running}\nstateDirectory=${state.stateDirectory}\nmessage=${state.message}"
    }

    private fun journalTail(arguments: JsonObject): String {
        return components.readJournalTailUseCase.execute(
            stateDirectory = arguments.stringValue("stateDirectory")?.let(Path::of),
            limit = arguments.intValue("limit"),
        ).joinToString("\n")
    }

    private fun stateGet(arguments: JsonObject): String {
        val file = stateFile(arguments)
        return if (Files.exists(file)) Files.readString(file) else ""
    }

    private fun stateSet(arguments: JsonObject): String {
        val file = stateFile(arguments)
        val value = requireStringArgument(arguments, "value")
        json.parseToJsonElement(value)
        Files.createDirectories(file.parent)
        Files.writeString(file, value)
        return "stateFile=$file"
    }

    private fun stateDelete(arguments: JsonObject): String {
        val file = stateFile(arguments)
        return "deleted=${Files.deleteIfExists(file)}\nstateFile=$file"
    }

    private fun stateList(arguments: JsonObject): String {
        val directory = stateDirectory(arguments).resolve(STATE_DIRECTORY)
        if (!Files.exists(directory)) return ""
        return Files.list(directory).use { stream ->
            stream
                .filter { file -> Files.isRegularFile(file) && file.fileName.toString().endsWith(".json") }
                .map { file -> file.fileName.toString().removeSuffix(".json") }
                .sorted()
                .toList()
                .joinToString("\n")
        }
    }

    private fun stateFile(arguments: JsonObject): Path {
        val key = requireStringArgument(arguments, "key")
        require(STATE_KEY_PATTERN.matches(key)) {
            "key must contain only letters, digits, dots, underscores and hyphens"
        }
        return stateDirectory(arguments).resolve(STATE_DIRECTORY).resolve("$key.json").normalize()
    }

    private fun requireScenarioArgument(arguments: JsonObject): String {
        return requireStringArgument(arguments, "scenario")
    }

    private fun stateDirectory(arguments: JsonObject): Path {
        return arguments.stringValue("stateDirectory")?.let(Path::of) ?: Path.of("var/state")
    }

    private fun requireStringArgument(arguments: JsonObject, name: String): String {
        return requireNotNull(arguments.stringValue(name)?.takeIf(String::isNotBlank)) {
            "$name argument is required"
        }
    }

    private fun response(
        id: JsonElement,
        result: JsonObject,
    ): String {
        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", id)
                put("result", result)
            },
        )
    }

    private fun toolTextResult(text: String): JsonObject {
        return buildJsonObject {
            put(
                "content",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(text))
                        },
                    ),
                ),
            )
        }
    }

    private fun errorResult(message: String): JsonObject {
        return toolTextResult(message)
    }

    private fun objectSchema(vararg properties: Pair<String, JsonObject>): JsonObject {
        return buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    properties.forEach { (name, schema) -> put(name, schema) }
                },
            )
            put("additionalProperties", JsonPrimitive(false))
        }
    }

    private fun stringProperty(
        name: String,
        description: String,
    ): Pair<String, JsonObject> {
        return name to buildJsonObject {
            put("type", JsonPrimitive("string"))
            put("description", JsonPrimitive(description))
        }
    }

    private fun proxyPortProperty(): Pair<String, JsonObject> {
        return "proxyPort" to buildJsonObject {
            put("type", JsonPrimitive("integer"))
            put("description", JsonPrimitive("Local proxy port. Defaults to 18081."))
        }
    }

    private fun JsonObject.stringValue(name: String): String? {
        return this[name]?.jsonPrimitive?.contentOrNull
    }

    private fun JsonObject.intValue(name: String): Int? {
        return this[name]?.jsonPrimitive?.intOrNull
    }

    private fun JsonObject.booleanValue(name: String): Boolean? {
        return this[name]?.jsonPrimitive?.booleanOrNull
    }

    private companion object {
        val NOTIFICATION_METHODS: Set<String> = setOf("initialized", "notifications/initialized")
        val TOOL_NAMES: List<String> = listOf(
            "scenario_list",
            "scenario_enable",
            "scenario_status",
            "scenario_disable",
            "proxy_start",
            "proxy_status",
            "proxy_stop",
            "journal_tail",
            "state_get",
            "state_set",
            "state_delete",
            "state_list",
            "ca_generate",
        )
        const val STATE_DIRECTORY = "kv"
        val STATE_KEY_PATTERN: Regex = Regex("[A-Za-z0-9._-]+")
    }
}
