package dev.mcp.proxy.infrastructure.ca

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dev.mcp.proxy.infrastructure.process.CommandResult
import dev.mcp.proxy.infrastructure.process.CommandRunner

class LocalCaManagerTest {
    @Test
    fun `generate creates ca directory and invokes openssl`() {
        val stateDirectory = createTempDirectory()
        val runner = RecordingCommandRunner()

        val state = LocalCaManager(
            stateDirectory = stateDirectory,
            commandRunner = runner,
        ).generate()

        assertEquals("Local CA generated", state.message)
        assertTrue(state.certificatePath.orEmpty().endsWith("mcp-proxy-root-ca.pem"))
        assertContains(runner.commands.single(), "openssl")
        assertTrue(stateDirectory.resolve("ca").toFile().exists())
    }

    @Test
    fun `install pushes generated ca to emulator and opens security settings`() {
        val stateDirectory = createTempDirectory()
        val runner = RecordingCommandRunner()

        val state = LocalCaManager(
            stateDirectory = stateDirectory,
            commandRunner = runner,
        ).install(udid = "emulator-5554")

        assertContains(state.message, "Android Encryption & credentials opened")
        assertTrue(state.certificatePath.orEmpty().endsWith("mcp-proxy-root-ca.crt"))
        assertTrue(
            runner.commands.any { command ->
                command == listOf(
                    "adb",
                    "-s",
                    "emulator-5554",
                    "push",
                    stateDirectory.resolve("ca/mcp-proxy-root-ca.crt").toAbsolutePath().normalize().toString(),
                    "/sdcard/Download/mcp-proxy-root-ca.crt",
                )
            },
        )
        assertTrue(
            runner.commands.any { command ->
                command.containsAll(
                    listOf(
                        "adb",
                        "-s",
                        "emulator-5554",
                        "com.google.android.settings.security.SECURITY_ADVANCED_SETTINGS",
                    ),
                )
            },
        )
        assertTrue(
            runner.commands.any { command ->
                command == listOf(
                    "adb",
                    "-s",
                    "emulator-5554",
                    "shell",
                    "input",
                    "tap",
                    "540",
                    "1272",
                )
            },
        )
    }

    private class RecordingCommandRunner : CommandRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(command: List<String>): CommandResult {
            commands += command
            if (command.firstOrNull() == "openssl") {
                command.writeFileAfter("-keyout", "key")
                command.writeFileAfter("-out", "certificate")
            }
            if (command.takeLast(2) == listOf("cat", "/sdcard/mcp-proxy-settings.xml")) {
                return CommandResult(exitCode = 0, stdout = MORE_SECURITY_SETTINGS_XML, stderr = "")
            }
            return CommandResult(exitCode = 0, stdout = "", stderr = "")
        }

        private fun List<String>.writeFileAfter(
            option: String,
            content: String,
        ) {
            val index = indexOf(option)
            if (index >= 0) {
                val file = Path.of(get(index + 1))
                Files.createDirectories(file.parent)
                Files.writeString(file, content)
            }
        }
    }

    private companion object {
        val MORE_SECURITY_SETTINGS_XML = """
            <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
            <hierarchy rotation="0">
                <node text="" clickable="false" enabled="true" bounds="[0,0][1080,2400]">
                    <node text="" clickable="true" enabled="true" bounds="[0,1169][1080,1375]">
                        <node text="Encryption &amp; credentials" clickable="false" enabled="true" bounds="[63,1211][631,1282]" />
                        <node text="Encrypted" clickable="false" enabled="true" bounds="[63,1282][229,1333]" />
                    </node>
                </node>
            </hierarchy>
        """.trimIndent()
    }
}
