package dev.mcp.proxy.infrastructure.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class AdminBodyFileResolver {
    fun resolve(
        stateDirectory: Path,
        relativePath: String?,
    ): Result {
        val requestedPath = relativePath?.takeIf(String::isNotBlank)
            ?: return Result.MissingPath
        return runCatching {
            val stateRoot = stateDirectory.toAbsolutePath().normalize().takeIf(Path::exists)
                ?.toRealPath()
                ?: return Result.NotFound
            val resolved = stateRoot.resolve(requestedPath).normalize()
            if (!resolved.startsWith(stateRoot) || !Files.isRegularFile(resolved)) {
                return Result.NotFound
            }
            val realPath = resolved.toRealPath()
            if (!realPath.startsWith(stateRoot)) {
                return Result.NotFound
            }
            Result.Found(path = realPath, sizeBytes = Files.size(realPath))
        }.getOrDefault(Result.NotFound)
    }

    sealed interface Result {
        data object MissingPath : Result
        data object NotFound : Result
        data class Found(
            val path: Path,
            val sizeBytes: Long,
        ) : Result
    }
}
