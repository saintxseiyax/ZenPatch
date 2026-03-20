package dev.zenpatch.cli

import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import java.io.File
import java.util.concurrent.Callable
import java.util.zip.ZipFile

@Command(
    name = "analyze",
    description = ["Analyze an APK and show metadata"],
    mixinStandardHelpOptions = true
)
class AnalyzeCommand : Callable<Int> {

    @Parameters(index = "0", description = ["APK file to analyze"])
    lateinit var apkFile: File

    @Option(names = ["--json"], description = ["Output in JSON format"])
    var json: Boolean = false

    override fun call(): Int {
        if (!apkFile.exists()) {
            System.err.println("ERROR: File not found: ${apkFile.absolutePath}")
            return 1
        }

        return try {
            val info = analyzeApk(apkFile)
            if (json) printJson(info) else printTable(info)
            0
        } catch (e: Exception) {
            System.err.println("ERROR: ${e.message}")
            1
        }
    }

    data class ApkMetadata(
        val file: String,
        val sizeBytes: Long,
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val minSdk: Int,
        val targetSdk: Int,
        val applicationClass: String?,
        val dexCount: Int,
        val abis: List<String>,
        val signatureSchemes: List<Int>,
        val isSplit: Boolean,
        val isZenPatched: Boolean,
        val isDeclaredDebuggable: Boolean
    )

    private fun analyzeApk(file: File): ApkMetadata {
        val zipFile = ZipFile(file)
        var packageName = "unknown"
        var versionCode = 0L
        var versionName = "unknown"
        var minSdk = 0
        var targetSdk = 0
        var applicationClass: String? = null
        var isSplit = false
        var isDeclaredDebuggable = false
        var isZenPatched = false
        val dexFiles = mutableListOf<String>()
        val abis = mutableSetOf<String>()
        val signatureSchemes = mutableListOf<Int>()

        zipFile.use { zip ->
            // Check for signature schemes
            var hasV1 = false
            for (entry in zip.entries()) {
                val name = entry.name
                when {
                    name.matches(Regex("classes\\d*\\.dex")) -> dexFiles.add(name)
                    name.startsWith("lib/") && name.endsWith(".so") -> {
                        val parts = name.split("/")
                        if (parts.size >= 3) abis.add(parts[1])
                    }
                    name.startsWith("META-INF/") && (name.endsWith(".RSA") || name.endsWith(".DSA")) -> hasV1 = true
                    name == "assets/zenpatch/config.properties" -> isZenPatched = true
                }
            }
            if (hasV1) signatureSchemes.add(1)

            // Basic manifest parsing
            val manifestEntry = zip.getEntry("AndroidManifest.xml")
            if (manifestEntry != null) {
                zip.getInputStream(manifestEntry).use { stream ->
                    val bytes = stream.readBytes()
                    // Simple package name extraction from manifest bytes
                    val result = parseBasicManifest(bytes)
                    packageName = result["package"] ?: "unknown"
                    versionCode = result["versionCode"]?.toLongOrNull() ?: 0L
                    versionName = result["versionName"] ?: "unknown"
                    minSdk = result["minSdk"]?.toIntOrNull() ?: 0
                    targetSdk = result["targetSdk"]?.toIntOrNull() ?: 0
                    applicationClass = result["applicationClass"]
                    isSplit = result["split"] != null
                    isDeclaredDebuggable = result["debuggable"] == "true"
                }
            }
        }

        // Check v2/v3 signing block
        detectV2V3Schemes(file, signatureSchemes)

        return ApkMetadata(
            file = file.absolutePath,
            sizeBytes = file.length(),
            packageName = packageName,
            versionCode = versionCode,
            versionName = versionName,
            minSdk = minSdk,
            targetSdk = targetSdk,
            applicationClass = applicationClass,
            dexCount = dexFiles.size,
            abis = abis.sorted(),
            signatureSchemes = signatureSchemes.distinct().sorted(),
            isSplit = isSplit,
            isZenPatched = isZenPatched,
            isDeclaredDebuggable = isDeclaredDebuggable
        )
    }

    private fun parseBasicManifest(bytes: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        // Simplified: scan for UTF-8/UTF-16 strings that match known patterns
        // Full implementation uses AXMLParser
        val content = String(bytes, Charsets.ISO_8859_1)
        Regex("package=\"([^\"]+)\"").find(content)?.groupValues?.get(1)?.let { result["package"] = it }
        return result
    }

    private fun detectV2V3Schemes(file: File, schemes: MutableList<Int>) {
        try {
            val bytes = file.readBytes()
            // Scan for APK Signing Block magic
            val magic = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)
            for (i in bytes.indices) {
                if (i + magic.size < bytes.size) {
                    var match = true
                    for (j in magic.indices) { if (bytes[i + j] != magic[j]) { match = false; break } }
                    if (match) { schemes.add(2); break }
                }
            }
        } catch (_: Exception) {}
    }

    private fun printTable(info: ApkMetadata) {
        println("\n=== APK Analysis: ${File(info.file).name} ===")
        println("Package:         ${info.packageName}")
        println("Version Code:    ${info.versionCode}")
        println("Version Name:    ${info.versionName}")
        println("Min SDK:         API ${info.minSdk}")
        println("Target SDK:      API ${info.targetSdk}")
        println("Application:     ${info.applicationClass ?: "(default android.app.Application)"}")
        println("DEX files:       ${info.dexCount}")
        println("ABIs:            ${info.abis.joinToString(", ").ifEmpty { "(none)" }}")
        println("Signatures:      v${info.signatureSchemes.joinToString("+v")}")
        println("Split APK:       ${if (info.isSplit) "yes" else "no"}")
        println("ZenPatched:      ${if (info.isZenPatched) "YES" else "no"}")
        println("Debuggable:      ${if (info.isDeclaredDebuggable) "yes" else "no"}")
        println("File size:       ${info.sizeBytes / 1024} KB (${info.sizeBytes} bytes)")
    }

    private fun printJson(info: ApkMetadata) {
        println("""{
  "file": "${info.file.replace("\\", "\\\\")}",
  "packageName": "${info.packageName}",
  "versionCode": ${info.versionCode},
  "versionName": "${info.versionName}",
  "minSdk": ${info.minSdk},
  "targetSdk": ${info.targetSdk},
  "applicationClass": ${if (info.applicationClass != null) "\"${info.applicationClass}\"" else "null"},
  "dexCount": ${info.dexCount},
  "abis": [${info.abis.joinToString(", ") { "\"$it\"" }}],
  "signatureSchemes": [${info.signatureSchemes.joinToString(", ")}],
  "isSplit": ${info.isSplit},
  "isZenPatched": ${info.isZenPatched},
  "isDeclaredDebuggable": ${info.isDeclaredDebuggable},
  "sizeBytes": ${info.sizeBytes}
}""")
    }
}
