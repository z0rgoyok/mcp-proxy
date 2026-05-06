package dev.mcp.proxy.infrastructure.server

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

class StateStoreAdminReader(
    private val stateDirectoryName: String = DEFAULT_STATE_DIRECTORY_NAME,
) {
    fun read(stateDirectory: Path): AdminStateResponse {
        val kvDirectory = stateDirectory.resolve(stateDirectoryName)
        val items = if (Files.exists(kvDirectory)) {
            Files.list(kvDirectory).use { stream ->
                stream
                    .filter { file -> Files.isRegularFile(file) && file.fileName.toString().endsWith(".json") }
                    .map { file ->
                        AdminStateItem(
                            key = file.fileName.toString().removeSuffix(".json"),
                            path = file.toAbsolutePath().normalize().toString(),
                            rawJson = Files.readString(file),
                        )
                    }
                    .sorted(Comparator.comparing(AdminStateItem::key))
                    .toList()
            }
        } else {
            emptyList()
        }
        return AdminStateResponse(
            directory = kvDirectory.toAbsolutePath().normalize().toString(),
            items = items,
        )
    }

    companion object {
        const val DEFAULT_STATE_DIRECTORY_NAME = "kv"
    }
}
