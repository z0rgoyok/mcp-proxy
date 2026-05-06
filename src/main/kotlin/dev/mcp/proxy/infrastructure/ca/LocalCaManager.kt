package dev.mcp.proxy.infrastructure.ca

import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import dev.mcp.proxy.domain.ca.CaManager
import dev.mcp.proxy.domain.ca.CaState
import dev.mcp.proxy.infrastructure.process.CommandRunner
import dev.mcp.proxy.infrastructure.process.ProcessBuilderCommandRunner

class LocalCaManager(
    private val stateDirectory: Path = Path.of("var/state"),
    private val commandRunner: CommandRunner = ProcessBuilderCommandRunner(),
) : CaManager {
    override fun generate(): CaState {
        val caDirectory = stateDirectory.resolve("ca").toAbsolutePath().normalize()
        Files.createDirectories(caDirectory)
        val keyFile = caDirectory.resolve("mcp-proxy-root-ca.key")
        val certificateFile = caDirectory.resolve("mcp-proxy-root-ca.pem")
        if (!Files.exists(keyFile) || !Files.exists(certificateFile)) {
            val result = commandRunner.run(
                listOf(
                    "openssl",
                    "req",
                    "-x509",
                    "-newkey",
                    "rsa:2048",
                    "-sha256",
                    "-days",
                    "365",
                    "-nodes",
                    "-subj",
                    "/CN=Generic MCP Proxy Local Root CA",
                    "-keyout",
                    keyFile.toString(),
                    "-out",
                    certificateFile.toString(),
                ),
            )
            check(result.exitCode == 0) {
                result.stderr.ifBlank { "openssl CA generation failed" }
            }
        }
        return CaState(
            message = "Local CA generated",
            certificatePath = certificateFile.toString(),
        )
    }

    override fun install(udid: String?): CaState {
        val caState = generate()
        val certificatePath = requireNotNull(caState.certificatePath) {
            "Generated CA certificate path is empty"
        }
        val androidCertificate = androidCertificateFile(Path.of(certificatePath))
        Files.copy(
            Path.of(certificatePath),
            androidCertificate,
            StandardCopyOption.REPLACE_EXISTING,
        )
        val deviceArguments = udid?.let { listOf("-s", it) }.orEmpty()
        val remotePath = "$ANDROID_DOWNLOAD_DIRECTORY/${androidCertificate.fileName}"
        val pushResult = commandRunner.run(
            listOf(ADB) + deviceArguments + listOf(
                "push",
                androidCertificate.toString(),
                remotePath,
            ),
        )
        check(pushResult.exitCode == 0) {
            pushResult.stderr.ifBlank { "adb push CA certificate failed" }
        }
        val settingsResult = commandRunner.run(
            listOf(ADB) + deviceArguments + listOf(
                "shell",
                "am",
                "start",
                "-a",
                "com.google.android.settings.security.SECURITY_ADVANCED_SETTINGS",
            ),
        )
        check(settingsResult.exitCode == 0) {
            settingsResult.stderr.ifBlank { "Android advanced security settings launch failed" }
        }
        val openedCredentials = openEncryptionCredentials(deviceArguments)
        return CaState(
            message = if (openedCredentials) {
                "CA copied to ${udid ?: "selected emulator"}:$remotePath; Android Encryption & credentials opened; install it from Install a certificate > CA certificate"
            } else {
                "CA copied to ${udid ?: "selected emulator"}:$remotePath; Android More security settings opened; tap Encryption & credentials, then Install a certificate > CA certificate"
            },
            certificatePath = androidCertificate.toString(),
        )
    }

    private fun openEncryptionCredentials(deviceArguments: List<String>): Boolean {
        val dumpResult = commandRunner.run(
            listOf(ADB) + deviceArguments + listOf(
                "shell",
                "uiautomator",
                "dump",
                UI_DUMP_PATH,
            ),
        )
        if (dumpResult.exitCode != 0) {
            return false
        }
        val xmlResult = commandRunner.run(
            listOf(ADB) + deviceArguments + listOf(
                "shell",
                "cat",
                UI_DUMP_PATH,
            ),
        )
        if (xmlResult.exitCode != 0) {
            return false
        }
        val tap = encryptionCredentialsTap(xmlResult.stdout) ?: return false
        val tapResult = commandRunner.run(
            listOf(ADB) + deviceArguments + listOf(
                "shell",
                "input",
                "tap",
                tap.x.toString(),
                tap.y.toString(),
            ),
        )
        return tapResult.exitCode == 0
    }

    private fun encryptionCredentialsTap(xml: String): TapPoint? {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(InputSource(StringReader(xml)))
        val nodes = document.getElementsByTagName("node")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index) as? Element ?: continue
            if (element.getAttribute("text") == "Encryption & credentials") {
                val target = element.closestClickableParent() ?: element
                return target.getAttribute("bounds").toTapPoint()
            }
        }
        return null
    }

    private fun Element.closestClickableParent(): Element? {
        var current = parentNode
        while (current is Element) {
            if (current.getAttribute("clickable") == "true" && current.getAttribute("enabled") == "true") {
                return current
            }
            current = current.parentNode
        }
        return null
    }

    private fun String.toTapPoint(): TapPoint? {
        val match = BOUNDS_REGEX.matchEntire(this) ?: return null
        val left = match.groupValues[1].toInt()
        val top = match.groupValues[2].toInt()
        val right = match.groupValues[3].toInt()
        val bottom = match.groupValues[4].toInt()
        return TapPoint(
            x = (left + right) / 2,
            y = (top + bottom) / 2,
        )
    }

    private fun androidCertificateFile(certificateFile: Path): Path {
        return certificateFile.parent.resolve("mcp-proxy-root-ca.crt")
    }

    private data class TapPoint(
        val x: Int,
        val y: Int,
    )

    private companion object {
        const val ADB = "adb"
        const val ANDROID_DOWNLOAD_DIRECTORY = "/sdcard/Download"
        const val UI_DUMP_PATH = "/sdcard/mcp-proxy-settings.xml"
        val BOUNDS_REGEX = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
    }
}
