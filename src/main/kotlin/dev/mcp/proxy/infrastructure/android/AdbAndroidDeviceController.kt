package dev.mcp.proxy.infrastructure.android

import dev.mcp.proxy.domain.android.AndroidDevice
import dev.mcp.proxy.domain.android.AndroidDeviceController
import dev.mcp.proxy.domain.android.AndroidProxyState
import dev.mcp.proxy.infrastructure.process.CommandRunner
import dev.mcp.proxy.infrastructure.process.ProcessBuilderCommandRunner

class AdbAndroidDeviceController(
    private val commandRunner: CommandRunner = ProcessBuilderCommandRunner(),
) : AndroidDeviceController {
    override fun listDevices(): List<AndroidDevice> {
        val result = commandRunner.run(listOf(ADB, "devices"))
        check(result.exitCode == 0) {
            result.stderr.ifBlank { "adb devices failed" }
        }
        return result.stdout
            .lineSequence()
            .drop(1)
            .map(String::trim)
            .filter(String::isNotBlank)
            .map { line ->
                val parts = line.split(WHITESPACE_REGEX)
                AndroidDevice(
                    udid = parts.first(),
                    status = parts.getOrElse(1) { UNKNOWN_STATUS },
                )
            }
            .toList()
    }

    override fun setProxy(
        udid: String?,
        proxyHost: String?,
        proxyPort: Int?,
    ): AndroidProxyState {
        val selectedUdid = udid ?: selectSingleDevice().udid
        val proxyValue = "${proxyHost ?: DEFAULT_EMULATOR_HOST}:${proxyPort ?: DEFAULT_PROXY_PORT}"
        adbShell(
            udid = selectedUdid,
            arguments = listOf("settings", "put", "global", "http_proxy", proxyValue),
        )
        return AndroidProxyState(
            udid = selectedUdid,
            value = proxyValue,
        )
    }

    override fun clearProxy(udid: String?): AndroidProxyState {
        val selectedUdid = udid ?: selectSingleDevice().udid
        GLOBAL_PROXY_KEYS.forEach { key ->
            adbShell(
                udid = selectedUdid,
                arguments = listOf("settings", "delete", "global", key),
            )
        }
        return AndroidProxyState(udid = selectedUdid, value = "")
    }

    override fun getProxy(udid: String?): AndroidProxyState {
        val selectedUdid = udid ?: selectSingleDevice().udid
        val result = adbShell(
            udid = selectedUdid,
            arguments = listOf("settings", "get", "global", "http_proxy"),
        )
        return AndroidProxyState(udid = selectedUdid, value = result.stdout.trim())
    }

    private fun selectSingleDevice(): AndroidDevice {
        val devices = listDevices().filter { device -> device.status == DEVICE_STATUS }
        check(devices.size == 1) {
            "Expected exactly one active Android device, got ${devices.size}"
        }
        return devices.single()
    }

    private fun adbShell(
        udid: String,
        arguments: List<String>,
    ): dev.mcp.proxy.infrastructure.process.CommandResult {
        val result = commandRunner.run(listOf(ADB, "-s", udid, "shell") + arguments)
        check(result.exitCode == 0) {
            result.stderr.ifBlank { "adb shell failed" }
        }
        return result
    }

    private companion object {
        const val ADB = "adb"
        const val DEFAULT_EMULATOR_HOST = "10.0.2.2"
        const val DEFAULT_PROXY_PORT = 18081
        const val DEVICE_STATUS = "device"
        const val UNKNOWN_STATUS = "unknown"
        val GLOBAL_PROXY_KEYS = listOf(
            "http_proxy",
            "global_http_proxy_host",
            "global_http_proxy_port",
            "global_http_proxy_exclusion_list",
            "global_proxy_pac_url",
        )
        val WHITESPACE_REGEX = Regex("\\s+")
    }
}
