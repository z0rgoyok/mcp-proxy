package dev.mcp.proxy.infrastructure.mcp

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

class StdioMcpServer(
    private val mcpServer: GenericProxyMcpServer,
    private val input: InputStream = System.`in`,
    private val output: OutputStream = System.out,
) {
    fun run() {
        while (true) {
            val request = readMessage() ?: return
            val response = mcpServer.handle(request) ?: continue
            writeMessage(response)
        }
    }

    private fun readMessage(): String? {
        val headers = linkedMapOf<String, String>()
        while (true) {
            val headerLine = readAsciiLine() ?: return null
            if (headerLine.isEmpty()) {
                break
            }
            val separatorIndex = headerLine.indexOf(':')
            check(separatorIndex > 0) { "Malformed MCP header: $headerLine" }
            val name = headerLine.substring(0, separatorIndex).trim().lowercase()
            val value = headerLine.substring(separatorIndex + 1).trim()
            headers[name] = value
        }
        val contentLength = headers["content-length"]?.toIntOrNull()
        check(contentLength != null && contentLength >= 0) {
            "Missing Content-Length header"
        }
        val body = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val bytesRead = input.read(body, offset, contentLength - offset)
            if (bytesRead < 0) {
                check(offset == 0) { "Unexpected EOF while reading MCP body" }
                return null
            }
            offset += bytesRead
        }
        return body.toString(StandardCharsets.UTF_8)
    }

    private fun readAsciiLine(): String? {
        val buffer = mutableListOf<Byte>()
        while (true) {
            val next = input.read()
            if (next < 0) {
                return if (buffer.isEmpty()) null else error("Unexpected EOF while reading MCP headers")
            }
            when (next.toByte()) {
                '\n'.code.toByte() -> {
                    val bytes = if (buffer.lastOrNull() == '\r'.code.toByte()) {
                        buffer.dropLast(1).toByteArray()
                    } else {
                        buffer.toByteArray()
                    }
                    return bytes.toString(StandardCharsets.US_ASCII)
                }
                else -> buffer += next.toByte()
            }
        }
    }

    private fun writeMessage(message: String) {
        val body = message.toByteArray(StandardCharsets.UTF_8)
        val headers = "Content-Length: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        output.write(headers)
        output.write(body)
        output.flush()
    }
}
