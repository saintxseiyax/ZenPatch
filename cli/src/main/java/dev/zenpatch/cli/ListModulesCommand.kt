package dev.zenpatch.cli

import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import picocli.CommandLine.Option
import java.io.File
import java.util.concurrent.Callable
import java.util.zip.ZipFile

@Command(
    name = "list-modules",
    description = ["List Xposed modules available for patching"],
    mixinStandardHelpOptions = true
)
class ListModulesCommand : Callable<Int> {

    @Parameters(index = "0", arity = "0..1", description = ["Directory to scan for module APKs"])
    var directory: File? = null

    @Option(names = ["--json"], description = ["Output in JSON format"])
    var json: Boolean = false

    override fun call(): Int {
        val scanDir = directory ?: File(".")

        if (!scanDir.exists() || !scanDir.isDirectory) {
            System.err.println("ERROR: Not a directory: ${scanDir.absolutePath}")
            return 1
        }

        val modules = discoverModules(scanDir)

        if (modules.isEmpty()) {
            println("No Xposed modules found in: ${scanDir.absolutePath}")
            return 0
        }

        if (json) {
            printJsonList(modules)
        } else {
            printTable(modules)
        }

        return 0
    }

    data class ModuleInfo(
        val apkPath: String,
        val packageName: String,
        val name: String,
        val description: String,
        val version: String,
        val minXposedVersion: Int,
        val entryPoints: List<String>
    )

    private fun discoverModules(dir: File): List<ModuleInfo> {
        val result = mutableListOf<ModuleInfo>()

        dir.walkTopDown()
            .maxDepth(3)
            .filter { it.isFile && it.extension.lowercase() == "apk" }
            .forEach { apk ->
                try {
                    val info = scanModuleApk(apk)
                    if (info != null) result.add(info)
                } catch (_: Exception) {}
            }

        return result.sortedBy { it.name }
    }

    private fun scanModuleApk(apk: File): ModuleInfo? {
        ZipFile(apk).use { zip ->
            // Check for xposed_init - indicates it's an Xposed module
            val xposedInit = zip.getEntry("assets/xposed_init") ?: return null

            val entryPoints = zip.getInputStream(xposedInit).use { stream ->
                stream.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            }

            if (entryPoints.isEmpty()) return null

            // Parse AndroidManifest for package info
            var packageName = apk.nameWithoutExtension
            var versionName = "?"
            var description = ""
            var minXposedVersion = 1
            var appLabel = packageName

            val manifestEntry = zip.getEntry("AndroidManifest.xml")
            if (manifestEntry != null) {
                zip.getInputStream(manifestEntry).use { stream ->
                    val bytes = stream.readBytes()
                    // Extract package name from AXML bytes
                    val str = String(bytes, Charsets.ISO_8859_1)
                    Regex("package=\"([a-z][a-z0-9.]+)\"").find(str)?.groupValues?.get(1)?.let {
                        packageName = it
                    }
                }
            }

            // Check for xposeddescription and xposedminversion meta
            // These are in AndroidManifest meta-data, hard to parse from binary AXML
            // In full implementation, use AXMLParser

            return ModuleInfo(
                apkPath = apk.absolutePath,
                packageName = packageName,
                name = appLabel,
                description = description,
                version = versionName,
                minXposedVersion = minXposedVersion,
                entryPoints = entryPoints
            )
        }
    }

    private fun printTable(modules: List<ModuleInfo>) {
        println("\n=== Discovered Xposed Modules ===\n")
        modules.forEachIndexed { i, m ->
            println("${i + 1}. ${m.name} (${m.packageName})")
            if (m.description.isNotBlank()) println("   ${m.description}")
            println("   Version: ${m.version}")
            println("   Entry points: ${m.entryPoints.joinToString(", ")}")
            println("   APK: ${m.apkPath}")
            println()
        }
        println("Total: ${modules.size} module(s)")
    }

    private fun printJsonList(modules: List<ModuleInfo>) {
        println("[")
        modules.forEachIndexed { i, m ->
            val comma = if (i < modules.size - 1) "," else ""
            println("""  {
    "packageName": "${m.packageName}",
    "name": "${m.name}",
    "version": "${m.version}",
    "description": "${m.description.replace("\"", "\\\"")}",
    "minXposedVersion": ${m.minXposedVersion},
    "entryPoints": [${m.entryPoints.joinToString(", ") { "\"$it\"" }}],
    "apkPath": "${m.apkPath.replace("\\", "\\\\")}"
  }$comma""")
        }
        println("]")
    }
}
