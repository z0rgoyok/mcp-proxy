package dev.mcp.proxy.infrastructure.android

import kotlin.test.Test
import kotlin.test.assertEquals
import dev.mcp.proxy.infrastructure.process.CommandResult
import dev.mcp.proxy.infrastructure.process.CommandRunner

class AdbAndroidDeviceControllerTest {
    @Test
    fun `lists adb devices`() {
        val runner = RecordingCommandRunner(
            results = listOf(
                CommandResult(
                    exitCode = 0,
                    stdout = "List of devices attached\nemulator-5554\tdevice\n",
                    stderr = "",
                ),
            ),
        )

        val devices = AdbAndroidDeviceController(commandRunner = runner).listDevices()

        assertEquals("emulator-5554", devices.single().udid)
        assertEquals("device", devices.single().status)
    }

    @Test
    fun `sets emulator proxy via adb shell`() {
        val runner = RecordingCommandRunner(
            results = listOf(CommandResult(exitCode = 0, stdout = "", stderr = "")),
        )

        val state = AdbAndroidDeviceController(commandRunner = runner).setProxy(
            udid = "emulator-5554",
            proxyHost = "10.0.2.2",
            proxyPort = 18081,
        )

        assertEquals("10.0.2.2:18081", state.value)
        assertEquals(
            listOf("adb", "-s", "emulator-5554", "shell", "settings", "put", "global", "http_proxy", "10.0.2.2:18081"),
            runner.commands.single(),
        )
    }

    @Test
    fun `clears emulator proxy via adb shell`() {
        val runner = RecordingCommandRunner(
            results = List(5) { CommandResult(exitCode = 0, stdout = "", stderr = "") },
        )

        AdbAndroidDeviceController(commandRunner = runner).clearProxy(udid = "emulator-5554")

        assertEquals(
            listOf(
                listOf("adb", "-s", "emulator-5554", "shell", "settings", "delete", "global", "http_proxy"),
                listOf("adb", "-s", "emulator-5554", "shell", "settings", "delete", "global", "global_http_proxy_host"),
                listOf("adb", "-s", "emulator-5554", "shell", "settings", "delete", "global", "global_http_proxy_port"),
                listOf(
                    "adb",
                    "-s",
                    "emulator-5554",
                    "shell",
                    "settings",
                    "delete",
                    "global",
                    "global_http_proxy_exclusion_list",
                ),
                listOf("adb", "-s", "emulator-5554", "shell", "settings", "delete", "global", "global_proxy_pac_url"),
            ),
            runner.commands,
        )
    }

    private class RecordingCommandRunner(
        private val results: List<CommandResult>,
    ) : CommandRunner {
        val commands = mutableListOf<List<String>>()
        private var index = 0

        override fun run(command: List<String>): CommandResult {
            commands += command
            return results[index++]
        }
    }
}
