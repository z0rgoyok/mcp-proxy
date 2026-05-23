package dev.mcp.proxy.application

import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.min

class BoundedFileTailReader(
    private val blockSize: Int = DEFAULT_BLOCK_SIZE,
) {
    init {
        require(blockSize > 0) { "Tail reader block size must be positive" }
    }

    fun readLastLines(
        file: Path,
        limit: Int,
    ): List<String> {
        require(limit >= 0) { "Tail line limit must not be negative" }
        if (limit == 0 || !Files.exists(file)) {
            return emptyList()
        }
        RandomAccessFile(file.toFile(), "r").use { input ->
            val fileLength = input.length()
            if (fileLength == 0L) {
                return emptyList()
            }
            val lines = ArrayDeque<String>(limit)
            val currentLine = ByteArrayOutputStream()
            val buffer = ByteArray(blockSize)
            var position = fileLength
            while (position > 0 && lines.size < limit) {
                val bytesToRead = min(blockSize.toLong(), position).toInt()
                position -= bytesToRead
                input.seek(position)
                input.readFully(buffer, 0, bytesToRead)
                for (index in bytesToRead - 1 downTo 0) {
                    val byte = buffer[index]
                    val absoluteIndex = position + index
                    if (byte == NEW_LINE) {
                        if (absoluteIndex == fileLength - 1 && currentLine.size() == 0) {
                            continue
                        }
                        lines.addFirst(currentLine.toUtf8Line())
                        currentLine.reset()
                        if (lines.size == limit) {
                            break
                        }
                    } else {
                        currentLine.write(byte.toInt())
                    }
                }
            }
            if (lines.size < limit && currentLine.size() > 0) {
                lines.addFirst(currentLine.toUtf8Line())
            }
            return lines.toList()
        }
    }

    private fun ByteArrayOutputStream.toUtf8Line(): String {
        val reversed = toByteArray().reversedArray()
        val lineBytes = if (reversed.lastOrNull() == CARRIAGE_RETURN) {
            reversed.copyOf(reversed.size - 1)
        } else {
            reversed
        }
        return lineBytes.toString(StandardCharsets.UTF_8)
    }

    companion object {
        private const val DEFAULT_BLOCK_SIZE = 8192
        private val NEW_LINE = '\n'.code.toByte()
        private val CARRIAGE_RETURN = '\r'.code.toByte()
    }
}
